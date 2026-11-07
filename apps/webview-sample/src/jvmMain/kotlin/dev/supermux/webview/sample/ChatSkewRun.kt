package dev.supermux.webview.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.ComposeWindow
import dev.supermux.webview.sample.chat.ChatState
import dev.supermux.webview.sample.chat.PreviewMarkers
import dev.supermux.webview.sample.checks.SkewCopier
import dev.supermux.webview.sample.checks.TrackpadChecks
import dev.supermux.webview.sample.checks.framePeriodNanosOf
import dev.supermux.webview.testing.MacEngineTesting
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** How long the chat rests before the first gesture: its previews load, and those on screen go live. */
private const val CHAT_SETTLE_MILLIS = 8_000L

/** Between gestures: the previews that came on screen go live (WhenVisible), so live pages are what moves next. */
private const val CHAT_REST_MILLIS = 2_500L

/** Two passes of drags and flicks, older (natural scrolling: the content down) and back, as a hand on a trackpad does. */
private val CHAT_GESTURES = List(2) {
    listOf(
        TrackpadChecks.Swipe("older-drag-500", 500f, 0.6, momentum = false),
        TrackpadChecks.Swipe("older-flick-1500", 1500f, 0.15, momentum = true),
        TrackpadChecks.Swipe("older-drag-800", 800f, 0.5, momentum = true),
        TrackpadChecks.Swipe("older-flick-2500", 2500f, 0.15, momentum = true),
        TrackpadChecks.Swipe("newer-drag-600", -600f, 0.6, momentum = false),
        TrackpadChecks.Swipe("newer-flick-2000", -2000f, 0.15, momentum = true),
        TrackpadChecks.Swipe("older-flick-3000", 3000f, 0.15, momentum = true),
        TrackpadChecks.Swipe("newer-drag-700", -700f, 0.5, momentum = true),
        TrackpadChecks.Swipe("newer-flick-1500", -1500f, 0.15, momentum = true),
    )
}.flatten()

/** The chat's column: [MAX_CHAT_WIDTH_DP] in the window's middle, under its top bar and its divider. */
private const val MAX_CHAT_WIDTH_DP = 720f
private const val TOP_BAR_DP = 65f

/**
 * The sample's chat as a person scrolls it on a Mac's trackpad (`-chat-skew`), its live WebGL previews and all: drags and
 * flicks with their momentum ([CHAT_GESTURES]), while the window is copied as fast as the window server gives it, and
 * every copy judged for how far each live preview's page is from its card ([dev.supermux.webview.sample.checks.CardSkew],
 * with [PreviewMarkers] on). Prints `MEASURE chat-skew: …`, then `CHECKS DONE`, which `SKEW=1 mac-engine-checks.sh` waits
 * for, and exits. Copies with a page well off its card go to [out]`/chat-skew`.
 */
@Composable
fun ChatSkewRun(window: ComposeWindow, chat: ChatState, out: File) {
    LaunchedEffect(chat) {
        // skiko's layer joins the window about the first frame (M8).
        while (window.windowHandle == 0L) withFrameNanos { }
        chat.list.scrollToItem(0)
        delay(CHAT_SETTLE_MILLIS)
        val s = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.takeIf { it > 0f } ?: 1f
        val width = window.contentPane.width * s
        val listHeight = chat.list.layoutInfo.viewportSize.height.toFloat()
        val left = width / 2 - MAX_CHAT_WIDTH_DP / 2 * s
        val top = TOP_BAR_DP * s + 2 * s
        val height = listHeight - 4 * s
        val copier = SkewCopier(
            window, left, top, MAX_CHAT_WIDTH_DP * s, height,
            minRun = 300,
            folder = File(out, "chat-skew"),
            framePeriodNanos = framePeriodNanosOf(window),
        )
        // Over the list, in the margin left of the chat's column: no page there takes the gesture.
        val pointerX = left / 2
        val pointerY = top + height / 2
        val engines = ArrayList<Int>()
        try {
            for (gesture in CHAT_GESTURES) {
                delay(CHAT_REST_MILLIS)
                engines += chat.lab.engines.existing
                val driver = thread(name = "chat-gesture", isDaemon = true) {
                    TrackpadChecks.swipe(window, pointerX, pointerY, gesture, onLifted = {})
                }
                withTimeoutOrNull(10_000) { while (driver.isAlive) delay(20) }
            }
            delay(CHAT_REST_MILLIS)
        } finally {
            val summary = copier.stop()
            val sync = MacEngineTesting.frameSyncState(window)
            println("MEASURE chat-skew: $summary; engines as each gesture began $engines; ${CHAT_GESTURES.size} gestures; frame sync $sync")
        }
        println("CHECKS DONE passed=1 failed=0")
        exitProcess(0)
    }
}
