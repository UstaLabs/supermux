// Android's slot wiring for the shared `SettingsHub` (cluster E7), mirroring desktop's
// `DesktopSettingsSections.kt`.
//
// This is what is left of `MoreScreens.kt`, the 2524-line settings monolith: every page it held is
// now a screen in `:ui`, so all this file answers is "which composable is section X on Android,
// and how does it reach the store". Android's store is the fleet's ACTIVE host, so each screen
// gets the `FleetStore` overload of its actions holder — desktop passes one `HostStore`.
//
// Every page is shared now, so the hub paints the pushed detail's chrome itself (E7 dropped the
// `compactTopBar` escape hatch it needed while these were Android pages) and nothing below carries
// a `Scaffold`, a `TopAppBar` or a `BackHandler` of its own: exactly one bar per page on a phone,
// and the hub's `BackHandler` owns Back everywhere.
package dev.supermux.android.settings

import androidx.compose.runtime.Composable
import dev.supermux.ui.update.AppUpdateScreen
import dev.supermux.state.FleetStore
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.settings.AppearanceSettingsScreen
import dev.supermux.ui.settings.AssistantSettingsScreen
import dev.supermux.ui.settings.CuratorSettingsScreen
import dev.supermux.ui.settings.DevicesSettingsScreen
import dev.supermux.ui.settings.EditorSettingsScreen
import dev.supermux.ui.settings.GitHostingScreen
import dev.supermux.ui.settings.PersonalAssistantsScreen
import dev.supermux.ui.settings.ProxiesSettingsScreen
import dev.supermux.ui.settings.SettingsExtra
import dev.supermux.ui.settings.SettingsSlotScope
import dev.supermux.ui.settings.SystemSettingsScreen
import dev.supermux.ui.settings.VoiceSettingsScreen
import dev.supermux.ui.settings.rememberAgentSettingsActions
import dev.supermux.ui.settings.rememberAssistantSettingsActions
import dev.supermux.ui.settings.rememberCuratorSettingsActions
import dev.supermux.ui.settings.rememberDevicesSettingsActions
import dev.supermux.ui.settings.rememberGitHostingActions
import dev.supermux.ui.settings.rememberPersonalAssistantsActions
import dev.supermux.ui.settings.rememberProxiesSettingsActions
import dev.supermux.ui.settings.rememberSystemSettingsActions
import dev.supermux.ui.settings.rememberVoiceSettingsActions

/** Renders [section] against the fleet's active host. Called from the hub's `content` slot. */
@Composable
fun AndroidSettingsSection(
    section: SettingsSection,
    scope: SettingsSlotScope,
    fleet: FleetStore,
) {
    when (section) {
        SettingsSection.PersonalAssistants -> PersonalAssistantsScreen(
            actions = rememberPersonalAssistantsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Assistant -> AssistantSettingsScreen(
            actions = rememberAssistantSettingsActions(fleet),
            onDirtyChange = scope.onDirtyChange,
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Agents -> AgentSettingsScreen(
            actions = rememberAgentSettingsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Curator -> CuratorSettingsScreen(
            actions = rememberCuratorSettingsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Voice -> VoiceSettingsScreen(
            actions = rememberVoiceSettingsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.EditorLsp -> EditorSettingsScreen(
            lspLoad = { fleet.lspLoad() },
            lspToggle = { id, enabled -> fleet.lspToggle(id, enabled) },
            lspInstall = { id -> fleet.lspInstall(id) },
            lspInstallLog = fleet.lspInstallLog,
            lspInstallDone = fleet.lspInstallDone,
            lspAddCustom = { args ->
                fleet.lspAddCustom(
                    args.id, args.label, args.command, args.extensions, args.args,
                    args.languageId, args.installCmd,
                )
            },
            lspRemoveCustom = { id -> fleet.lspRemoveCustom(id) },
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.GitHosting -> GitHostingScreen(
            actions = rememberGitHostingActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.System -> SystemSettingsScreen(
            actions = rememberSystemSettingsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        // Devices and Proxies are standalone routes too (Route.Devices / Route.Proxies) — the same
        // screens, which paint their own chrome there via `standalone = true`.
        SettingsSection.Devices -> DevicesSettingsScreen(
            actions = rememberDevicesSettingsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Proxies -> ProxiesSettingsScreen(
            actions = rememberProxiesSettingsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
    }
}

/**
 * Renders one host-local [SettingsExtra] row's page. Appearance is shared since E7 and the in-app
 * updater since G5; the ROWS are still host-local, so this one is only reached because
 * `Caps.appUpdate` is true here.
 */
@Composable
fun AndroidSettingsExtra(extra: SettingsExtra, scope: SettingsSlotScope) {
    when (extra) {
        SettingsExtra.Appearance -> AppearanceSettingsScreen(
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsExtra.AppUpdate -> AppUpdateScreen(
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
    }
}
