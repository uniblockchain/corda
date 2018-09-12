package net.corda.djvm.rewiring

import net.corda.djvm.SandboxConfiguration
import net.corda.djvm.analysis.AnalysisContext
import net.corda.djvm.code.ClassMutator
import net.corda.djvm.utilities.loggerFor
import org.objectweb.asm.ClassReader
import org.objectweb.asm.commons.ClassRemapper

/**
 * Functionality for rewrite parts of a class as it is being loaded.
 *
 * @property configuration The configuration of the sandbox.
 * @property classLoader The class loader used to load the classes that are to be rewritten.
 * @property remapper A sandbox-aware remapper for inspecting and correcting type names and descriptors.
 */
open class ClassRewriter(
        private val configuration: SandboxConfiguration,
        private val classLoader: ClassLoader,
        private val remapper: SandboxRemapper = SandboxRemapper(configuration.analysisConfiguration.classResolver)
) {

    /**
     * Process class and allow user to rewrite parts/all of its content through provided hooks.
     *
     * @param reader The reader providing the byte code for the desired class.
     * @param context The context in which the class is being analyzed and processed.
     */
    fun rewrite(reader: ClassReader, context: AnalysisContext): ByteCode {
        logger.debug("Rewriting class {}...", reader.className)
        val writer = SandboxClassWriter(reader, classLoader)
        val classRemapper = ClassRemapper(writer, remapper)
        val visitor = ClassMutator(
                classRemapper,
                configuration.analysisConfiguration,
                configuration.definitionProviders,
                configuration.emitters
        )
        visitor.analyze(reader, context, options = ClassReader.EXPAND_FRAMES)
        val hasBeenModified = visitor.hasBeenModified
        return ByteCode(writer.toByteArray(), hasBeenModified)
    }

    private companion object {
        private val logger = loggerFor<ClassRewriter>()
    }

}
