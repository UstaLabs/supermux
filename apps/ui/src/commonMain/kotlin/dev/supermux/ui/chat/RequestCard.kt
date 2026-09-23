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
 *  - the answers are ranked the way M3 ranks actions: filled for the safe one-off, tonal for the
 *    standing grant, a plain text button for the refusal (tinted with the error role, never a red
 *    filled button — refusing is not the destructive-emphasis case, it is the cautious one).
 *  - the moment an answer is tapped the whole card goes inert with a spinner, because the round
 *    trip to the agent is not instant and a second tap would be a different answer.
 *  - answering does not make the card vanish under the user's thumb: it leaves a one-line receipt
 *    ([ClosedRequestReceipt]) that states what was chosen, until the transcript moves on.
 *
 * Test tags are contract with `tests/ui/prompts-journey.spec.ts`: `request-card:<id>`,
 * `request-option:<id>`, `request-freetext`, `request-send`, `request-decline`.
 */
package dev.supermux.ui.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.supermux.proto.PromptQuestion
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.PromptRequestOption
import dev.supermux.proto.parsedQuestions
import dev.supermux.state.ClosedRequest
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.IconSize
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.theme.MonoFontFamily
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

/** Every tap target in the card clears the 44dp minimum, tonal buttons included. */
private val TAP_TARGET = 44.dp

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
            RequestHeader(
                request = request,
                disabled = disabled,
                showId = showId,
                onToggleExpand = { expanded = !expanded },
                onToggleId = { showId = !showId },
            )
            if (request.kind == "question") {
                QuestionBody(request, disabled, onRespond)
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
    disabled: Boolean,
    showId: Boolean,
    onToggleExpand: () -> Unit,
    onToggleId: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val permission = request.kind != "question"
    val name = requestName(request)
    val meta = buildList {
        name.qualifier?.let { add(it) }
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
        Surface(shape = CircleShape, color = cs.secondaryContainer, modifier = Modifier.size(28.dp)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = if (permission) Icons.Filled.Shield else Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = if (permission) "Permission request" else "Question from the agent",
                    tint = cs.onSecondaryContainer,
                    modifier = Modifier.size(IconSize.md),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                name.title,
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
    if (request.body.isNotBlank()) {
        val args = argumentRows(request.body)
        if (args != null) ArgumentRows(args, request.requestId) else QuotedBody(request, expanded, onToggleExpand)
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

    if (allows.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            allows.forEachIndexed { index, opt ->
                val mod = Modifier.weight(1f).heightIn(min = TAP_TARGET).testTag("request-option:${opt.id}")
                val label = @Composable { Text(opt.label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                // First allow = the one-off; the rest are standing grants, which get less weight.
                if (index == 0) {
                    Button(onClick = { onRespond(allowAnswer(opt)) }, enabled = !disabled, modifier = mod) { label() }
                } else {
                    FilledTonalButton(onClick = { onRespond(allowAnswer(opt)) }, enabled = !disabled, modifier = mod) { label() }
                }
            }
        }
    }
    if (noteOpen) {
        NoteField(
            value = note,
            placeholder = "Tell the agent why (optional)",
            enabled = !disabled,
            onChange = { note = it },
            onSend = { rejects.firstOrNull()?.let { reject(it) } },
        )
    }
    rejects.forEach { opt ->
        TextButton(
            onClick = { if (noteOpen) reject(opt) else noteOpen = true },
            enabled = !disabled,
            colors = ButtonDefaults.textButtonColors(contentColor = cs.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = TAP_TARGET).testTag("request-option:${opt.id}"),
        ) { Text(opt.label) }
    }
    if (noteOpen) {
        Text(
            "Tap ${rejects.firstOrNull()?.label ?: "Reject"} again to send it without a note.",
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
        )
    }
}

private fun allowAnswer(option: PromptRequestOption): JsonObject =
    buildJsonObject { put("optionId", JsonPrimitive(option.id)) }

/** `allow_once` / `allow_always`, or an id the adapter spelled that way when it sent no kind. */
private fun PromptRequestOption.isAllow(): Boolean =
    kind?.startsWith("allow") == true || (kind == null && id.startsWith("allow"))

/** The command / path, quoted verbatim: mono, wrapping, copyable, clipped until asked. */
@Composable
private fun QuotedBody(request: PromptRequest, expanded: Boolean, onToggleExpand: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    // Sticky: with maxLines lifted the layout no longer overflows, and the toggle must not vanish.
    var overflowed by remember(request.requestId) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(Radii.sm), color = cs.surfaceContainerLowest) {
        Column(Modifier.fillMaxWidth().padding(start = Space.sm, top = Space.sm, bottom = Space.xs)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    request.body,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = MonoFontFamily,
                    color = cs.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else COLLAPSED_BODY_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (it.hasVisualOverflow) overflowed = true },
                    modifier = Modifier.weight(1f).padding(top = Space.xs).testTag("request-body:${request.requestId}"),
                )
                IconButton(
                    onClick = { platform.copyToClipboard(request.body); platform.notices.show("Copied") },
                    modifier = Modifier.size(TAP_TARGET).testTag("request-copy:${request.requestId}"),
                ) {
                    Icon(
                        Icons.Filled.ContentCopy,
                        contentDescription = "Copy to clipboard",
                        tint = cs.onSurfaceVariant,
                        modifier = Modifier.size(IconSize.md),
                    )
                }
            }
            if (overflowed) {
                TextButton(
                    onClick = onToggleExpand,
                    modifier = Modifier.heightIn(min = TAP_TARGET).testTag("request-expand:${request.requestId}"),
                ) { Text(if (expanded) "Show less" else "Show more") }
            }
        }
    }
}

