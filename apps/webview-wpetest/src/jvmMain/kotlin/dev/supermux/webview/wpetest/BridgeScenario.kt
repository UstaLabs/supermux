package dev.supermux.webview.wpetest

import dev.supermux.webview.wpetest.Verdict.FAIL
import dev.supermux.webview.wpetest.Verdict.PASS
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * B1–B3: the bridge as a WebKit engine wires it, on WPE. The relay is in the script world holding the handler, the lab
 * shim is in the page world, and host messages are delivered by the delivery body in the script world, with the origin
 * test there.
 */
suspend fun bridge(lab: Lab) {
    val s = "bridge"
    val r = lab.report
    val view = lab.host.create(1280, 720, 1.0)
    view.configure()
    view.loadLab()
    lab.show(view)

    // B1: the relay names the document (start), reports its paint, and forwards the shim's $ready.
    val ready = withTimeoutOrNull(5_000) { view.pageMessages.receive() }
    val painted = lab.waitUntil(5_000) { view.paints > 0 }
    r.line(s, "B1-relay", if (view.document != null && painted && ready?.contains("\$ready") == true) PASS else FAIL,
        "document" to view.document, "paints" to view.paints, "first_message" to ready)

    // B2: host → page → host: delivered through the script world, answered through the relay.
    val rtt = Samples("rtt_ms")
    repeat(500) { i ->
        val want = """{"t":"pong","id":$i}"""
        val t = System.nanoTime()
        view.deliver("""{"t":"ping","id":$i}""", LabView.LAB_ORIGIN)
        val got = withTimeoutOrNull(2_000) {
            var m = view.pageMessages.receive()
            while (m != want) m = view.pageMessages.receive()
            m
        }
        if (got != null) rtt.add((System.nanoTime() - t) / 1e6)
    }
    r.line(s, "B2-round-trip", if (rtt.count >= 495 && rtt.p50 <= 2.0) PASS else FAIL, rtt.pairs())

    // B3: the page redefines its origin in its own world; delivery's test, in the script world, doesn't see that.
    view.deliver("""{"t":"spoof","origin":"https://evil.example"}""", LabView.LAB_ORIGIN)
    val spoofed = withTimeoutOrNull(2_000) { view.pageMessages.receive() }
    view.deliver("""{"t":"mark","n":1}""", "https://evil.example")
    view.deliver("""{"t":"mark","n":2}""", LabView.LAB_ORIGIN)
    delay(1_000)
    val got = generateSequence { view.pageMessages.tryReceive().getOrNull() }.toList()
    val wrong = got.any { it.contains("\"n\":1") }
    val right = got.any { it.contains("\"n\":2") }
    r.line(s, "B3-delivery-origin", if (!wrong && right) PASS else FAIL, "spoof_took" to (spoofed?.contains("evil.example") == true),
        "wrong_target_delivered" to wrong, "right_target_delivered" to right)
    view.destroy()
}
