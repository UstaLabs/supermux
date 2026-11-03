package dev.supermux.net

import dev.supermux.util.PerfTrace
import dev.supermux.util.serverTimingMs
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.http.contentLength
import io.ktor.util.AttributeKey

/** Card #54: which trace and request id an HTTP call belongs to (a request attribute). */
internal class PerfRequest(val trace: String, val req: String)

internal val PerfRequestKey = AttributeKey<PerfRequest>("supermux.perf.request")
private val PerfInstalledKey = AttributeKey<Boolean>("supermux.perf.installed")

/**
 * Time every request this client sends (to the response headers), record it in the load-time
 * trace with its status, size and the broker's own time (`server-timing`), and — while a broker
 * is tracing — tell it the trace and request id (`x-mux-trace: <trace>/<req>`). Installed once per
 * client however many [BrokerApi]s share it.
 */
internal fun HttpClient.installPerfTrace() {
    if (attributes.contains(PerfInstalledKey)) return
    attributes.put(PerfInstalledKey, true)
    plugin(HttpSend).intercept { request ->
        val perf = request.attributes.getOrNull(PerfRequestKey)
            ?: PerfRequest(
                // A chat's media downloads belong to its open; everything else to the launch.
                trace = (if (request.url.encodedPath.startsWith("/files/")) PerfTrace.freshChatTrace() else null) ?: PerfTrace.launchId,
                req = PerfTrace.nextReq(),
            ).also { request.attributes.put(PerfRequestKey, it) }
        if (PerfTrace.shipping && !request.headers.contains("x-mux-trace")) {
            request.headers.append("x-mux-trace", "${perf.trace}/${perf.req}")
        }
        val start = PerfTrace.elapsedMs()
        val call = execute(request)
        PerfTrace.event(
            "http",
            "req" to perf.req,
            "method" to request.method.value,
            "path" to request.url.encodedPath,
            "status" to call.response.status.value,
            "bytes" to call.response.contentLength(),
            "ms" to PerfTrace.elapsedMs() - start,
            "serverMs" to serverTimingMs(call.response.headers["server-timing"]),
            trace = perf.trace,
        )
        call
    }
}
