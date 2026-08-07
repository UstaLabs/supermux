package dev.supermux.desktop.ui

import java.awt.Component
import java.awt.Container
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.MouseWheelListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * Hosts a Swing interop child and can raise a transparent shield over it that
 * hands every mouse event to Compose instead.
 *
 * ── Why input needs its own fix, separate from painting ─────────────────────
 *
 * `compose.interop.blending` makes Compose PAINT over a Swing child on Metal.
 * It does not re-route INPUT, so a dialog drawn over the terminal looked right
 * and did nothing:
 *
 *   Ahmet: "it renders correctly on top of the terminal. But if I try to click
 *   on any button, it doesn't work."
 *
 * Painting order and hit-test order are decided by two different rules, which is
 * the whole bug. Measured from the live AWT tree rather than assumed:
 *
 *   ComposeWindowPanel
 *     [0] SwingInteropViewGroup      ← lightweight, and FIRST, so it wins hit-testing
 *           [0] the terminal widget
 *     [2] WindowSkiaLayerComponent
 *           [0] skiko.SkiaLayer      ← heavyweight, so it wins PAINTING regardless
 *
 * The interop group is not heavyweight; it simply sits at z-index 0. That is
 * what makes this fixable: a lightweight sibling at a LOWER index, inside a
 * container this app owns, can take the events first.
 *
 * So while a modal is open we add [shield] at index 0 and it forwards each event
 * to this panel's own parent — the `SwingInteropViewGroup`, which already
 * carries Compose's scene mouse listener. Compose then hit-tests the event
 * normally and the dialog's buttons work, with the terminal still fully visible
 * and live underneath.
 *
 * Verified by OBSERVED EFFECT, not by screenshot — the distinction that cost us
 * a release: a synthetic click on a dialog button logged `COMPOSE=1 SWING=0`
 * where it had been `COMPOSE=0 SWING=1`, and clicking a dropdown item selected
 * it while the panel's pixels stayed undimmed.
 *
 * Rejected alternatives, all measured: disabling the child (AWT dispatches to it
 * anyway), removing its listeners (the event still targets it and vanishes), and
 * sending the interop group to the back (input works, but blending stops
 * compositing it and the pane goes solid black).
 */
internal class ModalInteropOverlay(child: Component) : JPanel(null) {

    private val shield = object : JComponent() {
        init { isOpaque = false }
    }

    /**
     * True when the forward target is missing — see [forwardTarget]. The caller
     * uses it to fall back to hiding the child, which is the old behaviour, not
     * a new failure.
     */
    var forwardingBroken: Boolean = false
        private set

    init {
        add(child)

        val forward = object : MouseAdapter() {
            private fun send(e: MouseEvent) {
                val target = forwardTarget() ?: return
                target.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, target))
            }
            override fun mousePressed(e: MouseEvent) = send(e)
            override fun mouseReleased(e: MouseEvent) = send(e)
            override fun mouseClicked(e: MouseEvent) = send(e)
            override fun mouseMoved(e: MouseEvent) = send(e)
            override fun mouseDragged(e: MouseEvent) = send(e)
            override fun mouseEntered(e: MouseEvent) = send(e)
            override fun mouseExited(e: MouseEvent) = send(e)
        }
        shield.addMouseListener(forward)
        shield.addMouseMotionListener(forward)
        // Without this, scrolling over an open dropdown scrolls the TERMINAL
        // behind it — the menu is what the pointer is on, so it is what must move.
        shield.addMouseWheelListener(
            MouseWheelListener { e ->
                val target = forwardTarget() ?: return@MouseWheelListener
                target.dispatchEvent(SwingUtilities.convertMouseEvent(e.component, e, target) as? MouseWheelEvent ?: e)
            },
        )
    }

    /**
     * The `SwingInteropViewGroup` Compose parents this panel into. It carries
     * Compose's scene mouse listener, which is what makes forwarding work — an
     * undocumented internal, so treat its absence as normal and degrade.
     */
    private fun forwardTarget(): Container? {
        val target = parent
        forwardingBroken = target == null
        return target
    }

    /** Raise or drop the shield. Idempotent — this is called on every recomposition. */
    fun setShieldActive(active: Boolean) {
        val raised = shield.parent === this
        if (active == raised) return
        if (active) {
            add(shield, 0)
        } else {
            remove(shield)
            // Give the keyboard back. Without this the terminal stays unfocused
            // after a dialog closes and the next keystroke goes nowhere, so the
            // user has to click the pane before typing again.
            components.firstOrNull { it !== shield }?.requestFocusInWindow()
        }
        revalidate()
        repaint()
    }

    /** Every child fills the host; the shield must cover the child exactly. */
    override fun doLayout() {
        for (c in components) c.setBounds(0, 0, width, height)
    }
}
