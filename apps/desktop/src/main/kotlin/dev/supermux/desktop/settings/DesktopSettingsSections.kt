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
import dev.supermux.ui.settings.CuratorSettingsScreen
import dev.supermux.ui.settings.DevicesSettingsScreen
import dev.supermux.ui.settings.PersonalAssistantsScreen
import dev.supermux.ui.settings.ProxiesSettingsScreen
import dev.supermux.ui.settings.AssistantSettingsScreen
import dev.supermux.ui.settings.GitHostingScreen
import dev.supermux.ui.settings.AppearanceSettingsScreen
import dev.supermux.ui.settings.EditorSettingsScreen
import dev.supermux.ui.settings.SystemSettingsScreen
import dev.supermux.ui.settings.VoiceSettingsScreen
import dev.supermux.ui.settings.WorktreesSettingsScreen
import dev.supermux.ui.settings.rememberAgentSettingsActions
import dev.supermux.ui.settings.rememberCuratorSettingsActions
import dev.supermux.ui.settings.rememberDevicesSettingsActions
import dev.supermux.ui.settings.rememberPersonalAssistantsActions
import dev.supermux.ui.settings.rememberProxiesSettingsActions
import dev.supermux.ui.settings.rememberAssistantSettingsActions
import dev.supermux.ui.settings.rememberGitHostingActions
import dev.supermux.ui.settings.rememberSystemSettingsActions
import dev.supermux.ui.settings.rememberVoiceSettingsActions
import dev.supermux.ui.settings.rememberWorktreesSettingsActions
import dev.supermux.ui.settings.SettingsExtra
import dev.supermux.ui.settings.SettingsSlotScope
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.update.AppUpdateScreen

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
            actions = rememberDevicesSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.System -> SystemSettingsScreen(
            actions = rememberSystemSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Proxies -> ProxiesSettingsScreen(
            actions = rememberProxiesSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Worktrees -> WorktreesSettingsScreen(
            actions = rememberWorktreesSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Assistant -> AssistantSettingsScreen(
            actions = rememberAssistantSettingsActions(host),
            onDirtyChange = scope.onDirtyChange,
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Curator -> CuratorSettingsScreen(
            actions = rememberCuratorSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Voice -> VoiceSettingsScreen(
            actions = rememberVoiceSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        // E7: the section is the shared Editor page, which is the wrap/font steppers ABOVE the
        // same `LspSettingsScreen` desktop used to reach bare here. The steppers write the
        // `SettingsKeys.EDITOR_*` values `WebCodeEditor`/`DiffView` already read, so desktop's
        // editor behaviour is unchanged — it just gained a way to change them from Settings.
        SettingsSection.EditorLsp -> EditorSettingsScreen(
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
            topBarShown = scope.topBarShown,
        )
        SettingsSection.PersonalAssistants -> PersonalAssistantsScreen(
            actions = rememberPersonalAssistantsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.GitHosting -> GitHostingScreen(
            actions = rememberGitHostingActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
    }
}

/**
 * Renders one host-local [SettingsExtra] row's page (cluster E7 — desktop shows both rows now).
 *
 * Appearance is the shared screen, reading and writing the same `SettingsKeys.APPEARANCE` the
 * sidebar's theme toggle does. The updater is the shared screen too since cluster G5.
 */
@Composable
fun DesktopSettingsExtra(extra: SettingsExtra, scope: SettingsSlotScope) {
    when (extra) {
        SettingsExtra.Appearance -> AppearanceSettingsScreen(
            // Desktop opens dark when nobody has chosen — the same fallback `Main.kt` applies.
            defaultAppearance = AppearanceMode.DARK,
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsExtra.AppUpdate -> AppUpdateScreen(
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
    }
}
