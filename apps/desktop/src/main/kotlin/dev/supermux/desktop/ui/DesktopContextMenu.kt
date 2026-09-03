package dev.supermux.desktop.ui

import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.ContextMenuRepresentation
import androidx.compose.foundation.ContextMenuState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

/**
 * The right-click menu — text fields' cut/copy/paste and anything else that goes
 * through `ContextMenuArea` / `ContextMenuDataProvider`.
 *
 * ── Why this exists ─────────────────────────────────────────────────────────
 *
 * Ahmet: "in kmp desktop the menus are very ugly" — the right-click ones too.
 *
 * Compose Desktop draws these itself, and until something is provided at
 * `LocalContextMenuRepresentation` it uses `DefaultContextMenuRepresentation`:
 * a square-cornered slab with hardcoded greys that knows nothing about the app's
 * palette, so it stayed light-grey inside a dark app and had nothing in common
 * with the menus two clicks away. It is not a Swing popup and there is no theme
 * to fix — the only supported way to change it is to provide a different
 * representation, which is what this is.
 *
 * It reuses [MenuStyle] and [DropdownMenuItem] wholesale, so a right-click menu
 * and an in-app dropdown are the same object with different contents — which is
 * the entire reason to do this rather than hand-styling one popup.
 *
 * [ModalOpen] matters here as much as it does for the dropdowns: a right-click
 * inside a text field that sits over JediTerm or JCEF would otherwise open a menu
 * nobody can see (ModalPresence.kt has the measurements).
 */
class SupermuxContextMenuRepresentation : ContextMenuRepresentation {
    @Composable
    override fun Representation(state: ContextMenuState, items: () -> List<ContextMenuItem>) {
        val status = state.status
        if (status !is ContextMenuState.Status.Open) return
        val entries = items()
        if (entries.isEmpty()) return

        ModalOpen()
        val close = { state.status = ContextMenuState.Status.Closed }
        Popup(
            popupPositionProvider = remember(status.rect) { ContextMenuPositionProvider(status.rect) },
            onDismissRequest = close,
            properties = PopupProperties(focusable = true),
        ) {
            Surface(
                shape = MenuStyle.Shape,
                color = MenuStyle.containerColor,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = MenuStyle.border,
                shadowElevation = MenuStyle.ShadowElevation,
            ) {
                Column(
                    Modifier
                        .width(IntrinsicSize.Max)
                        .padding(MenuStyle.ListPaddingValues)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { item ->
                        DropdownMenuItem(
                            text = { Text(item.label) },
                            // Close first: an item that opens a dialog must not leave
                            // the menu (and therefore a retained ModalPresence) behind it.
                            onClick = {
                                close()
                                item.onClick()
                            },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Puts the menu's top-left corner at the pointer, the way every desktop platform
 * does, and keeps it inside the window.
 *
 * `anchorBounds` is the context-menu area in window coordinates and [rect] is the
 * click within that area, so the two add up to the pointer. When the menu would
 * run off the bottom or right it flips back across the pointer rather than
 * sliding, because sliding leaves the cursor sitting on top of a row the user
 * did not aim at.
 */
private class ContextMenuPositionProvider(private val rect: Rect) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val pointerX = anchorBounds.left + rect.left.roundToInt()
        val pointerY = anchorBounds.top + rect.top.roundToInt()
        val x = if (pointerX + popupContentSize.width > windowSize.width) {
            (pointerX - popupContentSize.width).coerceAtLeast(0)
        } else {
            pointerX
        }
        val y = if (pointerY + popupContentSize.height > windowSize.height) {
            (pointerY - popupContentSize.height).coerceAtLeast(0)
        } else {
            pointerY
        }
        return IntOffset(x, y)
    }
}
