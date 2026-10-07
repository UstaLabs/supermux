/**
 * The permission / question card: the one place the agent stops and asks, docked just above the
 * composer.
 *
 * Design intent — it has to be answerable on a phone, at a glance, without reading twice:
 *  - one card = one question. A kind icon, the tool that asked, and a "waiting for you" chip say
 *    what this is before any text is read.
 *  - the thing being asked about (a command, a path, MCP arguments) is quoted verbatim in mono,
 *    wrapped, copyable, and clipped to [COLLAPSED_BODY_LINES] lines so a 200-line diff cannot push
 *    the buttons off screen.
 *  - the answers read like a desktop approval prompt (Claude Code, Codex, Cursor), not a phone
 *    sheet: a row of compact, right-aligned [CardButton]s — Reject (outlined, error-role label),
 *    the standing grant (outlined), the one-off (filled) at the far right. They wrap onto a second
 *    right-aligned line on a narrow phone rather than turning into full-width slabs.
 *  - the header asks the question ("Allow Bash?"); the body never repeats the tool name.
 *  - the moment an answer is tapped the whole card goes inert with a spinner, because the round
 *    trip to the agent is not instant and a second tap would be a different answer.
 *  - answering does not make the card vanish under the user's thumb: it leaves a one-line receipt
 *    ([ClosedRequestReceipt]) that states what was chosen, until the transcript moves on.
 *
 *  - a question set is paged, one question at a time behind step tabs (see [QuestionBody] for
 *    why), each option a full-width row with a radio or checkbox glyph and the agent's
 *    description, "Other" as a text-field row of the same list, and one right-aligned primary
 *    (Next → Submit) that stays disabled until the answer is complete.
 *
 * Test tags are contract with `tests/ui/prompts-journey.spec.ts` and
 * `tests/ui/question-card-shots.spec.ts`: `request-card:<id>`, `request-option:<id>`,
 * `request-freetext`, `request-send`, `request-decline`, plus `request-next`, `request-back`,
 * `request-step:<questionId>` and `request-receipt:<id>` for the question flow.
 */
package dev.supermux.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.supermux.proto.PromptQuestion
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.PromptRequestOption
import dev.supermux.proto.parsedQuestions
import dev.supermux.state.ClosedRequest
import dev.supermux.ui.adaptive.InputMode
import dev.supermux.ui.adaptive.LocalInputMode
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Motion
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.theme.Stroke
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Lines of the quoted body shown before "Show more" takes over. */
private const val COLLAPSED_BODY_LINES = 6

/**
 * The open prompts for one session, oldest on top (the order the broker opened them), with the
 * receipts of the ones that just closed above them.
 */
@Composable
fun RequestCards(
    requests: List<PromptRequest>,
    disabledIds: Set<String>,
    onRespond: (requestId: String, answer: JsonObject) -> Unit,
    modifier: Modifier = Modifier,
    closed: List<ClosedRequest> = emptyList(),
) {
    if (requests.isEmpty() && closed.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        for (receipt in closed) ClosedRequestReceipt(receipt)
        for (req in requests) {
            RequestCard(
                request = req,
                disabled = req.requestId in disabledIds,
                onRespond = { onRespond(req.requestId, it) },
            )
        }
    }
}

@Composable
fun RequestCard(
    request: PromptRequest,
    disabled: Boolean,
    onRespond: (JsonObject) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    // Long-press reveals the request id — useful when two cards are stacked and a log has to be
    // matched to one of them, but never worth a line of chrome on a phone.
    var showId by remember(request.requestId) { mutableStateOf(false) }
    var expanded by remember(request.requestId) { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("request-card:${request.requestId}"),
        shape = RoundedCornerShape(Radii.md),
        color = cs.surfaceContainerHigh,
        tonalElevation = 1.dp,
        // A hairline outline, not elevation: the card sits on the composer dock, where a shadow
        // would read as a second sheet rather than as "this one is waiting".
        border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(
            Modifier.padding(Space.md).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            val question = request.kind == "question"
            val (questions, flat) = remember(request.requestId, request.body) {
                if (question) questionsOf(request) else emptyList<PromptQuestion>() to false
            }
            RequestHeader(
                request = request,
                title = if (question) questionTitle(request, questions) else null,
                disabled = disabled,
                showId = showId,
                onToggleExpand = { expanded = !expanded },
                onToggleId = { showId = !showId },
            )
            if (question) {
                QuestionBody(request, questions, flat, disabled, onRespond)
            } else {
                PermissionBody(request, disabled, expanded, { expanded = !expanded }, onRespond)
            }
        }
    }
}

