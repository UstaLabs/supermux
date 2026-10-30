// How the chat draws what other sessions send this one (decision #19, 2026-10-30): only the user's
// own messages sit on the right. A message from another session is a card on the LEFT, headed by
// the sender's agent mark and name; a council's forwarded report is the same card, headed by the
// council, round and member; supermux's own notices (a child crashed, was killed, stopped with an
// error) are a quiet centred line. `[name:id]` tokens are for agents: people see the name alone.
package dev.supermux.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.supermux.proto.LogEntry
import dev.supermux.ui.FilePathRef
import dev.supermux.ui.MdBlock
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.parseMarkdownBlocks
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke

/** The id an inbox row carries when supermux itself sent it (a child's or council member's end). */
internal const val SUPERMUX_SENDER_ID = "supermux"

/** A session's id (a UUID), or supermux's own. */
private const val SENDER_ID = """(?:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}|supermux)"""

/**
 * `[name:id]` — how supermux names a session to an agent. The name may hold colons, dashes and
 * spaces ("Fix: login bug"); the id is a UUID or "supermux", so a time (`[12:30]`), a tag
 * (`[note:todo]`) or a markdown link (`[docs: setup](…)`) is not a token.
 */
private val ID_TOKEN = Regex("""\[([^\[\]:\s][^\[\]\n]*?):($SENDER_ID)\](?!\()""")

/** A council report's first line ends with the member's token and a colon: ` [name:id]:`. */
private val COUNCIL_HEAD_TOKEN = Regex("""\s*\[[^\[\]\n]+:$SENDER_ID\]:?\s*$""")

/** An inbox row's sender as stored when it was delivered; an older row of supermux's own still reads "supermux". */
internal fun inboxSenderName(entry: LogEntry): String? =
    entry.from_session_name?.takeIf { it.isNotBlank() } ?: SUPERMUX_SENDER_ID.takeIf { entry.from_session_id == it }

/** [text] with every `[name:id]` token shown as the name alone. */
internal fun noticePlainText(text: String): String = ID_TOKEN.replace(text) { it.groupValues[1] }

/**
 * A council's forwarded report, `"<council> · round N · L — <label> [<name>:<id>]:\n<report>"`, as
 * its header (the first line without the token and the colon) and its body (the rest).
 */
internal fun councilHeadAndBody(text: String): Pair<String, String> {
    val first = text.substringBefore('\n')
    val body = if ('\n' in text) text.substringAfter('\n').trimStart('\n') else ""
    val head = first.replace(COUNCIL_HEAD_TOKEN, "").trimEnd().removeSuffix(":").trimEnd()
    return head to body
}

/** [text] with each `[name:id]` token as the name, in [nameColor] Medium, the rest in [baseColor]. */
internal fun noticeAnnotated(text: String, baseColor: Color, nameColor: Color): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (match in ID_TOKEN.findAll(text)) {
        withStyle(SpanStyle(color = baseColor)) { append(text.substring(at, match.range.first)) }
        withStyle(SpanStyle(color = nameColor, fontWeight = FontWeight.Medium)) { append(match.groupValues[1]) }
        at = match.range.last + 1
    }
    withStyle(SpanStyle(color = baseColor)) { append(text.substring(at)) }
}

/** A supermux notice shows at most this many lines. */
private const val NOTICE_MAX_LINES = 4

/**
 * [text]'s first [max] lines, a cut ending in "…": Text draws its ellipsis only on a line that
 * overflows, not when whole lines after a line break are dropped, so the cut would not show.
 */
internal fun noticeLines(text: String, max: Int = NOTICE_MAX_LINES): String {
    val lines = text.trim().lines()
    return if (lines.size <= max) lines.joinToString("\n") else lines.take(max).joinToString("\n").trimEnd() + "…"
}

/** A supermux notice that something went wrong (the error icon), not just news (the info icon). */
internal fun isAlarmingNotice(text: String): Boolean = "crashed" in text || "stopped with an error" in text

/** Only words: the card can hug them. Code, tables and images take the card's full width. */
private fun hugsContent(blocks: List<MdBlock>): Boolean =
    blocks.none { it is MdBlock.Code || it is MdBlock.Table || it is MdBlock.Image }

/**
 * A message another session (or a council) sent this one: a card on the left, no wider than 85% of
 * the chat, headed by the sender. Tapping the header opens the sender: this chat's own child opens
 * its card ([SubagentUi.open]); a session the app lists ([canOpenChat]) opens its chat through
 * [LocalChatSearch]. A council report's header is not a link.
 */
