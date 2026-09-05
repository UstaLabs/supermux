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
import dev.supermux.ui.settings.LspSettingsScreen
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
            agentStatuses = { host.agentStatuses() },
            agentStartLogin = { host.startAgentLogin(it) },
            agentPollLogin = { host.agentLoginState(it) },
            agentSendCode = { kind, code -> host.sendAgentLoginCode(kind, code) },
            agentCancelLogin = { host.cancelAgentLogin(it) },
            agentSaveSecret = { kind, value -> host.saveAgentSecret(kind, value) },
            agentStartInstall = { host.startAgentInstall(it) },
            agentPollInstall = { host.agentInstallState(it) },
            openCodeProviders = { host.openCodeProviders() },
            openCodeSetKey = { id, k -> host.setOpenCodeKey(id, k) },
            openCodeStartOAuth = { id, method -> host.startOpenCodeOAuth(id, method) },
            openCodeFinishOAuth = { id, method, code -> host.finishOpenCodeOAuth(id, method, code) },
        )
        SettingsSection.Devices -> DevicesSettingsScreen(
            devicesLoad = { host.devices() },
            deviceAdd = { name -> host.addDevice(name) },
            deviceRevoke = { name -> host.revokeDevice(name) },
        )
        SettingsSection.System -> SystemSettingsScreen(
            updateStatus = { host.updateStatus() },
            checkUpdate = { host.checkUpdate() },
            runUpdate = { host.runUpdate() },
            restartBroker = { host.restartBroker() },
        )
        SettingsSection.Proxies -> ProxiesSettingsScreen(
            proxiesLoad = { host.proxiesForSettings() },
            sessionNames = { host.sessions.value.map { it.name } },
            proxyCreate = { session, port, domain -> host.createProxy(session, port, domain) },
            proxySetPublic = { domain, isPublic -> host.setProxyPublic(domain, isPublic) },
            proxyRemove = { domain -> host.removeProxy(domain) },
        )
        SettingsSection.Assistant -> AssistantSettingsScreen(
            assistantLoad = { host.assistantLoad() },
            assistantSave = { paName, soul -> host.assistantSave(paName, soul) },
            onDirtyChange = scope.onDirtyChange,
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
            forgesLoad = { host.forgesLoad() },
            forgeAdd = { kind, token, host2, transport -> host.forgeAdd(kind, token, host2, transport) },
            forgeImport = { kind, transport -> host.forgeImport(kind, transport) },
            forgeRemove = { id -> host.forgeRemove(id) },
        )
    }
}