// ── header ────────────────────────────────────────────────────────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RequestHeader(
    request: PromptRequest,
    title: String?,
    disabled: Boolean,
    showId: Boolean,
    onToggleExpand: () -> Unit,
    onToggleId: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val permission = request.kind != "question"
    val name = requestName(request)
    val meta = buildList {
        // A subagent asking, not the main agent: say which one, first — it changes the answer.
        subagentLabel(request)?.let { add("from $it") }
        if (permission) name.qualifier?.let { add(it) }
        // A non-blocking ask comes from a turn the user is not watching; say so, or the card looks
        // like it appeared out of nowhere.
        if (!request.blocking) add("background turn")
        if (showId) add("#${request.requestId.takeLast(6)}")
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radii.sm))
            .combinedClickable(onClick = onToggleExpand, onLongClick = onToggleId),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // Tinted, not secondaryContainer: in this scheme that IS the card's own tone, and the badge
        // vanished into it. Warning-tinted shield for a permission, accent for a question.
        Surface(
            shape = CircleShape,
            color = if (permission) cs.tertiaryContainer else cs.primaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (permission) Icons.Filled.Shield else Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = if (permission) "Permission request" else "Question from the agent",
                    tint = if (permission) cs.onTertiaryContainer else cs.onPrimaryContainer,
                    modifier = Modifier.size(IconSize.md),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                title ?: "Allow ${name.title}?",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = MaterialTheme.typography.labelMedium,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        WaitingChip(disabled)
    }
}

/** "Waiting for you" until an answer is tapped, then the same slot becomes the spinner. */
@Composable
private fun WaitingChip(disabled: Boolean) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    if (disabled) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            CircularProgressIndicator(Modifier.size(IconSize.sm), color = cs.primary, strokeWidth = Stroke.thin)
            Text("Sending…", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        return
    }
    Surface(shape = RoundedCornerShape(Radii.pill), color = sem.warning.copy(alpha = 0.16f)) {
        Text(
            "Waiting for you",
            style = MaterialTheme.typography.labelMedium,
            color = sem.warning,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = 3.dp),
        )
    }
}

/** Who asked, when it was a subagent: its description, else its type, else a short id. */
internal fun subagentLabel(request: PromptRequest): String? {
    if (request.subagentId == null && request.subagentName == null && request.subagentDescription == null) return null
    return request.subagentDescription?.takeIf { it.isNotBlank() }
        ?: request.subagentName?.takeIf { it.isNotBlank() }
        ?: request.subagentId?.take(8)
}

/** What the header says: a tool name, plus the server/agent it belongs to when there is one. */
private data class RequestName(val title: String, val qualifier: String?)

private fun requestName(request: PromptRequest): RequestName {
    val raw = request.title.ifBlank { if (request.kind == "question") "Question" else "Permission" }
    // `mcp__<server>__<tool>` is the wire name for an MCP tool; nobody wants to read the prefix.
    if (raw.startsWith("mcp__")) {
        val parts = raw.removePrefix("mcp__").split("__")
        if (parts.size >= 2) return RequestName(parts.drop(1).joinToString("__"), parts[0])
    }
    // "mux-shim: rename_session" — the broker's own qualified form.
    val split = raw.indexOf(": ")
    if (split > 0) return RequestName(raw.substring(split + 2), raw.substring(0, split))
    return RequestName(raw, null)
}

// ── permission ────────────────────────────────────────────────────────────────────────────────

