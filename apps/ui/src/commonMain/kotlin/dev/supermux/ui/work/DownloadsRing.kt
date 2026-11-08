// The downloads' ring and line glyphs (#95, design A).
package dev.supermux.ui.work

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import dev.supermux.ui.theme.Motion
import dev.supermux.work.Download
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadState

/**
 * The downloads' glyph in a bar of icons — the sidebar's bottom bar, the collapsed rail (#95, design A). While one
 * runs: a small teal ↓ in our own thin ring, its track empty until the size is known, then an arc filling clockwise
 * from the top. Once nothing runs: the ↓ alone in [tint], with a teal dot while a finished file waits. Drawn here, not
 * Material's progress indicator.
 *
 * It fills the button it is drawn in: the dot sits [dotInset] in from the button's top end corner, ringed in
 * [surface] (the bar's own tone) so it stands off the glyph. [rest] is the ↓'s size once nothing runs.
 */
@Composable
internal fun DownloadsRing(items: List<Download>, rest: Dp, tint: Color, surface: Color, dotInset: Dp, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val lead = leadOf(items) ?: return
    val running = lead.state as? DownloadState.Running
    Box(modifier, contentAlignment = Alignment.Center) {
        if (running != null) {
            val fraction = running.fraction()
            val track = cs.onSurface.copy(alpha = 0.10f)
            val arc = cs.primary
            Canvas(Modifier.size(RingTokens.Ring)) {
                val stroke = RingTokens.Line.toPx()
                val r = (size.minDimension - stroke) / 2f - RingTokens.Inset.toPx()
                drawCircle(track, radius = r, style = Stroke(stroke))
                if (fraction != null && fraction > 0f) {
                    drawArc(
                        arc,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction,
                        useCenter = false,
                        topLeft = Offset(center.x - r, center.y - r),
                        size = Size(2 * r, 2 * r),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
            }
            Icon(DownloadGlyphs.InRing, contentDescription = null, tint = cs.primary, modifier = Modifier.size(RingTokens.Glyph))
        } else {
            Icon(DownloadGlyphs.Download, contentDescription = null, tint = tint, modifier = Modifier.size(rest))
            if (items.any { it.state is DownloadState.Done }) {
                val dot = cs.primary
                Canvas(Modifier.matchParentSize()) {
                    val r = RingTokens.Dot.toPx() / 2f
                    val inset = dotInset.toPx() + r
                    val c = Offset(if (layoutDirection == LayoutDirection.Rtl) inset else size.width - inset, inset)
                    drawCircle(surface, radius = r + RingTokens.DotRing.toPx(), center = c)
                    drawCircle(dot, radius = r, center = c)
                }
            }
        }
    }
}

/**
 * The ring as the bottom bar's icon (#95, design A): the bar's 30 dp square, washed while hovered or while its list
 * is open, the resting ↓ easing from dim to full ink — the one thing that eases. [onClick] opens the list.
 */
@Composable
internal fun DownloadsButton(items: List<Download>, open: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val lit = hovered || open
    val tint by animateColorAsState(if (lit) cs.onSurface else cs.onSurfaceVariant, Motion.stateChange(), label = "downloads-glyph")
    Box(
        modifier
            .size(RingTokens.Button)
            .clip(RoundedCornerShape(RingTokens.ButtonCorner))
            .background(if (lit) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Show downloads", onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .downloadsSemantics(items)
            .testTag("sidebar_footer_downloads"),
    ) {
        DownloadsRing(items, rest = 15.dp, tint = tint, surface = cs.surfaceContainerLow, dotInset = 5.dp, modifier = Modifier.matchParentSize())
    }
}

/** What the ring reads as: "Downloads", what its lead says, and a running lead's progress (assistive tech, tests). */
internal fun Modifier.downloadsSemantics(items: List<Download>): Modifier = semantics {
    contentDescription = "Downloads"
    val lead = leadOf(items) ?: return@semantics
    stateDescription = ringState(lead)
    (lead.state as? DownloadState.Running)?.let { running ->
        progressBarRangeInfo = running.fraction()?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
    }
}

/** What the ring shows: the newest running download, else the newest of all. */
internal fun leadOf(items: List<Download>): Download? = items.firstOrNull { it.state is DownloadState.Running } ?: items.firstOrNull()

private fun ringState(lead: Download): String = when (val state = lead.state) {
    is DownloadState.Running -> state.percent()?.let { "${lead.name}, $it%" } ?: "${lead.name}, downloading"
    is DownloadState.Done -> if (lead.purpose == DownloadPurpose.Installer) "${lead.name} is ready to install" else "${lead.name} downloaded"
    is DownloadState.Failed -> "${lead.name} failed"
    DownloadState.Cancelled -> "${lead.name} cancelled"
}

/**
 * The downloads' line glyphs: #91's rule (a 24 grid, one stroke weight, round ends, no fills) on the prototype's
 * paths. Tinted like any icon.
 */
internal object DownloadGlyphs {
    /** ↓ onto a line: a download. */
    val Download: ImageVector by lazy { glyph("download") { arrow() } }

    /** The same ↓ in a heavier stroke, for the small size it has inside the ring. */
    val InRing: ImageVector by lazy { glyph("download-in-ring", stroke = 2f) { arrow() } }

    /** A tick: downloaded. */
    val Done: ImageVector by lazy {
        glyph("downloaded") {
            moveTo(5.5f, 12.5f)
            lineTo(9.7f, 16.7f)
            lineTo(18.5f, 7.5f)
        }
    }

    /** "!" in a circle: it failed. */
    val Failed: ImageVector by lazy {
        glyph("download-failed") {
            circle()
            moveTo(12f, 7.8f)
            lineTo(12f, 12.8f)
            moveTo(12f, 16.2f)
            lineTo(12f, 16.4f)
        }
    }

    /** A struck circle: cancelled. */
    val Cancelled: ImageVector by lazy {
        glyph("download-cancelled") {
            circle()
            moveTo(6.2f, 17.8f)
            lineTo(17.8f, 6.2f)
        }
    }

    /** A box: an app update, to install. */
    val Package: ImageVector by lazy {
        glyph("download-package") {
            moveTo(4.5f, 8f)
            lineTo(12f, 4f)
            lineTo(19.5f, 8f)
            lineTo(19.5f, 16f)
            lineTo(12f, 20f)
            lineTo(4.5f, 16f)
            close()
            moveTo(4.5f, 8f)
            lineTo(12f, 12f)
            lineTo(19.5f, 8f)
            moveTo(12f, 12f)
            lineTo(12f, 20f)
        }
    }

    private fun PathBuilder.arrow() {
        moveTo(12f, 4.5f)
        lineTo(12f, 14.5f)
        moveTo(7.5f, 10.5f)
        lineTo(12f, 15f)
        lineTo(16.5f, 10.5f)
        moveTo(5f, 19.5f)
        lineTo(19f, 19.5f)
    }

    /** The circle the failed and cancelled glyphs draw their mark in. */
    private fun PathBuilder.circle() {
        moveTo(20.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, false, true, 3.5f, 12f)
        arcTo(8.5f, 8.5f, 0f, false, true, 20.5f, 12f)
        close()
    }

    private fun glyph(name: String, stroke: Float = 1.5f, draw: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = "downloads.$name",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = stroke,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        ).build()
}

/** The prototype's measures for the ring and its button. */
internal object RingTokens {
    /** The bottom bar's icon square, as its other icons'. */
    val Button = 30.dp
    val ButtonCorner = 8.dp

    /** The rail's tile, as its other tiles'. */
    val RailTile = 34.dp

    /** The ring, its line, and how far inside its box the line runs. */
    val Ring = 22.dp
    val Line = 1.6.dp
    val Inset = 0.25.dp

    /** The ↓ inside the ring. */
    val Glyph = 10.dp

    /** The waiting dot, and the ring of the bar's tone around it. */
    val Dot = 6.dp
    val DotRing = 1.5.dp
}
