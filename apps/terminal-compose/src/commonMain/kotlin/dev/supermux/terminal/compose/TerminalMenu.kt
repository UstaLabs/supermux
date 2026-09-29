package dev.supermux.terminal.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/** What the surface's own menu can do. */
enum class TerminalMenuAction(val label: String) {
    COPY("Copy"),
    PASTE("Paste"),
    SELECT_ALL("Select All"),
    FIND("Find"),
}

/**
 * A request to show the surface's menu at [anchor] (the surface's own pixels). [touch] picks the
 * shape: a horizontal bubble ABOVE the selection, like every phone's text-selection bar, or a
 * vertical context menu hanging from the mouse pointer. [belowY] is where a bubble that has no room
 * above goes instead: under the selection's END handle, never on top of it.
 */
@Immutable
internal data class TerminalMenuRequest(val anchor: Offset, val touch: Boolean, val belowY: Float = anchor.y)

/**
 * The copy/paste menu: a right-click on a desktop, a long press or a finished handle drag on a phone.
 *
 * A [Popup], not a child of the surface's box, for one reason that matters: the surface's pointer
 * handler sits on that box and would see a click on a child item as a press on the grid (focus, a
 * selection, a link). A popup is its own layer, so the grid never hears about it. It is NOT
 * focusable, so the terminal keeps the keyboard — and on a phone, the soft keyboard stays up.
 */
@Composable
internal fun TerminalMenu(
    request: TerminalMenuRequest,
    actions: List<Pair<TerminalMenuAction, Boolean>>,
    theme: TerminalTheme,
    onAction: (TerminalMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val density = LocalDensity.current
    val gapPx = with(density) { (if (request.touch) 28.dp else 2.dp).roundToPx() }
    val anchor = IntOffset(request.anchor.x.roundToInt(), request.anchor.y.roundToInt())
    Popup(
        popupPositionProvider = AnchoredPosition(anchor, request.belowY.roundToInt(), gapPx, above = request.touch),
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        MenuSurface(theme, Modifier.testTag(TerminalMenuTags.MENU)) {
            if (request.touch) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    actions.forEach { (action, enabled) -> MenuItem(action, enabled, theme, onAction, wide = false) }
                }
            } else {
                Column(Modifier.widthIn(min = 140.dp)) {
                    actions.forEach { (action, enabled) -> MenuItem(action, enabled, theme, onAction, wide = true) }
                }
            }
        }
    }
}

/**
 * "This paste may run commands": the confirmation the engine's paste safety check asks for.
 *
 * The engine refuses text that could inject a command — a newline while the program has NOT turned
 * bracketed paste on, where every line would run as it lands. Refusing silently is what the surface
 * did before, and a paste that does nothing is indistinguishable from a broken clipboard.
 */
@Composable
internal fun TerminalPasteConfirmation(
    text: String,
    theme: TerminalTheme,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val lines = text.count { it == '\n' } + if (text.endsWith('\n')) 0 else 1
    Popup(
        alignment = Alignment.Center,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = false),
    ) {
        MenuSurface(theme, Modifier.testTag(TerminalMenuTags.PASTE_CONFIRM).widthIn(max = 360.dp)) {
            Column(Modifier.padding(12.dp)) {
                BasicText(
                    text = "Paste $lines line${if (lines == 1) "" else "s"}?",
                    style = menuText(theme).copy(fontSize = 14.sp),
                )
                BasicText(
                    text = "The program did not turn on bracketed paste, so each line may run as a command.",
                    style = menuText(theme).copy(color = theme.foreground.copy(alpha = 0.7f), fontSize = 12.sp),
                    modifier = Modifier.padding(top = 6.dp, bottom = 10.dp),
                )
                Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MenuButton("Cancel", theme, TerminalMenuTags.PASTE_CANCEL, emphasized = false, onClick = onDismiss)
                    MenuButton("Paste", theme, TerminalMenuTags.PASTE_OK, emphasized = true, onClick = onConfirm)
                }
            }
        }
    }
}

/** Test tags of the surface's popups. */
object TerminalMenuTags {
    const val MENU = "terminal_menu"
    const val PASTE_CONFIRM = "terminal_paste_confirm"
    const val PASTE_OK = "terminal_paste_ok"
    const val PASTE_CANCEL = "terminal_paste_cancel"

    fun item(action: TerminalMenuAction): String = "terminal_menu_${action.name.lowercase()}"
}

@Composable
private fun MenuSurface(theme: TerminalTheme, modifier: Modifier, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    androidx.compose.foundation.layout.Box(
        modifier
            .clip(shape)
            .background(menuBackground(theme))
            .border(1.dp, theme.foreground.copy(alpha = 0.18f), shape)
            .padding(4.dp),
    ) { content() }
}

@Composable
private fun MenuItem(
    action: TerminalMenuAction,
    enabled: Boolean,
    theme: TerminalTheme,
    onAction: (TerminalMenuAction) -> Unit,
    wide: Boolean,
) {
    val style = menuText(theme).let { if (enabled) it else it.copy(color = theme.foreground.copy(alpha = 0.35f)) }
    BasicText(
        text = action.label,
        style = style,
        modifier = Modifier
            .testTag(TerminalMenuTags.item(action))
            .clip(RoundedCornerShape(5.dp))
            .clickable(enabled = enabled) { onAction(action) }
            .padding(horizontal = if (wide) 10.dp else 12.dp, vertical = if (wide) 6.dp else 10.dp),
    )
}

@Composable
private fun MenuButton(label: String, theme: TerminalTheme, tag: String, emphasized: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(6.dp)
    BasicText(
        text = label,
        style = menuText(theme).copy(color = if (emphasized) theme.background else theme.foreground),
        modifier = Modifier
            .testTag(tag)
            .clip(shape)
            .background(if (emphasized) theme.selectionHandle else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

private fun menuText(theme: TerminalTheme) =
    TextStyle(color = theme.foreground, fontFamily = theme.fontFamily, fontSize = 13.sp)

/** A surface a step away from the terminal's background, in whichever direction the theme runs. */
private fun menuBackground(theme: TerminalTheme): Color = lerp(theme.background, theme.foreground, 0.12f)

/**
 * Place the popup at [anchor] (relative to the surface's bounds): centred ABOVE it with [gapPx]
 * clearance when [above] (a finger would cover anything below), hanging down-right from it
 * otherwise, and always clamped inside the window.
 */
private class AnchoredPosition(
    private val anchor: IntOffset,
    private val belowY: Int,
    private val gapPx: Int,
    private val above: Boolean,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val x0 = anchorBounds.left + anchor.x
        val y0 = anchorBounds.top + anchor.y
        var x = if (above) x0 - popupContentSize.width / 2 else x0
        var y = if (above) y0 - popupContentSize.height - gapPx else y0 + gapPx
        // No room above: go below the selection, still clear of its end handle.
        if (above && y < 0) y = anchorBounds.top + belowY + gapPx
        x = x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
        y = y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0))
        return IntOffset(x, y)
    }
}
