package dev.supermux.desktop.shell

import java.awt.Frame
import java.awt.event.ComponentEvent
import java.awt.event.WindowEvent
import java.beans.PropertyChangeEvent
import javax.swing.JWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Card #132: the traffic-light inset was re-read every 200 ms forever (5 wakeups/s at idle). It now follows
 * what AWT announces, then settles, and never polls at rest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MacInsetEventsTest {
    @Test fun a_poke_reads_now_then_while_it_settles_then_never_again() = runTest {
        var reads = 0
        val settle = SettlingRefresh(backgroundScope) { reads++ }
        settle.poke()
        assertEquals(1, reads)
        advanceTimeBy(MAC_INSET_SETTLE_MS.last() + 1)
        assertEquals(1 + MAC_INSET_SETTLE_MS.size, reads)
        advanceTimeBy(60_000)
        assertEquals(1 + MAC_INSET_SETTLE_MS.size, reads, "no reads at rest")
    }

    @Test fun a_new_poke_starts_the_settling_over() = runTest {
        var reads = 0
        val settle = SettlingRefresh(backgroundScope) { reads++ }
        settle.poke()
        advanceTimeBy(150) // the 100 ms look has run
        settle.poke()
        runCurrent()
        assertEquals(3, reads)
        advanceTimeBy(MAC_INSET_SETTLE_MS.last() + 1)
        assertEquals(3 + MAC_INSET_SETTLE_MS.size, reads)
    }

    @Test fun cancel_stops_the_settling() = runTest {
        var reads = 0
        val settle = SettlingRefresh(backgroundScope) { reads++ }
        settle.poke()
        settle.cancel()
        advanceTimeBy(10_000)
        assertEquals(1, reads)
    }

    private fun withWindow(block: (JWindow, () -> Int) -> Unit) {
        val window = JWindow()
        var changes = 0
        val stop = listenForTitleBarChanges(window) { changes++ }
        try {
            block(window) { changes }
        } finally {
            stop()
            window.dispose()
        }
    }

    @Test fun a_resize_is_announced() = withWindow { w, changes ->
        w.dispatchEvent(ComponentEvent(w, ComponentEvent.COMPONENT_RESIZED))
        assertEquals(1, changes())
    }

    @Test fun a_move_is_announced() = withWindow { w, changes ->
        w.dispatchEvent(ComponentEvent(w, ComponentEvent.COMPONENT_MOVED))
        assertEquals(1, changes())
    }

    @Test fun being_shown_is_announced() = withWindow { w, changes ->
        w.dispatchEvent(ComponentEvent(w, ComponentEvent.COMPONENT_SHOWN))
        assertEquals(1, changes())
    }

    @Test fun a_window_state_change_is_announced() = withWindow { w, changes ->
        w.dispatchEvent(WindowEvent(w, WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.MAXIMIZED_BOTH))
        assertEquals(1, changes())
    }

    @Test fun another_screen_or_backing_scale_is_announced() = withWindow { w, changes ->
        val config = w.graphicsConfiguration
        w.getPropertyChangeListeners("graphicsConfiguration").forEach {
            it.propertyChange(PropertyChangeEvent(w, "graphicsConfiguration", config, config))
        }
        assertEquals(1, changes())
    }

    @Test fun nothing_is_heard_after_stopping() {
        val window = JWindow()
        val before = window.getPropertyChangeListeners("graphicsConfiguration").size
        var changes = 0
        val stop = listenForTitleBarChanges(window) { changes++ }
        stop()
        window.dispatchEvent(ComponentEvent(window, ComponentEvent.COMPONENT_RESIZED))
        window.dispatchEvent(WindowEvent(window, WindowEvent.WINDOW_STATE_CHANGED, Frame.NORMAL, Frame.MAXIMIZED_BOTH))
        assertEquals(before, window.getPropertyChangeListeners("graphicsConfiguration").size)
        assertEquals(0, changes)
        window.dispose()
    }
}
