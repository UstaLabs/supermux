// One download's ring and its menu (#95, round 2).
package dev.supermux.ui.work

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.platform.FileAction
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import dev.supermux.work.Download
import dev.supermux.work.DownloadState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The menu a download's ring opens. */
internal const val DOWNLOAD_MENU_TAG = "attachment_menu"

/**
 * One download's place on screen (#95, round 2): the chat file card's ↓ and a clip's loading state. While [item] is
 * listed in the fleet's downloads it is our thin ring — filling while it runs, the empty track once paused, the
 * failed or done glyph after — and a tap (or click; washed under the pointer, as the sidebar's ring is) opens its
 * menu: Pause · Cancel while it runs, Resume · Cancel once paused, Retry once failed, and once done the host's own
 * file actions. Pause is the center's cancel (what arrived is kept), Resume its retry, Cancel its dismiss.
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
    val cs = MaterialTheme.colorScheme
    if (item == null) {
        Box(modifier.size(ItemRingTokens.Button), contentAlignment = Alignment.Center) {
            if (busy) {
                ProgressRing(null, Modifier.semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, track = track)
            } else {
                rest()
            }
        }
        return
    }
    val downloads = rememberDownloads()
    val platform = LocalPlatform.current
    val scope = rememberCoroutineScope()
    var open by remember(item.key) { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val hovered by source.collectIsHoveredAsState()
    val state = item.state
    Box(
        modifier
            .size(ItemRingTokens.Button)
            .clip(RoundedCornerShape(ItemRingTokens.Corner))
            .background(if (hovered || open) cs.onSurface.copy(alpha = cs.washAlpha) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, role = Role.Button, onClickLabel = "Download options") { open = true }
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics {
                contentDescription = "Download"
                stateDescription = when (state) {
                    is DownloadState.Running -> state.percent()?.let { "$it%" } ?: "downloading"
                    is DownloadState.Done -> "downloaded"
                    is DownloadState.Failed -> "failed"
                    DownloadState.Cancelled -> "paused"
                }
                if (state is DownloadState.Running) {
                    progressBarRangeInfo = state.fraction()?.let { ProgressBarRangeInfo(it, 0f..1f) } ?: ProgressBarRangeInfo.Indeterminate
                }
            }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            is DownloadState.Running -> {
                ProgressRing(state.fraction(), track = track)
                Icon(DownloadGlyphs.InRing, contentDescription = null, tint = cs.primary, modifier = Modifier.size(RingTokens.Glyph))
            }
            DownloadState.Cancelled -> {
                ProgressRing(null, track = track)
                Icon(DownloadGlyphs.InRing, contentDescription = null, tint = tint, modifier = Modifier.size(RingTokens.Glyph))
            }
            is DownloadState.Failed -> Icon(DownloadGlyphs.Failed, contentDescription = null, tint = cs.error, modifier = Modifier.size(ItemRingTokens.Glyph))
            is DownloadState.Done -> Icon(DownloadGlyphs.Done, contentDescription = null, tint = cs.primary, modifier = Modifier.size(ItemRingTokens.Glyph))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.testTag(DOWNLOAD_MENU_TAG)) {
            for (word in ringWords(item, platform.files.fileActions)) {
                DropdownMenuItem(
                    text = { Text(word.label, fontSize = 13.sp) },
                    onClick = {
                        open = false
                        when (val act = word.act) {
                            RingAct.Pause -> downloads.cancel(item.key)
                            RingAct.Resume, RingAct.Retry -> downloads.retry(item.key)
                            RingAct.Cancel -> downloads.dismiss(item.key)
                            is RingAct.Hand -> scope.launch(start = CoroutineStart.UNDISPATCHED) { handOver(platform, downloads, item, act.action) }
                        }
                    },
                    modifier = Modifier.testTag("${DOWNLOAD_MENU_TAG}_${word.tag}"),
                )
            }
        }
    }
}

/** What a ring's menu word does. */
private sealed interface RingAct {
    data object Pause : RingAct

    data object Resume : RingAct

    data object Retry : RingAct

    data object Cancel : RingAct

    data class Hand(val action: FileAction) : RingAct
}

private class RingWord(val label: String, val tag: String, val act: RingAct)

/** The ring's menu for [item]'s state; once done, the host's [actions] in one order everywhere. */
private fun ringWords(item: Download, actions: Set<FileAction>): List<RingWord> = when (item.state) {
    is DownloadState.Running -> listOf(RingWord("Pause", "pause", RingAct.Pause), RingWord("Cancel", "cancel", RingAct.Cancel))
    DownloadState.Cancelled -> listOf(RingWord("Resume", "resume", RingAct.Resume), RingWord("Cancel", "cancel", RingAct.Cancel))
    is DownloadState.Failed -> listOf(RingWord("Retry", "retry", RingAct.Retry))
    is DownloadState.Done -> FileAction.entries.filter { it in actions }.map { action ->
        RingWord(
            when (action) {
                FileAction.Open -> "Open"
                FileAction.Save -> "Save"
                FileAction.Share -> "Share"
            },
            action.name.lowercase(),
            RingAct.Hand(action),
        )
    }
}

private object ItemRingTokens {
    /** The ring's tap square, as the bottom bar's icons. */
    val Button = 32.dp
    val Corner = 8.dp

    /** The failed and done glyphs, the size of the ↓ they stand in for. */
    val Glyph = 20.dp
}
