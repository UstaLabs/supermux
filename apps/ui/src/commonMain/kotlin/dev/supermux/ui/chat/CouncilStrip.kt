/**
 * A council while it runs, over the composer next to the running strip: the members side by side
 * as their agents' logos (a breathing ring while one works, a check once it answered), the
 * council and its progress, and one member's live line at a time ("Claude: Searching the web…").
 * A tap opens the council card's own content right there; it folds again on the header. Absent
 * once no member works.
 *
 * Test tags: `council-strip:<id>`, `council-strip-head:<id>`, `council-strip-avatar:<id>:<letter>`,
 * `council-strip-line:<id>`, `council-strip-body:<id>`.
 */
package dev.supermux.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.TimelineItem
import dev.supermux.chat.parseChatTs
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilView
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.coroutines.delay

/** A council still running: one of its members works in the current round. */
internal fun isRunningCouncil(c: CouncilView): Boolean = c.members.any { it.status == "working" }

/**
 * The lines the strip takes turns on, one per member with something to say: what a working member
 * does now ("Claude: Searching the web…"), else the round's latest answer. Members idle without an
 * answer ("" in [live]) say nothing.
 */
internal fun councilStripLines(c: CouncilView, live: Map<String, String>): List<String> {
    val doing = c.members.filter { it.status == "working" }.mapNotNull { m ->
        val now = live[m.session]
        if (now == "") null else "${councilMemberWho(m).first}: ${now ?: "Working"}"
    }
    if (doing.isNotEmpty()) return doing
    val said = c.rounds.firstOrNull { it.round == c.round }?.reports?.maxByOrNull { it.seq } ?: return emptyList()
    val who = c.members.firstOrNull { it.letter == said.letter }?.let { councilMemberWho(it).first } ?: said.letter
    return listOf("$who: ${councilPreviewText(said.text)}")
}

/** Every running council of the chat, a strip each (usually one). */
@Composable
fun CouncilStrips(
    cards: List<TimelineItem.CouncilCard>,
    onOpenFile: (FilePathRef) -> Unit = {},
    killSession: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().animateContentSize()) {
        for (card in cards) if (isRunningCouncil(card.council)) CouncilStrip(card, onOpenFile, killSession)
    }
}

@Composable
private fun CouncilStrip(item: TimelineItem.CouncilCard, onOpenFile: (FilePathRef) -> Unit, killSession: ((String) -> Unit)?) {
    val cs = MaterialTheme.colorScheme
    val c = item.council
    var open by rememberSaveable(c.id) { mutableStateOf(false) }
    val shape = RoundedCornerShape(if (open) 10.dp else Radii.md)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = Space.sm)
            .clip(shape)
            .background(if (open) cardSurface() else cs.surfaceContainerHigh)
            .border(Stroke.hairline, cs.outlineVariant.copy(alpha = if (open) 0.6f else 0.7f), shape)
            .testTag("council-strip:${c.id}"),
    ) {
        Head(c, open, onToggle = { open = !open })
        if (open) {
            Box(Modifier.fillMaxWidth().heightIn(min = 1.dp, max = 1.dp).background(cs.outlineVariant.copy(alpha = 0.45f)))
            // Capped like the lanes: a long answer scrolls instead of pushing the composer off screen.
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("council-strip-body:${c.id}"),
            ) {
                CouncilCardBody(item, onOpenFile, killSession)
            }
        }
    }
}

@Composable
private fun Head(c: CouncilView, open: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val live = LocalCouncilActivity.current
    val lines = councilStripLines(c, live)
    // The members take turns: one line at a time, every few seconds, like a conversation.
    var turn by rememberSaveable(c.id) { mutableIntStateOf(0) }
    LaunchedEffect(c.id, lines.size) {
        while (lines.size > 1) {
            delay(3_000)
            turn = (turn + 1) % lines.size
        }
    }
    val now = rememberTickingNow(true)
    val started = parseChatTs(c.rounds.firstOrNull { it.round == c.round }?.startedAt ?: c.createdAt)
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Hide the council" else "Show the council", onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("council-strip-head:${c.id}")
            .padding(start = Space.md, end = Space.sm, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (m in c.members) Avatar(c, m, live[m.session])
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                listOfNotNull(c.name.takeIf { it.isNotBlank() }, "Round ${c.round}", councilHeadline(c).first).joinToString(" · "),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            lines.getOrNull(turn % lines.size.coerceAtLeast(1))?.let { line ->
                Text(
                    line,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("council-strip-line:${c.id}"),
                )
            }
        }
        if (started != null) {
            Text(compactElapsed(now - started), fontFamily = MonoFontFamily, fontSize = 11.sp, color = cs.onSurfaceVariant, maxLines = 1)
        }
        Icon(
            if (open) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess,
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(IconSize.md),
        )
    }
}

/** A member at the table: its agent's logo in a ring that says how its round goes. */
@Composable
private fun Avatar(c: CouncilView, m: CouncilMember, activity: String?) {
    val cs = MaterialTheme.colorScheme
    val status = m.status
    val idle = status == "working" && activity == ""
    val working = status == "working" && !idle
    val ring = when (status) {
        "failed", "crashed" -> cs.error
        "reported" -> cs.primary.copy(alpha = 0.6f)
        else -> cs.outlineVariant
    }
    Box(
        Modifier
            .size(28.dp)
            .testTag("council-strip-avatar:${c.id}:${m.letter}")
            .semantics { stateDescription = "${councilMemberWho(m).first}, ${councilStatusWord(status)}" },
        contentAlignment = Alignment.Center,
    ) {
        if (working) {
            val pulse = rememberInfiniteTransition(label = "council-strip-ring")
            val alpha by pulse.animateFloat(
                initialValue = 1f,
                targetValue = 0.25f,
                animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
                label = "council-strip-ring-alpha",
            )
            Box(Modifier.size(26.dp).alpha(alpha).border(1.5.dp, cs.primary, CircleShape))
        } else {
            Box(Modifier.size(26.dp).border(1.dp, ring, CircleShape))
        }
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(cs.onSurface.copy(alpha = 0.05f))
                .alpha(if (idle || status == "killed" || status == "crashed") 0.45f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            AgentLogo(m.agent.ifBlank { null }, size = 13.dp, fallbackLetter = m.agent.ifBlank { m.letter }.take(1).uppercase())
        }
        if (status == "reported") {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(11.dp)
                    .clip(CircleShape)
                    .background(cs.surfaceContainerHigh)
                    .padding(1.dp)
                    .clip(CircleShape)
                    .background(cs.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(7.dp))
            }
        }
    }
}
