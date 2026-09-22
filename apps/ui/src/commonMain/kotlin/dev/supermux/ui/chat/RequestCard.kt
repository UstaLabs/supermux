package dev.supermux.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.proto.PromptQuestion
import dev.supermux.proto.PromptRequest
import dev.supermux.proto.PromptRequestOption
import dev.supermux.proto.parsedQuestions
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

@Composable
fun RequestCards(
    requests: List<PromptRequest>,
    disabledIds: Set<String>,
    onRespond: (requestId: String, answer: JsonObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (requests.isEmpty()) return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
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
    Surface(
        modifier = Modifier.fillMaxWidth().testTag("request-card:${request.requestId}"),
        shape = RoundedCornerShape(Radii.md),
        color = cs.surfaceContainerHigh,
        tonalElevation = 1.dp,
    ) {
        Column(
            Modifier.padding(Space.md).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                request.title.ifBlank { if (request.kind == "question") "Question" else "Permission" },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (request.kind == "question") {
                QuestionBody(request, disabled, onRespond)
            } else {
                PermissionBody(request, disabled, onRespond)
            }
        }
    }
}

@Composable
private fun PermissionBody(
    request: PromptRequest,
    disabled: Boolean,
    onRespond: (JsonObject) -> Unit,
) {
    if (request.body.isNotBlank()) {
        Text(request.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    var freeText by remember(request.requestId) { mutableStateOf("") }
    OptionButtons(request.options, disabled) { opt ->
        val reject = opt.kind?.startsWith("reject") == true || opt.id.startsWith("reject")
        onRespond(
            if (reject && request.allowFreeText && freeText.isNotBlank()) {
                buildJsonObject {
                    put("optionId", JsonPrimitive(opt.id))
                    put("message", JsonPrimitive(freeText))
                }
            } else {
                buildJsonObject { put("optionId", JsonPrimitive(opt.id)) }
            },
        )
    }
    if (request.allowFreeText) {
        FreeTextRow(freeText, disabled, onChange = { freeText = it }) {
            val reject = request.options.firstOrNull { it.kind?.startsWith("reject") == true }
                ?: request.options.firstOrNull { it.id.startsWith("reject") }
            if (reject != null) {
                onRespond(
                    buildJsonObject {
                        put("optionId", JsonPrimitive(reject.id))
                        put("message", JsonPrimitive(freeText))
                    },
                )
            }
        }
    }
}

@Composable
private fun QuestionBody(
    request: PromptRequest,
    disabled: Boolean,
    onRespond: (JsonObject) -> Unit,
) {
    val questions = request.parsedQuestions()
    if (questions.isEmpty()) {
        if (request.body.isNotBlank()) {
            Text(request.body, style = MaterialTheme.typography.bodyMedium)
        }
        OptionButtons(request.options, disabled) { opt ->
            onRespond(buildJsonObject { put("optionId", JsonPrimitive(opt.id)) })
        }
        if (request.allowFreeText) {
            var freeText by remember(request.requestId) { mutableStateOf("") }
            FreeTextRow(freeText, disabled, onChange = { freeText = it }) {
                onRespond(buildJsonObject { put("optionId", JsonPrimitive(freeText)) })
            }
        }
        TextButton(
            onClick = { onRespond(buildJsonObject { put("decline", JsonPrimitive(true)) }) },
            enabled = !disabled,
            modifier = Modifier.testTag("request-decline"),
        ) { Text("Decline") }
        return
    }

    val answers = remember(request.requestId) { mutableStateOf(mutableMapOf<String, Any>()) }
    questions.forEach { q ->
        Text(q.header ?: q.prompt, style = MaterialTheme.typography.labelLarge)
        if (q.header != null && q.prompt.isNotBlank()) {
            Text(q.prompt, style = MaterialTheme.typography.bodyMedium)
        }
        if (q.multiSelect) {
            q.options.forEach { opt ->
                val selected = (answers.value[q.id] as? Set<*>)?.contains(opt.id) == true
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = selected,
                        onCheckedChange = { checked ->
                            if (!disabled) {
                                val cur = ((answers.value[q.id] as? Set<*>)?.map { it.toString() }?.toMutableSet())
                                    ?: mutableSetOf()
                                if (checked) cur.add(opt.id) else cur.remove(opt.id)
                                answers.value = answers.value.toMutableMap().also { m -> m[q.id] = cur }
                            }
                        },
                        enabled = !disabled,
                        modifier = Modifier.testTag("request-option:${opt.id}"),
                    )
                    Text(opt.label)
                }
            }
        } else {
            OptionButtons(q.options, disabled) { opt ->
                answers.value = answers.value.toMutableMap().also { it[q.id] = opt.id }
                if (questions.size == 1 && !q.allowFreeText) {
                    onRespond(answersObject(mapOf(q.id to opt.id)))
                }
            }
        }
        if (q.allowFreeText) {
            var freeText by remember(q.id) { mutableStateOf("") }
            FreeTextRow(freeText, disabled, onChange = { freeText = it }) {
                answers.value = answers.value.toMutableMap().also { it[q.id] = freeText }
                onRespond(answersObject(answers.value))
            }
        }
    }
    if (questions.size > 1 || questions.any { it.multiSelect }) {
        Button(
            onClick = { onRespond(answersObject(answers.value)) },
            enabled = !disabled,
            modifier = Modifier.testTag("request-send"),
        ) { Text("Send") }
    }
    TextButton(
        onClick = { onRespond(buildJsonObject { put("decline", JsonPrimitive(true)) }) },
        enabled = !disabled,
        modifier = Modifier.testTag("request-decline"),
    ) { Text("Decline") }
}

@Composable
private fun OptionButtons(
    options: List<PromptRequestOption>,
    disabled: Boolean,
    onPick: (PromptRequestOption) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs), modifier = Modifier.fillMaxWidth()) {
        options.forEach { opt ->
            val primary = opt.kind?.startsWith("allow") == true || opt.id.startsWith("allow")
            val tag = Modifier.testTag("request-option:${opt.id}")
            if (primary) {
                Button(onClick = { onPick(opt) }, enabled = !disabled, modifier = tag) { Text(opt.label) }
            } else {
                OutlinedButton(
                    onClick = { onPick(opt) },
                    enabled = !disabled,
                    modifier = tag,
                    colors = ButtonDefaults.outlinedButtonColors(),
                ) { Text(opt.label) }
            }
        }
    }
}

@Composable
private fun FreeTextRow(value: String, disabled: Boolean, onChange: (String) -> Unit, onSend: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        BasicTextField(
            value = value,
            onValueChange = onChange,
            enabled = !disabled,
            modifier = Modifier.weight(1f).testTag("request-freetext"),
            decorationBox = { inner ->
                Surface(tonalElevation = 0.dp, shape = RoundedCornerShape(Radii.sm)) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(Space.sm)) { inner() }
                }
            },
        )
        Button(onClick = onSend, enabled = !disabled && value.isNotBlank(), modifier = Modifier.testTag("request-send")) {
            Text("Send")
        }
    }
}

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

