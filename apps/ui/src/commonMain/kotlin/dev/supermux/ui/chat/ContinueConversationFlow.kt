// "Continue in new conversation", shared by both hosts (cluster D4).
//
// One flow, two containers:
//   • WindowWidthClass.Compact → Android's [ModalBottomSheet] (`ContinueConversationSheet`)
//   • anything wider           → desktop's [AlertDialog] (the section inlined in `OverflowMenu`)
//
// One body, two picker shapes — keyed on `LocalPointerAvailable`, never on the host:
//   • pointer → desktop's [ContinuePickerPill] + [DropdownMenu] (with the per-option test tags)
//   • touch   → Android's [ModelPill]/[EffortPill] + [PickerSheet]
//
// The seeding effects are desktop's richer set (installed-agent list with a fallback, model +
// reasoning carried over when the agent is unchanged, `resolveReasoningLevel`); the failure text is
// Android's (`spawnFailureMessage`, which reports WHY a spawn failed instead of one flat line).
// Every test tag from both call sites is preserved — they already agreed (`overflow_continue*`).
package dev.supermux.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.chat.DEFAULT_MODEL_ID
import dev.supermux.net.ModelInfo
import dev.supermux.net.ReasoningLevel
import dev.supermux.net.ReasoningResponse
import dev.supermux.net.resolveReasoningLevel
import dev.supermux.net.showReasoningPicker
import dev.supermux.proto.SessionInfo
import dev.supermux.session.HandoffPrefill
import dev.supermux.state.ContinueHandoff
import dev.supermux.state.spawnFailureMessage
import dev.supermux.ui.adaptive.LocalPointerAvailable
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.retain.rememberRetained
import dev.supermux.ui.retain.rememberRetainedScope
import dev.supermux.ui.session.AgentLogo
import dev.supermux.ui.theme.Radii
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import dev.supermux.ui.widgets.DropdownMenu
import dev.supermux.ui.widgets.DropdownMenuItem
import kotlinx.coroutines.launch

/**
 * Test tags for the continue flow — the two apps already agreed on these strings.
 *
 * **Not all of them exist on both branches.** [CONTINUE], [CONTINUE_PICKERS], [CONTINUE_FIELD],
 * [CONTINUE_CONFIRM], [CONTINUE_CANCEL] and [CONTINUE_ERROR] are on the sheet AND the dialog. The
 * three picker tags — [CONTINUE_AGENT], [CONTINUE_MODEL], [CONTINUE_REASONING] — and their
 * per-option `<tag>_$id` dropdown rows exist ONLY under a pointer, where the pickers are
 * [ContinuePickerPill] + `DropdownMenu`; the touch branch renders `ModelPill`/`EffortPill` opening
 * a `PickerSheet`, which carries none of them. A test that drives a picker must therefore pin
 * `LocalPointerAvailable` rather than rely on a theme default.
 */
object ContinueTestIds {
    const val CONTINUE = "overflow_continue"
    const val CONTINUE_FIELD = "overflow_continue_field"
    const val CONTINUE_CONFIRM = "overflow_continue_confirm"
    const val CONTINUE_CANCEL = "overflow_continue_cancel"
    const val CONTINUE_PICKERS = "overflow_continue_pickers"
    const val CONTINUE_ERROR = "overflow_continue_error"
    const val CONTINUE_AGENT = "overflow_continue_agent"
    const val CONTINUE_MODEL = "overflow_continue_model"
    const val CONTINUE_REASONING = "overflow_continue_reasoning"
}

private val CONTINUE_AGENT_FALLBACK = listOf("claude", "codex", "cursor", "opencode", "grok", "antigravity")

/** The overflow-menu row that opens the flow. */
@Composable
fun ContinueMenuItem(onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text("Continue in new conversation") },
        modifier = Modifier.testTag(ContinueTestIds.CONTINUE),
        onClick = onClick,
    )
}

/** Hoisted open/closed state for the flow (kept so both apps' menus read the same). */
@Composable
fun rememberContinueSheetState(): MutableState<Boolean> = remember { mutableStateOf(false) }