@Composable
fun InboxMessageCard(
    entry: LogEntry,
    onOpenFile: (FilePathRef) -> Unit = {},
    subagentUi: SubagentUi? = null,
    canOpenChat: (String) -> Boolean = { false },
) {
    val cs = MaterialTheme.colorScheme
    val text = entry.text.orEmpty()
    val council = entry.council_id != null
    val (head, body) = if (council) councilHeadAndBody(text) else null to text
    val senderId = entry.from_session_id
    val childName = senderId?.let { subagentUi?.nameOf?.invoke(it) }
    val chatSearch = LocalChatSearch.current
    // What a tap on the header does, and what a screen reader calls it.
    val (onOpen, openLabel) = when {
        council || senderId == null -> Pair(null, null)
        subagentUi != null && childName != null -> Pair({ subagentUi.open(senderId) }, "Open subagent")
        chatSearch != null && canOpenChat(senderId) -> Pair({ chatSearch.openChat(senderId) }, "Open chat")
        else -> Pair(null, null)
    }
    // On touch the header is the only way to the sender: a full 48dp band across the card's top,
    // which takes the place of the card's own top padding and of the gap to the body.
    val touchTarget = onOpen != null && !LocalPointerAvailable.current
    val name = head
        ?: inboxSenderName(entry)
        ?: childName
        ?: senderId
        ?: "Another session"
    val blocks = remember(body) { parseMarkdownBlocks(body) }
    val shape = RoundedCornerShape(Radii.md)
    Box(Modifier.fillMaxWidth(0.85f)) {
        Column(
            Modifier
                .then(if (hugsContent(blocks)) Modifier.width(IntrinsicSize.Max) else Modifier.fillMaxWidth())
                .clip(shape)
                .background(cs.surfaceContainerLow)
                .border(Stroke.hairline, cs.outlineVariant, shape)
                .testTag("inbox-message:${entry.id}")
                .padding(start = 12.dp, end = 12.dp, top = if (touchTarget) 0.dp else 8.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(if (touchTarget) 0.dp else 4.dp),
        ) {
            Row(
                Modifier
                    .then(if (touchTarget) Modifier.fillMaxWidth().heightIn(min = 48.dp) else Modifier)
                    .then(
                        if (onOpen != null) {
                            Modifier
                                .clip(RoundedCornerShape(Radii.sm))
                                .clickable(onClickLabel = openLabel, onClick = onOpen)
                                .pointerHoverIcon(PointerIcon.Hand)
                        } else {
                            Modifier
                        },
                    )
                    // One unit for a screen reader (and for tests): the mark and the name.
                    .semantics(mergeDescendants = true) {}
                    .testTag("inbox-sender:${entry.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val agent = entry.from_agent?.takeIf { it.isNotBlank() && !council }
                if (agent != null) {
                    AgentLogo(agent, size = 16.dp)
                } else {
                    Icon(Icons.Filled.SmartToy, contentDescription = null, tint = cs.primary, modifier = Modifier.size(16.dp))
                }
                Text(
                    name,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (onOpen != null) cs.primary else cs.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (body.isNotBlank()) {
                LinkSelectionContainer {
                    MarkdownBody(text = body, modifier = Modifier.fillMaxWidth(), onOpenFile = onOpenFile, linkify = true)
                }
            }
        }
    }
}

/**
 * supermux's own notice (a child or council member crashed, was killed, stopped with an error): a
 * quiet centred line, at most four lines, in [dev.supermux.ui.accounts.AccountNoticeRow]'s tone.
 * The names show without their ids.
 */
@Composable
fun SupermuxNoticeRow(entryId: String, text: String) {
    val cs = MaterialTheme.colorScheme
    val alarming = isAlarmingNotice(text)
    val styled = remember(text, cs.onSurfaceVariant, cs.onSurface) { noticeAnnotated(noticeLines(text), cs.onSurfaceVariant, cs.onSurface) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Surface(
            // A pill on one line, a rounded block when the notice wraps.
            shape = RoundedCornerShape(13.dp),
            color = cs.surfaceContainerHigh.copy(alpha = 0.6f),
            border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.5f)),
            modifier = Modifier.widthIn(max = 520.dp).testTag("supermux-notice:$entryId"),
        ) {
            Row(
                Modifier.padding(start = Space.sm, end = Space.md, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    if (alarming) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = if (alarming) cs.error else cs.onSurfaceVariant,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    styled,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = NOTICE_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
