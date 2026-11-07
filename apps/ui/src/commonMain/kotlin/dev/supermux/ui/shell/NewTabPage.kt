// What a New tab shows (#91): a short list of what the tab can become, in the app's own chrome.
package dev.supermux.ui.shell

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Tab
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.adaptive.LocalHardwareKeyboard
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Motion
import dev.supermux.workspace.NewViewKind

/**
 * A New tab's page: where it opens ([workspace], [branch]) and a row per view kind ([choices]).
 * Picking one turns the tab into it ([onPick]); Esc closes the tab ([onClose]). [opening] is the
 * kind on its way, once one is picked.
 *
 * Keys: 1–5 open that row, ↑/↓ move the selection, Enter opens it. ⌘/Ctrl + digit is left alone: it
 * is kept for "go to tab N" (#96).
 *
 * Instant, on purpose (Ahmet: "the card animation makes the app feel slow"): nothing fades, slides
 * or scales — the selection, the hover wash and the press all change on the next frame. The one
 * thing that eases is a row's glyph turning teal, which he liked.
 */
@Composable
internal fun NewTabPage(
    choices: List<NewTabChoice>,
    workspace: String,
    branch: String?,
    opening: NewViewKind?,
    onPick: (NewViewKind) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalHardwareKeyboard.current && LocalPointerAvailable.current
    val touch = !LocalPointerAvailable.current
    var selected by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    // Not on touch: focus would bring up a soft keyboard nobody asked for.
    LaunchedEffect(Unit) { if (keyboard) runCatching { focus.requestFocus() } }

    fun pick(i: Int) {
        if (opening == null) choices.getOrNull(i)?.let { onPick(it.kind) }
    }

    val keys = Modifier.onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val digit = DIGITS.indexOf(e.key).takeIf { it >= 0 } ?: NUMPAD_DIGITS.indexOf(e.key).takeIf { it >= 0 }
        val plain = !e.isMetaPressed && !e.isCtrlPressed && !e.isAltPressed
        when {
            digit != null && plain && digit < choices.size -> { pick(digit); true }
            e.key == Key.DirectionDown -> { selected = (selected + 1).coerceAtMost(choices.size - 1); true }
            e.key == Key.DirectionUp -> { selected = (selected - 1).coerceAtLeast(0); true }
            e.key == Key.Enter || e.key == Key.NumPadEnter -> { pick(selected); true }
            e.key == Key.Escape -> { onClose(); true }
            else -> false
        }
    }

    BoxWithConstraints(modifier.fillMaxSize().then(keys).focusRequester(focus).focusable().testTag("new-tab-page")) {
        // Centred when it fits, a little above the middle where the eye lands; scrolls when it does not.
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = maxHeight)
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Column(Modifier.widthIn(max = 540.dp).fillMaxWidth()) {
                Eyebrow(workspace, branch, Modifier.padding(start = 12.dp))
                Spacer(Modifier.height(12.dp))
                choices.forEachIndexed { i, c ->
                    ChoiceRow(
                        c,
                        number = i + 1,
                        selected = selected == i,
                        opening = opening == c.kind,
                        touch = touch,
                        keyboard = keyboard,
                        onHover = { selected = i },
                        onClick = { pick(i) },
                    )
                }
                if (keyboard) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "↑↓ to move  ·  ↵ to open  ·  esc to close",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
            }
            Spacer(Modifier.height(64.dp))
        }
    }
}

private val DIGITS = listOf(Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
private val NUMPAD_DIGITS = listOf(
    Key.NumPad1, Key.NumPad2, Key.NumPad3, Key.NumPad4, Key.NumPad5, Key.NumPad6, Key.NumPad7, Key.NumPad8, Key.NumPad9,
)

/** "New tab · supermux · main". */
@Composable
private fun Eyebrow(workspace: String, branch: String?, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = cs.onSurface, fontWeight = FontWeight.Medium)) { append("New tab") }
            withStyle(SpanStyle(color = cs.onSurfaceVariant)) { append("  ·  $workspace") }
            if (branch != null) {
                withStyle(SpanStyle(color = cs.onSurfaceVariant.copy(alpha = 0.75f), fontFamily = MonoFontFamily, fontSize = 11.5.sp)) {
                    append("  ·  $branch")
                }
            }
        },
        fontSize = 12.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * One row: no frame and no fill at rest; the selected row (and the one under the pointer) wears a
 * soft wash, a shade deeper while pressed — all instant. Its number is its key.
 */
@Composable
private fun ChoiceRow(
    c: NewTabChoice,
    number: Int,
    selected: Boolean,
    opening: Boolean,
    touch: Boolean,
    keyboard: Boolean,
    onHover: () -> Unit,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val pressed by source.collectIsPressedAsState()
    LaunchedEffect(hovered) { if (hovered) onHover() }
    val wash = if (cs.surface.luminance() < 0.5f) 0.06f else 0.045f
    val fill = when {
        pressed -> cs.onSurface.copy(alpha = wash + 0.03f)
        selected -> cs.onSurface.copy(alpha = wash)
        else -> Color.Transparent
    }
    val glyph by animateColorAsState(if (selected) cs.primary else cs.onSurfaceVariant, Motion.stateChange(), label = "new-tab-glyph")
    Row(
        Modifier
            .fillMaxWidth()
            .height(if (touch) 52.dp else 40.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(fill)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp)
            .testTag("new-tab-choice-${c.kind.tag}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(c.icon, contentDescription = null, tint = glyph, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            c.label,
            color = cs.onSurface,
            fontSize = if (touch) 15.sp else 13.5.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            modifier = Modifier.width(if (touch) 96.dp else 84.dp),
        )
        Text(
            c.detail,
            color = cs.onSurfaceVariant,
            fontSize = if (touch) 14.sp else 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            opening -> Text("opening…", color = cs.primary, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
            c.alreadyOpen -> Box(
                Modifier
                    .padding(start = 8.dp)
                    .size(5.dp)
                    .background(LocalSemantics.current.success, CircleShape)
                    .testTag("new-tab-open-${c.kind.tag}"),
            )
        }
        if (keyboard) {
            Spacer(Modifier.width(12.dp))
            Text("$number", color = cs.onSurfaceVariant.copy(alpha = 0.7f), fontFamily = MonoFontFamily, fontSize = 11.5.sp)
        }
    }
}

/**
 * The phone's New tab: the tab grid's "+" opens the page full screen, over the grid, under a slim
 * bar like the one over any other view, with a way out. Touch-sized; no key hints and no keyboard
 * popping up.
 */
@Composable
internal fun PhoneNewTabPage(
    choices: List<NewTabChoice>,
    workspace: String,
    branch: String?,
    onPick: (NewViewKind) -> Unit,
    onClose: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.surfaceContainerLow).statusBarsPadding().testTag("phone_new_tab")) {
        Row(
            Modifier.fillMaxWidth().height(44.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Tab, contentDescription = null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                "New tab",
                color = cs.onSurface,
                fontFamily = MonoFontFamily,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            IconButton(onClick = onClose, modifier = Modifier.testTag("phone_new_tab_close")) {
                Icon(Icons.Filled.Close, contentDescription = "Close", tint = cs.onSurfaceVariant)
            }
        }
        NewTabPage(choices, workspace, branch, opening = null, onPick = onPick, onClose = onClose, modifier = Modifier.weight(1f))
    }
}
