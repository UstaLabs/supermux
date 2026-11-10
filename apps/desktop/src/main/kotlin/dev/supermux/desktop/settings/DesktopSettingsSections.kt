// Desktop's slot wiring for the shared `SettingsHub` (cluster E1).
//
// The hub itself is `dev.supermux.ui.settings.SettingsHub` — rail + detail here, index + push on a
// compact window. This file is only "which composable is section X on desktop, and how does it
// reach the store": every screen keeps the exact suspend lambdas the old desktop hub handed it, so
// E2–E6 can delete one branch at a time as each screen becomes shared.
package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import dev.supermux.state.HostStore
import dev.supermux.ui.accounts.AccountsSettingsScreen
import dev.supermux.ui.accounts.rememberAccountsActions
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.settings.AgentSettingsScreen
import dev.supermux.ui.settings.CuratorSettingsScreen
import dev.supermux.ui.settings.DevicesSettingsScreen
import dev.supermux.ui.settings.MachineSub
import dev.supermux.ui.settings.ProxiesSettingsScreen
import dev.supermux.ui.settings.GitHostingScreen
import dev.supermux.ui.settings.AppearanceSettingsScreen
import dev.supermux.ui.settings.EditorSettingsScreen
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.StorageSettingsScreen
import dev.supermux.ui.settings.SystemSettingsScreen
import dev.supermux.ui.settings.VoiceSettingsScreen
import dev.supermux.ui.settings.WorktreesSettingsScreen
import dev.supermux.ui.settings.pages.InterimMachinePage
import dev.supermux.ui.settings.pages.PrivacyLabsPage
import dev.supermux.ui.settings.rememberAgentSettingsActions
import dev.supermux.ui.settings.rememberCuratorSettingsActions
import dev.supermux.ui.settings.rememberDevicesSettingsActions
import dev.supermux.ui.settings.rememberProxiesSettingsActions
import dev.supermux.ui.settings.rememberGitHostingActions
import dev.supermux.ui.settings.rememberSystemSettingsActions
import dev.supermux.ui.settings.rememberVoiceSettingsActions
import dev.supermux.ui.settings.rememberWorktreesSettingsActions
import dev.supermux.ui.settings.SettingsExtra
import dev.supermux.ui.settings.SettingsSlotScope
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.update.AppUpdateScreen

/**
 * Renders [place]'s desktop screen against [host]. Called from the hub's `content` slot.
 *
 * #97's switch-over, the same temporary mapping as `FleetSettingsSection`: today's screens under their new
 * section until each page is rebuilt; Accounts is a provider's sub-page and Git hosting Coding's `github` anchor
 * meanwhile.
 */
@Composable
fun DesktopSettingsSection(
    place: SettingsPlace,
    scope: SettingsSlotScope,
    host: HostStore,
) {
    when (place.section) {
        SettingsSection.General -> AppearanceSettingsScreen(
            // Desktop opens dark when nobody has chosen — the same fallback `Main.kt` applies.
            defaultAppearance = AppearanceMode.DARK,
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.Providers -> if (place.sub == null) {
            AgentSettingsScreen(
                actions = rememberAgentSettingsActions(host),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
        } else {
            AccountsSettingsScreen(
                actions = rememberAccountsActions(host),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
                onSignInAgain = { scope.onOpenSection(SettingsSection.Providers) },
            )
        }
        SettingsSection.Voice -> VoiceSettingsScreen(
            actions = rememberVoiceSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        // E7: the Editor page is the wrap/font steppers ABOVE the same `LspSettingsScreen` desktop used to
        // reach bare here. The steppers write the `SettingsKeys.EDITOR_*` values `WebCodeEditor`/`DiffView`
        // already read, so desktop's editor behaviour is unchanged.
        SettingsSection.Coding -> if (place.anchor == "github") {
            GitHostingScreen(
                actions = rememberGitHostingActions(host),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
        } else {
            EditorSettingsScreen(
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
        }
        SettingsSection.Memory -> CuratorSettingsScreen(
            actions = rememberCuratorSettingsActions(host),
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
        SettingsSection.PrivacyLabs -> PrivacyLabsPage(
            system = rememberSystemSettingsActions(host),
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.Machine -> when (place.sub) {
            MachineSub.STATUS -> SystemSettingsScreen(
                actions = rememberSystemSettingsActions(host),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // The supervisor, host store and session count come in through `LocalHostSupervisor` & co.
            MachineSub.COMPUTER -> DesktopHostingSettings(
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            MachineSub.DEVICES -> DevicesSettingsScreen(
                actions = rememberDevicesSettingsActions(host),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            MachineSub.LINKS -> ProxiesSettingsScreen(
                actions = rememberProxiesSettingsActions(host),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            MachineSub.WORKTREES -> WorktreesSettingsScreen(
                actions = rememberWorktreesSettingsActions(host),
                onBack = scope.onClose,
                // Always: the hub (phone) or the rail (wide) owns the title, never this screen (inventory bug 7).
                topBarShown = true,
            )
            else -> InterimMachinePage(
                onOpen = { scope.onOpen(SettingsPlace(SettingsSection.Machine, it)) },
                // This computer's hosting, on this computer's own page (or with no machine known yet): a remote
                // machine's page has none, and the hub would not show it there.
                showComputer = LocalPlatform.current.caps.localBroker &&
                    dev.supermux.ui.settings.kit.LocalSettingsMachines.current?.current?.local != false,
                anchor = place.anchor,
                onAnchorShown = scope.onAnchorShown,
            )
        }
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
        SettingsExtra.Storage -> StorageSettingsScreen(
            onBack = scope.onClose,
            topBarShown = scope.topBarShown,
        )
    }
}
