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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.chat.TimelineItem
import dev.supermux.proto.CouncilMember
import dev.supermux.proto.CouncilReport
import dev.supermux.proto.CouncilView
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.accounts.agentDisplayName
import dev.supermux.ui.session.AgentLogo
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

/** How far the current round is, in words: "2 of 3 answered", "all answered". True while someone works. */
internal fun councilHeadline(c: CouncilView): Pair<String, Boolean> {
    val working = c.members.count { it.status == "working" }
    val reported = c.members.count { it.status == "reported" }
    val text = if (reported == c.members.size) "all answered" else "$reported of ${c.members.size} answered"
    return text to (working > 0)
}

/**
 * What a working member is doing right now, from its own live state: the tool call it is in (its
 * description when the agent gave one, else the tool's title or name), else "Thinking". Null when
 * it is not working.
 */
internal fun councilActivityLine(agent: dev.supermux.proto.AgentStatus?, events: List<dev.supermux.proto.ActivityEvent>?): String? {
    if (agent?.busy != true) return null
    val tool = events?.lastOrNull { it.kind == "tool" }
    if (tool != null && tool.phase != "completed" && tool.phase != "failed") {
        (tool.description ?: tool.title ?: tool.tool)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    if (agent.detail == "thinking" || agent.tool == null) return "Thinking"
    return agent.tool
}

/**
 * The live line of every member of [councils] the broker counts as working (alive, no answer yet),
 * keyed by its session id: what it is doing when it really runs, or "" when it is idle without an
 * answer (the broker's "working" only means it has not answered).
 */
fun councilActivityOf(
    councils: List<CouncilView>,
    agents: Map<String, dev.supermux.proto.AgentStatus>,
    activity: Map<String, List<dev.supermux.proto.ActivityEvent>>,
): Map<String, String> = buildMap {
    for (c in councils) for (m in c.members) {
        if (m.status != "working") continue
        put(m.session, councilActivityLine(agents[m.session], activity[m.session]) ?: "")
    }
}

/** Member session id → what it is doing right now (the chat panel provides it; tests and previews leave it empty). */
val LocalCouncilActivity = androidx.compose.runtime.compositionLocalOf<Map<String, String>> { emptyMap() }

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
        status == "working" -> "Working"
        status == "failed" -> failure?.let(::councilPreviewText) ?: "Failed"
        status == "crashed" -> "Crashed"
        status == "killed" -> "Stopped"
        report != null -> councilPreviewText(report.text)
        else -> "No answer in round $round"
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
    val shape = RoundedCornerShape(Radii.md)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs)
            .clip(shape)
            .background(cardSurface())
            .border(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.6f), shape)
            .animateContentSize()
            .testTag("council-card:${item.council.id}"),
    ) {
        CouncilCardBody(item, onOpenFile, killSession)
    }
}

/** The card's content without its frame: the chat's card, and the running strip's open council. */
@Composable
internal fun CouncilCardBody(
    item: TimelineItem.CouncilCard,
    onOpenFile: (FilePathRef) -> Unit = {},
    killSession: ((String) -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    val c = item.council
    val id = c.id
    val live = LocalCouncilActivity.current
    // Kept by the council's id, so scrolling the chat does not close a row or reset the round.
    var pickedRound by rememberSaveable(id) { mutableStateOf(0) }
    var openLetters by rememberSaveable(id) { mutableStateOf("") }
    val rounds = remember(c) { councilRounds(c) }
    // 0 (nothing picked) follows the current round as new rounds start.
    val round = pickedRound.takeIf { r -> r > 0 && rounds.any { it.first == r } } ?: c.round
    val open = openLetters.split(',').filter { it.isNotEmpty() }.toSet()
    val line = cs.outlineVariant.copy(alpha = 0.6f)
    Header(c, rounds, round, onPick = { pickedRound = it })
    for (m in c.members) {
        HorizontalDivider(thickness = Stroke.hairline, color = line, modifier = Modifier.padding(start = 14.dp))
        val status = councilMemberStatus(c, m, round)
        val expanded = m.letter in open
        MemberRow(
            c = c,
            m = m,
            round = round,
            status = status,
            failure = item.failures[m.letter],
            activity = if (round == c.round) live[m.session] else null,
            expanded = expanded,
            onToggle = {
                val next = if (expanded) open - m.letter else open + m.letter
                openLetters = next.sorted().joinToString(",")
            },
        )
        if (expanded) MemberReports(c, m, onOpenFile)
    }
    Footer(c, killSession = killSession)
}

@Composable
private fun Header(c: CouncilView, rounds: List<Pair<Int, String?>>, selected: Int, onPick: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The question is clipped to three lines; a tap shows it whole (and folds it again).
        var questionOpen by rememberSaveable(c.id) { mutableStateOf(false) }
        Text(
            c.question.ifBlank { c.name },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = cs.onSurface,
            maxLines = if (questionOpen) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClickLabel = if (questionOpen) "Fold the question" else "Show the whole question") { questionOpen = !questionOpen }
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag("council-question:${c.id}"),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                listOfNotNull(c.name.takeIf { it.isNotBlank() }, councilHeadline(c).first).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
                color = cs.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.testTag("council-headline:${c.id}"),
            )
            // The rounds read as words in the same line: the shown one in full ink, the others quiet.
            for ((round, focus) in rounds) {
                Text(" · ", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                RoundTab(
                    text = councilRoundLabel(round, focus),
                    selected = round == selected,
                    onClick = { onPick(round) },
                    modifier = Modifier.testTag("council-chip:${c.id}:$round"),
                )
            }
        }
    }
}

