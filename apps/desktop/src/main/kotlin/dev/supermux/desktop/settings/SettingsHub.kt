// Settings hub shell: left rail of sections + detail pane.
// Folds the existing Editor/LSP and Personal Assistants screens into one entry point
// alongside the new Agents section (desktop-parity Task 1).
package dev.supermux.desktop.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import dev.supermux.desktop.workspace.SettingsSection
import dev.supermux.net.AgentInstallJob
import dev.supermux.net.AgentInstallStatus
import dev.supermux.net.AgentLoginState
import dev.supermux.net.LspInstallResult
import dev.supermux.net.LspMutationResult
import dev.supermux.net.LspServer
import dev.supermux.net.OpenCodeOAuthStart
import dev.supermux.net.OpenCodeProvider
import dev.supermux.net.PADto
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
) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().background(cs.background).testTag("settings_hub")) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            TextButton(onClick = onBack, modifier = Modifier.testTag("settings_hub_back")) {
                Text("Back")
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
                        onClick = { onSectionChange(s) },
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
                    SettingsSection.EditorLsp -> LspSettingsScreen(
                        lspLoad = lspLoad,
                        lspToggle = lspToggle,
                        lspInstall = lspInstall,
                        lspInstallLog = lspInstallLog,
                        lspInstallDone = lspInstallDone,
                        lspAddCustom = lspAddCustom,
                        lspRemoveCustom = lspRemoveCustom,
                        onBack = onBack,
                        showTopBar = false,
                    )
                    SettingsSection.PersonalAssistants -> PersonalAssistantsScreen(
                        load = paLoad,
                        create = paCreate,
                        kill = paKill,
                        onBack = onBack,
                        showTopBar = false,
                    )
                }
            }
        }
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
    val bg = when {
        selected -> cs.surfaceContainerHighest
        focused -> cs.surfaceContainerHigh
        else -> cs.surfaceContainerLow
    }
    Text(
        label,
        color = if (selected) cs.primary else cs.onSurface,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier
            .fillMaxWidth()
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
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .background(bg)
            .then(
                if (focused && !selected) {
                    Modifier.border(width = 1.dp, color = cs.primary)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = Space.lg, vertical = Space.md)
            .testTag(testTag),
    )
}
