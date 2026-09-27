package dev.supermux.editor.compose

import kotlinx.cinterop.toKString

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
 *
 * ⚠️ This depends on Compose-internal class names (`ComposeTextInputView`, `NativeTextInputView`;
 * verified with Compose Multiplatform [VERIFIED_WITH]). Compose creates its input view class with the
 * first input session, so [disable] is retried until it finds it; [verify] runs once an input view is
 * the first responder and checks that it really answers `.no` for all three. Either failure is logged
 * once and reported in [EditorDiagnostics.smartPunctuation]; `device-checks/ios-sim.sh` fails on it.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object SmartPunctuation {
    const val VERIFIED_WITH = "1.12.0"
    private var patched = false
    private var warned = false

    fun disable() {
        if (patched) return
        if (dev.supermux.editor.compose.uikit.editor_disable_smart_punctuation() > 0) {
            patched = true
            dev.supermux.editor.compose.uikit.editor_reload_input_traits()
        }
    }

    /**
     * After an input session made its view first responder: patched, and all three traits `.no`.
     * A failure is logged once (a Compose upgrade renamed or reworked the input view: curly quotes).
     */
    fun verify() {
        disable()
        val traits = dev.supermux.editor.compose.uikit.editor_first_responder_traits()
        if (traits < 0) return // no text input focused yet: nothing to check
        val status = when {
            !patched -> "NOT PATCHED: no Compose input view class found (first responder ${firstResponderClass()})"
            traits == 0x111L -> "off"
            else -> "NOT OFF: ${firstResponderClass()} answers quotes=${traits and 0xF} dashes=${(traits shr 4) and 0xF} insertDelete=${(traits shr 8) and 0xF}"
        }
        EditorDiagnostics.smartPunctuation = status
        if (status != "off" && !warned) {
            warned = true
            val msg = "editor-compose: iOS Smart Punctuation is NOT off ($status). The shim targets Compose " +
                "Multiplatform's internal input view classes (verified with $VERIFIED_WITH): curly quotes and dashes will be typed."
            println(msg)
            // No varargs: NSLog("%@", kotlinString) aborted the app (Kotlin/Native variadic call).
            platform.Foundation.NSLog(msg.replace("%", "%%"))
        }
    }

    private fun firstResponderClass(): String = dev.supermux.editor.compose.uikit.editor_first_responder_class()?.toKString() ?: "?"
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
    return androidx.compose.runtime.remember(keyboard) { { SmartPunctuation.disable(); keyboard?.show(); SmartPunctuation.verify(); Unit } }
}

internal actual fun platformClipboardHasText(): Boolean? = platform.UIKit.UIPasteboard.generalPasteboard.hasStrings

internal actual fun platformAfterKeyboardShown() = SmartPunctuation.verify()
