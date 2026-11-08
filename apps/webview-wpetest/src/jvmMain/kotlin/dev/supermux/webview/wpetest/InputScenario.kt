package dev.supermux.webview.wpetest

import dev.supermux.webview.wpetest.Verdict.FAIL
import dev.supermux.webview.wpetest.Verdict.PASS
import java.awt.event.KeyEvent
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * L1–L5 and I1–I4: real X input (AWT Robot, xdotool) through Compose and the surface into WPE, timed to the frame that
 * shows it; Turkish text; the wheel; the cursor; then composition through WPE's input-method context.
 */
suspend fun input(lab: Lab) {
    val s = "input"
    val r = lab.report
    val o = lab.options
    val view = lab.host.create(o.width, o.height, 1.0)
    view.configure()
    view.loadLab()
    lab.show(view)
    check(lab.waitUntil(10_000) { lab.stage.frames.draws.isNotEmpty() }) { "no frame drawn in 10 s" }
    view.eval("__labPage.setMode('input')")
    lab.click(900, 500) // the window and the surface take focus
    delay(500)

    // L1, L2: each click or key bumps the page's counter.
    latency(lab, view, "L1-pointer", 30) { lab.click(900, 500) }
    latency(lab, view, "L2-keyboard", 30) { lab.key(KeyEvent.VK_A) }

    // L3: Turkish letters through the X server and AWT's key characters, into a text field.
    val text = "abc çşğıöü ÇŞĞİÖÜ"
    view.eval("__labPage.focusField()")
    view.eval("__labPage.clear()")
    lab.type(text)
    val typed = lab.waitUntil(5_000) { valueOf(lab, view) == text }
    r.line(s, "L3-turkish-text", if (typed) PASS else FAIL, "want" to text, "got" to valueOf(lab, view))
    view.eval("document.activeElement && document.activeElement.blur()")

    // L4: the wheel scrolls the page, downwards.
    view.eval("window.scrollTo(0, 0)")
    delay(300)
    lab.robot.mouseMove(900, 500)
    lab.robot.mouseWheel(3)
    val scrolled = lab.waitUntil(3_000) { (view.eval("__labPage.scrollY()")?.toDoubleOrNull() ?: 0.0) > 0.0 }
    r.line(s, "L4-scroll", if (scrolled) PASS else FAIL, "scroll_y" to view.eval("__labPage.scrollY()"), "scroll_sign" to o.scrollSign)
    view.eval("window.scrollTo(0, 0)")
    delay(300)

    // L5: hovering a link sets the pointer cursor (WPEView.set_cursor_from_name).
    val rect = lab.parse(view.eval("__labPage.rect('link')"))?.jsonArray?.map { it.jsonPrimitive.double }
    val t5 = System.nanoTime()
    if (rect != null) lab.robot.mouseMove((rect[0] + rect[2] / 2).roundToInt(), (rect[1] + rect[3] / 2).roundToInt())
    val cursor = view.awaitEvent(WpeNative.CURSOR, t5, 1_000) { it.s1 == "pointer" }
    r.line(s, "L5-cursor", if (cursor != null) PASS else FAIL, "ms" to cursor?.let { (it.atNs - t5) / 1e6 },
        "names" to view.events(WpeNative.CURSOR, t5).joinToString(",") { it.s1 ?: "" })

    // I1–I4: composition through WPE's input-method context (F15), driven as an input method would.
    val ti = System.nanoTime()
    view.eval("__labPage.focusField()")
    view.eval("__labPage.clear()")
    val focusIn = view.awaitEvent(WpeNative.IME_FOCUS, ti, 2_000) { it.a == 1L }
    view.eval("__labPage.events()")
    WpeNative.ime(view.id, WpeNative.IME_PREEDIT, "かな", -1)
    delay(500)
    val preedit = pageEvents(lab, view)
    r.line(s, "I1-preedit", if ("compositionstart" in preedit && "compositionupdate かな" in preedit) PASS else FAIL,
        "events" to preedit.joinToString("|"))
    WpeNative.ime(view.id, WpeNative.IME_COMMIT, "仮名", -1)
    delay(500)
    val commit = pageEvents(lab, view)
    val value = valueOf(lab, view)
    r.line(s, "I2-commit", if ("compositionend 仮名" in commit && value == "仮名") PASS else FAIL,
        "events" to commit.joinToString("|"), "value" to value)
    val area = view.events(WpeNative.IME_CURSOR, ti).lastOrNull()
    val height = area?.let { (it.b and 0xffffffffL).toInt() }
    r.line(s, "I3-cursor-area", if (area != null && (height ?: 0) > 0) PASS else FAIL,
        "x" to area?.let { (it.a shr 32).toInt() }, "y" to area?.let { (it.a and 0xffffffffL).toInt() },
        "w" to area?.let { (it.b shr 32).toInt() }, "h" to height)
    val tb = System.nanoTime()
    view.eval("document.activeElement.blur()")
    val focusOut = view.awaitEvent(WpeNative.IME_FOCUS, tb, 2_000) { it.a == 0L }
    r.line(s, "I4-focus", if (focusIn != null && focusOut != null) PASS else FAIL, "in" to (focusIn != null), "out" to (focusOut != null))
    view.destroy()
}

private suspend fun valueOf(lab: Lab, view: LabView): String? = lab.parse(view.eval("__labPage.value()"))?.jsonPrimitive?.content

private suspend fun pageEvents(lab: Lab, view: LabView): List<String> =
    lab.parse(view.eval("__labPage.events()"))?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()

/** Bars: 90 % of inputs seen; input → frame p95 ≤ 50 ms (three 60 Hz frames); input → drawn p95 ≤ 67 ms. */
private suspend fun latency(lab: Lab, view: LabView, check: String, n: Int, act: () -> Unit) {
    view.drain()
    lab.stage.frames.draws.clear()
    val stimuli = ArrayList<Stimulus>()
    repeat(n) {
        val before = lab.counter(view)
        val at = System.nanoTime()
        act()
        stimuli += Stimulus(check, at, (before + 1) and 0xffff)
        delay(150)
    }
    delay(500)
    val arrivals = view.drain().mapNotNull { rec -> Markers.counter(rec.marker)?.let { Arrival(rec.seq, it, rec.arrivalNs) } }
    val latencies = Timeline.latencies(stimuli, arrivals, lab.stage.frames.draws.toList())
    val toFrame = Samples("arrival_ms")
    val toDrawn = Samples("drawn_ms")
    latencies.forEach { l ->
        l.arrivalMs?.let { toFrame.add(it) }
        l.drawnMs?.let { toDrawn.add(it) }
    }
    val ok = toFrame.count >= n * 9 / 10 && toFrame.p95 <= 50.0 && toDrawn.p95 <= 67.0
    lab.report.line("input", check, if (ok) PASS else FAIL, listOf("n" to n) + toFrame.pairs() + toDrawn.pairs())
}
