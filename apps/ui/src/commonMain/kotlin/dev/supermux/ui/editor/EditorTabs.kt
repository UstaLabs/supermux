// Shared editor tab strip (UI cluster B, task B3): one implementation for both apps.
//
// Desktop's copy had NO caller (`grep 'EditorTabs(' apps/desktop/src/main` only found its own
// declaration — desktop renders editor tabs through :ui `PaneStripChrome`), so its flush square
// variant is dropped and ANDROID's geometry is the shared one: rounded 6dp-top chips, Space.sm/4dp
// strip padding, 10dp chip h-padding, 4dp inter-chip gaps, 6dp inner gap, haptic tick on
// select/close. Desktop's two additive touches are folded in: `pointerHoverIcon` on the chip and
// its close glyph, and Material `Icons.Filled.Close` in place of the bundled `ic_x` drawable.
package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.theme.HapticKind
import dev.supermux.ui.theme.Haptics
import dev.supermux.ui.theme.LocalPanes
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.rememberHaptics

@Composable
fun EditorTabs(
    tabs: List<Document>,
    activeTabPath: String?,
    loadingPath: String? = null,
    isDirty: (String) -> Boolean,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val scroll = rememberScrollState()

    if (tabs.isEmpty() && loadingPath == null) return

    Row(
        modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHigh)
            .horizontalScroll(scroll)
            .padding(horizontal = Space.sm, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            TabChip(
                path = tab.path,
                label = tab.path.substringAfterLast('/'),
                active = tab.path == activeTabPath,
                dirty = isDirty(tab.path),
                loading = false,
                onSelect = { onSelect(tab.path) },
                onClose = { onClose(tab.path) },
                haptic = haptic,
            )
        }
        if (loadingPath != null) {
            TabChip(
                path = loadingPath,
                label = loadingPath.substringAfterLast('/'),
                active = false,
                dirty = false,
                loading = true,
                onSelect = {},
                onClose = {},
                haptic = haptic,
            )
        }
    }
}

/** Test tag on a tab chip for [path]. */
fun editorTabTag(path: String): String = "editor_tab_$path"

/** Test tag on a tab chip's close glyph for [path]. */
fun editorTabCloseTag(path: String): String = "editor_tab_close_$path"

@Composable
private fun TabChip(
    path: String,
    label: String,
    active: Boolean,
    dirty: Boolean,
    loading: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    haptic: Haptics,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(if (active) cs.surfaceContainer else Color.Transparent)
            .clickable(enabled = !loading) {
                haptic.perform(HapticKind.Tick)
                onSelect()
            }
            // The loading chip stays non-interactive (default arrow).
            .pointerHoverIcon(if (loading) PointerIcon.Default else PointerIcon.Hand)
            .padding(horizontal = 10.dp)
            .testTag(editorTabTag(path)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            loading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(10.dp),
                    strokeWidth = 1.5.dp,
                    color = cs.onSurfaceVariant,
                )
            }
            dirty -> {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(Color(c.warning)),
                )
            }
        }
        Text(
            label,
            color = if (active) cs.onSurface else cs.onSurfaceVariant,
            fontFamily = MonoFontFamily,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (!loading) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Close",
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(14.dp)
                    .clickable {
                        haptic.perform(HapticKind.Tick)
                        onClose()
                    }
                    .pointerHoverIcon(PointerIcon.Hand)
                    .testTag(editorTabCloseTag(path)),
            )
        }
    }
}
