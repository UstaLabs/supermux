// Ported from apps/android/src/main/kotlin/dev/supermux/android/editor/EditorSearchBar.kt — keep
// in sync until a shared UI module exists.
//
// Desktop adaptations vs. the Android source:
//   - Bundled drawable ic_search → Icons.Filled.Search.
//   - `rememberHaptics()(HapticKind.Tick)` on scrim-dismiss / result-tap dropped — see FileTree.kt's
//     note; no haptic actuator on desktop, and no ported desktop file wires the no-op haptics stub
//     at a call site.
//   - `pointerHoverIcon(PointerIcon.Hand)` added to the dismiss scrim and each result row (desktop
//     mouse affordance; Android is touch-only).
package dev.supermux.desktop.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.desktop.theme.MonoFontFamily
import dev.supermux.desktop.theme.Space
import dev.supermux.net.FsSearchResult

@Composable
fun EditorSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme

    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(cs.surfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = Space.sm, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Search,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(14.dp),
        )
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            textStyle = TextStyle(
                color = cs.onSurface,
                fontFamily = MonoFontFamily,
                fontSize = 12.sp,
            ),
            cursorBrush = SolidColor(cs.primary),
            modifier = Modifier
                .weight(1f)
                .padding(start = Space.sm),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            "Search files…",
                            color = cs.onSurfaceVariant,
                            fontFamily = MonoFontFamily,
                            fontSize = 12.sp,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/** Full-screen overlay: dim scrim + dropdown below the editor header. */
@Composable
fun EditorSearchOverlay(
    results: List<FsSearchResult>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val scroll = rememberScrollState()

    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.25f))
                .clickable { onDismiss() }
                .pointerHoverIcon(PointerIcon.Hand),
        )
        Column(
            Modifier
                .align(Alignment.TopStart)
                .padding(top = 44.dp, start = 48.dp, end = 48.dp)
                .fillMaxWidth()
                .shadow(8.dp, RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .background(cs.surfaceContainer)
                .heightIn(max = 240.dp)
                .verticalScroll(scroll),
        ) {
            results.forEach { result ->
                val alpha = if (result.ignored) 0.5f else 1f
                Text(
                    result.path,
                    color = cs.onSurface.copy(alpha = alpha),
                    fontFamily = MonoFontFamily,
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = result.path }
                        .clickable { onSelect(result.path) }
                        .pointerHoverIcon(PointerIcon.Hand)
                        .padding(horizontal = Space.md, vertical = 10.dp),
                )
            }
        }
    }
}
