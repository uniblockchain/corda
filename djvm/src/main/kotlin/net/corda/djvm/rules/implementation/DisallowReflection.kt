package net.corda.djvm.rules.implementation

import net.corda.djvm.code.Instruction
import net.corda.djvm.code.instructions.MemberAccessInstruction
import net.corda.djvm.formatting.MemberFormatter
import net.corda.djvm.rules.InstructionRule
import net.corda.djvm.validation.RuleContext

/**
 * Rule that checks for illegal references to reflection APIs.
 */
class DisallowReflection : InstructionRule() {

    override fun validate(context: RuleContext, instruction: Instruction) = context.validate {
        // TODO Enable controlled use of reflection APIs
        if (instruction is MemberAccessInstruction) {
            invalidReflectionUsage(instruction) given (instruction.owner.startsWith("java/lang/reflect/"))
            invalidReflectionUsage(instruction) given (instruction.owner.startsWith("java/lang/invoke/"))
            invalidReflectionUsage(instruction) given (instruction.owner.startsWith("sun/reflect/"))
            invalidReflectionUsage(instruction) given (instruction.owner == "sun/misc/Unsafe" || instruction.owner == "sun/misc/VM")
        }
    }

    private fun RuleContext.invalidReflectionUsage(instruction: MemberAccessInstruction) =
            this.fail("Disallowed reference to reflection API; ${memberFormatter.format(instruction.member)}")

    private val memberFormatter = MemberFormatter()

}
