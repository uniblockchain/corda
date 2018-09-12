package net.corda.djvm.validation

import net.corda.djvm.analysis.AnalysisConfiguration
import net.corda.djvm.analysis.AnalysisContext
import net.corda.djvm.analysis.ClassAndMemberVisitor
import net.corda.djvm.analysis.SourceLocation
import net.corda.djvm.formatting.MemberFormatter
import net.corda.djvm.messages.Message
import net.corda.djvm.messages.Severity
import net.corda.djvm.references.*
import net.corda.djvm.rewiring.SandboxClassLoadingException
import net.corda.djvm.utilities.loggerFor

/**
 * Module used to validate all traversable references before instantiating and executing a [java.util.function.Function].
 *
 * @param configuration The analysis configuration to use for the validation.
 * @property memberFormatter Module with functionality for formatting class members.
 */
class ReferenceValidator(
        private val configuration: AnalysisConfiguration,
        private val memberFormatter: MemberFormatter = MemberFormatter()
) {

    /**
     * Container holding the current state of the validation.
     *
     * @property context The context in which references are to be validated.
     * @property analyzer Underlying analyzer used for processing classes.
     */
    private class State(
            val context: AnalysisContext,
            val analyzer: ClassAndMemberVisitor
    )

    /**
     * Validate whether or not the classes in a class hierarchy can be safely instantiated and run in a sandbox by
     * checking that all references are rooted in deterministic code.
     *
     * @param context The context in which the check should be made.
     * @param analyzer Underlying analyzer used for processing classes.
     */
    fun validate(context: AnalysisContext, analyzer: ClassAndMemberVisitor): ReferenceValidationSummary =
            State(context, analyzer).let { state ->
                logger.debug("Validating {} references across {} class(es)...",
                        context.references.numberOfReferences, context.classes.size)
                context.references.process { validateReference(state, it) }
                logger.debug("Reference validation completed; {} class(es) and {} message(s)",
                        context.references.numberOfReferences, context.classes.size)
                ReferenceValidationSummary(state.context.classes, state.context.messages, state.context.classOrigins)
            }

    /**
     * Construct a message from an invalid reference and its source location.
     */
    private fun referenceToMessage(referenceWithLocation: ReferenceWithLocation): Message {
        val (location, reference, description) = referenceWithLocation
        val referenceMessage = when {
            reference is ClassReference ->
                "Invalid reference to class ${configuration.classModule.getFormattedClassName(reference.className)}"
            reference is MemberReference && configuration.memberModule.isConstructor(reference) ->
                "Invalid reference to constructor ${memberFormatter.format(reference)}"
            reference is MemberReference && configuration.memberModule.isField(reference) ->
                "Invalid reference to field ${memberFormatter.format(reference)}"
            reference is MemberReference && configuration.memberModule.isMethod(reference) ->
                "Invalid reference to method ${memberFormatter.format(reference)}"
            else ->
                "Invalid reference to $reference"
        }
        val message = if (description.isNotBlank()) {
            "$referenceMessage, $description"
        } else {
            referenceMessage
        }
        return Message(message, Severity.ERROR, location)
    }

    /**
     * Validate a reference made from a class or class member.
     */
    private fun validateReference(state: State, reference: EntityReference) {
        if (configuration.whitelist.matches(reference.className)) {
            // The referenced class has been whitelisted - no need to go any further.
            return
        }
        when (reference) {
            is ClassReference -> {
                logger.debug("Validating class reference {}", reference)
                val clazz = getClass(state, reference.className)
                val reason = when (clazz) {
                    null -> Reason(Reason.Code.NON_EXISTENT_CLASS)
                    else -> getReasonFromEntity(clazz)
                }
                if (reason != null) {
                    logger.debug("Recorded invalid class reference to {}; reason = {}", reference, reason)
                    state.context.messages.addAll(state.context.references.locationsFromReference(reference).map {
                        referenceToMessage(ReferenceWithLocation(it, reference, reason.description))
                    })
                }
            }
            is MemberReference -> {
                logger.debug("Validating member reference {}", reference)
                // Ensure that the dependent class is loaded and analyzed
                val clazz = getClass(state, reference.className)
                val member = state.context.classes.getMember(
                        reference.className, reference.memberName, reference.signature
                )
                val reason = when {
                    clazz == null -> Reason(Reason.Code.NON_EXISTENT_CLASS)
                    member == null -> Reason(Reason.Code.NON_EXISTENT_MEMBER)
                    else -> getReasonFromEntity(state, member)
                }
                if (reason != null) {
                    logger.debug("Recorded invalid member reference to {}; reason = {}", reference, reason)
                    state.context.messages.addAll(state.context.references.locationsFromReference(reference).map {
                        referenceToMessage(ReferenceWithLocation(it, reference, reason.description))
                    })
                }
            }
        }
    }

    /**
     * Get a class from the class hierarchy by its binary name.
     */
    private fun getClass(state: State, className: String, originClass: String? = null): ClassRepresentation? {
        val name = if (configuration.classModule.isArray(className)) {
            val arrayType = arrayTypeExtractor.find(className)?.groupValues?.get(1)
            when (arrayType) {
                null -> "java/lang/Object"
                else -> arrayType
            }
        } else {
            className
        }
        var clazz = state.context.classes[name]
        if (clazz == null) {
            logger.trace("Loading and analyzing referenced class {}...", name)
            val origin = state.context.references
                    .locationsFromReference(ClassReference(name))
                    .map(SourceLocation::className)
                    .firstOrNull() ?: originClass
            state.analyzer.analyze(name, state.context, origin)
            clazz = state.context.classes[name]
        }
        if (clazz == null) {
            logger.warn("Failed to load class {}", name)
            state.context.messages.add(Message("Referenced class not found; $name", Severity.ERROR))
        }
        clazz?.apply {
            val ancestors = listOf(superClass) + interfaces
            for (ancestor in ancestors.filter(String::isNotBlank)) {
                getClass(state, ancestor, clazz.name)
            }
        }
        return clazz
    }

    /**
     * Check if a top-level class definition is considered safe or not.
     */
    private fun isNonDeterministic(state: State, className: String): Boolean = when {
        configuration.whitelist.matches(className) -> false
        else -> {
            try {
                getClass(state, className)?.let {
                    isNonDeterministic(it)
                } ?: true
            } catch (exception: SandboxClassLoadingException) {
                true // Failed to load the class, which means the class is non-deterministic.
            }
        }
    }

    /**
     * Check if a top-level class definition is considered safe or not.
     */
    private fun isNonDeterministic(clazz: ClassRepresentation) =
            getReasonFromEntity(clazz) != null

    /**
     * Derive what reason to give to the end-user for an invalid class.
     */
    private fun getReasonFromEntity(clazz: ClassRepresentation): Reason? = when {
        configuration.whitelist.matches(clazz.name) -> null
        configuration.whitelist.inNamespace(clazz.name) -> Reason(Reason.Code.NOT_WHITELISTED)
        configuration.classModule.isNonDeterministic(clazz) -> Reason(Reason.Code.ANNOTATED)
        else -> null
    }

    /**
     * Derive what reason to give to the end-user for an invalid member.
     */
    private fun getReasonFromEntity(state: State, member: Member): Reason? = when {
        configuration.whitelist.matches(member.reference) -> null
        configuration.whitelist.inNamespace(member.reference) -> Reason(Reason.Code.NOT_WHITELISTED)
        configuration.memberModule.isNonDeterministic(member) -> Reason(Reason.Code.ANNOTATED)
        else -> {
            val invalidClasses = configuration.memberModule.findReferencedClasses(member)
                    .filter { isNonDeterministic(state, it) }
            if (invalidClasses.isNotEmpty()) {
                Reason(Reason.Code.INVALID_CLASS, invalidClasses.map {
                    configuration.classModule.getFormattedClassName(it)
                })
            } else {
                null
            }
        }
    }

    private companion object {

        private val logger = loggerFor<ReferenceValidator>()

        private val arrayTypeExtractor = "^\\[*L([^;]+);$".toRegex()

    }

}
