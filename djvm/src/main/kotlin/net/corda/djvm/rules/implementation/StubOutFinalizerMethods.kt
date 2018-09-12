package net.corda.djvm.rules.implementation

import net.corda.djvm.analysis.AnalysisRuntimeContext
import net.corda.djvm.code.MemberDefinitionProvider
import net.corda.djvm.references.Member
import net.corda.djvm.rules.MemberRule
import net.corda.djvm.validation.RuleContext
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes.*

/**
 * Rule that checks for invalid use of finalizers.
 */
class StubOutFinalizerMethods : MemberRule(), MemberDefinitionProvider {

    override fun validate(context: RuleContext, member: Member) = context.validate {
        if (isFinalizer(member)) {
            trace("finalizer will be deleted")
        }
    }

    override fun define(context: AnalysisRuntimeContext, member: Member) = when {
        // Discard any other method body and replace with stub that just returns.
        isFinalizer(member) -> member.copy(body = listOf(::writeMethodBody))
        else -> member
    }

    private fun writeMethodBody(mv: MethodVisitor): Unit = with(mv) {
        visitInsn(RETURN)
    }

    private fun isFinalizer(member: Member): Boolean
        = member.memberName == "finalize" && member.signature == "()V" && !member.className.startsWith("java/lang/")
}
