package dev.supermux.webview.layouttest

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.CopyOnWriteArrayList

/** The two layouts; the ordinal is what the glue takes. */
enum class Layout(val tag: String) { Under("A"), Over("B") }

/**
 * The test window's state: Compose state where the UI reads it, plain fields where only the scenario does. Main thread,
 * except [listener], which the glue calls on the AppKit thread, and which only stamps and queues.
 */
class TestModel {
    var window: Long = 0L
    var density: Float = 2f
    var page: Long by mutableStateOf(0L)
    var layout: Layout by mutableStateOf(Layout.Under)
    var painted: Boolean by mutableStateOf(false)
    var menuOpen: Boolean by mutableStateOf(false)
    var dialogOpen: Boolean by mutableStateOf(false)

    /** False only for the control screenshot: layout B with nothing cut out. */
    var cutoutsEnabled: Boolean by mutableStateOf(true)
    var keyRouting: Boolean by mutableStateOf(false)
    var accessibilityBridge: Boolean by mutableStateOf(false)
    var paneWidth: Dp by mutableStateOf(0.dp)
    var composeText: String by mutableStateOf("")
    val listScroll = ScrollState(0)

    /** Where named things were last laid out, in window px. */
    val bounds = HashMap<String, Rect>()
    val received = CopyOnWriteArrayList<Received>()
    val pageFrames = CopyOnWriteArrayList<Double>()

    @Volatile var pageValue: String = ""

    @Volatile var pageScrollY: Float = 0f

    /** The page's text box: left, top, width, height in CSS px of its viewport. */
    @Volatile var textArea: List<Float>? = null

    val accessibility = Channel<PageEvent.Ax>(Channel.UNLIMITED)

    /** Everything the page and the glue said, stamped on arrival; applied on the main thread by [consume]. */
    val events = Channel<Pair<Long, PageEvent>>(Channel.UNLIMITED)
    val listener = PageListener { json -> events.trySend(System.currentTimeMillis() to PageMessages.parse(json)) }

    /** Compose took a click: a clickable's onClick, or the root's press listener. */
    fun composeClick(what: String) {
        received += Received(Side.Compose, what, System.currentTimeMillis())
    }

    /** Applies the page's events to this model until cancelled. Main thread. */
    suspend fun consume() {
        for ((at, event) in events) {
            when (event) {
                PageEvent.Painted -> painted = true
                is PageEvent.Down -> received += Received(Side.Page, "down@${event.target}", at)
                is PageEvent.Input -> pageValue = event.value
                is PageEvent.Scrolled -> pageScrollY = event.y
                is PageEvent.Frames -> pageFrames += event.intervalsMillis
                is PageEvent.Layout -> textArea = event.textArea
                is PageEvent.Ax -> accessibility.trySend(event)
                is PageEvent.Unknown -> println("page: ${event.raw}")
            }
        }
    }

    fun rect(name: String): Rect = bounds[name] ?: error("'$name' was never laid out")
}
