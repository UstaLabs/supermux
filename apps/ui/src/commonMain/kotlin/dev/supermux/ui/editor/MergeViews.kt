package dev.supermux.ui.editor

import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.Plurals
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Space

// #72: the quiet notes the Changes view shows about the chat's base — it moved on, or a merge from it
// stopped on a clash — and the choices for one clashing file. The base is always named.

@Composable
private fun Note(icon: ImageVector, warn: Boolean, tag: String, text: String, actions: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tint = if (warn) LocalSemantics.current.warning else cs.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.md, vertical = Space.sm)
            .clip(RoundedCornerShape(12.dp))
            .background(cs.surfaceContainerHigh)
            .then(if (warn) Modifier.border(1.dp, tint.copy(alpha = 0.45f), RoundedCornerShape(12.dp)) else Modifier)
            .padding(horizontal = Space.md, vertical = 10.dp)
            .testTag(tag),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text, fontSize = 12.5.sp, color = cs.onSurface)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) { actions() }
        }
    }
}

@Composable
private fun Link(text: String, tag: String, onClick: () -> Unit) {
    Text(
        text,
        fontSize = 12.5.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).testTag(tag),
    )
}

@Composable
internal fun BaseMovedHint(base: String, behind: Int, onGetLatest: () -> Unit) {
    Note(Icons.Outlined.ArrowDownward, false, "git_base_moved", localizedPlural(Plurals.git_base_moved_hint, behind, behind, base)) {
        Link(localized(Str.git_get_latest, base), "git_get_latest_link", onGetLatest)
    }
}

@Composable
internal fun ConflictBanner(base: String, clashing: Int, onAskAgent: (() -> Unit)?, onCancel: () -> Unit) {
    val text = if (clashing > 0) localizedPlural(Plurals.git_conflict_banner, clashing, clashing, base)
        else localized(Str.git_conflicts_settled)
    Note(Icons.Outlined.WarningAmber, clashing > 0, "git_conflict_banner", text) {
        if (clashing > 0 && onAskAgent != null) Link(localized(Str.git_ask_agent_resolve), "git_ask_agent_resolve", onAskAgent)
        Link(localized(Str.git_cancel_merge), "git_cancel_merge", onCancel)
    }
}

/** Under a clashing file: keep my side, the base's, or both — or hand it to the agent. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConflictChoices(path: String, base: String, enabled: Boolean, onPick: (String) -> Unit, onAskAgent: (() -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    @Composable
    fun Choice(text: String, tag: String, onClick: () -> Unit) {
        Text(
            text,
            fontSize = 12.5.sp,
            color = cs.onSurface,
            modifier = Modifier
                .height(32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surfaceContainerHighest)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 13.dp, vertical = 7.dp)
                .testTag("$tag:$path"),
        )
    }
    FlowRow(
        Modifier.fillMaxWidth().padding(start = 46.dp, end = Space.md, top = 2.dp, bottom = Space.sm),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Choice(localized(Str.git_use_mine), "git_use_mine") { onPick("mine") }
        Choice(localized(Str.git_use_base, base), "git_use_base") { onPick("theirs") }
        Choice(localized(Str.git_use_both), "git_use_both") { onPick("both") }
        if (onAskAgent != null) Choice(localized(Str.git_ask_agent), "git_ask_agent", onAskAgent)
    }
}
