/**
 * A council in its caller's chat (spec 2026-10-28 §5.1, decision #19, variant A "member rows"): ONE
 * card per council, in place of the caller's `council` tool call. Its header is the question, the
 * council's name, round and focus, and how many members still work. A row per member: its letter,
 * agent and model, its status for the shown round and its latest report there in two lines. A tap
 * on a row opens every report of that member, round by round. Round chips at the foot switch the
 * round the rows show; Stop ends every member still alive, after a confirm.
 *
 * The forwarded reports live only here (`mergeTimeline` folds their inbox rows out of the chat).
 *
 * Test tags: `council-card:<id>`, `council-headline:<id>`, `council-row:<id>:<letter>`,
 * `council-status:<id>:<letter>`, `council-preview:<id>:<letter>`, `council-body:<id>:<letter>`,
 * `council-chip:<id>:<round>`, `council-stop:<id>`, `council-stop-confirm:<id>`.
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilReport
import dev.supermux.proto.CouncilView
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.accounts.agentDisplayName
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import dev.supermux.ui.widgets.AlertDialog

// ── the model the card draws (pure, tested) ──────────────────────────────────────────────────

/** A member's status in [round]: the broker's for the current round; a past one has a report or none ("none"). */
internal fun councilMemberStatus(c: CouncilView, m: CouncilMember, round: Int): String = when {
    round == c.round -> m.status
    councilLatestReport(c, m.letter, round) != null -> "reported"
    else -> "none"
}

/** [letter]'s latest report in [round], or null. */
internal fun councilLatestReport(c: CouncilView, letter: String, round: Int): CouncilReport? =
    c.rounds.firstOrNull { it.round == round }?.reports?.filter { it.letter == letter }?.maxByOrNull { it.seq }

/** A member whose session still runs: Stop ends it. Crashed and killed ones are gone. */
internal fun isLiveCouncilMember(m: CouncilMember): Boolean = m.status != "killed" && m.status != "crashed"

/** The header's pill: "2 working" (true: something runs), "all reported", or "2 of 3 reported". */
internal fun councilHeadline(c: CouncilView): Pair<String, Boolean> {
    val working = c.members.count { it.status == "working" }
    val reported = c.members.count { it.status == "reported" }
    return when {
        working > 0 -> "$working working" to true
        reported == c.members.size -> "all reported" to false
        else -> "$reported of ${c.members.size} reported" to false
    }
}

/** "council-3 · Round 2 · focus: background noise". */
internal fun councilSubtitle(c: CouncilView): String =
    listOfNotNull(c.name.takeIf { it.isNotBlank() }, "Round ${c.round}", c.focus?.takeIf { it.isNotBlank() }?.let { "focus: $it" })
        .joinToString(" · ")

/** "Round 1", "Round 2 · noise". */
internal fun councilRoundLabel(round: Int, focus: String?): String =
    if (focus.isNullOrBlank()) "Round $round" else "Round $round · $focus"

/** The rounds the chips offer: every round the broker has, and at least the current one. */
internal fun councilRounds(c: CouncilView): List<Pair<Int, String?>> {
    val known = c.rounds.map { it.round to it.focus }
    return if (known.any { it.first == c.round }) known.sortedBy { it.first } else (known + (c.round to c.focus)).sortedBy { it.first }
}

/** Who the member is: the agent's name, and the label when it says more ("Codex" + "GPT-6"). */
internal fun councilMemberWho(m: CouncilMember): Pair<String, String?> {
    val agent = m.agent.takeIf { it.isNotBlank() }?.let(::agentDisplayName)
    val label = m.label.takeIf { it.isNotBlank() }
    return when {
        agent == null -> (label ?: m.name.ifBlank { m.letter }) to null
        label == null || label.equals(agent, ignoreCase = true) -> agent to null
        else -> agent to label
    }
}

/** A report as two lines of plain words: markdown marks dropped, whitespace run together. */
internal fun councilPreviewText(text: String): String =
    text.lineSequence()
        .map { it.trim().trimStart('#', '>', ' ').removePrefix("- ").removePrefix("* ") }
        .filter { it.isNotEmpty() && !it.startsWith("```") }
        .joinToString(" ")
        .replace("**", "")
        .replace("`", "")
        .replace(Regex("\\s+"), " ")
        .trim()

/** What a row says under its name for [status] in [round]: the report, or why there is none. */
internal fun councilPreview(c: CouncilView, m: CouncilMember, round: Int, status: String, failure: String?): String {
    val report = councilLatestReport(c, m.letter, round)
    return when {
        status == "reported" && report != null -> councilPreviewText(report.text)
        status == "working" -> "Working…"
        status == "failed" -> failure?.let(::councilPreviewText) ?: "Failed"
        status == "crashed" -> "Crashed"
        status == "killed" -> "Stopped"
        report != null -> councilPreviewText(report.text)
        else -> "No report in round $round"
    }
}

