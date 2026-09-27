package dev.supermux.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString

/**
 * The platform clipboard, as the terminal needs it: plain text in, plain text out.
 *
 * **Why an interface and not `LocalClipboard` directly.** Compose Multiplatform 1.12 ships two
 * clipboards. `LocalClipboard` (`Clipboard`, suspending, `ClipEntry`-based) is the one that is not
 * deprecated — but `ClipEntry` is an `actual` class per target with no COMMON way to build one from
 * a string (the desktop one wraps an AWT `Transferable`, Android's a `ClipData`), so a common-source
 * terminal cannot put text on it without one `actual` helper per platform. `LocalClipboardManager`
 * (`ClipboardManager.setText`/`getText`) is deprecated but common and is exactly plain text, which
 * is all a terminal ever copies or pastes.
 *
 * So the surface goes through this interface: [rememberTerminalClipboard] uses the common path
 * today, a host can substitute its own (a broker-side clipboard, a test double), and the day CMP
 * gives `ClipEntry` a common text constructor the swap is this one file.
 *
 * **Reads never happen on their own.** Only a user action — a paste key, the paste accessory, the
 * a11y paste action — ever calls [read]. Nothing the PROGRAM prints can reach it; see
 * [Terminal]'s `onClipboard`.
 */
@Stable
interface TerminalClipboard {
    /** The clipboard's text, or null when it holds none. */
    suspend fun read(): String?

    /** Put [text] on the clipboard. */
    suspend fun write(text: String)
}

/** The host platform's clipboard. */
@Composable
fun rememberTerminalClipboard(): TerminalClipboard {
    @Suppress("DEPRECATION")
    val manager = LocalClipboardManager.current
    return remember(manager) { ComposeTerminalClipboard(manager) }
}

@Suppress("DEPRECATION")
private class ComposeTerminalClipboard(private val manager: ClipboardManager) : TerminalClipboard {
    override suspend fun read(): String? = manager.getText()?.text?.takeIf { it.isNotEmpty() }

    override suspend fun write(text: String) {
        manager.setText(AnnotatedString(text))
    }
}
