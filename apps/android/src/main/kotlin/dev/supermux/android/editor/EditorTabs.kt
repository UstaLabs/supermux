package dev.supermux.android.editor

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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.LocalPanes
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics

@Composable
fun EditorTabs(
    tabs: List<EditorTab>,
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

@Composable
private fun TabChip(
    label: String,
    active: Boolean,
    dirty: Boolean,
    loading: Boolean,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    haptic: (HapticKind) -> Unit,
) {
    val c = LocalPanes.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
            .background(if (active) cs.surfaceContainer else Color.Transparent)
            .clickable(enabled = !loading) {
                haptic(HapticKind.Tick)
                onSelect()
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            loading -> {
                androidx.compose.material3.CircularProgressIndicator(
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
                painter = painterResource(R.drawable.ic_x),
                contentDescription = "Close",
                tint = cs.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier
                    .size(14.dp)
                    .clickable {
                        haptic(HapticKind.Tick)
                        onClose()
                    },
            )
        }
    }
}
