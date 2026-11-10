package dev.supermux.ui.browser

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.pointerInput
import dev.supermux.editor.compose.isApplePlatform

/** Opens a link where the workspace says (#105): the tab that shows it, a new Browser tab, or the system's browser. */
fun interface LinkOpener {
    fun open(url: String, sourceViewId: String?, modified: Boolean)
}

/** The workspace's opener. Null outside a workspace, and on the web client, whose links open a tab of the browser itself. */
val LocalLinkOpener = staticCompositionLocalOf<LinkOpener?> { null }

/** What a chat's links call: its view's opener, with the click's modifier. Null → `Platform.openUrl`. */
val LocalOpenLink = staticCompositionLocalOf<((String) -> Unit)?> { null }

/** Whether the last press inside held the platform's modifier, for a link click, which carries no modifiers of its own. */
class ClickModifiers {
    var modified: Boolean = false
        internal set
}

/**
 * Records [into] whether each press held ⌘ on Apple platforms or Ctrl elsewhere (#96's rule: never either), before
 * anything inside handles the press (the Initial pass).
 */
fun Modifier.trackClickModifiers(into: ClickModifiers, apple: Boolean = isApplePlatform): Modifier = pointerInput(into, apple) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Press) {
                into.modified = if (apple) event.keyboardModifiers.isMetaPressed else event.keyboardModifiers.isCtrlPressed
            }
        }
    }
}