// ── the card ──────────────────────────────────────────────────────────────────────────────────

@Composable
fun CouncilCard(
    item: TimelineItem.CouncilCard,
    onOpenFile: (FilePathRef) -> Unit = {},
    /** Ends one member's session (the session kill the header's Kill uses); null = no Stop. */
    killSession: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val c = item.council
    val id = c.id
    // Kept by the council's id, so scrolling the chat does not close a row or reset the round.
    var pickedRound by rememberSaveable(id) { mutableStateOf(0) }
    var openLetters by rememberSaveable(id) { mutableStateOf("") }
    val rounds = remember(c) { councilRounds(c) }
    // 0 (nothing picked) follows the current round as new rounds start.
    val round = pickedRound.takeIf { r -> r > 0 && rounds.any { it.first == r } } ?: c.round
    val open = openLetters.split(',').filter { it.isNotEmpty() }.toSet()
    val shape = RoundedCornerShape(Radii.md)
    val line = cs.outlineVariant.copy(alpha = 0.8f)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs)
            .clip(shape)
            .background(cardSurface())
            .border(Stroke.hairline, line, shape)
            .animateContentSize()
            .testTag("council-card:$id"),
    ) {
        Header(c)
        for (m in c.members) {
            HorizontalDivider(thickness = Stroke.hairline, color = line)
            val status = councilMemberStatus(c, m, round)
            val expanded = m.letter in open
            MemberRow(
                c = c,
                m = m,
                round = round,
                status = status,
                failure = item.failures[m.letter],
                expanded = expanded,
                onToggle = {
                    val next = if (expanded) open - m.letter else open + m.letter
                    openLetters = next.sorted().joinToString(",")
                },
            )
            if (expanded) MemberReports(c, m, onOpenFile)
        }
        HorizontalDivider(thickness = Stroke.hairline, color = line)
        Footer(c, rounds, round, onPick = { pickedRound = it }, killSession = killSession)
    }
}

@Composable
private fun Header(c: CouncilView) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = Space.md, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier.padding(top = 1.dp).size(24.dp).clip(RoundedCornerShape(7.dp)).background(cs.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Groups, contentDescription = "Council", tint = cs.onPrimaryContainer, modifier = Modifier.size(15.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                c.question.ifBlank { c.name },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = cs.onSurface,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                councilSubtitle(c),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val (text, live) = councilHeadline(c)
        StatusPill(
            kind = if (live) PillKind.Work else if (text == "all reported") PillKind.Ok else PillKind.Off,
            text = text,
            modifier = Modifier.padding(top = 1.dp).testTag("council-headline:${c.id}"),
        )
    }
}