@Composable
private fun PermissionBody(
    request: PromptRequest,
    disabled: Boolean,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onRespond: (JsonObject) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val body = permissionBodyText(request)
    if (body.isNotBlank()) {
        val args = argumentRows(body)
        if (args != null) ArgumentRows(args, request.requestId) else QuotedBody(request, body, expanded, onToggleExpand)
    }

    val allows = request.options.filter { it.isAllow() }
    val rejects = request.options.filterNot { it.isAllow() }
    // A refusal the agent can learn from: the first tap on Reject opens the note, a second sends
    // the refusal as it stands. The note is optional on purpose — refusing must stay one gesture
    // away for someone who just wants the command to stop.
    var noteOpen by remember(request.requestId) { mutableStateOf(false) }
    var note by remember(request.requestId) { mutableStateOf("") }

    fun reject(option: PromptRequestOption) {
        onRespond(
            buildJsonObject {
                put("optionId", JsonPrimitive(option.id))
                if (note.isNotBlank()) put("message", JsonPrimitive(note))
            },
        )
    }

    if (noteOpen) {
        NoteField(
            value = note,
            placeholder = "Tell the agent why (optional)",
            enabled = !disabled,
            onChange = { note = it },
            onSubmit = { rejects.firstOrNull()?.let { reject(it) } },
        )
        Text(
            "${rejects.firstOrNull()?.label ?: "Reject"} again sends it, with or without a note.",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
    }
    // Desktop-dialog order, right-aligned: refusals, then standing grants, then the one-off as
    // the filled primary at the far right — where the eye lands last and the thumb lands first.
    // On a phone that runs out of room the row wraps (primary keeps the right edge) instead of
    // turning into full-width slabs.
    ActionRow {
        rejects.forEach { opt ->
            CardButton(
                label = opt.label,
                style = CardButtonStyle.Danger,
                enabled = !disabled,
                testTag = "request-option:${opt.id}",
            ) { if (noteOpen) reject(opt) else noteOpen = true }
        }
        allows.drop(1).forEach { opt ->
            CardButton(
                label = opt.label,
                style = CardButtonStyle.Secondary,
                enabled = !disabled,
                testTag = "request-option:${opt.id}",
            ) { onRespond(allowAnswer(opt)) }
        }
        allows.firstOrNull()?.let { opt ->
            CardButton(
                label = opt.label,
                style = CardButtonStyle.Primary,
                enabled = !disabled,
                testTag = "request-option:${opt.id}",
            ) { onRespond(allowAnswer(opt)) }
        }
    }
}

/**
 * The body minus the tool name the broker prefixes it with ("Bash git status" under a "Bash"
 * header said "Bash" twice). Client-side so it also holds against an older broker.
 */
private fun permissionBodyText(request: PromptRequest): String {
    val body = request.body.trim()
    val tool = request.title.trim()
    if (tool.isEmpty() || !body.startsWith(tool)) return body
    val rest = body.removePrefix(tool)
    return if (rest.firstOrNull()?.isWhitespace() == true) rest.trimStart() else body
}

private fun allowAnswer(option: PromptRequestOption): JsonObject =
    buildJsonObject { put("optionId", JsonPrimitive(option.id)) }

/** `allow_once` / `allow_always`, or an id the adapter spelled that way when it sent no kind. */
private fun PromptRequestOption.isAllow(): Boolean =
    kind?.startsWith("allow") == true || (kind == null && id.startsWith("allow"))

// ── buttons ───────────────────────────────────────────────────────────────────────────────────

/** Visual height of a card button: desktop-dialog compact; a touch screen gets a little more. */
@Composable
internal fun buttonHeight() = if (LocalInputMode.current == InputMode.Touch) 40.dp else 34.dp

internal enum class CardButtonStyle { Primary, Secondary, Danger, Ghost }

/**
 * The one button both request cards use: compact, 8dp corners (a pill reads as a phone FAB-row),
 * sized to its label. Primary is filled accent; Secondary a hairline outline; Danger the same
 * outline with the error role on the label (refusing is the cautious act, not a red slab); Ghost
 * is text only. Material still pads the touch target to 48dp on touch, invisibly.
 */
@Composable
internal fun CardButton(
    label: String,
    style: CardButtonStyle,
    enabled: Boolean,
    testTag: String,
    leading: ImageVector? = null,
    trailing: ImageVector? = null,
    onClick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.sm)
    val padding = PaddingValues(start = if (leading != null) 10.dp else 14.dp, end = if (trailing != null) 10.dp else 14.dp)
    val mod = Modifier.heightIn(min = buttonHeight()).pointerHoverIcon(PointerIcon.Hand).testTag(testTag)
    val content: @Composable RowScope.() -> Unit = {
        if (leading != null) {
            Icon(leading, contentDescription = null, modifier = Modifier.size(IconSize.md))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, contentDescription = null, modifier = Modifier.size(IconSize.md))
        }
    }
    when (style) {
        CardButtonStyle.Primary -> Button(
            onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding,
            modifier = mod, content = content,
        )
        CardButtonStyle.Secondary, CardButtonStyle.Danger -> OutlinedButton(
            onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding,
            border = BorderStroke(Stroke.hairline, if (enabled) cs.outline else cs.outlineVariant),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (style == CardButtonStyle.Danger) cs.error else cs.onSurface,
            ),
            modifier = mod, content = content,
        )
        CardButtonStyle.Ghost -> TextButton(
            onClick = onClick, enabled = enabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.textButtonColors(contentColor = cs.onSurfaceVariant),
            modifier = mod, content = content,
        )
    }
}