/**
 * Spawn a NEW session in the same working directory, told to read [session] first.
 *
 * [onContinue] returns the new session id (or null / throws on failure); [onContinued] hands it to
 * the shell so it can select it.
 *
 * [retainKey] names the form kept for a flow that a rotation or a width change rebuilds (#89): the
 * text, the picks and a spawn in flight. Null keeps all of it in this composition.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContinueConversationFlow(
    session: SessionInfo,
    onContinue: suspend (ContinueHandoff) -> String?,
    onContinued: (String) -> Unit,
    loadAgents: suspend () -> List<String>,
    loadModels: suspend (String) -> List<ModelInfo>,
    loadReasoning: suspend (String, String?) -> ReasoningResponse?,
    onDismiss: () -> Unit,
    retainKey: String? = null,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact

    // Kept outside this composition when [retainKey] names it (#89), and so is a spawn in flight:
    // it runs on the fleet's scope, and its outcome waits in the form for the flow on screen when
    // it lands. A new opening starts from the prefill, as a new composition did.
    val form = if (retainKey != null) rememberRetained(retainKey) { ContinueForm() } else remember(session.id) { ContinueForm() }
    if (form.fresh) form.open(session)
    val scope = if (retainKey != null) rememberRetainedScope() else rememberCoroutineScope()
    var text by form::text
    var agent by form::agent
    var agents by remember { mutableStateOf(CONTINUE_AGENT_FALLBACK) }
    var model by form::model
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var reasoning by form::reasoning
    var reasoningLevels by remember { mutableStateOf<List<ReasoningLevel>>(emptyList()) }
    var reasoningVisible by remember { mutableStateOf(false) }
    var busy by form::busy
    var error by form::error
    // Desktop's seeding guard: hold the model/reasoning loads until the agent list has settled, so
    // a carried-over model isn't cleared by a load against the pre-seed agent.
    var seeding by remember(session.id) { mutableStateOf(true) }
    // Every way out marks the form, so the next opening starts from the prefill again.
    val close: () -> Unit = {
        form.fresh = true
        onDismiss()
    }

    LaunchedEffect(session.id) {
        seeding = true
        val installed = runCatching { loadAgents() }.getOrNull().orEmpty()
            .map { it.lowercase() }.filter { it.isNotBlank() }
        agents = installed.ifEmpty { CONTINUE_AGENT_FALLBACK }
        // Once per opening: a flow rebuilt under the user's picks keeps them (#89).
        if (!form.seeded) {
            val next = HandoffPrefill.defaultAgent(session.agent)
            agent = if (next in agents) next else agents.first()
            // Same agent → carry the source session's model/thinking level over as the default.
            val sameAgent = session.agent.equals(agent, ignoreCase = true)
            model = if (sameAgent) session.model?.takeIf { it.isNotBlank() } else null
            reasoning = if (sameAgent) session.reasoningLevel?.takeIf { it.isNotBlank() } else null
            form.seeded = true
        }
        seeding = false
    }
    LaunchedEffect(agent, seeding) {
        if (seeding) return@LaunchedEffect
        models = runCatching { loadModels(agent) }.getOrNull().orEmpty()
        if (model != null && models.none { it.id == model }) model = null
    }
    LaunchedEffect(agent, model, seeding) {
        if (seeding) return@LaunchedEffect
        val resp = runCatching { loadReasoning(agent, model) }.getOrNull()
        val levels = resp?.levels.orEmpty()
        reasoningLevels = levels
        reasoningVisible = resp != null && resp.visible && showReasoningPicker(levels)
        reasoning = if (reasoningVisible) resolveReasoningLevel(levels, reasoning) else null
    }

    val start: () -> Unit = {
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
            }.getOrElse { e -> error = spawnFailureMessage(e); null }
            busy = false
            if (id != null) {
                form.created = id
            } else if (error == null) {
                error = "Couldn't start — check the agent is installed and signed in."
            }
        }
        Unit
    }
    // The spawn's outcome, read by the flow on screen when it lands: the one that started it, or
    // the one a rotation put in its place.
    LaunchedEffect(form, form.created) {
        val id = form.created ?: return@LaunchedEffect
        form.created = null
        onContinued(id)
        close()
    }
    val canStart = !busy && text.isNotBlank() && session.workdir.isNotBlank()

    val pickers: @Composable () -> Unit = {
        ContinuePickers(
            agent = agent,
            agents = agents,
            model = model,
            models = models,
            reasoning = reasoning,
            reasoningLevels = reasoningLevels,
            reasoningVisible = reasoningVisible,
            onPickAgent = { a ->
                if (a != agent) { agent = a; model = null; reasoning = null }
            },
            onPickModel = { model = it },
            onPickReasoning = { reasoning = it },
        )
    }
    val field: @Composable () -> Unit = {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; error = null },
            modifier = Modifier.fillMaxWidth().testTag(ContinueTestIds.CONTINUE_FIELD),
            minLines = 8,
            maxLines = 14,
        )
    }
    val errorLine: @Composable () -> Unit = {
        error?.let { err ->
            Text(
                err,
                color = cs.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = Space.sm).testTag(ContinueTestIds.CONTINUE_ERROR),
            )
        }
    }

    if (compact) {
        ModalBottomSheet(
            onDismissRequest = { if (!busy) close() },
            modifier = Modifier.testTag("continue_sheet"),
        ) {
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
                pickers()
                Spacer(Modifier.height(12.dp))
                field()
                errorLine()
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(
                        onClick = close,
                        enabled = !busy,
                        modifier = Modifier.testTag(ContinueTestIds.CONTINUE_CANCEL),
                    ) { Text("Cancel") }
                    TextButton(
                        onClick = start,
                        enabled = canStart,
                        modifier = Modifier.testTag(ContinueTestIds.CONTINUE_CONFIRM),
                    ) { Text(if (busy) "Starting…" else "Continue") }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    } else {
        AlertDialog(
            onDismissRequest = { if (!busy) close() },
            title = { Text("Continue in new conversation") },
            text = {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    Text(
                        "Same working directory as ${session.name}. The new agent is told to read this session first. Edit freely before start.",
                        color = cs.onSurfaceVariant,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(bottom = Space.sm),
                    )
                    pickers()
                    Spacer(Modifier.height(Space.sm))
                    field()
                    errorLine()
                }
            },
            confirmButton = {
                TextButton(
                    onClick = start,
                    enabled = canStart,
                    modifier = Modifier.testTag(ContinueTestIds.CONTINUE_CONFIRM),
                ) { Text(if (busy) "Starting…" else "Start") }
            },
            dismissButton = {
                TextButton(
                    onClick = close,
                    enabled = !busy,
                    modifier = Modifier.testTag(ContinueTestIds.CONTINUE_CANCEL),
                ) { Text("Cancel") }
            },
        )
    }
}

/** The continue form, kept for a flow rebuilt by a rotation or a width change (`rememberRetained`, #89). */
private class ContinueForm {
    /** A new opening: [open] fills the prefill in. */
    var fresh by mutableStateOf(true)
    var text by mutableStateOf("")
    var agent by mutableStateOf("")
    var model by mutableStateOf<String?>(null)
    var reasoning by mutableStateOf<String?>(null)
    /** The agent list has seeded this opening's picks; from then on they are the user's. */
    var seeded by mutableStateOf(false)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    /** The session a spawn answered, until the flow on screen opens it. */
    var created by mutableStateOf<String?>(null)

