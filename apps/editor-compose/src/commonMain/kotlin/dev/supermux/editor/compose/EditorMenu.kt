package dev.supermux.editor.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus
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

/** One entry of the selection menu, in the platforms' own order. */
internal enum class MenuItem(val label: String) { CUT("Cut"), COPY("Copy"), PASTE("Paste"), SELECT_ALL("Select All") }

internal object EditorMenu {
    /** The test tag of the surface's own (non-platform) menu. */
    const val TAG = "editor-selection-menu"

    /** How long the menu stays hidden after the last scroll step. */
    const val SCROLL_SETTLE_MILLIS = 300L
}

/**
 * Whether the selection menu is the platform's text toolbar ([LocalTextToolbar]: Android's floating
 * action mode, iOS's `UIEditMenuInteraction`) or the surface's own popup (desktop and web, where
 * Compose's toolbar draws nothing touch-like). Tests switch it.
 */
internal val LocalEditorPlatformMenu = staticCompositionLocalOf { platformTextToolbarPreferred }

/**
 * What the menu offers now: Cut and Copy when something is selected (Cut not when read-only), Paste
 * when the clipboard has text (not when read-only), Select All unless everything is selected.
 */
internal fun menuItems(view: EditorView, readOnly: Boolean, clipboardHasText: Boolean): List<MenuItem> {
    val st = view.state
    val selected = st.selection.ranges.any { !it.empty }
    val main = st.selection.main
    val all = st.selection.ranges.size == 1 && main.from == 0 && main.to == st.doc.length
    return buildList {
        if (selected && !readOnly) add(MenuItem.CUT)
        if (selected) add(MenuItem.COPY)
        if (clipboardHasText && !readOnly) add(MenuItem.PASTE)
        if (!all) add(MenuItem.SELECT_ALL)
    }
}

/** Run [item] through [DefaultCommands]; Select All keeps the menu and shows the handles. */
internal fun runMenuItem(c: EditorController, item: MenuItem) {
    val view = c.view
    when (item) {
        MenuItem.CUT -> DefaultCommands.cut.run(view)
        MenuItem.COPY -> DefaultCommands.copy.run(view)
        MenuItem.PASTE -> DefaultCommands.paste.run(view)
        MenuItem.SELECT_ALL -> DefaultCommands.selectAll.run(view)
    }
    if (item == MenuItem.SELECT_ALL) {
        c.handles = TouchHandles.SELECTION
        c.menuShown = true
    } else {
        c.menuShown = false
    }
}

/**
 * The floating selection menu near the selection (or the caret): hidden while a handle is dragged
 * or the view scrolls, back afterwards; never without focus.
 */
@Composable
internal fun EditorSelectionMenu(c: EditorController, readOnly: Boolean, clipboard: EditorClipboard, theme: EditorTheme) {
    val visible = c.menuShown && !c.menuHeld && !c.scrolling && c.view.focused
    val items = if (visible) menuItems(c.view, readOnly, clipboard.hasText()) else emptyList()
    val anchor = if (visible && items.isNotEmpty()) c.menuAnchor() else null
    if (LocalEditorPlatformMenu.current) {
        PlatformMenu(c, LocalTextToolbar.current, items, anchor)
    } else if (anchor != null) {
        OwnMenu(c, items, anchor, theme)
    }
}

/**
 * The platform's toolbar: shown (again) whenever what it offers or where it points changes, hidden
 * when the menu goes away or the surface leaves the composition. Its rect is in root coordinates.
 */
@Composable
private fun PlatformMenu(c: EditorController, toolbar: TextToolbar, items: List<MenuItem>, anchor: Rect?) {
    val shown = remember(toolbar) { arrayOfNulls<Any>(1) }
    SideEffect {
        if (anchor != null) {
            val co = c.coordinates?.takeIf { it.isAttached }
            // Down past the touch handles: iOS's edit menu goes below the rect when it likes, and
            // must not cover them (seen on the simulator).
            val below = if (c.handles != TouchHandles.NONE) (EditorTouch.RADIUS_DP * 2 + 4) * c.densityValue else 0f
            val padded = Rect(anchor.left, anchor.top, anchor.right, anchor.bottom + below)
            val rect = if (co == null) padded else Rect(co.localToRoot(padded.topLeft), co.localToRoot(padded.bottomRight))
            val key = items to rect
            if (shown[0] != key) {
                shown[0] = key
                fun action(item: MenuItem): (() -> Unit)? = if (item in items) ({ runMenuItem(c, item) }) else null
                toolbar.showMenu(
                    rect,
                    onCopyRequested = action(MenuItem.COPY),
                    onPasteRequested = action(MenuItem.PASTE),
                    onCutRequested = action(MenuItem.CUT),
                    onSelectAllRequested = action(MenuItem.SELECT_ALL),
                )
            }
        } else if (shown[0] != null) {
            shown[0] = null
            if (toolbar.status == TextToolbarStatus.Shown) toolbar.hide()
        }
    }
    DisposableEffect(toolbar) {
        onDispose { if (shown[0] != null && toolbar.status == TextToolbarStatus.Shown) toolbar.hide() }
    }
}

/**
 * The surface's own menu, where the platform has no touch toolbar: a row of labels on an inverted
 * rounded bar (the look of iOS's and Android's), above the selection, or below its handles when
 * there is no room above.
 */
@Composable
private fun OwnMenu(c: EditorController, items: List<MenuItem>, anchor: Rect, theme: EditorTheme) {
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    val belowHandles = with(density) { (EditorTouch.RADIUS_DP * 2 + 8).dp.roundToPx() }
    val provider = remember(anchor, gap, belowHandles) { MenuPosition(anchor, gap, belowHandles) }
    val shape = RoundedCornerShape(8.dp)
    Popup(popupPositionProvider = provider, properties = PopupProperties(focusable = false)) {
        Row(
            Modifier.testTag(EditorMenu.TAG).shadow(4.dp, shape).clip(shape).background(theme.foreground),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEachIndexed { i, item ->
                if (i > 0) Box(Modifier.width(1.dp).height(18.dp).background(theme.background.copy(alpha = 0.25f)))
                BasicText(
                    item.label,
                    Modifier.clickable { runMenuItem(c, item) }.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = TextStyle(color = theme.background, fontSize = 14.sp),
                )
            }
        }
    }
}

/** Centred over [anchor] (surface pixels) [gap] above it, else [below] under it; kept in the window. */
private class MenuPosition(private val anchor: Rect, private val gap: Int, private val below: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        val cx = anchorBounds.left + anchor.center.x
        val x = (cx - w / 2f).roundToInt().coerceIn(0, maxOf(0, windowSize.width - w))
        val above = (anchorBounds.top + anchor.top).roundToInt() - gap - h
        val y = if (above >= maxOf(0, anchorBounds.top)) above else (anchorBounds.top + anchor.bottom).roundToInt() + below
        return IntOffset(x, y.coerceIn(0, maxOf(0, windowSize.height - h)))
    }
}
