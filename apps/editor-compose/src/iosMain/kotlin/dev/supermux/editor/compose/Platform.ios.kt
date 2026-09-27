package dev.supermux.editor.compose

import androidx.compose.ui.unit.dp
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
 * PlatformImeOptions has no field for them), so the cinterop shim adds the three getters to their
 * ObjC base classes `CMPEditMenuView` (the base of `ComposeTextInputView`, BasicTextField's input view
 * in 1.12.0) and `CMPTextInputView`, answering `.no` only while an editor's field has the focus
 * ([focusChanged]) and the default otherwise: other Compose text fields of the app keep the user's
 * setting.
 *
 * ⚠️ This depends on Compose-internal class names (verified with Compose Multiplatform
 * [VERIFIED_WITH]). [verify] runs once per focus, after the input view became first responder, and
 * checks that it really answers `.no` for all three. A missing class or a wrong answer is logged once
 * and reported in [EditorDiagnostics.smartPunctuation]; `device-checks/ios-sim.sh` fails on it.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object SmartPunctuation {
    const val VERIFIED_WITH = "1.12.0"
    private val patched: Boolean by lazy { dev.supermux.editor.compose.uikit.editor_patch_smart_punctuation() != 0 }
    private var warned = false
    private var verifiedThisFocus = false

    fun disable() { patched }

    /** An editor's surface took or left the focus. */
    fun focusChanged(focused: Boolean) {
        disable()
        dev.supermux.editor.compose.uikit.editor_set_smart_punctuation_off(if (focused) 1 else 0)
        if (!focused) verifiedThisFocus = false
    }

    /**
     * Once per focus, after the input view became first responder: patched, and all three traits
     * `.no`. A failure is logged once (a Compose upgrade renamed or reworked the input view).
     */
    fun verify() {
        if (verifiedThisFocus) return
        val traits = dev.supermux.editor.compose.uikit.editor_first_responder_traits()
        if (patched && traits < 0) return // no text input focused yet: check on the next request
        verifiedThisFocus = true
        val status = when {
            !patched -> "NOT PATCHED: Compose's CMPEditMenuView / CMPTextInputView classes not found (first responder ${firstResponderClass()})"
            traits == 0x111L -> "off"
            else -> "NOT OFF: ${firstResponderClass()} answers quotes=${traits and 0xF} dashes=${(traits shr 4) and 0xF} insertDelete=${(traits shr 8) and 0xF}"
        }
        EditorDiagnostics.smartPunctuation = status
        if (status != "off" && !warned) {
            warned = true
            val msg = "editor-compose: iOS Smart Punctuation is NOT off ($status). The shim targets Compose " +
                "Multiplatform's internal input view classes CMPEditMenuView / CMPTextInputView (verified with $VERIFIED_WITH): curly quotes and dashes will be typed."
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

internal actual fun syncPlatformField(c: EditorController, f: FieldText) {}

internal actual fun platformEditorClipboard(compose: EditorClipboard): EditorClipboard = compose

internal actual val platformSurfaceText: Boolean = true

internal actual fun platformFieldLabel(c: EditorController, label: String) {}

internal actual val platformClearsFieldSemantics: Boolean = true

/**
 * iOS: Compose's `SoftwareKeyboardController.show()` makes the input view first responder, but only
 * while an input session exists; at the tap itself it does not yet (the session starts once the
 * field has recomposed with the touch's options), so the keyboard is asked for again here, after.
 */
@androidx.compose.runtime.Composable
internal actual fun rememberPlatformKeyboardShow(): (() -> Unit)? {
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    return androidx.compose.runtime.remember(keyboard) { { keyboard?.show(); Unit } }
}

internal actual fun platformClipboardHasText(): Boolean? = platform.UIKit.UIPasteboard.generalPasteboard.hasStrings

internal actual fun platformAfterKeyboardShown() {
    SmartPunctuation.verify()
    FloatingCursorBridge.install()
}

internal actual fun platformFocusChanged(c: EditorController, focused: Boolean) {
    SmartPunctuation.focusChanged(focused)
    FloatingCursorBridge.focusChanged(c, focused)
}

/**
 * The space-bar trackpad: the cinterop shim replaces the three floating cursor calls of the focused
 * input view's class (Compose's ComposeTextInputView, found as the first responder once the keyboard
 * is up) and, while an editor's field has the focus, sends them to that editor's [FloatingCursor];
 * any other Compose text field keeps Compose's handling. [EditorDiagnostics.floatingCursor] says
 * whether it is mapped.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
internal object FloatingCursorBridge {
    private var focused: EditorController? = null
    private var installed = false

    fun focusChanged(c: EditorController, isFocused: Boolean) {
        if (isFocused) focused = c else if (focused === c) { focused?.floatingCursor?.end(); focused = null }
    }

    fun install() {
        if (installed) return
        val r = dev.supermux.editor.compose.uikit.editor_patch_floating_cursor(kotlinx.cinterop.staticCFunction(::onFloatingCursor))
        if (r == -1) return // no first responder yet: on the next keyboard request
        installed = true
        EditorDiagnostics.floatingCursor = when (r) {
            1 -> "mapped"
            0 -> "NOT MAPPED: the input view has no floating cursor calls"
            else -> "NOT MAPPED: another input view class was patched"
        }
    }

    fun forward(phase: Int, x: Double, y: Double): Boolean {
        val fc = focused?.floatingCursor ?: return false
        val p = androidx.compose.ui.unit.DpOffset(x.dp, y.dp)
        when (phase) { 0 -> fc.begin(p); 1 -> fc.update(p); else -> fc.end() }
        return true
    }
}

private fun onFloatingCursor(phase: Int, x: Double, y: Double): Int = if (FloatingCursorBridge.forward(phase, x, y)) 1 else 0

/**
 * Debug API, for device checks: sends the first responder what a space-bar drag does (UIKit's
 * floating cursor calls: begin, updates over [dx], [dy] points, end), through UIKit's own dispatch.
 * False when no text input is first responder. Not for production use.
 */
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun debugDriveFloatingCursor(dx: Double, dy: Double): Boolean = dev.supermux.editor.compose.uikit.editor_drive_floating_cursor(dx, dy) == 1
