package dev.supermux.android.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.supermux.android.R
import dev.supermux.android.theme.Space

/**
 * The resize gutter between the expanded sidebar and the detail: a hairline [outlineVariant] rule
 * inside a ~16dp-wide horizontal drag hit area (testTag `sidebar_divider`) whose drag reports a
 * width delta in dp via [onDragDelta] (px→dp conversion happens inside `pointerInput`, whose scope
 * is a Density). Near the top it hosts the collapse chevron (testTag `sidebar_collapse`,
 * [onCollapse]) — placed below the sidebar's app bar so it never collides with the list overflow.
 *
 * [onStartDrag]/[onEndDrag] bracket a drag so the caller can suppress the collapse/expand width
 * animation while the user is actively resizing (otherwise the spring lags behind the finger).
 */
@Composable
fun SidebarDivider(
    onDragDelta: (Dp) -> Unit,
    onCollapse: () -> Unit,
    onStartDrag: () -> Unit = {},
    onEndDrag: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxHeight()
            .width(16.dp),
    ) {
        // Hairline rule, centered in the gutter.
        Box(
            Modifier
                .align(Alignment.Center)
                .width(1.dp)
                .fillMaxHeight()
                .background(cs.outlineVariant),
        )
        // Drag-to-resize hit area over the whole gutter.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { onStartDrag() },
                        onDragEnd = { onEndDrag() },
                        onDragCancel = { onEndDrag() },
                    ) { _, drag -> onDragDelta(drag.x.toDp()) }
                }
                .testTag("sidebar_divider"),
        )
        // Collapse chevron, tucked just below the sidebar's app bar (clears its overflow menu). The
        // 48dp touch target (a11y minimum) grows DOWNward from the same top offset so it never rises
        // into the app-bar overflow button; the visible chip stays ~24dp.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 58.dp)
                .size(48.dp)
                .clickable(onClick = onCollapse)
                .testTag("sidebar_collapse"),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = "Collapse sidebar",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .rotate(180f),
                )
            }
        }
    }
}