/** MCP-style arguments, one `key: value` row each, when the body carries a JSON object. */
@Composable
private fun ArgumentRows(args: List<Pair<String, String>>, requestId: String) {
    val cs = MaterialTheme.colorScheme
    Surface(shape = RoundedCornerShape(Radii.sm), color = cs.surfaceContainerLowest) {
        Column(
            Modifier.fillMaxWidth().padding(Space.sm).testTag("request-body:$requestId"),
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

@Composable
private fun QuestionBody(
    request: PromptRequest,
    disabled: Boolean,
    onRespond: (JsonObject) -> Unit,
) {
    val questions = request.parsedQuestions()
    if (questions.isEmpty()) {
        FlatQuestion(request, disabled, onRespond)
        return
    }
    // One map for the whole card: a question set is answered as a unit, with a single Send.
    var answers by remember(request.requestId) { mutableStateOf(mapOf<String, Any>()) }
    var freeText by remember(request.requestId) { mutableStateOf(mapOf<String, String>()) }
    questions.forEach { q ->
        QuestionPrompt(q)
        q.options.forEach { opt ->
            val selected = if (q.multiSelect) {
                (answers[q.id] as? Set<*>)?.contains(opt.id) == true
            } else {
                answers[q.id] == opt.id
            }
            ChoiceRow(
                label = opt.label,
                selected = selected,
                multiSelect = q.multiSelect,
                enabled = !disabled,
                testTag = "request-option:${opt.id}",
            ) {
                answers = if (q.multiSelect) {
                    val cur = (answers[q.id] as? Set<*>)?.mapNotNull { it as? String }?.toMutableSet() ?: mutableSetOf()
                    if (selected) cur.remove(opt.id) else cur.add(opt.id)
                    answers + (q.id to cur.toSet())
                } else {
                    answers + (q.id to opt.id)
                }
            }
        }
        if (q.allowFreeText) {
            NoteField(
                value = freeText[q.id].orEmpty(),
                placeholder = if (q.options.isEmpty()) "Your answer" else "Something else…",
                enabled = !disabled,
                onChange = { freeText = freeText + (q.id to it) },
                onSend = null,
            )
        }
    }
    val typed = freeText.filterValues { it.isNotBlank() }
    QuestionActions(
        disabled = disabled || (answers.isEmpty() && typed.isEmpty()),
        // Free text wins over a selection for the same question: it is the later, more specific act.
        onSend = { onRespond(answersObject(answers + typed)) },
        onDecline = { onRespond(buildJsonObject { put("decline", JsonPrimitive(true)) }) },
    )
}

/** A question the broker could not break into [PromptQuestion]s — options straight off the request. */
@Composable
private fun FlatQuestion(request: PromptRequest, disabled: Boolean, onRespond: (JsonObject) -> Unit) {
    if (request.body.isNotBlank()) {
        Text(request.body, style = MaterialTheme.typography.bodyMedium)
    }
    var picked by remember(request.requestId) { mutableStateOf<String?>(null) }
    var freeText by remember(request.requestId) { mutableStateOf("") }
    request.options.forEach { opt ->
        ChoiceRow(
            label = opt.label,
            selected = picked == opt.id,
            multiSelect = false,
            enabled = !disabled,
            testTag = "request-option:${opt.id}",
        ) { picked = opt.id }
    }
    if (request.allowFreeText) {
        NoteField(
            value = freeText,
            placeholder = "Your answer",
            enabled = !disabled,
            onChange = { freeText = it },
            onSend = null,
        )
    }
    QuestionActions(
        disabled = disabled || (picked == null && freeText.isBlank()),
        onSend = {
            val chosen = freeText.ifBlank { picked.orEmpty() }
            onRespond(buildJsonObject { put("optionId", JsonPrimitive(chosen)) })
        },
        onDecline = { onRespond(buildJsonObject { put("decline", JsonPrimitive(true)) }) },
    )
}

@Composable
private fun QuestionPrompt(q: PromptQuestion) {
    Text(q.header ?: q.prompt, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
    if (q.header != null && q.prompt.isNotBlank()) {
        Text(q.prompt, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One option as a full-width row — the control and its label are a single 44dp tap target, which a
 * bare [RadioButton] is not.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChoiceRow(
    label: String,
    selected: Boolean,
    multiSelect: Boolean,
    enabled: Boolean,
    testTag: String,
    onPick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = TAP_TARGET)
            .clip(RoundedCornerShape(Radii.sm))
            .combinedClickable(enabled = enabled, onClick = onPick)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // null handlers on purpose: the ROW owns the click, so the control cannot fire a second
        // toggle of its own and cancel the row's.
        if (multiSelect) {
            Checkbox(checked = selected, onCheckedChange = null, enabled = enabled)
        } else {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = Space.sm))
    }
}

@Composable
private fun QuestionActions(disabled: Boolean, onSend: () -> Unit, onDecline: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Button(
            onClick = onSend,
            enabled = !disabled,
            modifier = Modifier.weight(1f).heightIn(min = TAP_TARGET).testTag("request-send"),
        ) { Text("Send") }
        TextButton(
            onClick = onDecline,
            enabled = !disabled,
            modifier = Modifier.heightIn(min = TAP_TARGET).testTag("request-decline"),
        ) { Text("Decline") }
    }
}

/**
 * The one-line field shared by a refusal's note and a question's free text. [onSend] non-null adds
 * the inline send affordance; where the card already has a Send button it stays null.
 */
@Composable
private fun NoteField(
    value: String,
    placeholder: String,
    enabled: Boolean,
    onChange: (String) -> Unit,
    onSend: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyMedium) },
            textStyle = MaterialTheme.typography.bodyMedium,
            shape = RoundedCornerShape(Radii.sm),
            modifier = Modifier.weight(1f).testTag("request-freetext"),
        )
        if (onSend != null) {
            Button(
                onClick = onSend,
                enabled = enabled && value.isNotBlank(),
                modifier = Modifier.heightIn(min = TAP_TARGET).testTag("request-send"),
            ) { Text("Send") }
        }
    }
}

