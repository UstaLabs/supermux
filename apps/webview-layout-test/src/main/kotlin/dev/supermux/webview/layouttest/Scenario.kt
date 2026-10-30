package dev.supermux.webview.layouttest

import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** How long an overlay takes to animate in, and the glue to apply what it was sent. */
private const val SETTLE_MILLIS = 700L

/** How long after a click its receivers are counted. */
private const val CLICK_MILLIS = 700L

/** How long each motion runs. */
private const val MOTION_SECONDS = 3.0

/** The scripted runs (plan, Task 11). Main thread, inside the window's composition. */
class Scenario(
    private val model: TestModel,
    private val config: TestConfig,
    private val windowState: WindowState,
    private val report: Report,
    private val shots: Screenshots,
) {
    private val window: Long get() = model.window

    suspend fun run() {
        val painted = withTimeoutOrNull(20_000) { snapshotFlow { model.painted }.first { it } }
        if (painted == null) {
            report.add("A+B", "page", "painted", Verdict.FAIL, "the page never reported its first paint in 20 s")
            report.finish()
            return
        }
        val info = info()
        report.note(
            "macOS ${info.macos}; screen '${info.screen}' up to ${info.maxFps} Hz, scale ${info.backingScale}; " +
                "input source ${info.inputSource}; this process may post HID events: ${info.canPostEvents}",
        )
        if (!ensureKey()) report.note("the window never became key: click and keyboard results are INCONCLUSIVE")
        if (config.mode == Mode.Probe) {
            probe()
        } else {
            for (layout in Layout.entries) runLayout(layout)
            promotion(info())
        }
        report.finish()
    }

    private fun info(): WindowInfo = WindowInfo.parse(Native.windowInfo(window))

    /** Window px → screenshot px: the content is under the title bar. */
    private fun frame(info: WindowInfo) =
        ShotFrame(0, (info.titlebar * info.backingScale).roundToInt(), info.backingScale / model.density)

    /** WKWebView refuses the first click on an inactive window (F18): make the window key, or ask for one click. */
    private suspend fun ensureKey(): Boolean {
        Native.activate(window)
        repeat(30) {
            if (info().isKey) return true
            delay(100)
        }
        println("NEEDS-USER-CLICK")
        report.note("the window isn't key (macOS refused the activation): waiting up to 120 s for one click on it")
        repeat(1200) {
            if (info().isKey) return true
            delay(100)
        }
        return false
    }

    private suspend fun probe() {
        report.note("layer tree:\n" + Native.layerTree(window).trimEnd())
        for (layout in Layout.entries) {
            model.layout = layout
            screen(layout, "rest") { restProbes() }
        }
        report.note("layer tree after B:\n" + Native.layerTree(window).trimEnd())
    }

    private suspend fun runLayout(layout: Layout) {
        model.layout = layout
        model.menuOpen = false
        model.dialogOpen = false
        model.cutoutsEnabled = true
        model.keyRouting = false
        model.paneWidth = 0.dp
        model.listScroll.scrollTo(0)
        Native.evaluate(model.page, "stopAutoScroll();scrollTo(0,0);ta.value='';report()")
        delay(SETTLE_MILLIS)
        report.note("layout ${layout.tag} layer tree:\n" + Native.layerTree(window).trimEnd())
        screens(layout)
        clicks(layout)
        wheel(layout)
        keyboard(layout)
        if (layout == Layout.Under) accessibility()
        motion(layout)
    }

    // Screens ---------------------------------------------------------------------------------------------------------

    private fun beacon(expect: Paint = Paint.Marker): ColorProbe =
        model.rect("beacon").let { ColorProbe("beacon", it.center.x, it.center.y, expect) }

    /** A point of the page, as fractions of its size. */
    private fun at(fx: Float, fy: Float): Offset = model.rect("page").let { Offset(it.left + it.width * fx, it.top + it.height * fy) }

    private fun restProbes(): List<ColorProbe> {
        val page = model.rect("page")
        val inside = at(0.55f, 0.75f)
        return listOf(
            beacon(),
            ColorProbe("page", inside.x, inside.y, Paint.Page),
            ColorProbe("page-band", page.left + Palette.BAND_CSS_PX * model.density / 2, page.center.y, Paint.Band),
        )
    }

    /** Waits for overlays to settle, captures the window, and checks [probes], read after the wait. */
    private suspend fun screen(layout: Layout, name: String, probes: () -> List<ColorProbe>) {
        delay(SETTLE_MILLIS)
        val points = probes()
        val info = info()
        val file = withContext(Dispatchers.IO) { shots.capture("${layout.tag}-$name", info.windowNumber) }
        val image = file?.let { withContext(Dispatchers.IO) { ImageIO.read(it) } }
        if (image == null) {
            report.add(layout.tag, "screens", name, Verdict.INCONCLUSIVE, "no screenshot (shots=${config.shots}, file=${file?.name})")
            return
        }
        val results = ShotCheck.check(image, frame(info), points)
        val verdict = when {
            // No beacon: the capture holds no window content (a permission) or an old frame.
            !results.first().pass -> Verdict.INCONCLUSIVE
            results.all { it.pass } -> Verdict.PASS
            else -> Verdict.FAIL
        }
        report.add(layout.tag, "screens", name, verdict, results.joinToString("; ") { it.line() } + " [${file.name}]")
    }

    private suspend fun screens(layout: Layout) {
        screen(layout, "rest") { restProbes() }
        model.menuOpen = true
        screen(layout, "menu") {
            val item = model.rect("menu-item-2")
            val beside = at(0.8f, 0.85f)
            listOf(
                beacon(),
                ColorProbe("menu-item", item.right - 16f, item.center.y, Paint.Menu),
                ColorProbe("page-beside-menu", beside.x, beside.y, Paint.Page),
            )
        }
        if (layout == Layout.Over) {
            // The control: with nothing cut out, the page hides the menu, and the checks must see the page there.
            model.cutoutsEnabled = false
            screen(layout, "menu-without-cutouts") {
                val item = model.rect("menu-item-2")
                listOf(beacon(), ColorProbe("menu-item-under-page", item.right - 16f, item.center.y, Paint.Page))
            }
            model.cutoutsEnabled = true
        }
        model.menuOpen = false
        model.dialogOpen = true
        screen(layout, "dialog") {
            val dialog = model.rect("dialog")
            val dimmed = at(0.8f, 0.85f)
            listOf(
                // The dialog's scrim covers the whole window (the README's recipe), the beacon in the top bar too.
                beacon(Paint.MarkerDimmed),
                ColorProbe("dialog-surface", dialog.center.x, dialog.top + 12f, Paint.Dialog),
                ColorProbe("page-under-scrim", dimmed.x, dimmed.y, Paint.PageDimmed),
            )
        }
        model.dialogOpen = false
        delay(300)
    }

    // Clicks ----------------------------------------------------------------------------------------------------------

    private suspend fun click(layout: Layout, probe: ClickProbe) {
        val from = System.currentTimeMillis()
        Native.click(window, model.density, probe.x, probe.y)
        delay(CLICK_MILLIS)
        val verdict = ClickJudge.judge(probe, model.received.toList(), from, CLICK_MILLIS)
        report.add(
            layout.tag, "clicks", probe.name, if (verdict.pass) Verdict.PASS else Verdict.FAIL,
            verdict.detail() + " at (${probe.x.roundToInt()}, ${probe.y.roundToInt()})",
        )
    }

    private suspend fun clickWithMenu(layout: Layout, name: String, expected: Side, point: () -> Offset) {
        model.menuOpen = true
        delay(SETTLE_MILLIS)
        val p = point()
        click(layout, ClickProbe(name, p.x, p.y, expected))
        if (expected == Side.Page) {
            // Compose never sees a click the page took, so it can't dismiss the menu on it.
            report.add(layout.tag, "clicks", "$name-menu-still-open", Verdict.INFO, "menu open after the page took the click: ${model.menuOpen}")
        }
        model.menuOpen = false
        delay(300)
    }

    private suspend fun clicks(layout: Layout) {
        Native.evaluate(model.page, "startAutoScroll()") // the "live, scrolling page" of the criterion
        clickWithMenu(layout, "menu-item", Side.Compose) { model.rect("menu-item-2").let { Offset(it.right - 16f, it.center.y) } }
        clickWithMenu(layout, "menu-edge-inside", Side.Compose) { model.rect("menu-item-1").let { Offset(it.left + 3f, it.center.y) } }
        clickWithMenu(layout, "menu-edge-outside", Side.Page) { model.rect("menu-item-1").let { Offset(it.left - 6f, it.center.y) } }
        clickWithMenu(layout, "page-beside-menu", Side.Page) { at(0.8f, 0.85f) }
        model.dialogOpen = true
        delay(SETTLE_MILLIS)
        model.rect("dialog-ok").let { click(layout, ClickProbe("dialog-button", it.center.x, it.center.y, Side.Compose)) }
        model.dialogOpen = true // OK closed it
        delay(SETTLE_MILLIS)
        at(0.8f, 0.85f).let { click(layout, ClickProbe("dialog-scrim-over-page", it.x, it.y, Side.Compose)) }
        model.dialogOpen = false
        delay(300)
        at(0.55f, 0.75f).let { click(layout, ClickProbe("page-alone", it.x, it.y, Side.Page)) }
        Native.evaluate(model.page, "stopAutoScroll()")
    }

    // Wheel -----------------------------------------------------------------------------------------------------------

    private suspend fun wheelAt(layout: Layout, name: String, expected: Side, point: Offset) {
        Native.evaluate(model.page, "report()")
        delay(200)
        val pageBefore = model.pageScrollY
        val listBefore = model.listScroll.value
        Native.scroll(window, model.density, point.x, point.y, -240f)
        delay(700)
        Native.evaluate(model.page, "report()")
        delay(200)
        val got = buildSet {
            if (abs(model.pageScrollY - pageBefore) > 20f) add(Side.Page)
            if (abs(model.listScroll.value - listBefore) > 4) add(Side.Compose)
        }
        val verdict = when {
            got.isEmpty() -> Verdict.INCONCLUSIVE
            got == setOf(expected) -> Verdict.PASS
            else -> Verdict.FAIL
        }
        val note = if (got.isEmpty()) " (the synthetic wheel event reached neither side: check by hand)" else ""
        report.add(
            layout.tag, "wheel", name, verdict,
            "expected $expected; page scrollY ${pageBefore.roundToInt()}→${model.pageScrollY.roundToInt()}, list $listBefore→${model.listScroll.value}$note",
        )
    }

    private suspend fun wheel(layout: Layout) {
        Native.evaluate(model.page, "stopAutoScroll();scrollTo(0,1200)")
        model.listScroll.scrollTo(120)
        delay(SETTLE_MILLIS)
        wheelAt(layout, "wheel-over-page", Side.Page, at(0.55f, 0.75f))
        wheelAt(layout, "wheel-over-compose", Side.Compose, model.rect("row-1").center)
        model.listScroll.scrollTo(0)
        Native.evaluate(model.page, "scrollTo(0,0)")
        delay(300)
    }

    // Keyboard --------------------------------------------------------------------------------------------------------

    private fun verdictOf(ok: Boolean) = if (ok) Verdict.PASS else Verdict.FAIL

    /** The centre of the page's text box, in window px. */
    private fun textBox(): Offset? {
        val box = model.textArea ?: return null
        val page = model.rect("page")
        return Offset(page.left + (box[0] + box[2] / 2) * model.density, page.top + (box[1] + box[3] / 2) * model.density)
    }

    private suspend fun keyboard(layout: Layout) {
        val info = info()
        if (info.inputMethodActive) {
            report.add(
                layout.tag, "keyboard", "all", Verdict.INCONCLUSIVE,
                "the input source is an input method (${info.inputSource}): synthetic ASCII would be composed",
            )
            return
        }
        Native.evaluate(model.page, "ta.value='';report()")
        model.keyRouting = false
        delay(SETTLE_MILLIS)
        val box = textBox()
        if (box == null) {
            report.add(layout.tag, "keyboard", "all", Verdict.INCONCLUSIVE, "the page never reported its text box")
            return
        }
        Native.click(window, model.density, box.x, box.y)
        delay(400)
        val pageResponder = Native.firstResponder(window)
        report.add(layout.tag, "keyboard", "focus-page", verdictOf(pageResponder.startsWith("page")), "first responder after clicking the page's text box: $pageResponder")
        Native.typeText(window, "abc")
        delay(500)
        report.add(layout.tag, "keyboard", "type-into-page", verdictOf(model.pageValue == "abc"), "page text box '${model.pageValue}' after typing 'abc'")
        Native.commandKey(window, "z")
        delay(500)
        report.add(
            layout.tag, "keyboard", "cmd-z-without-monitor", Verdict.INFO,
            "page text box '${model.pageValue}' after ⌘Z with the key monitor off ('abc' unchanged: AWT took the key, F10)",
        )
        model.keyRouting = true
        delay(300)
        Native.typeText(window, "def")
        delay(500)
        val typed = model.pageValue
        Native.commandKey(window, "z")
        delay(500)
        report.add(layout.tag, "keyboard", "cmd-z-with-monitor", verdictOf(model.pageValue != typed), "page text box '$typed' → '${model.pageValue}' after ⌘Z with the key monitor on")
        Native.commandKey(window, "a")
        delay(200)
        Native.typeText(window, "x")
        delay(500)
        report.add(layout.tag, "keyboard", "cmd-a-with-monitor", verdictOf(model.pageValue == "x"), "page text box '${model.pageValue}' after ⌘A and 'x'")
        model.composeText = ""
        val field = model.rect("compose-field")
        Native.click(window, model.density, field.center.x, field.center.y)
        delay(400)
        val composeResponder = Native.firstResponder(window)
        report.add(layout.tag, "keyboard", "focus-compose", verdictOf(composeResponder.startsWith("compose")), "first responder after clicking the Compose field: $composeResponder")
        val pageBefore = model.pageValue
        Native.typeText(window, "q")
        delay(500)
        report.add(
            layout.tag, "keyboard", "type-into-compose", verdictOf(model.composeText == "q" && model.pageValue == pageBefore),
            "Compose field '${model.composeText}', page text box '${model.pageValue}' after typing 'q'",
        )
        model.keyRouting = false
    }

    // Accessibility ---------------------------------------------------------------------------------------------------

    private suspend fun axProbe(): PageEvent.Ax? {
        while (model.accessibility.tryReceive().isSuccess) Unit // drop stale answers
        Native.probeAccessibility(window)
        return withTimeoutOrNull(10_000) { model.accessibility.receive() }
    }

    private suspend fun accessibility() {
        model.accessibilityBridge = false
        delay(500)
        val without = axProbe()
        model.accessibilityBridge = true
        delay(500)
        val with = axProbe()
        report.add(
            "A+B", "accessibility", "reachable-without-bridge", Verdict.INFO,
            without?.let { "reaches=${it.reaches} after ${it.nodes} nodes" } ?: "no answer in 10 s",
        )
        val verdict = when {
            with == null -> Verdict.INCONCLUSIVE
            with.reaches && with.bridge -> Verdict.PASS
            else -> Verdict.FAIL
        }
        report.add(
            "A+B", "accessibility", "reachable-with-bridge", verdict,
            with?.let { "reaches=${it.reaches} bridge=${it.bridge} after ${it.nodes} nodes" } ?: "no answer in 10 s",
        )
        model.accessibilityBridge = false
    }

    // Motion ----------------------------------------------------------------------------------------------------------

    /** [step] every frame for MOTION_SECONDS; Compose's frame times, and screenshots taken meanwhile if [capture]. */
    private suspend fun move(layout: Layout, name: String, capture: Boolean, step: (Double) -> Unit): Pair<List<Long>, List<File>> {
        val windowNumber = info().windowNumber
        val frames = ArrayList<Long>()
        val captured = Collections.synchronizedList(ArrayList<File>())
        val running = AtomicBoolean(true)
        val shooter = if (!capture) null else thread(name = "shots-$name") {
            var i = 0
            while (running.get() && i < 40) shots.capture("${layout.tag}-$name-${i++}", windowNumber)?.let(captured::add)
        }
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            frames += now
            val t = (now - start) / 1e9
            if (t >= MOTION_SECONDS) break
            step(t)
        }
        running.set(false)
        withContext(Dispatchers.IO) { shooter?.join(20_000) }
        return frames to captured.toList()
    }

    private fun frameVerdict(summary: FrameSummary, nominal: Double): Verdict = when {
        summary.count < 30 -> Verdict.INCONCLUSIVE
        summary.p95 <= nominal * 1.5 -> Verdict.PASS
        else -> Verdict.FAIL
    }

    /**
     * A motion run twice: for frame timing with no screenshots, then with screenshots, measuring each one's [edge]
     * offset along [line] from [from] to [to] (window px). [reset] puts things back between runs.
     */
    private suspend fun measure(
        layout: Layout,
        name: String,
        edge: Edge,
        line: Float,
        from: Float,
        to: Float,
        reset: () -> Unit,
        step: (Double) -> Unit,
    ) {
        val info = info()
        val nominal = info.nominalFrameMillis
        val pageBefore = model.pageFrames.size
        val (frames, _) = move(layout, name, capture = false, step)
        val pageIntervals = model.pageFrames.drop(pageBefore)
        reset()
        delay(SETTLE_MILLIS)
        val (_, files) = move(layout, name, capture = true, step)
        reset()
        delay(SETTLE_MILLIS)
        val markerPx = (Palette.MARKER_DP * model.density).roundToInt()
        val bandPx = (Palette.BAND_CSS_PX * model.density).roundToInt()
        val frame = frame(info)
        val offsets = withContext(Dispatchers.IO) {
            files.mapNotNull { file -> ImageIO.read(file)?.let { ShotCheck.edgeOffset(it, frame, edge, line, from, to, markerPx, bandPx) } }
        }
        val sync = when {
            offsets.size < 5 -> Verdict.INCONCLUSIVE
            offsets.all { abs(it) <= 2 } -> Verdict.PASS
            else -> Verdict.FAIL
        }
        report.add(
            layout.tag, "motion-sync", name, sync,
            "shots=${files.size} measured=${offsets.size} max|offset|=${offsets.maxOfOrNull { abs(it) } ?: "-"}px offsets=${offsets.joinToString(",")}",
        )
        val compose = FrameStats.summarize(FrameStats.intervals(frames), nominal)
        val page = FrameStats.summarize(pageIntervals, nominal)
        report.add(layout.tag, "frames", "$name-compose", frameVerdict(compose, nominal), "${compose.line()} on a ${info.maxFps} Hz display")
        report.add(layout.tag, "frames", "$name-page", frameVerdict(page, nominal), "${page.line()} on a ${info.maxFps} Hz display")
    }

    private suspend fun motion(layout: Layout) {
        model.menuOpen = false
        model.dialogOpen = false
        Native.evaluate(model.page, "stopAutoScroll();scrollTo(0,0)")
        delay(SETTLE_MILLIS)
        val page = model.rect("page")
        val info = info()
        // The list 0…160 px and back: the page moves with Compose's content; its top marker stays in view.
        measure(
            layout, "list-scroll", Edge.Top, page.left + page.width * 0.4f, page.top - 300f, page.top + 200f,
            reset = { model.listScroll.dispatchRawDelta(-model.listScroll.value.toFloat()) },
        ) { t -> model.listScroll.dispatchRawDelta((80 + 80 * sin(2 * PI * t / 1.5)).toFloat() - model.listScroll.value) }
        // The pane divider 0…160 dp and back: the page's left edge moves and its width changes.
        measure(
            layout, "pane-drag", Edge.Left, page.top + page.height * 0.6f, 0f, page.left + 400f * model.density,
            reset = { model.paneWidth = 0.dp },
        ) { t -> model.paneWidth = (80 + 80 * sin(2 * PI * t / 1.5)).dp }
        // The window 0…200 dp wider and back: the page's right edge follows the window's.
        val width = windowState.size.width
        val height = windowState.size.height
        measure(
            layout, "window-resize", Edge.Right, page.top + page.height * 0.6f, page.left,
            ((info.contentWidth + 220) * model.density).toFloat(),
            reset = { windowState.size = DpSize(width, height) },
        ) { t -> windowState.size = DpSize(width + (100 * (1 - cos(2 * PI * t / 1.5))).dp, height) }
        pageScrollingItself(layout)
    }

    private suspend fun pageScrollingItself(layout: Layout) {
        val info = info()
        Native.evaluate(model.page, "startAutoScroll()")
        delay(600)
        val before = model.pageFrames.size
        delay((MOTION_SECONDS * 1000).toLong())
        Native.evaluate(model.page, "stopAutoScroll()")
        val page = FrameStats.summarize(model.pageFrames.drop(before), info.nominalFrameMillis)
        report.add(layout.tag, "frames", "page-scrolling-itself", frameVerdict(page, info.nominalFrameMillis), "${page.line()} on a ${info.maxFps} Hz display")
    }

    private fun promotion(info: WindowInfo) {
        if (info.maxFps >= 120) {
            report.add("A+B", "fps120", "promotion", Verdict.INFO, "'${info.screen}' runs up to ${info.maxFps} Hz: read the frames rows against ${info.nominalFrameMillis.roundToInt()} ms")
        } else {
            report.add("A+B", "fps120", "promotion", Verdict.NOT_RUN, "'${info.screen}' runs up to ${info.maxFps} Hz: no ProMotion display on this Mac")
        }
    }
}
