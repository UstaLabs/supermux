// The one personal-assistants settings screen for both apps (cluster E4).
//
// Base = desktop's `settings/PersonalAssistantsScreen.kt` (list + create dialog + kill confirm);
// Android's `MoreScreens.kt` `PersonalAssistantsSettingsPage` was the same screen with a `TopAppBar`
// and a FAB, so it contributes the Compact branch. Neither host had a single test tag here — the
// new suite's tags are added on the shared screen, so both get them.
//
// Desktop's `showTopBar = true` branch (a nested Back + title) is gone: the hub is the only caller
// and it always passed false, and compact Back is the hub's job now.
package dev.supermux.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import dev.supermux.net.PADto
import dev.supermux.state.FleetStore
import dev.supermux.state.HostStore
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.theme.Space
import dev.supermux.ui.widgets.AlertDialog
import kotlinx.coroutines.launch

private val PA_AGENTS = listOf("claude", "codex", "cursor", "opencode", "grok")

/** Every broker call the Personal assistants screen makes, in one holder. */
@Immutable
class PersonalAssistantsActions(
    val load: suspend () -> List<PADto> = { emptyList() },
    val create: suspend (name: String, agent: String, focus: String?) -> Boolean =
        { _, _, _ -> false },
    val kill: suspend (id: String) -> Unit = {},
)

/** [PersonalAssistantsActions] against one paired host — desktop's wiring. */
@Composable
fun rememberPersonalAssistantsActions(app: HostStore): PersonalAssistantsActions = remember(app) {
    PersonalAssistantsActions(
        load = { app.personalAssistants() },
        create = { name, agent, focus -> app.createPersonalAssistant(name, agent, focus) },
        kill = { id -> app.killPersonalAssistant(id) },
    )
}

/** [PersonalAssistantsActions] against the fleet's ACTIVE host — Android's wiring. */
@Composable
fun rememberPersonalAssistantsActions(fleet: FleetStore): PersonalAssistantsActions =
    remember(fleet) {
        PersonalAssistantsActions(
            load = { fleet.personalAssistants() },
            create = { name, agent, focus -> fleet.createPersonalAssistant(name, agent, focus) },
            kill = { id -> fleet.killPersonalAssistant(id) },
        )
    }

