package net.corda.djvm.rules.implementation

import net.corda.djvm.code.Instruction
import net.corda.djvm.code.instructions.DynamicInvocationInstruction
import net.corda.djvm.rules.InstructionRule
import net.corda.djvm.validation.RuleContext

/**
 * Rule that checks for invalid dynamic invocations.
 */
class DisallowDynamicInvocation : InstructionRule() {

    override fun validate(context: RuleContext, instruction: Instruction) = context.validate {
        fail("Disallowed dynamic invocation in method") given (!clazz.name.startsWith("java/") && instruction is DynamicInvocationInstruction)
    }

}
