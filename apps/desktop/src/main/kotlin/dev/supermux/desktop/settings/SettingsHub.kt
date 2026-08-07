// Settings hub shell: left rail of sections + detail pane.
// Folds the existing Editor/LSP and Personal Assistants screens into one entry point
// alongside Agents (Task 1), Devices (Task 2), and Proxies/Assistant/Voice (Task 5).
package dev.supermux.desktop.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import dev.supermux.desktop.ui.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.supermux.desktop.theme.Space
import dev.supermux.desktop.shell.SettingsSection
import dev.supermux.net.AddDeviceResponse
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.AppConfigDto
import dev.supermux.net.CreateProxyResponse
import dev.supermux.net.CuratorSettingsResponse
import dev.supermux.net.DeviceDto
import dev.supermux.net.ForgeConnectionsResponse
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.net.ModelInfo
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.net.PADto
import dev.supermux.net.RunUpdateResult
import dev.supermux.net.UpdateStatus
import dev.supermux.net.ProxyDto
import dev.supermux.net.ReasoningResponse
import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.StateFlow

/**
 * Full-pane Settings hub. [section] selects the detail; the rail lists all shipped sections.
 * Back closes the whole hub (same as Escape on the overlay host).
 */
@Composable
fun SettingsHub(
    section: SettingsSection,
    onSectionChange: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    /** Register Escape/close path that honors identity dirty-state (AppShell wires this). */
    onRegisterCloseHandler: ((() -> Unit) -> Unit)? = null,
    // Agents — null list means load failure (distinct from empty).
    agentStatuses: suspend () -> List<AgentInstallStatus>?,
    agentStartLogin: suspend (kind: String) -> AgentLoginState?,
    agentPollLogin: suspend (kind: String) -> AgentLoginState?,
    agentSendCode: suspend (kind: String, code: String) -> Unit,
    agentCancelLogin: suspend (kind: String) -> Unit,
    agentSaveSecret: suspend (kind: String, value: String) -> Boolean,
    agentStartInstall: suspend (kind: String) -> AgentInstallJob?,
    agentPollInstall: suspend (kind: String) -> AgentInstallJob?,
    openCodeProviders: suspend () -> List<OpenCodeProvider>,
    openCodeSetKey: suspend (providerId: String, key: String) -> Boolean,
    openCodeStartOAuth: suspend (providerId: String, method: Int) -> OpenCodeOAuthStart?,
    openCodeFinishOAuth: suspend (providerId: String, method: Int, code: String) -> Boolean,
    // Devices — null list means load failure (distinct from empty).
    devicesLoad: suspend () -> List<DeviceDto>?,
    deviceAdd: suspend (name: String) -> AddDeviceResponse?,
    deviceRevoke: suspend (name: String) -> Boolean,
    // System / maintenance — broker update + restart (not the desktop app's AppUpdate).
    updateStatus: suspend () -> UpdateStatus?,
    checkUpdate: suspend () -> UpdateStatus?,
    runUpdate: suspend () -> RunUpdateResult?,
    restartBroker: suspend () -> Boolean,
    // Proxies (Task 5)
    proxiesLoad: suspend () -> List<ProxyDto>?,
    proxySessionNames: () -> List<String>,
    proxyCreate: suspend (sessionName: String, port: Int, domain: String?) -> CreateProxyResponse?,
    proxySetPublic: suspend (domain: String, isPublic: Boolean) -> Boolean,
    proxyRemove: suspend (domain: String) -> Boolean,
    // Assistant identity + curator (Task 5)
    assistantLoad: suspend () -> Pair<String, String>?,
    /** null = success; non-null = error message. */
    assistantSave: suspend (paName: String, soul: String) -> String?,
    curatorLoad: suspend () -> CuratorSettingsResponse?,
    curatorSave: suspend (
        enabled: Boolean,
        hour: Int,
        minute: Int,
        agent: String,
        model: String?,
        reasoningLevel: String?,
    ) -> CuratorSettingsResponse?,
    curatorRunNow: suspend () -> Boolean,
    curatorLoadModels: suspend (agent: String) -> List<ModelInfo>,
    curatorLoadReasoning: suspend (agent: String, model: String?) -> ReasoningResponse?,
    // Voice (Task 5)
    voiceLoadConfig: suspend () -> AppConfigDto?,
    voiceLoadModels: suspend (family: String) -> List<ModelInfo>,
    voiceSaveStt: suspend (engine: String?) -> Boolean,
    voiceSaveTts: suspend (engine: String?) -> Boolean,
    voiceSaveCleanup: suspend (engine: String?, model: String?) -> Boolean,
    /** Null = load failure (not empty). */
    glossaryLoad: suspend () -> List<String>?,
    glossarySave: suspend (List<String>) -> List<String>?,
    // Editor / LSP
    lspLoad: suspend () -> List<LspServer>,
    lspToggle: suspend (id: String, enabled: Boolean) -> List<LspServer>?,
    lspInstall: suspend (id: String) -> LspInstallResult?,
    lspInstallLog: StateFlow<Map<String, List<String>>>,
    lspInstallDone: StateFlow<Map<String, ServerFrame.LspInstallDone>>,
    lspAddCustom: suspend (AddCustomLspArgs) -> LspMutationResult?,
    lspRemoveCustom: suspend (id: String) -> LspMutationResult?,
    // Personal assistants
    paLoad: suspend () -> List<PADto>,
    paCreate: suspend (name: String, agent: String, focus: String?) -> Boolean,
    paKill: suspend (id: String) -> Unit,
    // Git hosting (Task 4)
    forgesLoad: suspend () -> ForgeConnectionsResponse?,
    forgeAdd: suspend (kind: String, token: String, host: String?, transport: String) -> Boolean,
    forgeImport: suspend (kind: String, transport: String) -> Boolean,
    forgeRemove: suspend (id: String) -> Boolean,
) {
    val cs = MaterialTheme.colorScheme
    var identityDirty by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    /** When non-null, discard-confirm will switch to this section instead of closing the hub. */
    var pendingSection by remember { mutableStateOf<SettingsSection?>(null) }

    fun tryClose() {
        if (identityDirty) {
            pendingSection = null
            showDiscardDialog = true
        } else {
            onBack()
        }
    }

    fun trySectionChange(s: SettingsSection) {
        if (s == section) return
        if (identityDirty) {
            pendingSection = s
            showDiscardDialog = true
        } else {
            onSectionChange(s)
        }
    }

    fun confirmDiscard() {
        showDiscardDialog = false
        identityDirty = false
        val next = pendingSection
        pendingSection = null
        if (next != null) {
            onSectionChange(next)
        } else {
            onBack()
        }
    }

    // Keep the registered close handler current for Escape on the outer overlay.
    DisposableEffect(identityDirty) {
        onRegisterCloseHandler?.invoke { tryClose() }
        onDispose { onRegisterCloseHandler?.invoke { onBack() } }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(cs.background)
            .testTag("settings_hub"),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            IconButton(onClick = { tryClose() }, modifier = Modifier.testTag("settings_hub_back")) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = cs.onSurface,
                    modifier = Modifier.size(18.dp),
                )
            }
            Text(
                "Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = cs.outlineVariant)

        Row(Modifier.fillMaxSize()) {
            // Left rail
            Column(
                Modifier
                    .width(200.dp)
                    .fillMaxHeight()
                    .background(cs.surfaceContainerLow)
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = Space.sm)
                    .testTag("settings_hub_rail"),
            ) {
                SettingsSection.entries.forEach { s ->
                    RailRow(
                        label = s.label,
                        selected = s == section,
                        onClick = { trySectionChange(s) },
                        testTag = "settings_section_${s.name.lowercase()}",
                    )
                }
            }
            VerticalDivider(color = cs.outlineVariant)
            // Detail pane — no nested Back on the folded screens (hub owns navigation)
            Box(Modifier.weight(1f).fillMaxHeight().testTag("settings_hub_detail")) {
                when (section) {
                    SettingsSection.Agents -> AgentSettingsScreen(
                        agentStatuses = agentStatuses,
                        agentStartLogin = agentStartLogin,
                        agentPollLogin = agentPollLogin,
                        agentSendCode = agentSendCode,
                        agentCancelLogin = agentCancelLogin,
                        agentSaveSecret = agentSaveSecret,
                        agentStartInstall = agentStartInstall,
                        agentPollInstall = agentPollInstall,
                        openCodeProviders = openCodeProviders,
                        openCodeSetKey = openCodeSetKey,
                        openCodeStartOAuth = openCodeStartOAuth,
                        openCodeFinishOAuth = openCodeFinishOAuth,
                    )
                    SettingsSection.Devices -> DevicesSettingsScreen(
                        devicesLoad = devicesLoad,
                        deviceAdd = deviceAdd,
                        deviceRevoke = deviceRevoke,
                    )
                    SettingsSection.System -> SystemSettingsScreen(
                        updateStatus = updateStatus,
                        checkUpdate = checkUpdate,
                        runUpdate = runUpdate,
                        restartBroker = restartBroker,
                    )
                    SettingsSection.Proxies -> ProxiesSettingsScreen(
                        proxiesLoad = proxiesLoad,
                        sessionNames = proxySessionNames,
                        proxyCreate = proxyCreate,
                        proxySetPublic = proxySetPublic,
                        proxyRemove = proxyRemove,
                    )
                    SettingsSection.Assistant -> AssistantSettingsScreen(
                        assistantLoad = assistantLoad,
                        assistantSave = assistantSave,
                        onDirtyChange = { identityDirty = it },
                    )
                    SettingsSection.Curator -> CuratorSettingsScreen(
                        curatorLoad = curatorLoad,
                        curatorSave = curatorSave,
                        curatorRunNow = curatorRunNow,
                        loadModels = curatorLoadModels,
                        loadReasoning = curatorLoadReasoning,
                    )
                    SettingsSection.Voice -> VoiceSettingsScreen(
                        loadConfig = voiceLoadConfig,
                        loadModels = voiceLoadModels,
                        saveVoiceStt = voiceSaveStt,
                        saveVoiceTts = voiceSaveTts,
                        saveVoiceCleanup = voiceSaveCleanup,
                        glossaryLoad = glossaryLoad,
                        glossarySave = glossarySave,
                    )
                    SettingsSection.EditorLsp -> LspSettingsScreen(
                        lspLoad = lspLoad,
                        lspToggle = lspToggle,
                        lspInstall = lspInstall,
                        lspInstallLog = lspInstallLog,
                        lspInstallDone = lspInstallDone,
                        lspAddCustom = lspAddCustom,
                        lspRemoveCustom = lspRemoveCustom,
                        onBack = { tryClose() },
                        showTopBar = false,
                    )
                    SettingsSection.PersonalAssistants -> PersonalAssistantsScreen(
                        load = paLoad,
                        create = paCreate,
                        kill = paKill,
                        onBack = { tryClose() },
                        showTopBar = false,
                    )
                    SettingsSection.GitHosting -> GitHostingScreen(
                        forgesLoad = forgesLoad,
                        forgeAdd = forgeAdd,
                        forgeImport = forgeImport,
                        forgeRemove = forgeRemove,
                    )
                }
            }
        }
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = {
                showDiscardDialog = false
                pendingSection = null
            },
            title = { Text("Discard unsaved changes?") },
            text = {
                Text("You have unsaved edits to PA name or soul.md. Leave without saving?")
            },
            confirmButton = {
                TextButton(
                    onClick = { confirmDiscard() },
                    modifier = Modifier.testTag("settings_discard_confirm"),
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDiscardDialog = false
                        pendingSection = null
                    },
                    modifier = Modifier.testTag("settings_discard_cancel"),
                ) { Text("Keep editing") }
            },
            modifier = Modifier.testTag("settings_discard_dialog"),
        )
    }
}

@Composable
private fun RailRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    val cs = MaterialTheme.colorScheme
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val hovered by interaction.collectIsHoveredAsState()
    // Hover + keyboard focus are both first-class: hover lifts surface; focus draws a border
    // (including when the row is already selected). Click keeps LocalIndication for press/ripple.
    val bg = when {
        selected -> cs.surfaceContainerHighest
        focused || hovered -> cs.surfaceContainerHigh
        else -> cs.surfaceContainerLow
    }
    Text(
        label,
        color = if (selected) cs.primary else cs.onSurface,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
            .hoverable(interactionSource = interaction)
            .focusable(interactionSource = interaction)
            .onPreviewKeyEvent { e ->
                if (e.type == KeyEventType.KeyDown &&
                    (e.key == Key.Enter || e.key == Key.NumPadEnter || e.key == Key.Spacebar)
                ) {
                    onClick()
                    true
                } else {
                    false
                }
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            )
            .background(bg)
            .then(
                if (focused) {
                    Modifier.border(width = 1.dp, color = cs.primary)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag(testTag),
    )
}
