package dev.supermux.editor.sample

import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import dev.supermux.editor.syntax.NativeBackend
import java.awt.Component
import java.awt.event.MouseWheelEvent
import javax.swing.SwingUtilities

/**
 * The desktop sample: `./gradlew :editor-sample:run`. With `-Psample.bench=true` it opens the
 * 10k-line file, runs the in-window benchmark (keystrokes, then wheel scrolling) and prints the
 * result as a line starting with `EDITOR_BENCH`; the window stays open.
 */
fun main() {
    val bench = System.getProperty("editor.sample.bench") == "true"
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "supermux editor-sample",
            state = rememberWindowState(width = 1100.dp, height = 820.dp),
        ) {
            val w = window
            SampleApp(
                loadBackend = { NativeBackend() },
                bench = if (bench) { json -> println("EDITOR_BENCH $json") } else null,
                scrollDriver = { repeat(400) { wheel(w, 1f); withFrameNanos { } } },
                typeDriver = {
                    click(w)
                    val random = kotlin.random.Random(7)
                    repeat(200) { delay(random.nextLong(30, 80)); typeX(w) }
                },
            )
        }
    }
}

/** The component under the window's centre (Compose's surface), and that point in it. */
private fun surfaceAt(window: java.awt.Window): Pair<Component, java.awt.Point>? {
    val root = (window as? javax.swing.RootPaneContainer)?.contentPane ?: return null
    val x = root.width / 2
    val y = root.height / 2 + 40
    val target: Component = SwingUtilities.getDeepestComponentAt(root, x, y) ?: return null
    return target to SwingUtilities.convertPoint(root, x, y, target)
}

// The injections below run as their OWN event-queue events (invokeLater), as real input does:
// dispatching from inside a Compose coroutine would re-enter Compose's frame dispatcher.

/** A mouse click into the text (it focuses the editor and places the caret). */
private fun click(window: java.awt.Window) = SwingUtilities.invokeLater {
    val (c, p) = surfaceAt(window) ?: return@invokeLater
    val now = System.currentTimeMillis()
    for (id in listOf(java.awt.event.MouseEvent.MOUSE_PRESSED, java.awt.event.MouseEvent.MOUSE_RELEASED)) {
        c.dispatchEvent(java.awt.event.MouseEvent(c, id, now, java.awt.event.InputEvent.BUTTON1_DOWN_MASK, p.x, p.y, p.x, p.y, 1, false, java.awt.event.MouseEvent.BUTTON1))
    }
}

/** An `x` typed on the keyboard: pressed, typed, released (the time is recorded for the benchmark). */
private fun typeX(window: java.awt.Window) = SwingUtilities.invokeLater {
    val (c, _) = surfaceAt(window) ?: return@invokeLater
    val now = System.currentTimeMillis()
    lastKeyPostedMs = platformNowMs()
    c.dispatchEvent(java.awt.event.KeyEvent(c, java.awt.event.KeyEvent.KEY_PRESSED, now, 0, java.awt.event.KeyEvent.VK_X, 'x'))
    c.dispatchEvent(java.awt.event.KeyEvent(c, java.awt.event.KeyEvent.KEY_TYPED, now, 0, java.awt.event.KeyEvent.VK_UNDEFINED, 'x'))
    c.dispatchEvent(java.awt.event.KeyEvent(c, java.awt.event.KeyEvent.KEY_RELEASED, now, 0, java.awt.event.KeyEvent.VK_X, 'x'))
}

/** A wheel event into the window's Compose surface (the component under the window's centre). */
private fun wheel(window: java.awt.Window, notches: Float) = SwingUtilities.invokeLater {
    val (target, p) = surfaceAt(window) ?: return@invokeLater
    target.dispatchEvent(
        MouseWheelEvent(
            target, MouseWheelEvent.MOUSE_WHEEL, System.currentTimeMillis(), 0, p.x, p.y, p.x, p.y, 0, false,
            MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, notches.toInt().coerceAtLeast(1), notches.toDouble(),
        ),
    )
}