@Composable
private fun MemberRow(
    c: CouncilView,
    m: CouncilMember,
    round: Int,
    status: String,
    failure: String?,
    activity: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val (who, model) = councilMemberWho(m)
    // "" = alive but idle without an answer; null = unknown (no live state here): say working.
    val idle = status == "working" && activity == ""
    val working = status == "working" && !idle
    val preview = when {
        idle -> "Idle, hasn't answered"
        working -> activity ?: "Working"
        else -> councilPreview(c, m, round, status, failure)
    }
    val previewColor = when (status) {
        "failed", "crashed" -> cs.error
        "reported" -> cs.onSurface
        else -> cs.onSurfaceVariant
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (expanded) "Hide answers" else "Show answers", onClick = onToggle)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag("council-row:${c.id}:${m.letter}")
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // The letter is how the members know each other: plain, quiet, fixed width so the names line up.
        Text(
            m.letter,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(14.dp).padding(top = 1.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (m.agent.isNotBlank()) AgentLogo(m.agent, size = 14.dp)
                Text(who, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1)
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
            // Open, the answers are right below: the preview would say the latest one twice.
            if (!(expanded && status == "reported")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (working) PulsingDot(cs.primary)
                    Text(
                        preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = previewColor,
                        maxLines = if (working) 1 else 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .testTag("council-preview:${c.id}:${m.letter}")
                            .semantics { stateDescription = councilStatusWord(status) },
                    )
                }
            }
        }
    }
}

/** The status as a word, for screen readers (the card shows no badge). */
internal fun councilStatusWord(status: String): String = when (status) {
    "reported" -> "answered"
    "working" -> "working"
    "failed" -> "failed"
    "crashed" -> "crashed"
    "killed" -> "stopped"
    "none" -> "no answer"
    else -> status
}

/** Every answer of the member, round by round, under its row. */
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
            // Under the name, past the letter.
            .padding(start = 38.dp, end = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (byRound.isEmpty()) {
            Text("No answers yet.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        }
        for ((r, reports) in byRound) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    councilRoundLabel(r.round, r.focus),
                    style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant,
                )
                // A size under the chat's own messages: the answers sit inside a card.
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

/** Stop, as a quiet text action at the card's foot: only while a member still runs. */
@Composable
private fun Footer(c: CouncilView, killSession: ((String) -> Unit)?) {
    val live = c.members.filter(::isLiveCouncilMember)
    if (killSession == null || live.isEmpty()) return
    val cs = MaterialTheme.colorScheme
    var confirming by remember(c.id) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = { confirming = true }, modifier = Modifier.testTag("council-stop:${c.id}")) {
            Text("Stop council", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
    }
    if (confirming) {
        val letters = live.joinToString(", ") { it.letter }
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Stop ${c.name.ifBlank { "the council" }}?") },
            text = {
                Text(
                    if (live.size == 1) "This ends member $letters and its session. Its answers stay here."
                    else "This ends members $letters and their sessions. Their answers stay here.",
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

/** A working member's dot, breathing (1.2s, down to 30%). */
@Composable
private fun PulsingDot(color: Color) {
    val pulse = rememberInfiniteTransition(label = "council-pulse")
    val alpha by pulse.animateFloat(
        initialValue = 1f,
        targetValue = 0.3f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "council-pulse-alpha",
    )
    Box(Modifier.size(6.dp).alpha(alpha).clip(CircleShape).background(color))
}

/** "Round 2 · noise" as words in the header line: the shown round in full ink, the others quiet. */
@Composable
private fun RoundTab(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        color = if (selected) cs.onSurface else cs.onSurfaceVariant,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .semantics { this.selected = selected }
            .padding(horizontal = 2.dp, vertical = 2.dp),
    )
}