    fun open(session: SessionInfo) {
        text = HandoffPrefill.build(session.name, session.id)
        agent = HandoffPrefill.defaultAgent(session.agent)
        model = null
        reasoning = null
        seeded = false
        busy = false
        error = null
        created = null
        fresh = false
    }
}

/** Agent / model / thinking-level pickers — dropdowns under a pointer, sheets under touch. */
@Composable
private fun ContinuePickers(
    agent: String,
    agents: List<String>,
    model: String?,
    models: List<ModelInfo>,
    reasoning: String?,
    reasoningLevels: List<ReasoningLevel>,
    reasoningVisible: Boolean,
    onPickAgent: (String) -> Unit,
    onPickModel: (String?) -> Unit,
    onPickReasoning: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val pointer = LocalPointerAvailable.current
    val agentLabel = agent.replaceFirstChar { it.uppercase() }
    val modelLabel = model?.let { id -> models.firstOrNull { it.id == id }?.displayName ?: id } ?: "Default"
    val reasoningLabel = reasoning?.replaceFirstChar { it.uppercase() } ?: "Default"

    var agentMenu by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    var reasoningMenu by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().testTag(ContinueTestIds.CONTINUE_PICKERS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (pointer) 2.dp else 8.dp),
    ) {
        Box {
            if (pointer) {
                ContinuePickerPill(
                    label = agentLabel,
                    onClick = { agentMenu = true },
                    testTag = ContinueTestIds.CONTINUE_AGENT,
                    leading = { AgentLogo(agent, size = 12.dp) },
                )
                DropdownMenu(expanded = agentMenu, onDismissRequest = { agentMenu = false }) {
                    agents.forEach { a ->
                        DropdownMenuItem(
                            text = { Text(a.replaceFirstChar { it.uppercase() }) },
                            leadingIcon = { AgentLogo(a, size = 14.dp) },
                            modifier = Modifier.testTag("${ContinueTestIds.CONTINUE_AGENT}_$a"),
                            onClick = { onPickAgent(a); agentMenu = false },
                        )
                    }
                }
            } else {
                ModelPill(current = agentLabel, onClick = { agentMenu = true })
            }
        }
        Box {
            if (pointer) {
                ContinuePickerPill(
                    label = modelLabel,
                    onClick = { modelMenu = true },
                    testTag = ContinueTestIds.CONTINUE_MODEL,
                )
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    val opts = listOf(DEFAULT_MODEL_ID to "Default") + models.map { it.id to it.displayName }
                    opts.forEach { (id, label) ->
                        val selected = (model ?: DEFAULT_MODEL_ID) == id
                        DropdownMenuItem(
                            text = { Text(label) },
                            trailingIcon = {
                                if (selected) {
                                    Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = cs.primary)
                                }
                            },
                            modifier = Modifier.testTag("${ContinueTestIds.CONTINUE_MODEL}_$id"),
                            onClick = {
                                onPickModel(if (id == DEFAULT_MODEL_ID) null else id)
                                modelMenu = false
                            },
                        )
                    }
                }
            } else {
                ModelPill(current = modelLabel, onClick = { modelMenu = true })
            }
        }
        if (reasoningVisible) {
            Box {
                if (pointer) {
                    ContinuePickerPill(
                        label = reasoningLabel,
                        onClick = { reasoningMenu = true },
                        testTag = ContinueTestIds.CONTINUE_REASONING,
                    )
                    DropdownMenu(
                        expanded = reasoningMenu,
                        onDismissRequest = { reasoningMenu = false },
                    ) {
                        reasoningLevels.forEach { level ->
                            DropdownMenuItem(
                                text = { Text(level.id.replaceFirstChar { it.uppercase() }) },
                                modifier = Modifier.testTag("${ContinueTestIds.CONTINUE_REASONING}_${level.id}"),
                                onClick = { onPickReasoning(level.id); reasoningMenu = false },
                            )
                        }
                    }
                } else {
                    EffortPill(current = reasoning?.replaceFirstChar { it.uppercase() }) {
                        reasoningMenu = true
                    }
                }
            }
        }
    }

    // Touch: the same three choices as Material3 bottom sheets (Android's original shape).
    if (!pointer && agentMenu) {
        PickerSheet(
            title = "Select Agent",
            options = agents.map { it to it.replaceFirstChar { c -> c.uppercase() } },
            current = agent,
            onPick = onPickAgent,
            onDismiss = { agentMenu = false },
        )
    }
    if (!pointer && modelMenu) {
        val opts = listOf(DEFAULT_MODEL_ID to "Default") + models.map { it.id to it.displayName }
        PickerSheet(
            title = "Select Model",
            options = opts,
            current = model ?: DEFAULT_MODEL_ID,
            onPick = { picked -> onPickModel(if (picked == DEFAULT_MODEL_ID) null else picked) },
            onDismiss = { modelMenu = false },
        )
    }
    if (!pointer && reasoningMenu) {
        PickerSheet(
            title = "Thinking level",
            options = reasoningLevels.map { it.id to (it.description ?: it.id) },
            current = reasoning,
            onPick = onPickReasoning,
            onDismiss = { reasoningMenu = false },
        )
    }
}

/** Desktop's flat picker pill (label + caret), used under a pointer. */
@Composable
private fun ContinuePickerPill(
    label: String,
    onClick: () -> Unit,
    testTag: String,
    leading: (@Composable () -> Unit)? = null,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(Radii.pill))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        leading?.invoke()
        Text(label.take(22), color = cs.onSurfaceVariant, fontSize = 12.sp, maxLines = 1)
        Icon(
            Icons.Filled.KeyboardArrowDown,
            contentDescription = null,
            tint = cs.onSurfaceVariant.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp),
        )
    }
}
