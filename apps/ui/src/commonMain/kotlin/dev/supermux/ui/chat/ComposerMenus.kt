package dev.supermux.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.supermux.ui.widgets.ModalHost

// The composer's `/` and `@` menus as one floating card over the chat (card #56, option B): its left
// edge a little inside the composer's, its bottom just above it. The field keeps focus the whole time
// (the popup never takes it), so typing goes on filtering while the card is up.

/** Room around the card inside the popup, so its shadow is not clipped. */
private val ShadowRoom = 8.dp

/** The `/` card's width over a composer [composerWidth] wide: up to 760dp, never past the composer. */
fun slashMenuWidth(composerWidth: Dp): Dp = min(760.dp, composerWidth - ShadowRoom * 2)

/** The `@` card's width: a file list needs less. */
fun mentionMenuWidth(composerWidth: Dp): Dp = min(520.dp, composerWidth - ShadowRoom * 2)

/** The menu card itself, wherever it is drawn: rounded, raised, a whisper of an edge on dark surfaces. */
@Composable
internal fun Modifier.composerMenuCard(raised: Boolean): Modifier {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val dark = cs.surface.luminance() < 0.5f
    return this
        .then(if (raised) Modifier.shadow(18.dp, shape, clip = false) else Modifier)
        .clip(shape)
        .background(cs.surfaceContainerHigh)
        .then(if (dark) Modifier.border(1.dp, cs.onSurface.copy(alpha = 0.07f), shape) else Modifier)
}

/**
 * [content] as a card floating above the composer that hosts this call, [width] wide. Registers with
 * [ModalHost] like every menu, so no heavyweight native view paints over it.
 */
@Composable
internal fun ComposerMenuPopover(width: Dp, content: @Composable () -> Unit) {
    ModalHost {}
    Popup(
        popupPositionProvider = AboveComposer,
        // Never focusable: the field keeps focus, the caret and the keys. Back and outside clicks
        // belong to the screen; the menu closes when its token goes.
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Box(Modifier.padding(ShadowRoom)) {
            Box(Modifier.width(width).composerMenuCard(raised = true)) { content() }
        }
    }
}

/** Above the anchor, left edges together (the card then sits [ShadowRoom] inside the composer's edge). */
private object AboveComposer : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
        val x = anchorBounds.left.coerceIn(0, maxX)
        val y = (anchorBounds.top - popupContentSize.height).coerceAtLeast(0)
        return IntOffset(x, y)
    }
}
