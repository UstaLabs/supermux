package dev.supermux.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp

/**
 * How many modal surfaces — dialogs, menus, popups — are open right now.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────
 *
 * Ahmet: "most modals etc. stays under when there is terminal view".
 *
 * Compose cannot paint over a heavyweight AWT child. The two in this app are
 * JediTerm (DesktopTerminalPanel) and KCEF (WebCodeEditor), and everything
 * Compose draws in its own layer — all 22 AlertDialogs, both raw Dialogs, all 24
 * DropdownMenus — is simply invisible while one of them is on screen.
 *
 * Measured, rather than assumed, with a probe under Xvfb (InteropZOrderProbe):
 *
 *   AlertDialog over a SwingPanel ............ invisible
 *   DropdownMenu / Popup over a SwingPanel ... invisible
 *   compose.layers.type=WINDOW ............... no effect, screenshot byte-identical
 *   compose.interop.blending=true ............ no effect, screenshot byte-identical
 *   DialogWindow (a real OS window) .......... works
 *
 * DialogWindow works but only solves dialogs: a dropdown cannot sensibly become
 * its own OS window, and it would mean converting 49 call sites. So this takes
 * the approach the codebase already proves everywhere else — swap, don't overlay
 * (DropZones.kt:26, EditorPanel.kt:563, DesktopTerminalPanel.kt:170). While
 * anything modal is open the heavyweight child is laid out at 0×0, which is the
 * only kind of hiding it respects, and Compose then draws normally.
 *
 * The count is a COUNT, not a flag: menus nest (a dialog containing a dropdown),
 * and two overlapping opens must not have the first close re-show the terminal
 * underneath the second.
 */
@Stable
class ModalPresence {
    var count by mutableStateOf(0)
        private set

    /** True while any modal surface is open, so heavyweight children must hide. */
    val anyOpen: Boolean get() = count > 0

    fun retain() {
        count += 1
    }

    fun release() {
        // Never below zero: a stray release would leave the terminal hidden.
        count = (count - 1).coerceAtLeast(0)
    }
}

/**
 * App-wide, so a dialog anywhere hides the heavyweight children everywhere. The
 * default instance keeps previews, tests and design fixtures composable without
 * a provider — they simply never hide anything.
 */
val LocalModalPresence = staticCompositionLocalOf { ModalPresence() }

/**
 * Count this composable as an open modal for exactly as long as it is composed.
 *
 * Put it INSIDE the branch that shows the modal, so it comes and goes with the
 * modal itself rather than with the screen hosting it.
 */
@Composable
fun ModalOpen() {
    val presence = LocalModalPresence.current
    DisposableEffect(presence) {
        presence.retain()
        onDispose { presence.release() }
    }
}

/**
 * Wrap a heavyweight AWT child (JediTerm, KCEF) so it steps aside while anything
 * modal is open.
 *
 * The outer box KEEPS its full size; only the inner slot collapses to 0×0. That
 * distinction matters: [KeepAlivePanel] shrinks the wrapper itself, which is
 * right for a background tab nobody can see, but here the pane is still on
 * screen behind the dialog — collapsing it outright makes every sibling reflow
 * and the layout visibly jump the moment a menu opens. Reserving the space
 * leaves whatever the parent paints (the terminal's own background) showing
 * through, so the pane just goes quiet instead of vanishing.
 *
 * Hiding is by LAYOUT because that is the only kind a heavyweight AWT child
 * respects — alpha and zIndex are drawing modifiers and it ignores both. The
 * content stays in the same composition slot throughout, so the terminal keeps
 * its websocket, grid and scrollback, and the browser is never recreated.
 */
@Composable
fun HeavyweightModalShield(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val hidden = LocalModalPresence.current.anyOpen
    Box(modifier.fillMaxSize()) {
        Box(if (hidden) Modifier.size(0.dp).clipToBounds() else Modifier.fillMaxSize()) {
            content()
        }
    }
}
