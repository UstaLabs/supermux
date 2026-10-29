// The Agents settings screen (spec 2026-10-29-agents-design §7), shared by every app. Replaces
// PersonalAssistantsScreen: same list + create + kill shape and the same Compact chrome, but the
// providers come from the host, the create asks only name + provider + model, and every broker
// refusal is shown instead of swallowed.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.net.ModelInfo
import dev.supermux.net.PADto
import dev.supermux.state.AccountResult
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.state.agentFailureText
import dev.supermux.state.agentNameError
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch

/** Every broker call the Agents screen makes, in one holder. */
@Immutable
class AgentsActions(
    val load: suspend () -> AccountResult<List<PADto>> = { AccountResult.Ok(emptyList()) },
    /** Installed providers (agent kinds), in the host's order. */
    val providers: suspend () -> List<String> = { emptyList() },
    val models: suspend (provider: String) -> List<ModelInfo> = { emptyList() },
    val create: suspend (name: String, provider: String, model: String?) -> AccountResult<Unit> =
        { _, _, _ -> AccountResult.Ok(Unit) },
    val kill: suspend (id: String) -> AccountResult<Unit> = { AccountResult.Ok(Unit) },
    val setDefault: suspend (id: String) -> AccountResult<Unit> = { AccountResult.Ok(Unit) },
    val rename: suspend (id: String, name: String) -> AccountResult<Unit> = { _, _ -> AccountResult.Ok(Unit) },
    /** Emits when the host says the Agent list changed (`agents_changed`); the list reloads. */
    val changes: Flow<Unit> = emptyFlow(),
)

/** [AgentsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberAgentsActions(app: HostStore): AgentsActions = remember(app) {
    AgentsActions(
        load = { app.listAgents() },
        providers = { app.launcherAgents() },
        models = { app.launcherModels(it) },
        create = { name, provider, model -> app.createAgent(name, provider, model) },
        kill = { id -> app.killAgent(id) },
        setDefault = { id -> app.setDefaultAgent(id) },
        rename = { id, name -> app.renameAgent(id, name) },
        changes = app.agentsChanged,
    )
}

/** [AgentsActions] against the fleet's ACTIVE host — Android's and web's wiring. */
@Composable
fun rememberAgentsActions(fleet: FleetStore): AgentsActions = remember(fleet) {
    AgentsActions(
        load = { fleet.listAgents() },
        providers = { fleet.agentStatuses().orEmpty().filter { it.installed }.map { it.kind } },
        models = { fleet.launcherModels(it) },
        create = { name, provider, model -> fleet.createAgent(name, provider, model) },
        kill = { id -> fleet.killAgent(id) },
        setDefault = { id -> fleet.setDefaultAgent(id) },
        rename = { id, name -> fleet.renameAgent(id, name) },
        changes = fleet.activeAgentsChanged,
    )
}

