package net.corda.nodeapi.internal.tracing

import co.paralleluniverse.strands.Strand
import io.jaegertracing.Configuration
import io.jaegertracing.Configuration.*
import io.jaegertracing.internal.samplers.ConstSampler
import io.opentracing.Span
import io.opentracing.Tracer
import io.opentracing.log.Fields
import io.opentracing.tag.Tags
import net.corda.core.context.InvocationOrigin
import net.corda.core.internal.FlowStateMachine
import java.util.concurrent.ConcurrentHashMap

class CordaTracer private constructor(private val tracer: Tracer) {

    class Builder {
        var endpoint: String = "http://localhost:14268/api/traces"
            private set

        var serviceName: String = "Corda"
            private set

        var flushInterval: Int = 200
            private set

        var logSpans: Boolean = true
            private set

        fun withEndpoint(endpoint: String): Builder {
            this.endpoint = endpoint
            return this
        }

        fun withServiceName(serviceName: String): Builder {
            this.serviceName = serviceName
            return this
        }

        fun withFlushInterval(interval: Int): Builder {
            this.flushInterval = interval
            return this
        }

        fun withLogSpans(logSpans: Boolean): Builder {
            this.logSpans = logSpans
            return this
        }
    }

    companion object {

        private fun createTracer(name: String): CordaTracer {
            val builder = Builder()
                    .withServiceName("Corda ($name)")
            val sampler = SamplerConfiguration.fromEnv()
                    .withType(ConstSampler.TYPE)
                    .withParam(1)
            val sender = SenderConfiguration.fromEnv()
                    .withEndpoint(builder.endpoint)
            val reporter = ReporterConfiguration.fromEnv()
                    .withSender(sender)
                    .withLogSpans(builder.logSpans)
                    .withFlushInterval(builder.flushInterval)
            val tracer: Tracer = Configuration(builder.serviceName)
                    .withSampler(sampler)
                    .withReporter(reporter)
                    .tracer
            return CordaTracer(tracer)
        }

        private val tracers: ConcurrentHashMap<String, CordaTracer> = ConcurrentHashMap()

        val current: CordaTracer
            get() = getTracer(identity)

        fun getTracer(name: String): CordaTracer {
            return tracers.getOrPut(name) {
                createTracer(name)
            }
        }

        fun Span?.tag(key: String, value: Any?) {
            val span = this ?: return
            when (value) {
                is Boolean -> span.setTag(key, value)
                is Number -> span.setTag(key, value)
                else -> span.setTag(key, value?.toString())
            }
        }

        fun Span?.error(message: String, exception: Throwable? = null) {
            val span = this ?: return
            Tags.ERROR.set(span, true)
            if (exception != null) {
                span.log(mapOf(
                        Fields.EVENT to "error",
                        Fields.ERROR_OBJECT to exception,
                        Fields.MESSAGE to message
                ))
            } else {
                span.log(mapOf(
                        Fields.EVENT to "error",
                        Fields.MESSAGE to message
                ))
            }
        }

        fun Span?.finish() = this?.finish()

        private val identity: String
            get() = flow?.ourIdentity?.name?.organisation ?: flow?.ourIdentity?.name.toString() ?: "Unknown"

        private val flow: FlowStateMachine<*>?
            get() = Strand.currentStrand() as? FlowStateMachine<*>

        private val FlowStateMachine<*>.flowId: String
            get() = this.id.uuid.toString()

        private fun Tracer.SpanBuilder.decorate() {
            flow?.apply {
                withTag("flow-id", flowId)
                withTag("our-identity", ourIdentity.toString())
                withTag("fiber-id", Strand.currentStrand().id)
                withTag("thread-id", Thread.currentThread().id)
                withTag("session-id", context.trace.sessionId.toString())
                withTag("invocation-id", context.trace.invocationId.value)
                withTag("origin", when (context.origin) {
                    is InvocationOrigin.Peer -> "peer(${(context.origin as InvocationOrigin.Peer).party})"
                    is InvocationOrigin.RPC -> "rpc(${context.origin.principal().name} @ ${(context.origin as InvocationOrigin.RPC).actor.owningLegalIdentity})"
                    is InvocationOrigin.Scheduled -> "scheduled(${(context.origin as InvocationOrigin.Scheduled).scheduledState.ref})"
                    is InvocationOrigin.Service -> "service(${(context.origin as InvocationOrigin.Service).owningLegalIdentity})"
                    is InvocationOrigin.Shell -> "shell"
                    else -> context.origin.toString()
                })
            }
        }

        var rootSpan: Span? = null

        private fun createRootSpan(): Span? {
            rootSpan = getTracer("Network").tracer.buildSpan("Execution").start()
            return rootSpan
        }

        fun terminate() {
            rootSpan?.finish()
        }

    }

    private var flowSpans: ConcurrentHashMap<String, Span> = ConcurrentHashMap()

    fun <T> flowSpan(action: (Span, FlowStateMachine<*>) -> T): T? {
        return flow?.let { flow ->
            val span = flowSpans.getOrPut(flow.flowId) {
                tracer.buildSpan(flow.logic.toString()).apply {
                    (rootSpan ?: createRootSpan())?.apply { asChildOf(this) }
                    decorate()

                }.start()
            }
            action(span, flow)
        }
    }

    fun span(name: String): Span? {
        return flowSpan { parentSpan, _ ->
            tracer.buildSpan(name).apply {
                asChildOf(parentSpan)
                // TODO addReference(References.CHILD_OF, parentSpan.context())
                decorate()

            }.start()
        }
    }

    fun <T> span(name: String, closeAutomatically: Boolean = true, block: (Span?) -> T): T {
        return span(name)?.let { span ->
            try {
                block(span)
            } catch (ex: Exception) {
                Tags.ERROR.set(span, true)
                span.log(mapOf(
                        Fields.EVENT to "error",
                        Fields.ERROR_OBJECT to ex,
                        Fields.MESSAGE to ex.message
                ))
                throw ex
            } finally {
                if (closeAutomatically) {
                    span.finish()
                }
            }
        } ?: block(null)
    }

    fun endFlow() {
        flow?.id?.uuid.toString().let { flowId ->
            val span = flowSpans.remove(flowId)
            span?.finish()
        }
    }

}