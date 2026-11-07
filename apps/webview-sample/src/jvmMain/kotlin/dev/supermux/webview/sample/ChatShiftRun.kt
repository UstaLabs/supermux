package dev.supermux.webview.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.awt.ComposeWindow
import dev.supermux.webview.sample.chat.ChatState
import dev.supermux.webview.sample.checks.TrackpadChecks
import dev.supermux.webview.testing.MacEngineTesting
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/** How long the chat rests before the first gesture: its previews load, and those on screen go live. */
private const val SHIFT_SETTLE_MILLIS = 8_000L

/** Between gestures, as a person reads: the previews that came on screen go live (WhenVisible), load and report. */
private const val SHIFT_REST_MILLIS = 1_600L

/** The most gestures a way: the chat is read to its end well before. */
private const val SHIFT_MAX_GESTURES = 60

/** The chat's column: [SHIFT_COLUMN_DP] in the window's middle, under its top bar and its divider. */
private const val SHIFT_COLUMN_DP = 720f
private const val SHIFT_TOP_BAR_DP = 65f

/**
 * The sample's chat read as a person reads it on a Mac's trackpad (`-chat-shift`, with the chat's probe on): flicks with
 * their momentum toward the older messages, a rest after each as if reading, to the chat's start; then back to the
 * newest the same way, now that the pages it saw are remembered. The probe ([dev.supermux.webview.sample.chat.ChatProbe])
 * prints what moved under the reader's eyes, each preview's resizes and the slow frames, with a summary for each way.
 * Then `CHECKS DONE`, which `SHIFT=1 mac-engine-checks.sh` waits for, and it exits.
 */
@Composable
fun ChatShiftRun(window: ComposeWindow, chat: ChatState, out: File) {
    LaunchedEffect(chat) {
        val probe = chat.probe ?: error("-chat-shift needs the chat's probe")
        out.mkdirs()
        // skiko's layer joins the window about the first frame (M8).
        while (window.windowHandle == 0L) withFrameNanos { }
        chat.list.scrollToItem(0)
        delay(SHIFT_SETTLE_MILLIS)
        val s = window.graphicsConfiguration?.defaultTransform?.scaleX?.toFloat()?.takeIf { it > 0f } ?: 1f
        val width = window.contentPane.width * s
        val listHeight = chat.list.layoutInfo.viewportSize.height.toFloat()
        val left = width / 2 - SHIFT_COLUMN_DP / 2 * s
        // Over the list, in the margin left of the chat's column: no page there takes the gesture. At the list's middle,
        // where the probe has the reader's eyes.
        val pointerX = left / 2
        val pointerY = SHIFT_TOP_BAR_DP * s + listHeight / 2
        val older = TrackpadChecks.Swipe("older-flick", 1200f, 0.15, momentum = true)
        val newer = TrackpadChecks.Swipe("newer-flick", -1200f, 0.15, momentum = true)
        val watch = AppKitWatch(window, out, probe::now)
        for ((name, gesture) in listOf("trackpad, to the oldest, first look" to older, "trackpad, back to the newest" to newer)) {
            watch.drain()
            // By the wall clock, for a recording of the screen; and where the window is on it.
            println("MEASURE chat-shift $name from ${System.currentTimeMillis()} ms (probe ${probe.now().toLong()} ms), the window at ${window.bounds}")
            probe.begin(name)
            var gestures = 0
            while (gestures < SHIFT_MAX_GESTURES && (if (gesture === older) chat.list.canScrollForward else chat.list.canScrollBackward)) {
                gestures++
                probe.event("gesture $gestures")
                val driver = thread(name = "chat-shift-gesture", isDaemon = true) {
                    TrackpadChecks.swipe(window, pointerX, pointerY, gesture, onLifted = {})
                }
                withTimeoutOrNull(10_000) { while (driver.isAlive) delay(20) }
                probe.event("momentum done")
                delay(SHIFT_REST_MILLIS)
            }
            probe.end()
            println("MEASURE chat-shift $name: $gestures gestures; engines ${chat.lab.sample().enginesLine()}")
            println("MEASURE chat-shift $name: ${watch.drain()}")
        }
        watch.stop()
        println("MEASURE chat-shift: frame sync ${MacEngineTesting.frameSyncState(window)}")
        println("CHECKS DONE passed=1 failed=0")
        exitProcess(0)
    }
}