/** Right-aligned buttons that wrap onto a second right-aligned line rather than squeeze. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionRow(content: @Composable () -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = Space.xs),
        horizontalArrangement = Arrangement.spacedBy(Space.sm, Alignment.End),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) { content() }
}

/** The command / path, quoted verbatim: mono, wrapping, copyable, clipped until asked. */
@Composable
private fun QuotedBody(request: PromptRequest, body: String, expanded: Boolean, onToggleExpand: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    // A shell prompt glyph says "this will be run" without a word of chrome.
    val shell = request.title.equals("Bash", ignoreCase = true) || request.title.equals("shell", ignoreCase = true)
    // Sticky: with maxLines lifted the layout no longer overflows, and the toggle must not vanish.
    var overflowed by remember(request.requestId) { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radii.sm)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(cs.surfaceContainerLowest)
            .border(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.6f), shape),
    ) {
        // The copy button floats in the corner so a one-line command stays a one-line block.
        Box(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(start = Space.md, top = 10.dp, bottom = 10.dp, end = 36.dp)) {
                // bodySmall, not body: a command is read character by character, and at body size
                // a phone wrapped every pipe onto its own line.
                // Ligatures off: the mono face fused " --short" into an arrow-ish glyph that read as
                // "status--short" — in a command being approved, every character must be literal.
                val mono = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFontFamily, fontFeatureSettings = "liga 0, calt 0")
                if (shell) {
                    Text("$", style = mono, color = cs.onSurfaceVariant, modifier = Modifier.padding(end = Space.sm))
                }
                Text(
                    body,
                    style = mono,
                    color = cs.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_BODY_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow) overflowed = true },
                    modifier = Modifier.weight(1f).testTag("request-body:${request.requestId}"),
                )
            }
            IconButton(
                onClick = { platform.copyToClipboard(body); platform.notices.show("Copied") },
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(28.dp)
                    .pointerHoverIcon(PointerIcon.Hand).testTag("request-copy:${request.requestId}"),
            ) {
                Icon(
                    Icons.Filled.ContentCopy,
                    contentDescription = "Copy to clipboard",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        }
        if (overflowed) {
            Text(
                if (expanded) "Show less" else "Show more",
                style = MaterialTheme.typography.labelMedium,
                color = cs.primary,
                modifier = Modifier
                    .padding(start = Space.xs, bottom = Space.xs)
                    .clip(RoundedCornerShape(Radii.sm))
                    .clickable(onClick = onToggleExpand)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(horizontal = Space.sm, vertical = 6.dp)
                    .testTag("request-expand:${request.requestId}"),
            )
        }
    }
}

