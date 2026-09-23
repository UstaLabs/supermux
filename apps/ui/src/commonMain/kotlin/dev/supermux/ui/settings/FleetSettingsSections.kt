// The slot wiring for the shared `SettingsHub` (cluster E7) on every FLEET host — Android and iOS.
//
// This is what is left of `MoreScreens.kt`, the 2524-line settings monolith: every page it held is
// now a screen in `:ui`, so all this file answers is "which composable is section X, and how does
// it reach the store". It lived in `apps/android` until H4, when iOS needed it and the honest
// choice was one copy or two. Two would have been two answers to a question with one answer: the
// mapping is decided entirely by `SettingsSection` and `FleetStore`, both of them shared, and
// nothing in it is Android's.
//
// It is the fleet spelling specifically. Desktop keeps `DesktopSettingsSections.kt` because its
// store is one `HostStore` and every screen's actions holder has a different overload for that;
// here the store is the fleet's ACTIVE host.
//
// The hub paints the pushed detail's chrome itself (E7 dropped the `compactTopBar` escape hatch it
// needed while these were Android pages), so nothing below carries a `Scaffold`, a `TopAppBar` or a
// `BackHandler` of its own: exactly one bar per page on a phone, and the hub's `BackHandler` owns
// Back everywhere.
package dev.supermux.ui.settings

import androidx.compose.runtime.Composable
import dev.supermux.ui.update.AppUpdateScreen
import dev.supermux.state.FleetStore
import dev.supermux.ui.nav.SettingsSection

/** Renders [section] against the fleet's active host. Called from the hub's `content` slot. */
@Composable
fun FleetSettingsSection(
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
        SettingsSection.Worktrees -> WorktreesSettingsScreen(
            actions = rememberWorktreesSettingsActions(fleet),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
    }
}

/**
 * Renders one host-local [SettingsExtra] row's page. Appearance is shared since E7 and the in-app
 * updater since G5; the ROWS are still host-local — the hub filters them by `Caps` — so a host
 * reaches only the ones it advertises. iOS never reaches `AppUpdate`: `Caps.appUpdate` is false
 * there because the App Store owns updates.
 */
@Composable
fun FleetSettingsExtra(extra: SettingsExtra, scope: SettingsSlotScope) {
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
