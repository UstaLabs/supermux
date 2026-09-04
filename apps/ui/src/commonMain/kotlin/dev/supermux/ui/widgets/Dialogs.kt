package dev.supermux.ui.widgets

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.window.DialogProperties

/**
 * How a modal surface announces itself to the host platform.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────
 *
 * Compose Desktop cannot paint over a heavyweight AWT child (JediTerm, JCEF), so on desktop every
 * dialog and every open menu has to make those children step aside while it is on screen — see
 * `desktop/ui/ModalPresence.kt` (`ModalPresence`, `ModalOpen`, `HeavyweightModalShield`), which is
 * a genuine desktop actual and stays there. Android has no such problem and needs nothing.
 *
 * The Material3 half of a dialog is identical on both, so it lives here, and the platform half is
 * reached through this local: a wrapper that is composed around (or alongside) the modal for
 * exactly as long as the modal is open. The default is the identity wrapper — Android, previews and
 * tests simply render the content. `DesktopTheme` provides `{ content -> ModalOpen(); content() }`.
 */
val LocalModalHost = staticCompositionLocalOf<@Composable (@Composable () -> Unit) -> Unit> {
    { content -> content() }
}

/**
 * Register the enclosed content as an open modal for as long as it is composed.
 *
 * Put it INSIDE the branch that shows the modal, so it comes and goes with the modal itself rather
 * than with the screen hosting it.
 */
@Composable
fun ModalHost(content: @Composable () -> Unit) {
    LocalModalHost.current(content)
}

/**
 * Drop-in replacements for the Material3 / Compose modal surfaces that also register with
 * [LocalModalHost].
 *
 * These deliberately carry the SAME NAMES as the originals. A call site opts in by changing one
 * import line —
 *
 *     -import androidx.compose.material3.AlertDialog
 *     +import dev.supermux.ui.widgets.AlertDialog
 *
 * — and nothing else, so nobody has to remember a bespoke wrapper name at the ~60 places this
 * matters. Kotlin resolves the explicitly imported symbol, so there is no ambiguity with the
 * library function.
 *
 * Only the parameters the two apps actually pass are exposed. Adding one later is a one-line change
 * here; guessing at Material3's full default surface today would pin us to internal default symbols
 * for no benefit.
 */
@Composable
fun AlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
) {
    ModalHost {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = confirmButton,
            modifier = modifier,
            dismissButton = dismissButton,
            icon = icon,
            title = title,
            text = text,
            properties = properties,
        )
    }
}

/** The raw (unstyled) dialog window — a full-bleed image/display viewer, not an M3 alert. */
@Composable
fun Dialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit,
) {
    ModalHost {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = onDismissRequest,
            properties = properties,
            content = content,
        )
    }
}