/** MCP-style arguments, one `key: value` row each, when the body carries a JSON object. */
@Composable
private fun ArgumentRows(args: List<Pair<String, String>>, requestId: String) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radii.sm)
    Surface(shape = shape, color = cs.surfaceContainerLowest, border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.6f))) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = 10.dp).testTag("request-body:$requestId"),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            for ((key, value) in args) {
                Row(Modifier.fillMaxWidth()) {
                    Text(
                        key,
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = MonoFontFamily,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = MonoFontFamily,
                        color = cs.onSurface,
                        maxLines = COLLAPSED_BODY_LINES,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val bodyJson = Json { ignoreUnknownKeys = true; isLenient = true }

/**
 * `{ "path": "/tmp/x", "recursive": true }` inside the body → key/value rows. Returns null for the
 * ordinary case (a command line), which reads better verbatim than as one long "command:" row.
 */
private fun argumentRows(body: String): List<Pair<String, String>>? {
    val start = body.indexOf('{')
    if (start < 0 || !body.trimEnd().endsWith("}")) return null
    val parsed = runCatching { bodyJson.parseToJsonElement(body.substring(start)) }.getOrNull()
    val obj = parsed as? JsonObject ?: return null
    if (obj.isEmpty()) return null
    return obj.entries.map { (key, value) ->
        key to ((value as? JsonPrimitive)?.content ?: value.toString())
    }
}

// ── question ──────────────────────────────────────────────────────────────────────────────────

/** Answer id for the flat (unparsed) question, which has no per-question id of its own. */
private const val FLAT_QUESTION_ID = "_flat"

/** Minimum height of an option row — a touch target with room for a two-line label. */
private val OPTION_MIN_HEIGHT = 48.dp

/** Diameter of the radio / checkbox glyph at the start of an option row. */
private val INDICATOR_SIZE = 20.dp

/**
 * The questions a `kind=question` request asks. The broker ships them JSON-encoded in the body;
 * when that fails to parse (an older or foreign adapter) the request's own options become one
 * synthetic question, so both paths share one UI.
 */
private fun questionsOf(request: PromptRequest): Pair<List<PromptQuestion>, Boolean> {
    val parsed = request.parsedQuestions()
    if (parsed.isNotEmpty()) return parsed to false
    return listOf(
        PromptQuestion(
            id = FLAT_QUESTION_ID,
            prompt = request.body,
            allowFreeText = request.allowFreeText,
            options = request.options,
        ),
    ) to true
}

/**
 * The card title for a question. Never the same words as the text right under it: a set of
 * questions is titled by its count (the step tabs carry each header), a single question by its
 * header; a question with no header is just "Question", because the broker's fallback title for
 * it IS the prompt, which the body is about to show.
 */
private fun questionTitle(request: PromptRequest, questions: List<PromptQuestion>): String {
    if (questions.size > 1) return "${questions.size} questions"
    val only = questions.firstOrNull()
    only?.header?.takeIf { it.isNotBlank() }?.let { return it }
    val name = requestName(request).title
    return if (name.isBlank() || name == only?.prompt?.trim() || name == request.body.trim()) "Question" else name
}

/** One question's in-progress answer: the picked option ids, plus the "Other" field. */
private data class Draft(
    val picked: List<String> = emptyList(),
    val other: String = "",
    /** "Other" is the chosen answer (single-select) / one of them (multi-select). */
    val otherOn: Boolean = false,
)

/**
 * What a draft sends: an option id or free text for single-select, a list for multi-select, null
 * while unanswered. Free text is sent verbatim; the broker passes anything that is not an option
 * id straight through to the agent.
 */
private fun PromptQuestion.valueOf(d: Draft): Any? {
    val typed = d.other.trim().takeIf { it.isNotEmpty() && (d.otherOn || options.isEmpty()) }
    if (multiSelect) {
        val ids = options.map { it.id }.filter { it in d.picked }
        val all = if (typed != null) ids + typed else ids
        return all.ifEmpty { null }
    }
    return typed ?: d.picked.firstOrNull()
}

/**
 * A question set, one question per page.
 *
 * Paged, not stacked, on every width: this card is pinned in the composer dock, which does not
 * scroll, so its height comes straight out of the transcript. Three stacked questions with
 * descriptions are taller than a phone screen (the old stacked card pushed its own Send button
 * off screen at 390px) and still crowd a laptop. The step tabs keep the overview a stacked layout
 * gives — every header, which ones are answered, a tap to jump — at the height of one question.
 */
@Composable
private fun QuestionBody(
    request: PromptRequest,
    questions: List<PromptQuestion>,
    flat: Boolean,
    disabled: Boolean,
    onRespond: (JsonObject) -> Unit,
) {
    var page by remember(request.requestId) { mutableStateOf(0) }
    var drafts by remember(request.requestId) { mutableStateOf(mapOf<String, Draft>()) }
    val current = questions[page.coerceIn(0, questions.lastIndex)]
    fun draftOf(q: PromptQuestion) = drafts[q.id] ?: Draft()
    // (A question that offers nothing to answer with — no options, no free text — cannot block.)
    fun answered(q: PromptQuestion) = q.valueOf(draftOf(q)) != null || (q.options.isEmpty() && !q.allowFreeText)
    val last = page >= questions.lastIndex
    val canAdvance = !disabled && answered(current)
    val canSubmit = !disabled && questions.all { answered(it) }

    fun submit() {
        if (!canSubmit) return
        if (flat) {
            val value = current.valueOf(draftOf(current))?.toString().orEmpty()
            onRespond(buildJsonObject { put("optionId", JsonPrimitive(value)) })
        } else {
            onRespond(answersObject(questions.mapNotNull { q -> q.valueOf(draftOf(q))?.let { q.id to it } }.toMap()))
        }
    }
    fun advance() {
        if (last) submit() else if (canAdvance) page += 1
    }

    if (questions.size > 1) {
        StepTabs(
            questions = questions,
            page = page,
            answered = { answered(it) },
            enabled = !disabled,
            onPick = { page = it },
        )
    }
    // A plain swap, no AnimatedContent: a cross-fade keeps the outgoing page's rows alive (and
    // tappable) for its duration, and a tap landing on one that is detaching crashed the web client
    // ("Cannot read CompositionLocal because the Modifier node is not currently attached").
    key(page) {
        val q = current
        QuestionPage(
            question = q,
            showPrompt = q.prompt.isNotBlank(),
            draft = draftOf(q),
            enabled = !disabled,
            onChange = { drafts = drafts + (q.id to it) },
            onDone = ::advance,
        )
    }
    QuestionActions(
        showBack = page > 0,
        primaryLabel = if (last) "Submit" else "Next",
        primaryTag = if (last) "request-send" else "request-next",
        primaryEnabled = if (last) canSubmit else canAdvance,
        disabled = disabled,
        onBack = { page -= 1 },
        onPrimary = ::advance,
        onDecline = { onRespond(buildJsonObject { put("decline", JsonPrimitive(true)) }) },
    )
}

/** "① Goal  ② Features  ③ Branch": where you are, what is answered, and a tap to jump. */
@Composable
private fun StepTabs(
    questions: List<PromptQuestion>,
    page: Int,
    answered: (PromptQuestion) -> Boolean,
    enabled: Boolean,
    onPick: (Int) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        questions.forEachIndexed { index, q ->
            val active = index == page
            val done = answered(q)
            val container by animateColorAsState(
                if (active) cs.primary.copy(alpha = 0.14f) else Color.Transparent,
                Motion.stateChange(),
                label = "step-bg",
            )
            val content = if (active) cs.primary else cs.onSurfaceVariant
            Row(
                Modifier
                    .clip(RoundedCornerShape(Radii.pill))
                    .background(container)
                    .selectable(selected = active, enabled = enabled, role = Role.Tab, onClick = { onPick(index) })
                    .pointerHoverIcon(PointerIcon.Hand)
                    .heightIn(min = 32.dp)
                    .padding(start = Space.xs + 2.dp, end = Space.md)
                    .testTag("request-step:${q.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StepBadge(number = index + 1, done = done, active = active)
                Text(
                    q.header?.takeIf { it.isNotBlank() } ?: "Question ${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = content,
                    maxLines = 1,
                )
            }
        }
    }
}

/** The step number, or a tick once that question has an answer. */
@Composable
private fun StepBadge(number: Int, done: Boolean, active: Boolean) {
    val cs = MaterialTheme.colorScheme
    val fill = when {
        done -> cs.primary
        active -> cs.primary.copy(alpha = 0.22f)
        else -> cs.surfaceContainerHighest
    }
    Box(Modifier.size(20.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        if (done) {
            Icon(Icons.Filled.Check, contentDescription = "Answered", tint = cs.onPrimary, modifier = Modifier.size(IconSize.sm))
        } else {
            Text(
                "$number",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (active) cs.primary else cs.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuestionPage(
    question: PromptQuestion,
    showPrompt: Boolean,
    draft: Draft,
    enabled: Boolean,
    onChange: (Draft) -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showPrompt) {
            Text(
                question.prompt,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = cs.onSurface,
                modifier = Modifier.padding(top = Space.xs).testTag("request-prompt:${question.id}"),
            )
        }
        if (question.multiSelect && question.options.isNotEmpty()) {
            Text("Select all that apply", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(2.dp))
        question.options.forEach { opt ->
            val selected = opt.id in draft.picked
            OptionRow(
                label = opt.label,
                description = opt.description?.takeIf { it.isNotBlank() },
                selected = selected,
                multiSelect = question.multiSelect,
                enabled = enabled,
                testTag = "request-option:${opt.id}",
            ) {
                onChange(
                    if (question.multiSelect) {
                        draft.copy(picked = if (selected) draft.picked - opt.id else draft.picked + opt.id)
                    } else {
                        // Picking an option is the answer; it takes the choice back from "Other".
                        draft.copy(picked = listOf(opt.id), otherOn = false)
                    },
                )
            }
        }
        if (question.allowFreeText || question.options.isEmpty()) {
            OtherRow(
                value = draft.other,
                active = draft.otherOn && draft.other.isNotBlank(),
                standalone = question.options.isEmpty(),
                multiSelect = question.multiSelect,
                enabled = enabled,
                onChange = { text ->
                    onChange(
                        if (question.multiSelect) {
                            draft.copy(other = text, otherOn = text.isNotBlank())
                        } else {
                            // Typing is choosing "Other": the radio moves to the field.
                            draft.copy(other = text, otherOn = text.isNotBlank(), picked = if (text.isNotBlank()) emptyList() else draft.picked)
                        },
                    )
                },
                onToggle = {
                    val on = !(draft.otherOn && draft.other.isNotBlank())
                    onChange(
                        if (question.multiSelect || !on) draft.copy(otherOn = on)
                        else draft.copy(otherOn = true, picked = emptyList()),
                    )
                },
                onDone = onDone,
            )
        }
    }
}

/** The shared shell of an option row: surface, border, hover and selected states. */
@Composable
private fun optionColors(selected: Boolean, hovered: Boolean, focused: Boolean = false): Pair<Color, Color> {
    val cs = MaterialTheme.colorScheme
    val container by animateColorAsState(
        when {
            selected -> cs.primary.copy(alpha = 0.10f)
            hovered -> cs.onSurface.copy(alpha = 0.05f)
            // Light: a white row on the tinted card. Dark: the lowest container is near-black and
            // read as a hole in the card, so the row is a faint lift of the card instead.
            cs.surface.luminance() < 0.5f -> cs.onSurface.copy(alpha = 0.04f)
            else -> cs.surfaceContainerLowest
        },
        Motion.stateChange(),
        label = "option-bg",
    )
    val border by animateColorAsState(
        when {
            selected || focused -> cs.primary
            hovered -> cs.outline
            else -> cs.outlineVariant
        },
        Motion.stateChange(),
        label = "option-border",
    )
    return container to border
}

/**
 * One option as a full-width row: the glyph, the label and the description are one tap target,
 * with a tinted surface and an accent border once chosen.
 */
@Composable
private fun OptionRow(
    label: String,
    description: String?,
    selected: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    testTag: String,
    onPick: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val (container, border) = optionColors(selected, hovered && enabled)
    val shape = RoundedCornerShape(Radii.md)
    val click = if (multiSelect) {
        Modifier.toggleable(
            value = selected,
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.Checkbox,
            onValueChange = { onPick() },
        )
    } else {
        Modifier.selectable(
            selected = selected,
            interactionSource = interaction,
            indication = LocalIndication.current,
            enabled = enabled,
            role = Role.RadioButton,
            onClick = onPick,
        )
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = OPTION_MIN_HEIGHT)
            .clip(shape)
            .background(container)
            .border(Stroke.hairline, border, shape)
            .then(click)
            .pointerHoverIcon(PointerIcon.Hand)
            .testTag(testTag)
            .padding(horizontal = Space.md, vertical = 10.dp),
        verticalAlignment = if (description == null) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        ChoiceGlyph(
            selected = selected,
            multiSelect = multiSelect,
            enabled = enabled,
            modifier = if (description == null) Modifier else Modifier.padding(top = 1.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (enabled) cs.onSurface else cs.onSurface.copy(alpha = 0.6f),
            )
            if (description != null) {
                Text(description, style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
        }
    }
}

/** A radio dot (one answer) or a check box (several), drawn to match the row's accent. */
@Composable
private fun ChoiceGlyph(selected: Boolean, multiSelect: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val accent = if (enabled) cs.primary else cs.onSurfaceVariant
    // onSurfaceVariant, not outline: the outline token all but vanished on a dark option row.
    val ring by animateColorAsState(
        if (selected) accent else cs.onSurfaceVariant.copy(alpha = 0.75f),
        Motion.stateChange(),
        label = "glyph-ring",
    )
    if (multiSelect) {
        val shape = RoundedCornerShape(5.dp)
        Box(
            modifier
                .size(INDICATOR_SIZE)
                .clip(shape)
                .background(if (selected) accent else Color.Transparent)
                .border(1.5.dp, ring, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = cs.onPrimary, modifier = Modifier.size(IconSize.sm))
        }
    } else {
        Box(
            modifier.size(INDICATOR_SIZE).clip(CircleShape).border(1.5.dp, ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
        }
    }
}

/**
 * "Other": a row of the option list whose label is a text field. With no options at all it is
 * the whole answer, so the glyph goes and the placeholder asks for the answer directly.
 */
@Composable
private fun OtherRow(
    value: String,
    active: Boolean,
    standalone: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onToggle: () -> Unit,
    onDone: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val (container, border) = optionColors(active, hovered && enabled, focused)
    val shape = RoundedCornerShape(Radii.md)
    val focus = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = OPTION_MIN_HEIGHT)
            .clip(shape)
            .background(container)
            .border(Stroke.hairline, border, shape)
            .hoverable(interaction)
            // 2dp + the 40dp glyph target (glyph centred in it) lines the glyph and the text up with
            // the option rows above: 12dp to the glyph, 12dp from glyph to text.
            .padding(start = if (standalone) 0.dp else 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!standalone) {
            // The glyph is its own 40dp target: tapping it toggles "Other" without opening the
            // keyboard; tapping the text opens the keyboard.
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickable(enabled = enabled, role = if (multiSelect) Role.Checkbox else Role.RadioButton) {
                        if (value.isBlank()) runCatching { focus.requestFocus() } else onToggle()
                    }
                    .testTag("request-other-toggle"),
                contentAlignment = Alignment.Center,
            ) {
                ChoiceGlyph(selected = active, multiSelect = multiSelect, enabled = enabled)
            }
        }
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
            cursorBrush = SolidColor(cs.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone() }),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focus)
                .onFocusChanged { focused = it.isFocused }
                .padding(start = if (standalone) Space.md else 2.dp, end = Space.md, top = 12.dp, bottom = 12.dp)
                .testTag("request-freetext"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(
                            if (standalone) "Type your answer" else "Other — type your own answer",
                            style = MaterialTheme.typography.bodyLarge,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
    }
}

/**
 * Back on the left (only past the first question); Decline and the one primary action on the
 * right, the same compact buttons as the permission card.
 */
@Composable
private fun QuestionActions(
    showBack: Boolean,
    primaryLabel: String,
    primaryTag: String,
    primaryEnabled: Boolean,
    disabled: Boolean,
    onBack: () -> Unit,
    onPrimary: () -> Unit,
    onDecline: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        if (showBack) {
            CardButton(
                label = "Back",
                style = CardButtonStyle.Ghost,
                enabled = !disabled,
                testTag = "request-back",
                leading = Icons.AutoMirrored.Filled.ArrowBack,
                onClick = onBack,
            )
        }
        Spacer(Modifier.weight(1f))
        CardButton(label = "Decline", style = CardButtonStyle.Secondary, enabled = !disabled, testTag = "request-decline", onClick = onDecline)
        CardButton(
            label = primaryLabel,
            style = CardButtonStyle.Primary,
            enabled = primaryEnabled,
            testTag = primaryTag,
            trailing = if (primaryTag == "request-next") Icons.AutoMirrored.Filled.ArrowForward else null,
            onClick = onPrimary,
        )
    }
}

/**
 * The one-line field a refusal's note is typed into — a row surface like the question card's
 * "Other" row; Enter sends the refusal.
 */
@Composable
private fun NoteField(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var focused by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val (container, border) = optionColors(selected = false, hovered = false, focused = focused)
    val shape = RoundedCornerShape(Radii.sm)
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    BasicTextField(
        value = value,
        onValueChange = onChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
        cursorBrush = SolidColor(cs.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
        keyboardActions = KeyboardActions(onSend = { onSubmit() }),
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(container)
            .border(Stroke.hairline, border, shape)
            .focusRequester(focus)
            .onFocusChanged { focused = it.isFocused }
            .padding(horizontal = Space.md, vertical = 10.dp)
            .testTag("request-freetext"),
        decorationBox = { inner ->
            Box(contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant, maxLines = 1)
                }
                inner()
            }
        },
    )
}

// ── receipt ───────────────────────────────────────────────────────────────────────────────────

/**
 * What a card leaves behind until the transcript moves on: a quiet one-card-high strip that says
 * what happened — "Answered  Test supermux features, Multi-select", "Bash  Allow once",
 * "Declined  Deploy". It states the answer the broker reported, so it stays truthful for a
 * rejection; a tick, a cross or a neutral stop mark says which kind of ending it was.
 */
@Composable
private fun ClosedRequestReceipt(receipt: ClosedRequest) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val question = receipt.kind == "question"
    val label = receipt.answerLabel?.takeIf { it.isNotBlank() }
    val unanswered = receipt.outcome != "answered"
    // The broker's decline label is the fixed string "Declined" (request-map.ts answerLabel).
    val declined = question && label == "Declined"
    val rejected = receipt.answerKind?.startsWith("reject") == true
    val icon = when {
        unanswered || declined -> Icons.Filled.Block
        rejected -> Icons.Filled.Close
        else -> Icons.Filled.Check
    }
    val tint = when {
        unanswered || declined -> cs.onSurfaceVariant
        rejected -> sem.danger
        else -> sem.success
    }
    val title = requestNameOf(receipt)
    val (lead, rest) = when {
        unanswered -> receipt.outcome.replaceFirstChar { it.uppercase() } to title
        declined -> "Declined" to title
        question -> "Answered" to label
        else -> (title ?: "Permission") to (label ?: "Answered")
    }
    Surface(
        shape = RoundedCornerShape(Radii.md),
        color = cs.surfaceContainerHigh.copy(alpha = 0.6f),
        border = BorderStroke(Stroke.hairline, cs.outlineVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().testTag("request-receipt:${receipt.requestId}"),
    ) {
        Row(
            Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Box(
                Modifier.size(20.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = when {
                        unanswered -> "Request closed unanswered"
                        declined -> "Question declined"
                        else -> "Request answered"
                    },
                    tint = tint,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = cs.onSurfaceVariant, fontWeight = FontWeight.Medium)) { append(lead) }
                    if (!rest.isNullOrBlank()) {
                        append("  ")
                        withStyle(SpanStyle(color = cs.onSurface)) { append(rest) }
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

private fun requestNameOf(receipt: ClosedRequest): String? = receipt.title.takeIf { it.isNotBlank() }

// ── wire shapes ───────────────────────────────────────────────────────────────────────────────

private fun answersObject(raw: Map<String, Any>): JsonObject = buildJsonObject {
    put("answers", buildJsonObject {
        for ((k, v) in raw) {
            when (v) {
                is Collection<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
    })
}
