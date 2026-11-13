package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import dev.supermux.proto.HtmlCardDto
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.browser.AppWebViews
import dev.supermux.ui.browser.LocalAppWebViews
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.SupermuxTheme
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import dev.supermux.webview.testing.FakeEngineBehavior
import dev.supermux.webview.testing.FakeWebEngineFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * #104: scrolling a phone-width chat with a tall html card in view. The chrome auto-hide slides the header and the
 * composer for 200 ms at each change of direction; the card (a live native web view, which iOS re-lays out and
 * repaints at every new size) must keep its size while it does, and the transcript must not recompose per frame.
 */
@OptIn(ExperimentalTestApi::class)
class HtmlCardScrollTest {
    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")

    // A page far taller than the card's cap, so the cap is the card's height.
    private val card = HtmlCardDto("abc", "Sales by region", height = 300)

    private val entries: List<LogEntry> = (1..70).map {
        LogEntry(id = "m$it", ts = "2026-01-01T00:%02d:%02dZ".format(it / 60, it % 60), direction = "outbound", text = "message number $it")
    } + LogEntry(id = "card1", ts = "2026-01-01T00:01:20Z", direction = "outbound", text = "", card = card) +
        (71..76).map {
            LogEntry(id = "m$it", ts = "2026-01-01T00:%02d:%02dZ".format(it / 60 + 1, it % 60 + 30), direction = "outbound", text = "message number $it")
        }

    private val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 3000f }))
    private val host = HtmlCardHost(page = { "<div style='height:3000px'></div>" }, send = { _, _ -> null }, peek = { "<div style='height:3000px'></div>" })
    private val MOVES = 24
    private val compositions = mutableMapOf<String, Int>()

    private fun ComposeUiTest.panel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 4, snapshotCacheBytes = 8L shl 20, factory = engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            CompositionLocalProvider(
                LocalAppWebViews provides AppWebViews(runtime),
                LocalChatCompositionProbe provides { name -> compositions[name] = (compositions[name] ?: 0) + 1 },
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.requiredSize(width = 400.dp, height = 700.dp)) {
                        ChatPanel(
                            session = session,
                            state = ChatState(messages = entries),
                            actions = ChatActions(htmlCards = host),
                            draft = "",
                            onDraftChange = {},
                            showHeader = true,
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    /** A slow finger drag (no fling): positive [dy] moves the finger down = scrolls UP the transcript. */
    private fun SemanticsNodeInteraction.drag(dy: Float) = performTouchInput {
        down(center)
        repeat(20) { moveBy(Offset(0f, dy / 20)) }
        up()
    }

    private class Frames {
        val cardHeights = mutableListOf<Float>()
        val surfaces = mutableListOf<IntSize>()
        val docks = mutableListOf<Float>()
        val transcript = mutableListOf<Int>()
        val card = mutableListOf<Int>()

        fun changes(xs: List<Any>) = xs.zipWithNext().count { (a, b) -> a != b }
        override fun toString() =
            "frames=${docks.size} dock=${docks.map { it.toInt() }} card=${cardHeights.map { it.toInt() }} surfaces=${surfaces.map { it.height }} " +
                "transcript compositions per frame=$transcript card compositions per frame=$card"
    }

    /**
     * A slow drag of [dy] px, one move per frame (the clock stepped by hand), then a few frames more: the chrome flips
     * a few moves in and slides for its 200 ms while the finger is still moving, as on a phone. Records every frame.
     */
    private fun ComposeUiTest.slide(dy: Float): Frames {
        val frames = Frames()
        val body = onNodeWithTag("chat_body")
        fun frame(step: () -> Unit) {
            val t = compositions["transcript"] ?: 0
            val c = compositions["card"] ?: 0
            step()
            frames.docks += onNodeWithTag("chat_composer_dock").getBoundsInRoot().height.value
            // The view's own height, not its clipped bounds: the list clips a card that runs under the composer.
            frames.cardHeights += onNodeWithTag("html-card-page:card1").fetchSemanticsNode().size.height.toFloat()
            frames.surfaces += runOnUiThread { engines.created.filter { it.calls.any { c -> c.startsWith("load:") } }.map { it.surfaceSize }.single() }
            frames.transcript += (compositions["transcript"] ?: 0) - t
            frames.card += (compositions["card"] ?: 0) - c
        }
        mainClock.autoAdvance = false
        body.performTouchInput { down(center) }
        // Each move is an event 16 ms after the last one: the clock moves a frame per move.
        repeat(MOVES) { frame { body.performTouchInput { moveBy(Offset(0f, dy / MOVES)) } } }
        body.performTouchInput { up() }
        repeat(4) { frame { mainClock.advanceTimeByFrame() } }
        mainClock.autoAdvance = true
        waitForIdle()
        return frames
    }

    @Test fun aTallCardKeepsItsSizeAndTheTranscriptDoesNotRecomposeWhileTheChromeSlides() = runComposeUiTest {
        panel()
        // Up from the end, far enough that the bottom zone lets the chrome hide, with the card still in view.
        onNodeWithTag("chat_body").drag(+300f)
        waitForIdle()
        waitForIdle()
        // On the UI thread: the fake engines record their calls there.
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { engines.created.any { e -> e.calls.any { it.startsWith("load:") } } } }
        // Let the page's reported height settle the card at its cap.
        mainClock.advanceTimeBy(2_000)
        waitForIdle()

        val hiding = slide(-200f)
        val showing = slide(+200f)
        println("hiding:  $hiding")
        println("showing: $showing")

        for ((name, f) in listOf("hiding" to hiding, "showing" to showing)) {
            assertTrue(f.changes(f.docks) >= 3, "$name: the chrome slides over several frames: $f")
            assertTrue(f.surfaces.first().height > 100, "$name: the card is in view: $f")
            assertEquals(0, f.changes(f.cardHeights), "$name: the card's height never changes while the chrome slides: $f")
            assertEquals(0, f.changes(f.surfaces), "$name: the web view's surface is never resized while the chrome slides: $f")
            // Once, as the chrome flips (the panel reads whether it is hidden), never per frame of the slide.
            assertTrue(f.transcript.sum() <= 1, "$name: the transcript does not recompose while the chrome slides: $f")
            assertEquals(0, f.card.sum(), "$name: the card does not recompose while the chrome slides: $f")
        }
    }
}
