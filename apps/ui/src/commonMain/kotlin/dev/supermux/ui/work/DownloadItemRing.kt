// One download's ring, which toggles it, and the ✕ beside it (#95, round 3).
package dev.supermux.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.unit.dp
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.work.Download
import dev.supermux.work.DownloadState

/** The ✕ beside a ring is tagged as the ring, with this after it. */
internal const val RING_REMOVE_SUFFIX = "_remove"

/**
 * One download's place on screen (#95, round 3): the chat file card's ↓ and a clip's loading state. While [item] is
 * listed in the fleet's downloads it is [RingControl] on the fleet's center: the ring toggles it (Pause is the
 * center's cancel, which keeps what arrived; Resume and a failed ring's Retry are its retry) and the ✕ beside a paused
 * or failed ring is its dismiss. A done [item] is the quiet ✓ alone; the card's own tap hands the file over.
 *
 * With no [item]: [busy] turns the ring (a fetch outside the center: a preview's bytes), else [rest] is drawn.
 */
@Composable
internal fun DownloadItemRing(
    item: Download?,
    tag: String,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    track: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    rest: @Composable () -> Unit = {},
) {
    if (item == null) {
        Box(modifier.size(ringTarget()), contentAlignment = Alignment.Center) {
            if (busy) {
                ProgressRing(null, Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, track = track)
            } else {
                rest()
            }
        }
        return
    }
    val downloads = rememberDownloads()
    RingControl(
        item = item,
        tag = tag,
        modifier = modifier,
        tint = tint,
        track = track,
        onToggle = {
            when (item.state) {
                is DownloadState.Running -> downloads.cancel(item.key)
                DownloadState.Cancelled, is DownloadState.Failed -> downloads.retry(item.key)
                is DownloadState.Done -> Unit
            }
        },
        onRemove = { downloads.dismiss(item.key) },
    )
}

/**
 * The ring as a control, for the card, a clip and a downloads row alike. Running: our ring filling, the ↓ in it; a tap
 * pauses. Paused: the ring stays drawn where it stopped ([Download.pausedAt]), a ▷ in it; a tap resumes. Failed: the
 * "!" glyph; a tap retries. Done: the ✓ alone, no ring, no tap of its own.
 *
 * The ✕ beside it ([onRemove]) shows once the download has stopped — paused or failed — not while it runs: a running
 * ring's one tap is the safe Pause, which keeps what arrived, so a slip never throws a download away; removing a
 * running one is Pause, then ✕. Each is a finger's 44 dp on touch, the bar's 32 dp under a pointer, whatever the glyph.
 *
 * [progressSemantics] false leaves the progress to say to the caller (a downloads row says it on its hairline).
 */
@Composable
internal fun RingControl(
    item: Download,
    tag: String,
    onToggle: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    track: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
    progressSemantics: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val target = ringTarget()
    val state = item.state
    if (state is DownloadState.Done) {
        Box(
            modifier
                .size(target)
                .semantics {
                    contentDescription = "Downloaded"
                    stateDescription = "downloaded"
                }
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(DownloadGlyphs.Done, contentDescription = null, tint = cs.primary, modifier = Modifier.size(ItemRingTokens.Glyph))
        }
        return
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        val label = when (state) {
            is DownloadState.Running -> "Pause"
            DownloadState.Cancelled -> "Resume"
            else -> "Retry"
        }
        QuietSquare(
            size = target,
            label = label,
            onClick = onToggle,
            modifier = Modifier
                .semantics {
                    contentDescription = "Download"
                    stateDescription = when (state) {
                        is DownloadState.Running -> state.percent()?.let { "$it%" } ?: "downloading"
                        is DownloadState.Failed -> "failed"
                        else -> "paused"
                    }
                    if (progressSemantics) {
                        val at = (state as? DownloadState.Running) ?: item.pausedAt.takeIf { state == DownloadState.Cancelled }
                        if (at != null) progressBarRangeInfo = at.fraction()?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
                    }
                }
                .testTag(tag),
        ) {
            when (state) {
                is DownloadState.Running -> {
                    ProgressRing(state.fraction(), track = track)
                    Icon(DownloadGlyphs.InRing, contentDescription = null, tint = cs.primary, modifier = Modifier.size(RingTokens.Glyph))
                }
                DownloadState.Cancelled -> {
                    ProgressRing(item.pausedAt?.fraction(), track = track)
                    Icon(DownloadGlyphs.Resume, contentDescription = null, tint = tint, modifier = Modifier.size(RingTokens.Glyph))
                }
                else -> Icon(DownloadGlyphs.Failed, contentDescription = null, tint = cs.error, modifier = Modifier.size(ItemRingTokens.Glyph))
            }
        }
        if (state == DownloadState.Cancelled || state is DownloadState.Failed) {
            QuietSquare(
                size = target,
                label = "Remove",
                onClick = onRemove,
                modifier = Modifier.semantics { contentDescription = "Remove download" }.testTag(tag + RING_REMOVE_SUFFIX),
            ) {
                Icon(DownloadGlyphs.Remove, contentDescription = null, tint = tint, modifier = Modifier.size(ItemRingTokens.Cross))
            }
        }
    }
}

/** A tap square: no ripple, a soft wash under the pointer, as the bar's icons. */
@Composable
private fun QuietSquare(size: Dp, label: String, onClick: () -> Unit, modifier: Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(ItemRingTokens.Corner))
            .background(if (hovered) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = label, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .then(modifier),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/** A finger's 44 dp on touch; the bar's 32 dp under a pointer. */
@Composable
private fun ringTarget(): Dp = if (LocalPointerAvailable.current) ItemRingTokens.Button else ItemRingTokens.TouchButton

private object ItemRingTokens {
    /** The ring's tap square under a pointer, as the bottom bar's icons. */
    val Button = 32.dp

    /** The ring's tap square on touch: a finger's. */
    val TouchButton = 44.dp
    val Corner = 8.dp

    /** The failed and done glyphs, the size of the ↓ they stand in for. */
    val Glyph = 20.dp

    /** The ✕: small and thin; its square is what takes the tap. */
    val Cross = 14.dp
}