@Composable
private fun MemberRow(
    c: CouncilView,
    m: CouncilMember,
    round: Int,
    status: String,
    failure: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (who, model) = councilMemberWho(m)
    val preview = councilPreview(c, m, round, status, failure)
    val previewColor = when (status) {
        "failed", "crashed" -> cs.error
        "reported" -> cs.onSurface
        else -> cs.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide reports" else "Show reports", onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("council-row:${c.id}:${m.letter}")
            .padding(start = 14.dp, end = Space.md, top = Space.sm, bottom = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LetterBadge(m.letter)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.agent.isNotBlank()) AgentLogo(m.agent, size = 14.dp)
                Text(
                    who,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (model != null) {
                    Text(
                        model,
                        style = MaterialTheme.typography.labelMedium,
                        color = cs.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
            // Open, the reports are right below: the preview would say the latest one twice.
            if (!(expanded && status == "reported")) {
                Text(
                    preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = previewColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("council-preview:${c.id}:${m.letter}"),
                )
            }
        }
        val (kind, label) = when (status) {
            "reported" -> PillKind.Ok to "✓ reported"
            "working" -> PillKind.Work to "working"
            "failed" -> PillKind.Err to "✕ failed"
            "crashed" -> PillKind.Err to "✕ crashed"
            "killed" -> PillKind.Off to "killed"
            "none" -> PillKind.Off to "no report"
            else -> PillKind.Off to status
        }
        StatusPill(kind, label, Modifier.testTag("council-status:${c.id}:${m.letter}"))
    }
}

/** Every report of the member, round by round, under its row. */
@Composable
private fun MemberReports(c: CouncilView, m: CouncilMember, onOpenFile: (FilePathRef) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val byRound = c.rounds.sortedBy { it.round }.mapNotNull { r ->
        r.reports.filter { it.letter == m.letter }.sortedBy { it.seq }.takeIf { it.isNotEmpty() }?.let { r to it }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .testTag("council-body:${c.id}:${m.letter}")
            // Under the name, past the letter badge.
            .padding(start = 48.dp, end = Space.md, bottom = Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (byRound.isEmpty()) {
            Text("No reports yet.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        for ((r, reports) in byRound) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    councilRoundLabel(r.round, r.focus),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = cs.onSurfaceVariant,
                )
                // A size under the chat's own messages: the reports sit inside a card.
                val type = MaterialTheme.typography
                MaterialTheme(typography = type.copy(bodyLarge = type.bodyMedium)) {
                    for (report in reports) {
                        LinkSelectionContainer {
                            MarkdownBody(text = report.text, modifier = Modifier.fillMaxWidth(), onOpenFile = onOpenFile, linkify = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Footer(
    c: CouncilView,
    rounds: List<Pair<Int, String?>>,
    selected: Int,
    onPick: (Int) -> Unit,
    killSession: ((String) -> Unit)?,
) {
    val live = c.members.filter(::isLiveCouncilMember)
    var confirming by remember(c.id) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = Space.md, end = Space.sm, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Row(
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for ((round, focus) in rounds) {
                RoundChip(
                    text = councilRoundLabel(round, focus),
                    selected = round == selected,
                    onClick = { onPick(round) },
                    modifier = Modifier.testTag("council-chip:${c.id}:$round"),
                )
            }
        }
        if (killSession != null && live.isNotEmpty()) {
            CardButton(
                label = "Stop",
                style = CardButtonStyle.Danger,
                enabled = true,
                testTag = "council-stop:${c.id}",
                leading = Icons.Filled.Stop,
                onClick = { confirming = true },
            )
        }
    }
    if (confirming && killSession != null) {
        val cs = MaterialTheme.colorScheme
        val letters = live.joinToString(", ") { it.letter }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Stop ${c.name.ifBlank { "the council" }}?") },
            text = {
                Text(
                    if (live.size == 1) "This ends member $letters and its session. Its reports stay here."
                    else "This ends members $letters and their sessions. Their reports stay here.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirming = false
                        live.forEach { killSession(it.session) }
                    },
                    modifier = Modifier.testTag("council-stop-confirm:${c.id}"),
                ) { Text("Stop", color = cs.error) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text("Cancel") } },
        )
    }
}

// ── small pieces ──────────────────────────────────────────────────────────────────────────────

private enum class PillKind { Ok, Work, Err, Off }

/** A status pill: tinted for reported / working / failed, a hairline outline otherwise. */
@Composable
private fun StatusPill(kind: PillKind, text: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val (fg, bg) = when (kind) {
        PillKind.Ok -> sem.success to sem.success.copy(alpha = 0.14f)
        PillKind.Work -> cs.primary to cs.primary.copy(alpha = 0.12f)
        PillKind.Err -> cs.error to cs.error.copy(alpha = 0.12f)
        PillKind.Off -> cs.onSurfaceVariant to Color.Transparent
    }
    val shape = RoundedCornerShape(Radii.pill)
    Row(
        modifier
            .clip(shape)
            .background(bg)
            .then(if (kind == PillKind.Off) Modifier.border(Stroke.hairline, cs.outlineVariant, shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (kind == PillKind.Work) PulsingDot(fg)
        Text(text, style = MaterialTheme.typography.labelMedium, color = fg, maxLines = 1)
    }
}

/** The working pill's dot, breathing like the mockup's (1.2s, down to 30%). */
@Composable
private fun PulsingDot(color: Color) {
    val pulse = rememberInfiniteTransition(label = "council-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "council-pulse-alpha",
    )
    Box(Modifier.size(7.dp).alpha(alpha).clip(CircleShape).background(color))
}

/** The member's letter in a small round badge. */
@Composable
private fun LetterBadge(letter: String) {
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(cs.surfaceContainerHigh)
            .border(Stroke.hairline, cs.outlineVariant, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(letter, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = cs.onSurface, maxLines = 1)
    }
}

/** "Round 2 · noise": accent-tinted when it is the round the rows show. */
@Composable
private fun RoundChip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.pill)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) cs.primary else cs.onSurface,
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(if (selected) cs.primary.copy(alpha = 0.12f) else Color.Transparent)
            .then(if (selected) Modifier else Modifier.border(Stroke.hairline, cs.outlineVariant, shape))
            .clickable(role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { this.selected = selected }
            .height(28.dp)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}
