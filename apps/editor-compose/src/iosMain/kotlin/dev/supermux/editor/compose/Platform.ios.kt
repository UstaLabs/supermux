package dev.supermux.editor.compose

internal actual fun detectApplePlatform(): Boolean = true


internal actual fun installFastTyping(view: EditorView, controller: EditorController): (() -> Unit)? {
    SmartPunctuation.disable()
    return null
}

/**
 * iOS Smart Punctuation turns a typed `"` into `“`/`”`, `'` into `‘`/`’` and `--` into a dash: wrong
 * characters in code, and closing brackets never see a straight quote. Compose Multiplatform 1.12
 * sets no `smartQuotesType` / `smartDashesType` / `smartInsertDeleteType` on its input views (and
 * PlatformImeOptions has no field for them), so they are added to those classes (the cinterop shim).
 * Compose creates its input view class with the first input session, so this is retried until it
 * finds it; once patched, the keyboard is asked to read the traits again.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object SmartPunctuation {
    private var patched = false

    fun disable() {
        if (patched) return
        if (dev.supermux.editor.compose.uikit.editor_disable_smart_punctuation() > 0) {
            patched = true
            dev.supermux.editor.compose.uikit.editor_reload_input_traits()
        }
    }

    /** The first responder's smartQuotesType (1: off), -1 without one, -2 when it has no such trait. */
    fun firstResponderSmartQuotes(): Long = dev.supermux.editor.compose.uikit.editor_first_responder_smart_quotes()
}

internal actual fun androidx.compose.ui.Modifier.editorMagnifier(center: () -> androidx.compose.ui.geometry.Offset): androidx.compose.ui.Modifier = this

internal actual val platformTextToolbarPreferred: Boolean = true

internal actual val platformInputOnAnyFocus: Boolean = false

internal actual fun syncPlatformField(f: FieldText) {}

internal actual fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard = compose

internal actual val platformSurfaceText: Boolean = true

internal actual fun platformFieldLabel(label: String) {}

internal actual val platformClearsFieldSemantics: Boolean = true

/**
 * iOS: Compose's `SoftwareKeyboardController.show()` makes the input view first responder, but only
 * while an input session exists; at the tap itself it does not yet (the session starts once the
 * field has recomposed with the touch's options), so the keyboard is asked for again here, after.
 */
@androidx.compose.runtime.Composable
internal actual fun rememberPlatformKeyboardShow(): (() -> Unit)? {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    return androidx.compose.runtime.remember(keyboard) { { SmartPunctuation.disable(); keyboard?.show(); Unit } }
}

internal actual fun platformClipboardHasText(): Boolean? = platform.UIKit.UIPasteboard.generalPasteboard.hasStrings