// ── receipt ───────────────────────────────────────────────────────────────────────────────────

/**
 * What a card leaves behind: "✓ Allow once · Bash". It states the answer the broker reported, so
 * it stays truthful for a rejection, and disappears once the transcript moves on.
 */
@Composable
private fun ClosedRequestReceipt(receipt: ClosedRequest) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val unanswered = receipt.outcome != "answered"
    val rejected = receipt.answerKind?.startsWith("reject") == true
    val icon = when {
        unanswered -> Icons.Filled.Block
        rejected -> Icons.Filled.Close
        else -> Icons.Filled.Check
    }
    val tint = when {
        unanswered -> cs.onSurfaceVariant
        rejected -> sem.danger
        else -> sem.success
    }
    val text = listOfNotNull(
        receipt.answerLabel?.takeIf { it.isNotBlank() && !unanswered } ?: receipt.outcome.replaceFirstChar { it.uppercase() },
        requestNameOf(receipt),
    ).joinToString(" · ")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = Space.sm).testTag("request-receipt:${receipt.requestId}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Icon(
            icon,
            contentDescription = if (unanswered) "Request closed unanswered" else "Request answered",
            tint = tint,
            modifier = Modifier.size(IconSize.sm),
        )
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = cs.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun requestNameOf(receipt: ClosedRequest): String? = receipt.title.takeIf { it.isNotBlank() }

// ── wire shapes ───────────────────────────────────────────────────────────────────────────────

private fun answersObject(raw: Map<String, Any>): JsonObject = buildJsonObject {
    put("answers", buildJsonObject {
        for ((k, v) in raw) {
            when (v) {
                is Set<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
                is Collection<*> -> put(k, JsonArray(v.map { JsonPrimitive(it.toString()) }))
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
    })
}
