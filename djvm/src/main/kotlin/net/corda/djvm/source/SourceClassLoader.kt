package net.corda.djvm.source

import net.corda.djvm.analysis.AnalysisContext
import net.corda.djvm.analysis.ClassResolver
import net.corda.djvm.analysis.SourceLocation
import net.corda.djvm.messages.Message
import net.corda.djvm.messages.Severity
import net.corda.djvm.rewiring.SandboxClassLoadingException
import net.corda.djvm.utilities.loggerFor
import org.objectweb.asm.ClassReader
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Customizable class loader that allows the user to explicitly specify additional JARs and directories to scan.
 *
 * @param paths The directories and explicit JAR files to scan.
 * @property classResolver The resolver to use to derive the original name of a requested class.
 */
open class SourceClassLoader(
    paths: List<Path>,
    private val classResolver: ClassResolver
) : URLClassLoader(resolvePaths(paths), SourceClassLoader::class.java.classLoader) {

    /**
     * Open a [ClassReader] for the provided class name.
     */
    fun classReader(
            className: String, context: AnalysisContext, origin: String? = null
    ): ClassReader {
        val originalName = classResolver.reverse(className.replace('.', '/'))
        return try {
            logger.trace("Opening ClassReader for class {}...", originalName)
            getResourceAsStream("$originalName.class").use {
                ClassReader(it)
            }
        } catch (exception: IOException) {
            context.messages.add(Message(
                    message ="Class file not found; $originalName.class",
                    severity = Severity.ERROR,
                    location = SourceLocation(origin ?: "")
            ))
            logger.error("Failed to open ClassReader for class", exception)
            throw SandboxClassLoadingException(context)
        }
    }

    /**
     * Search our own jars for the given resource, delegating
     * to our parent's classloader if we don't have it.
     */
    override fun getResource(name: String): URL? {
        return findResource(name) ?: parent?.getResource(name)
    }

    /**
     * Find and load the class with the specified name from the search path.
     */
    override fun findClass(name: String): Class<*> {
        logger.trace("Finding class {}...", name)
        val originalName = classResolver.reverseNormalized(name)
        return super.findClass(originalName)
    }

    /**
     * Load the class with the specified binary name.
     */
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        logger.trace("Loading class {}, resolve={}...", name, resolve)
        val originalName = classResolver.reverseNormalized(name)
        return super.loadClass(originalName, resolve)
    }

    private companion object {

        private val logger = loggerFor<SourceClassLoader>()

        private fun resolvePaths(paths: List<Path>): Array<URL> {
            return paths.map(this::expandPath).flatMap {
                when {
                    !Files.exists(it) -> throw FileNotFoundException("File not found; $it")
                    Files.isDirectory(it) -> {
                        listOf(it.toURL()) + Files.list(it).filter(::isJarFile).map { jar -> jar.toURL() }.toList()
                    }
                    Files.isReadable(it) && isJarFile(it) -> listOf(it.toURL())
                    else -> throw IllegalArgumentException("Expected JAR or class file, but found $it")
                }
            }.apply {
                logger.trace("Resolved paths: {}", this)
            }.toTypedArray()
        }

        private fun expandPath(path: Path): Path {
            val pathString = path.toString()
            if (pathString.startsWith("~/")) {
                return homeDirectory.resolve(pathString.removePrefix("~/"))
            }
            return path
        }

        private fun isJarFile(path: Path) = path.toString().endsWith(".jar", true)

        private fun Path.toURL() = this.toUri().toURL()

        private val homeDirectory: Path
            get() = Paths.get(System.getProperty("user.home"))

    }

}