/**
 * Personal assistants: the persistent orchestrators, listed with their agent/model and a kill.
 *
 * @param onBack leave the screen; only reachable from the Compact top bar this screen paints for
 *   itself (pass the hub's `SettingsSlotScope.onClose`).
 * @param topBarShown the hub already painted a `TopAppBar` for this detail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PersonalAssistantsScreen(
    actions: PersonalAssistantsActions,
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
                    title = { Text("Personal assistants", color = cs.onSurface) },
                    navigationIcon = {
                        IconButton(
                            onClick = onBack,
                            modifier = Modifier.testTag("pa_settings_back"),
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = cs.onSurface,
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = cs.surfaceContainerHigh,
                    ),
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { showCreate = true },
                    modifier = Modifier.testTag("pa_create_fab"),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create personal assistant")
                }
            },
            containerColor = cs.background,
        ) { padding ->
            PersonalAssistantsBody(
                actions = actions,
                modifier = modifier.padding(padding),
                showHeaderCreate = false,
                showCreate = showCreate,
                onShowCreateChange = { showCreate = it },
            )
        }
    } else {
        PersonalAssistantsBody(
            actions = actions,
            modifier = modifier,
            showHeaderCreate = true,
            showCreate = showCreate,
            onShowCreateChange = { showCreate = it },
        )
    }
}

@Composable
private fun PersonalAssistantsBody(
    actions: PersonalAssistantsActions,
    modifier: Modifier,
    showHeaderCreate: Boolean,
    showCreate: Boolean,
    onShowCreateChange: (Boolean) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var assistants by remember { mutableStateOf<List<PADto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var killTarget by remember { mutableStateOf<PADto?>(null) }

    suspend fun refresh() {
        loading = true
        assistants = actions.load()
        loading = false
    }
    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("pa_settings_screen"),
    ) {
        if (showHeaderCreate) {
            // Hub chrome: action row only — no nested Back/title.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.End,
            ) {
                Button(
                    onClick = { onShowCreateChange(true) },
                    modifier = Modifier.testTag("pa_create_button"),
                ) { Text("Create") }
            }
            HorizontalDivider(color = cs.outlineVariant)
        }

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.testTag("pa_settings_loading"))
            }
            assistants.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(Space.xl).testTag("pa_settings_empty"),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text("No personal assistants", fontWeight = FontWeight.SemiBold)
                    Text(
                        "They are optional. Create one when you want a persistent orchestrator.",
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().testTag("pa_list"),
                contentPadding = PaddingValues(bottom = Space.xxl + Space.xl),
            ) {
                items(assistants, key = { it.id }) { pa ->
                    ListItem(
                        headlineContent = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                            ) {
                                Text(pa.name, fontWeight = FontWeight.Medium)
                                if (pa.isDefault) {
                                    Text(
                                        "default",
                                        color = cs.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.testTag("pa_default_${pa.id}"),
                                    )
                                }
                            }
                        },
                        supportingContent = {
                            Text(
                                listOfNotNull(pa.agent, pa.model).joinToString(" · ")
                                    .ifBlank { pa.workdir },
                                maxLines = 1,
                            )
                        },
                        leadingContent = {
                            Box(
                                Modifier
                                    .size(Space.sm + 1.dp)
                                    .clip(CircleShape)
                                    .background(if (pa.connected) cs.primary else cs.outline)
                                    .testTag("pa_dot_${pa.id}"),
                            )
                        },
                        trailingContent = {
                            TextButton(
                                onClick = { killTarget = pa },
                                modifier = Modifier.testTag("pa_kill_${pa.id}"),
                            ) { Text("Kill") }
                        },
                        modifier = Modifier.testTag("pa_row_${pa.id}"),
                    )
                    HorizontalDivider(color = cs.outlineVariant)
                }
            }
        }
    }

    if (showCreate) {
        CreatePersonalAssistantDialog(
            onDismiss = { onShowCreateChange(false) },
            onCreate = { name, agent, focus ->
                scope.launch {
                    if (actions.create(name, agent, focus)) {
                        onShowCreateChange(false)
                        refresh()
                    }
                }
            },
        )
    }
    killTarget?.let { pa ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text("Kill ${pa.name}?") },
            text = {
                Text(
                    "Its session will be archived. You can create another personal assistant later.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        killTarget = null
                        scope.launch { actions.kill(pa.id); refresh() }
                    },
                    modifier = Modifier.testTag("pa_kill_confirm"),
                ) { Text("Kill") }
            },
            dismissButton = {
                TextButton(
                    onClick = { killTarget = null },
                    modifier = Modifier.testTag("pa_kill_cancel"),
                ) { Text("Cancel") }
            },
            modifier = Modifier.testTag("pa_kill_dialog"),
        )
    }
}

@Composable
private fun CreatePersonalAssistantDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String, String?) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var agent by remember { mutableStateOf("claude") }
    var focus by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create personal assistant") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("pa_create_name"),
                )
                Text(
                    "Agent",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                PA_AGENTS.chunked(2).forEach { choices ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        choices.forEach { value ->
                            FilterChip(
                                selected = agent == value,
                                onClick = { agent = value },
                                label = { Text(value) },
                                modifier = Modifier.weight(1f).testTag("pa_create_agent_$value"),
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = focus,
                    onValueChange = { focus = it },
                    label = { Text("Focus (optional)") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().testTag("pa_create_focus"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreate(name.trim(), agent, focus.trim().takeIf { it.isNotEmpty() }) },
                modifier = Modifier.testTag("pa_create_confirm"),
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("pa_create_cancel"),
            ) { Text("Cancel") }
        },
        modifier = Modifier.testTag("pa_create_dialog"),
    )
}
