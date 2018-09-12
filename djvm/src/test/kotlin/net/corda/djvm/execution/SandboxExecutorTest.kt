package net.corda.djvm.execution

import foo.bar.sandbox.MyObject
import foo.bar.sandbox.testRandom
import foo.bar.sandbox.toNumber
import net.corda.djvm.TestBase
import net.corda.djvm.analysis.Whitelist
import net.corda.djvm.assertions.AssertionExtensions.withProblem
import net.corda.djvm.costing.ThresholdViolationException
import net.corda.djvm.rewiring.SandboxClassLoadingException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatExceptionOfType
import org.junit.Test
import java.nio.file.Files
import java.util.*
import java.util.function.Function

class SandboxExecutorTest : TestBase() {

    @Test
    fun `can load and execute runnable`() = sandbox(Whitelist.MINIMAL) {
        val contractExecutor = DeterministicSandboxExecutor<Int, String>(configuration)
        val summary = contractExecutor.run<TestSandboxedRunnable>(1)
        val result = summary.result
        assertThat(result).isEqualTo("sandbox")
    }

    class TestSandboxedRunnable : Function<Int, String> {
        override fun apply(input: Int): String {
            return "sandbox"
        }
    }

    @Test
    fun `can load and execute contract`() = sandbox(
            pinnedClasses = setOf(Transaction::class.java)
    ) {
        val contractExecutor = DeterministicSandboxExecutor<Transaction, Unit>(configuration)
        val tx = Transaction(1)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<Contract>(tx) }
                .withCauseInstanceOf(IllegalArgumentException::class.java)
                .withMessageContaining("Contract constraint violated")
    }

    class Contract : Function<Transaction?, Unit> {
        override fun apply(input: Transaction?) {
            throw IllegalArgumentException("Contract constraint violated")
        }
    }

    data class Transaction(val id: Int?)

    @Test
    fun `can load and execute code that overrides object hash code`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        val summary = contractExecutor.run<TestObjectHashCode>(0)
        val result = summary.result
        assertThat(result).isEqualTo(0xfed_c0de + 2)
    }

    class TestObjectHashCode : Function<Int, Int> {
        override fun apply(input: Int): Int {
            val obj = Object()
            val hash1 = obj.hashCode()
            val hash2 = obj.hashCode()
            require(hash1 == hash2)
            return Object().hashCode()
        }
    }

    @Test
    fun `can load and execute code that overrides object hash code when derived`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        val summary = contractExecutor.run<TestObjectHashCodeWithHierarchy>(0)
        val result = summary.result
        assertThat(result).isEqualTo(0xfed_c0de + 1)
    }

    class TestObjectHashCodeWithHierarchy : Function<Int, Int> {
        override fun apply(input: Int): Int {
            val obj = MyObject()
            return obj.hashCode()
        }
    }

    @Test
    fun `can detect breached threshold`() = sandbox(DEFAULT, ExecutionProfile.DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestThresholdBreach>(0) }
                .withMessageContaining("terminated due to excessive use of looping")
    }

    class TestThresholdBreach : Function<Int, Int> {
        private var x = 0
        override fun apply(input: Int): Int {
            for (i in 0..1_000_000) {
                x += 1
            }
            return x
        }
    }

    @Test
    fun `can detect stack overflow`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestStackOverflow>(0) }
                .withCauseInstanceOf(StackOverflowError::class.java)
    }

    class TestStackOverflow : Function<Int, Int> {
        override fun apply(input: Int): Int {
            return a()
        }

        private fun a(): Int = b()
        private fun b(): Int = a()
    }


    @Test
    fun `can detect illegal references in Kotlin meta-classes`() = sandbox(DEFAULT, ExecutionProfile.DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestKotlinMetaClasses>(0) }
                .withMessageContaining("java/util/Random.<clinit>(): Disallowed reference to reflection API; sun.misc.Unsafe.getUnsafe()")
    }

    class TestKotlinMetaClasses : Function<Int, Int> {
        override fun apply(input: Int): Int {
            val someNumber = testRandom()
            return "12345".toNumber() * someNumber
        }
    }

    @Test
    fun `cannot execute runnable that references non-deterministic code`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestNonDeterministicCode>(0) }
                .withCauseInstanceOf(SandboxClassLoadingException::class.java)
                .withProblem("java/util/Random.<clinit>(): Disallowed reference to reflection API; sun.misc.Unsafe.getUnsafe()")
    }

    class TestNonDeterministicCode : Function<Int, Int> {
        override fun apply(input: Int): Int {
            return Random().nextInt()
        }
    }

    @Test
    fun `cannot execute runnable that catches ThreadDeath`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestCatchThreadDeath>(0) }
                .withCauseInstanceOf(SandboxClassLoadingException::class.java)
                .withMessageContaining("Disallowed catch of ThreadDeath exception")
                .withMessageContaining(TestCatchThreadDeath::class.java.simpleName)
    }

    class TestCatchThreadDeath : Function<Int, Int> {
        override fun apply(input: Int): Int {
            return try {
                0
            } catch (exception: ThreadDeath) {
                1
            }
        }
    }

    @Test
    fun `cannot execute runnable that catches ThresholdViolationException`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestCatchThresholdViolationException>(0) }
                .withCauseInstanceOf(SandboxClassLoadingException::class.java)
                .withMessageContaining("Disallowed catch of threshold violation exception")
                .withMessageContaining(TestCatchThresholdViolationException::class.java.simpleName)
    }

    class TestCatchThresholdViolationException : Function<Int, Int> {
        override fun apply(input: Int): Int {
            return try {
                0
            } catch (exception: ThresholdViolationException) {
                1
            }
        }
    }

    @Test
    fun `can catch Throwable`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        contractExecutor.run<TestCatchThrowableAndError>(1).apply {
            assertThat(result).isEqualTo(1)
        }
    }

    @Test
    fun `can catch Error`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        contractExecutor.run<TestCatchThrowableAndError>(2).apply {
            assertThat(result).isEqualTo(2)
        }
    }

    @Test
    fun `cannot catch ThreadDeath`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestCatchThrowableErrorAndThreadDeath>(3) }
                .withCauseInstanceOf(ThreadDeath::class.java)
    }

    class TestCatchThrowableAndError : Function<Int, Int> {
        override fun apply(input: Int): Int {
            return try {
                when (input) {
                    1 -> throw Throwable()
                    2 -> throw Error()
                    else -> 0
                }
            } catch (exception: Error) {
                2
            } catch (exception: Throwable) {
                1
            }
        }
    }

    class TestCatchThrowableErrorAndThreadDeath : Function<Int, Int> {
        override fun apply(input: Int): Int {
            return try {
                when (input) {
                    1 -> throw Throwable()
                    2 -> throw Error()
                    3 -> throw ThreadDeath()
                    else -> 0
                }
            } catch (exception: Error) {
                2
            } catch (exception: Throwable) {
                1
            }
        }
    }

    @Test
    fun `cannot persist state across sessions`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        val result1 = contractExecutor.run<TestStatePersistence>(0)
        val result2 = contractExecutor.run<TestStatePersistence>(0)
        assertThat(result1.result)
                .isEqualTo(result2.result)
                .isEqualTo(1)
    }

    class TestStatePersistence : Function<Int, Int> {
        override fun apply(input: Int): Int {
            ReferencedClass.value += 1
            return ReferencedClass.value
        }
    }

    object ReferencedClass {
        @JvmField
        var value = 0
    }

    @Test
    fun `can load and execute code that uses IO`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestIO>(0) }
                .withCauseInstanceOf(SandboxClassLoadingException::class.java)
    }

    class TestIO : Function<Int, Int> {
        override fun apply(input: Int): Int {
            val file = Files.createTempFile("test", ".dat")
            Files.newBufferedWriter(file).use {
                it.write("Hello world!")
            }
            return 0
        }
    }

    @Test
    fun `can load and execute code that uses reflection`() = sandbox(DEFAULT) {
        val contractExecutor = DeterministicSandboxExecutor<Int, Int>(configuration)
        assertThatExceptionOfType(SandboxException::class.java)
                .isThrownBy { contractExecutor.run<TestReflection>(0) }
                .withCauseInstanceOf(SandboxClassLoadingException::class.java)
                .withMessageContaining("Disallowed reference to reflection API")
                .withMessageContaining("java.lang.reflect.Method.invoke(Object, Object[])")
    }

    class TestReflection : Function<Int, Int> {
        override fun apply(input: Int): Int {
            val clazz = Object::class.java
            val obj = clazz.newInstance()
            val result = clazz.methods.first().invoke(obj)
            return obj.hashCode() + result.hashCode()
        }
    }

}
