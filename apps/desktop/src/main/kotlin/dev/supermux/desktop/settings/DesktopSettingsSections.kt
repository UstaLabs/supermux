// Desktop's slot wiring for the shared `SettingsHub` (cluster E1).
//
// The hub itself is `dev.supermux.ui.settings.SettingsHub` — rail + detail here, index + push on a
// compact window. This file is only "which composable is section X on desktop, and how does it
// reach the store": every screen keeps the exact suspend lambdas the old desktop hub handed it, so
// E2–E6 can delete one branch at a time as each screen becomes shared.
package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import dev.supermux.state.HostStore
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.settings.AssistantSettingsScreen
import dev.supermux.ui.settings.GitHostingScreen
import dev.supermux.ui.settings.LspSettingsScreen
import dev.supermux.ui.settings.SystemSettingsScreen
import dev.supermux.ui.settings.rememberAgentSettingsActions
import dev.supermux.ui.settings.rememberAssistantSettingsActions
import dev.supermux.ui.settings.rememberGitHostingActions
import dev.supermux.ui.settings.rememberSystemSettingsActions
import dev.supermux.ui.settings.SettingsSlotScope

/** Renders [section]'s desktop screen against [host]. Called from the hub's `content` slot. */
@Composable
fun DesktopSettingsSection(
    section: SettingsSection,
    scope: SettingsSlotScope,
    host: HostStore,
) {
    when (section) {
        SettingsSection.Agents -> AgentSettingsScreen(
            actions = rememberAgentSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Devices -> DevicesSettingsScreen(
            devicesLoad = { host.devices() },
            deviceAdd = { name -> host.addDevice(name) },
            deviceRevoke = { name -> host.revokeDevice(name) },
        )
        SettingsSection.System -> SystemSettingsScreen(
            actions = rememberSystemSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Proxies -> ProxiesSettingsScreen(
            proxiesLoad = { host.proxiesForSettings() },
            sessionNames = { host.sessions.value.map { it.name } },
            proxyCreate = { session, port, domain -> host.createProxy(session, port, domain) },
            proxySetPublic = { domain, isPublic -> host.setProxyPublic(domain, isPublic) },
            proxyRemove = { domain -> host.removeProxy(domain) },
        )
        SettingsSection.Assistant -> AssistantSettingsScreen(
            actions = rememberAssistantSettingsActions(host),
            onDirtyChange = scope.onDirtyChange,
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Curator -> CuratorSettingsScreen(
            curatorLoad = { host.curatorSettings() },
            curatorSave = { enabled, hour, minute, agent, model, reasoning ->
                host.saveCurator(enabled, hour, minute, agent, model, reasoning)
            },
            curatorRunNow = { host.runCuratorNow() },
            loadModels = { agent -> host.launcherModels(agent) },
            loadReasoning = { agent, model -> host.launcherReasoning(agent, model) },
        )
        SettingsSection.Voice -> VoiceSettingsScreen(
            loadConfig = { host.appConfig() },
            loadModels = { family -> host.launcherModels(family) },
            saveVoiceStt = { engine -> host.saveVoiceStt(engine) },
            saveVoiceTts = { engine -> host.saveVoiceTts(engine) },
            saveVoiceCleanup = { engine, model -> host.saveVoiceCleanup(engine, model) },
            glossaryLoad = { host.fetchGlossary() },
            glossarySave = { terms -> host.updateGlossary(terms) },
        )
        SettingsSection.EditorLsp -> LspSettingsScreen(
            lspLoad = { host.lspLoad() },
            lspToggle = { id, enabled -> host.lspToggle(id, enabled) },
            lspInstall = { id -> host.lspInstall(id) },
            lspInstallLog = host.lspInstallLog,
            lspInstallDone = host.lspInstallDone,
            lspAddCustom = { args ->
                host.lspAddCustom(
                    args.id, args.label, args.command, args.extensions, args.args,
                    args.languageId, args.installCmd,
                )
            },
            lspRemoveCustom = { id -> host.lspRemoveCustom(id) },
            onBack = scope.onClose,
            showTopBar = false,
        )
        SettingsSection.PersonalAssistants -> PersonalAssistantsScreen(
            load = { host.personalAssistants() },
            create = { name, agent, focus -> host.createPersonalAssistant(name, agent, focus) },
            kill = { host.killPersonalAssistant(it) },
            onBack = scope.onClose,
            showTopBar = false,
        )
        SettingsSection.GitHosting -> GitHostingScreen(
            actions = rememberGitHostingActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
    }
}
