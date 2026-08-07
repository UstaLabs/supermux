package dev.supermux.desktop.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Full-pane drop surface shown **instead of** the active view while a tab drag
 * is in progress.
 *
 * ⚠ Compose paints nothing above a SwingPanel (JediTerm / KCEF). Overlaying
 * these zones on a live editor or terminal makes them invisible. The host
 * swaps this surface in for the heavyweight child for the duration of the drag
 * and remounts the view on release — "swap the pane, don't overlay".
 *
 * Edge regions are the outer ~25% of each side; the centre means "move into
 * this group as a tab".
 */
@Composable
fun DropZoneSurface(
    activeZone: DropZone?,
    modifier: Modifier = Modifier,
    /**
     * False for a pane holding a single view: it cannot be split (splitGroup would
     * leave an empty group), so the edge targets are not drawn at all. Showing a
     * target that silently swallows the drop is worse than showing none.
     */
    edgesEnabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val idle = cs.primary.copy(alpha = 0.08f)
    val hot = cs.primary.copy(alpha = 0.28f)
    val border = cs.primary.copy(alpha = 0.55f)

    Column(modifier.fillMaxSize().background(cs.surface).testTag("drop-zone-surface")) {
        // Top edge
        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.25f)
                .background(if (!edgesEnabled) Color.Transparent else if (activeZone == DropZone.Top) hot else idle)
                .then(if (edgesEnabled && activeZone == DropZone.Top) Modifier.border(2.dp, border) else Modifier)
                .testTag("drop-zone-top"),
            contentAlignment = Alignment.Center,
        ) {
            if (edgesEnabled && activeZone == DropZone.Top) ZoneLabel("Split above")
        }
        Row(Modifier.fillMaxWidth().weight(0.5f)) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(0.25f)
                    .background(if (!edgesEnabled) Color.Transparent else if (activeZone == DropZone.Left) hot else idle)
                    .then(if (edgesEnabled && activeZone == DropZone.Left) Modifier.border(2.dp, border) else Modifier)
                    .testTag("drop-zone-left"),
                contentAlignment = Alignment.Center,
            ) {
                if (edgesEnabled && activeZone == DropZone.Left) ZoneLabel("Split left")
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(0.5f)
                    .background(if (activeZone == DropZone.Centre) hot else Color.Transparent)
                    .then(if (activeZone == DropZone.Centre) Modifier.border(2.dp, border) else Modifier)
                    .padding(8.dp)
                    .testTag("drop-zone-centre"),
                contentAlignment = Alignment.Center,
            ) {
                if (activeZone == DropZone.Centre) ZoneLabel("Move here")
            }
            Box(
                Modifier
                    .fillMaxHeight()
                    .weight(0.25f)
                    .background(if (!edgesEnabled) Color.Transparent else if (activeZone == DropZone.Right) hot else idle)
                    .then(if (edgesEnabled && activeZone == DropZone.Right) Modifier.border(2.dp, border) else Modifier)
                    .testTag("drop-zone-right"),
                contentAlignment = Alignment.Center,
            ) {
                if (edgesEnabled && activeZone == DropZone.Right) ZoneLabel("Split right")
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(0.25f)
                .background(if (!edgesEnabled) Color.Transparent else if (activeZone == DropZone.Bottom) hot else idle)
                .then(if (edgesEnabled && activeZone == DropZone.Bottom) Modifier.border(2.dp, border) else Modifier)
                .testTag("drop-zone-bottom"),
            contentAlignment = Alignment.Center,
        ) {
            if (edgesEnabled && activeZone == DropZone.Bottom) ZoneLabel("Split below")
        }
    }
}

@Composable
private fun ZoneLabel(text: String) {
    Text(
        text = text,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
    )
}