/**
 * Agents: long-lived, user-named sessions with their own memory, listed with provider/model and a kill.
 *
 * @param onBack leave the screen; only reachable from the Compact top bar this screen paints for
 *   itself (pass the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    actions: AgentsActions,
    modifier: Modifier = Modifier,
    onBack: () -> Unit = {},
    topBarShown: Boolean = false,
) {
    val cs = MaterialTheme.colorScheme
    val compact = LocalWindowWidthClass.current == WindowWidthClass.Compact
    var showCreate by remember { mutableStateOf(false) }
    if (compact && !topBarShown) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Agents", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("agents_back")) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = cs.onSurface)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.surfaceContainerHigh),
                )
            },
            floatingActionButton = {
                FloatingActionButton(onClick = { showCreate = true }, modifier = Modifier.testTag("agents_create_fab")) {
                    Icon(Icons.Filled.Add, contentDescription = "Create agent")
                }
            },
            containerColor = cs.background,
        ) { padding ->
            AgentsBody(actions, modifier.padding(padding), showHeaderCreate = false, showCreate) { showCreate = it }
        }
    } else {
        AgentsBody(actions, modifier, showHeaderCreate = true, showCreate) { showCreate = it }
    }
}

@Composable
private fun AgentsBody(
    actions: AgentsActions,
    modifier: Modifier,
    showHeaderCreate: Boolean,
    showCreate: Boolean,
    onShowCreateChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var agents by remember { mutableStateOf<List<PADto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var killTarget by remember { mutableStateOf<PADto?>(null) }
    var killError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<PADto?>(null) }
    var rowError by remember { mutableStateOf<String?>(null) }

    // The spinner only covers the first load and a retry; reloads after a change keep the list up.
    suspend fun refresh(showSpinner: Boolean = false) {
        if (showSpinner) loading = true
        when (val r = actions.load()) {
            is AccountResult.Ok -> { agents = r.value; loadError = null }
            is AccountResult.Failed -> loadError = agentFailureText(r, "load the agents")
        }
        loading = false
    }
    LaunchedEffect(Unit) { refresh(showSpinner = true) }
    LaunchedEffect(actions) { actions.changes.collect { refresh() } }

    Column(modifier.fillMaxSize().background(cs.background).testTag("agents_screen")) {
        if (showHeaderCreate) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Button(onClick = { onShowCreateChange(true) }, modifier = Modifier.testTag("agents_create_button")) { Text("Create") }
            }
            HorizontalDivider(color = cs.outlineVariant)
        }
        killError?.let {
            Text(
                it,
                color = cs.error,
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm).testTag("agents_kill_error"),
            )
        }
        rowError?.let {
            Text(it, color = cs.error, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm).testTag("agents_row_error"))
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag("agents_loading"))
            }
            loadError != null -> Box(
                Modifier.fillMaxSize().padding(Space.xl).testTag("agents_load_error"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(loadError!!, color = cs.error)
                    TextButton(onClick = { scope.launch { refresh(showSpinner = true) } }, modifier = Modifier.testTag("agents_retry")) { Text("Retry") }
                }
            }
            agents.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(Space.xl).testTag("agents_empty"),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text("No agents yet", fontWeight = FontWeight.SemiBold)
                    Text("An agent is a long-lived session with its own memory. Give it a name to create one.", color = cs.onSurfaceVariant)
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("agents_list"),
                // Android's 88dp: the last row's Kill must clear the FAB, not sit under it.
                contentPadding = PaddingValues(bottom = Space.xxl + Space.xxl + Space.xl),
            ) {
                items(agents, key = { it.id }) { a ->
                    ListItem(
                        headlineContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                                Text(a.name, fontWeight = FontWeight.Medium)
                                if (a.isDefault) {
                                    Text(
                                        "default",
                                        color = cs.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.testTag("agents_default_${a.id}"),
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Text(listOfNotNull(a.agent, a.model).joinToString(" · ").ifBlank { a.workdir }, maxLines = 1)
                        },
                        leadingContent = {
                            Box(
                                Modifier.size(Space.sm + 1.dp).clip(CircleShape)
                                    .background(if (a.connected) cs.primary else cs.outline)
                                    .testTag("agents_dot_${a.id}"),
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (!a.isDefault) {
                                    TextButton(
                                        onClick = {
                                            scope.launch {
                                                rowError = (actions.setDefault(a.id) as? AccountResult.Failed)
                                                    ?.let { agentFailureText(it, "make ${a.name} the default") }
                                                refresh()
                                            }
                                        },
                                        modifier = Modifier.testTag("agents_make_default_${a.id}"),
                                    ) { Text("Make default") }
                                }
                                TextButton(onClick = { renameTarget = a }, modifier = Modifier.testTag("agents_rename_${a.id}")) { Text("Rename") }
                                TextButton(onClick = { killTarget = a }, modifier = Modifier.testTag("agents_kill_${a.id}")) { Text("Kill") }
                            }
                        },
                        modifier = Modifier.testTag("agents_row_${a.id}"),
                    )
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }

    if (showCreate) {
        CreateAgentDialog(
            actions = actions,
            onDismiss = { onShowCreateChange(false) },
            onCreated = {
                onShowCreateChange(false)
                scope.launch { refresh() }
            },
        )
    }
    killTarget?.let { a ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("Kill ${a.name}?") },
            text = { Text("Its session will be archived.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        killTarget = null
                        scope.launch {
                            when (val r = actions.kill(a.id)) {
                                is AccountResult.Ok -> killError = null
                                is AccountResult.Failed -> killError = agentFailureText(r.copy(message = null), "kill ${a.name}")
                            }
                            refresh()
                        }
                    },
                    modifier = Modifier.testTag("agents_kill_confirm"),
                ) { Text("Kill") }
            },
            dismissButton = {
                TextButton(onClick = { killTarget = null }, modifier = Modifier.testTag("agents_kill_cancel")) { Text("Cancel") }
            },
            modifier = Modifier.testTag("agents_kill_dialog"),
        )
    }
    renameTarget?.let { a ->
        RenameAgentDialog(
            agent = a,
            rename = actions.rename,
            onDismiss = { renameTarget = null },
            onRenamed = {
                renameTarget = null
                scope.launch { refresh() }
            },
        )
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CreateAgentDialog(
    actions: AgentsActions,
    onDismiss: () -> Unit,
    onCreated: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<String>?>(null) }
    var provider by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<List<ModelInfo>>(emptyList()) }
    var model by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val list = actions.providers()
        providers = list
        provider = list.firstOrNull { it == "claude" } ?: list.firstOrNull()
    }
    LaunchedEffect(provider) {
        model = null
        models = provider?.let { actions.models(it) }.orEmpty()
    }

    val nameError = if (name.isEmpty()) null else agentNameError(name)
    val canCreate = !busy && provider != null && name.isNotBlank() && nameError == null

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Create agent") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("agents_create_name"),
                )
                Text("Provider", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                when {
                    providers == null -> CircularProgressIndicator(Modifier.size(Space.lg))
                    providers!!.isEmpty() -> Text(
                        "No provider is installed. Install one in Settings ▸ Providers.",
                        color = cs.onSurfaceVariant,
                        modifier = Modifier.testTag("agents_create_no_provider"),
                    )
                    else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        providers!!.forEach { p ->
                            FilterChip(
                                selected = provider == p,
                                onClick = { provider = p },
                                label = { Text(p) },
                                modifier = Modifier.testTag("agents_create_provider_$p"),
                            )
                        }
                    }
                }
                if (models.isNotEmpty()) {
                    Text("Model", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        FilterChip(
                            selected = model == null,
                            onClick = { model = null },
                            label = { Text("Default") },
                            modifier = Modifier.testTag("agents_create_model_default"),
                        )
                        models.forEach { m ->
                            FilterChip(
                                selected = model == m.id,
                                onClick = { model = m.id },
                                label = { Text(m.displayName) },
                                modifier = Modifier.testTag("agents_create_model_${m.id}"),
                            )
                        }
                    }
                }
                error?.let { Text(it, color = cs.error, modifier = Modifier.testTag("agents_create_error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canCreate,
                onClick = {
                    val p = provider ?: return@TextButton
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = actions.create(name.trim(), p, model)) {
                            is AccountResult.Ok -> onCreated()
                            is AccountResult.Failed -> error = agentFailureText(r, "create the agent")
                        }
                        busy = false
                    }
                },
                modifier = Modifier.testTag("agents_create_confirm"),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.testTag("agents_create_cancel")) { Text("Cancel") }
        },
        modifier = Modifier.testTag("agents_create_dialog"),
    )
}

@Composable
private fun RenameAgentDialog(
    agent: PADto,
    rename: suspend (id: String, name: String) -> AccountResult<Unit>,
    onDismiss: () -> Unit,
    onRenamed: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var name by remember(agent.id) { mutableStateOf(agent.name) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val nameError = if (name.isEmpty()) null else agentNameError(name)
    val canRename = !busy && name.isNotBlank() && nameError == null && name.trim() != agent.name

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Rename ${agent.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = null },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth().testTag("agents_rename_name"),
                )
                Text("Its memory and history stay with it.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                error?.let { Text(it, color = cs.error, modifier = Modifier.testTag("agents_rename_error")) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canRename,
                onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        when (val r = rename(agent.id, name.trim())) {
                            is AccountResult.Ok -> onRenamed()
                            is AccountResult.Failed -> error = agentFailureText(r, "rename ${agent.name}")
                        }
                        busy = false
                    }
                },
                modifier = Modifier.testTag("agents_rename_confirm"),
            ) { Text("Rename") }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss, modifier = Modifier.testTag("agents_rename_cancel")) { Text("Cancel") }
        },
        modifier = Modifier.testTag("agents_rename_dialog"),
    )
}
