package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
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
 * #104: scrolling a phone-width chat while a LIVE html card moves on screen. The rows around the card (markdown
 * replies) must not recompose per frame: nothing they show changes as the card moves.
 */
@OptIn(ExperimentalTestApi::class)
class HtmlCardRowRecompositionTest {
    private val session = SessionInfo(id = "s1", name = "demo", workdir = "/home/u/proj", agent = "claude")
    private val card = HtmlCardDto("abc", "Sales by region", height = 240)

    private fun reply(n: Int) =
        "**Reply $n** with `code`, a path src/app/Main$n.kt and a [link](https://example.com/$n).\n\n- one\n- two"

    private val entries: List<LogEntry> = (1..30).map {
        LogEntry(id = "m$it", ts = "2026-01-01T00:00:%02dZ".format(it), direction = "outbound", text = reply(it))
    } + LogEntry(id = "card1", ts = "2026-01-01T00:00:40Z", direction = "outbound", text = "", card = card) +
        (31..36).map {
            LogEntry(id = "m$it", ts = "2026-01-01T00:00:%02dZ".format(it + 10), direction = "outbound", text = reply(it))
        }

    private val engines = FakeWebEngineFactory(behavior = FakeEngineBehavior(contentHeight = { 240f }))
    private val page = "<div style='height:240px'></div>"
    private val host = HtmlCardHost(page = { page }, send = { _, _ -> null }, peek = { page })
    private val compositions = mutableMapOf<String, Int>()
    private fun count(name: String) = compositions[name] ?: 0

    /** Read by the host: a write recomposes the host, and ChatPanel with it (new lambdas). */
    private val hostTick = androidx.compose.runtime.mutableIntStateOf(0)

    private fun ComposeUiTest.panel() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val runtime = WebViewRuntime(
            RuntimeConfig(maxLive = 4, snapshotCacheBytes = 8L shl 20, factory = engines, scope = scope, clock = { mainClock.currentTime }),
        )
        setPlatformContent(pointer = false, widthClass = WindowWidthClass.Compact, inputMode = InputMode.Touch) {
            CompositionLocalProvider(
                LocalAppWebViews provides AppWebViews(runtime),
                LocalChatCompositionProbe provides { name -> compositions[name] = count(name) + 1 },
            ) {
                SupermuxTheme(appearance = AppearanceMode.DARK) {
                    Box(Modifier.requiredSize(width = 400.dp, height = 700.dp)) {
                    val tick = hostTick.intValue
                        ChatPanel(
                            session = session,
                            state = ChatState(messages = entries),
                            // New actions and new callbacks each time the host recomposes, as the app's chat host
                            // (ViewHost's ChatViewPane) hands them down: nothing in them changed for the rows.
                            actions = ChatActions(htmlCards = host, endCouncil = { tick.let { } }),
                            draft = "",
                            onDraftChange = { tick.let { } },
                            showHeader = true,
                            onOpenFile = { tick.let { } },
                            onOpenWalkthrough = { tick.let { } },
                        )
                    }
                }
            }
        }
        waitForIdle()
    }

    private class Frame(val cardTop: Float, val rows: Int, val markdown: Int, val transcript: Int, val changed: List<String>)

    @Test fun rowsDoNotRecomposeWhileALiveCardMoves() = runComposeUiTest {
        panel()
        val body = onNodeWithTag("chat_body")
        // Up from the end (finger down), so the card is in the middle of the screen and the chrome stays shown.
        body.performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, 15f)) }
            up()
        }
        waitForIdle()
        waitUntil(timeoutMillis = 5_000) { runOnUiThread { engines.created.any { e -> e.calls.any { it.startsWith("load:") } } } }
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        assertTrue(onNodeWithTag("fake-engine-${runOnUiThread { engines.created.single { e -> e.calls.any { it.startsWith("load:") } } }.id}", useUnmergedTree = true).fetchSemanticsNode().size.height > 100, "the card's page is live and laid out")

        // Every state object a global apply changes, by frame.
        val changed = mutableListOf<String>()
        val observer = Snapshot.registerApplyObserver { objs, _ -> objs.forEach { changed += describe(it) } }
        val frames = mutableListOf<Frame>()
        try {
            mainClock.autoAdvance = false
            body.performTouchInput { down(center) }
            // Past the touch slop first, so every recorded frame scrolls.
            body.performTouchInput { moveBy(Offset(0f, 40f)) }
            mainClock.advanceTimeByFrame()
            repeat(MOVES) {
                val r = count("row")
                val m = count("markdown")
                val t = count("transcript")
                changed.clear()
                body.performTouchInput { moveBy(Offset(0f, 6f)) }
                mainClock.advanceTimeByFrame()
                frames += Frame(
                    onNodeWithTag("html-card-page:card1").fetchSemanticsNode().positionInRoot.y,
                    count("row") - r, count("markdown") - m, count("transcript") - t, changed.toList(),
                )
            }
            body.performTouchInput { up() }
        } finally {
            observer.dispose()
            mainClock.autoAdvance = true
        }
        waitForIdle()
        frames.forEachIndexed { i, f ->
            println("frame $i: cardTop=${f.cardTop} rows=${f.rows} markdown=${f.markdown} transcript=${f.transcript} changed=${f.changed.groupingBy { it }.eachCount()}")
        }
        val tops = frames.map { it.cardTop }
        assertTrue(tops.zipWithNext().count { (a, b) -> a != b } >= MOVES - 2, "the card moves every frame: $tops")
        assertEquals(0, frames.sumOf { it.rows }, "no row recomposes while the card moves: ${frames.map { it.rows }}")
        assertEquals(0, frames.sumOf { it.markdown }, "no markdown body recomposes while the card moves: ${frames.map { it.markdown }}")
        assertEquals(0, frames.sumOf { it.transcript }, "the transcript does not recompose while the card moves: ${frames.map { it.transcript }}")
    }

    @Test fun rowsDoNotRecomposeWhenTheirHostRecomposesWithNothingNew() = runComposeUiTest {
        panel()
        mainClock.advanceTimeBy(2_000)
        waitForIdle()
        val per = (1..5).map {
            val r = count("row")
            val m = count("markdown")
            val t = count("transcript")
            hostTick.intValue++
            waitForIdle()
            Triple(count("transcript") - t, count("row") - r, count("markdown") - m)
        }
        println("host recompositions (transcript, rows, markdown): $per")
        assertTrue(per.all { it.first >= 1 }, "the transcript recomposes with its host: $per")
        assertEquals(0, per.sumOf { it.second }, "no row recomposes: $per")
        assertEquals(0, per.sumOf { it.third }, "no markdown body recomposes: $per")
    }

    private companion object {
        const val MOVES = 16

        /** A state object as its class and, for a value holder, the value's class (or the value, when short). */
        fun describe(o: Any): String {
            val value = (o as? androidx.compose.runtime.State<*>)?.let { s ->
                runCatching { Snapshot.withoutReadObservation { s.value } }.getOrNull()
            }
            val shown = when (value) {
                null -> "null"
                is Number, is Boolean -> value.toString()
                else -> value::class.simpleName ?: value::class.toString()
            }
            if (o is androidx.compose.runtime.snapshots.SnapshotStateMap<*, *>) return "Map(${o.keys.map { k -> k?.let { it::class.simpleName + ":" + it.toString().take(40) } }})"
            return "${o::class.simpleName}($shown)"
        }
    }
}
