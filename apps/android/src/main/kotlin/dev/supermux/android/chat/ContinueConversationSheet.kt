package dev.supermux.android.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import dev.supermux.ui.widgets.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.showReasoningPicker
import dev.supermux.proto.SessionInfo
import dev.supermux.session.HandoffPrefill
import kotlinx.coroutines.launch
import dev.supermux.state.ContinueHandoff
import dev.supermux.state.spawnFailureMessage

private const val DEFAULT_MODEL_ID = "__default__"
private val CONTINUE_AGENT_FALLBACK = listOf("claude", "codex", "cursor", "opencode", "grok")

@Composable
fun ContinueMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Continue in new conversation") },
        modifier = Modifier.testTag(ChatOverflowTestIds.CONTINUE),
        onClick = onClick,
    )
}

@Composable
fun rememberContinueSheetState(): MutableState<Boolean> = remember { mutableStateOf(false) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinueConversationSheet(
    session: SessionInfo,
    onContinue: suspend (ContinueHandoff) -> String?,
    onContinued: (String) -> Unit,
    loadAgents: suspend () -> List<String>,
    loadModels: suspend (String) -> List<ModelInfo>,
    loadReasoning: suspend (String, String?) -> ReasoningResponse?,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var text by remember(session.id) {
        mutableStateOf(HandoffPrefill.build(session.name, session.id))
    }
    var agent by remember(session.id) { mutableStateOf(HandoffPrefill.defaultAgent(session.agent)) }
    var agents by remember { mutableStateOf(CONTINUE_AGENT_FALLBACK) }
    var model by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var reasoning by remember { mutableStateOf<String?>(null) }
    var reasoningLevels by remember { mutableStateOf<List<ReasoningLevel>>(emptyList()) }
    var reasoningVisible by remember { mutableStateOf(false) }
    var showAgentSheet by remember { mutableStateOf(false) }
    var showModelSheet by remember { mutableStateOf(false) }
    var showReasoningSheet by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val fetched = runCatching { loadAgents() }.getOrNull().orEmpty()
        if (fetched.isNotEmpty()) {
            agents = fetched
            if (agent !in fetched) agent = fetched.first()
        }
    }
    LaunchedEffect(agent) {
        models = runCatching { loadModels(agent) }.getOrNull().orEmpty()
        if (model != null && models.none { it.id == model }) model = null
    }
    LaunchedEffect(agent, model) {
        val resp = runCatching { loadReasoning(agent, model) }.getOrNull()
        val levels = resp?.levels.orEmpty()
        reasoningLevels = levels
        reasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        if (!reasoningVisible) reasoning = null
        else if (reasoning == null || levels.none { it.id == reasoning }) {
            reasoning = levels.firstOrNull()?.id
        }
    }

    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Continue in new conversation", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Same working directory as ${session.name}. The new agent is told to read this session first.",
                color = cs.onSurfaceVariant,
                fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModelPill(
                    current = agent.replaceFirstChar { it.uppercase() },
                    onClick = { showAgentSheet = true },
                )
                ModelPill(
                    current = model?.let { id -> models.firstOrNull { it.id == id }?.displayName ?: id }
                        ?: "Default",
                    onClick = { showModelSheet = true },
                )
                if (reasoningVisible) {
                    EffortPill(
                        current = reasoning?.replaceFirstChar { it.uppercase() },
                        onClick = { showReasoningSheet = true },
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; error = null },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(ChatOverflowTestIds.CONTINUE_FIELD),
                minLines = 8,
                maxLines = 14,
            )
            error?.let { err ->
                Text(err, color = cs.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                TextButton(
                    onClick = {
                        scope.launch {
                            busy = true
                            error = null
                            val id = runCatching {
                                onContinue(
                                    ContinueHandoff(
                                        message = text,
                                        agent = agent,
                                        model = model,
                                        reasoningLevel = reasoning,
                                    ),
                                )
                            }.getOrElse { e ->
                                error = spawnFailureMessage(e)
                                null
                            }
                            busy = false
                            if (id != null) {
                                onContinued(id)
                                onDismiss()
                            } else if (error == null) {
                                error = "Couldn't start — check the agent is installed and signed in."
                            }
                        }
                    },
                    enabled = !busy && text.isNotBlank() && session.workdir.isNotBlank(),
                    modifier = Modifier.testTag(ChatOverflowTestIds.CONTINUE_CONFIRM),
                ) { Text(if (busy) "Starting…" else "Continue") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
    if (showAgentSheet) {
        PickerSheet(
            title = "Select Agent",
            options = agents.map { it to it.replaceFirstChar { c -> c.uppercase() } },
            current = agent,
            onPick = {
                if (it != agent) {
                    agent = it
                    model = null
                    reasoning = null
                }
            },
            onDismiss = { showAgentSheet = false },
        )
    }
    if (showModelSheet) {
        val opts = listOf(DEFAULT_MODEL_ID to "Default") + models.map { it.id to it.displayName }
        PickerSheet(
            title = "Select Model",
            options = opts,
            current = model ?: DEFAULT_MODEL_ID,
            onPick = { picked -> model = if (picked == DEFAULT_MODEL_ID) null else picked },
            onDismiss = { showModelSheet = false },
        )
    }
    if (showReasoningSheet) {
        PickerSheet(
            title = "Thinking level",
            options = reasoningLevels.map { it.id to (it.description ?: it.id) },
            current = reasoning,
            onPick = { reasoning = it },
            onDismiss = { showReasoningSheet = false },
        )
    }
}
