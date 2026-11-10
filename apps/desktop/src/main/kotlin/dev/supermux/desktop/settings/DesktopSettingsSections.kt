// Desktop's slot wiring for the shared `SettingsHub` (cluster E1).
//
// The hub itself is `dev.supermux.ui.settings.SettingsHub` — rail + detail here, index + push on a
// compact window. This file is only "which composable is section X on desktop, and how does it
// reach the store": every screen keeps the exact suspend lambdas the old desktop hub handed it, so
// E2–E6 can delete one branch at a time as each screen becomes shared.
package dev.supermux.desktop.settings

import androidx.compose.runtime.Composable
import dev.supermux.state.HostStore
import dev.supermux.ui.accounts.rememberAccountsActions
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.settings.DevicesSettingsScreen
import dev.supermux.ui.settings.MachineSub
import dev.supermux.ui.settings.ProxiesSettingsScreen
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.SystemSettingsScreen
import dev.supermux.ui.settings.WorktreesSettingsScreen
import dev.supermux.ui.settings.GLOSSARY_SUB
import dev.supermux.ui.settings.pages.CodingPage
import dev.supermux.ui.settings.pages.GeneralPage
import dev.supermux.ui.settings.pages.GlossaryPage
import dev.supermux.ui.settings.pages.InterimMachinePage
import dev.supermux.ui.settings.pages.MemoryPage
import dev.supermux.ui.settings.pages.PrivacyLabsPage
import dev.supermux.ui.settings.pages.ProviderDetailPage
import dev.supermux.ui.settings.pages.ProvidersPage
import dev.supermux.ui.settings.pages.rememberProvidersState
import dev.supermux.ui.settings.pages.VoicePage
import dev.supermux.ui.settings.pages.rememberVoiceSettingsActions
import dev.supermux.ui.settings.rememberAgentSettingsActions
import dev.supermux.ui.settings.pages.rememberCuratorSettingsActions
import dev.supermux.ui.settings.rememberDevicesSettingsActions
import dev.supermux.ui.settings.rememberProxiesSettingsActions
import dev.supermux.ui.settings.rememberCodeIntelligenceActions
import dev.supermux.ui.settings.rememberGitHostingActions
import dev.supermux.ui.settings.rememberSystemSettingsActions
import dev.supermux.ui.settings.rememberWorktreesSettingsActions
import dev.supermux.ui.settings.SettingsSlotScope
import dev.supermux.ui.theme.AppearanceMode

/**
 * Renders [place]'s desktop screen against [host]. Called from the hub's `content` slot.
 *
 * #97, the same mapping as `FleetSettingsSection`: every section is its page, the machine's own screens are on the
 * interim machine page; accounts live on each provider's page, and Git hosting is Coding's `github` anchor.
 */
@Composable
fun DesktopSettingsSection(
    place: SettingsPlace,
    scope: SettingsSlotScope,
    host: HostStore,
) {
    when (place.section) {
        SettingsSection.General -> GeneralPage(
            // Desktop opens dark when nobody has chosen — the same fallback `Main.kt` applies (spec §10.6).
            defaultAppearance = AppearanceMode.DARK,
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.Providers -> {
            val agents = rememberAgentSettingsActions(host)
            val accounts = rememberAccountsActions(host)
            // Above the list and the provider pages, so a login or an install keeps going between them.
            val providers = rememberProvidersState(agents)
            when (val agent = place.sub) {
                null -> ProvidersPage(
                    providers,
                    accounts,
                    onOpenProvider = { scope.onOpen(SettingsPlace(SettingsSection.Providers, it)) },
                    anchor = place.anchor,
                    onAnchorShown = scope.onAnchorShown,
                )
                else -> ProviderDetailPage(agent, providers, agents, accounts, onBack = scope.onClose)
            }
        }
        SettingsSection.Voice -> {
            val voice = rememberVoiceSettingsActions(host)
            if (place.sub == GLOSSARY_SUB) {
                GlossaryPage(voice, onBack = scope.onClose)
            } else {
                VoicePage(
                    actions = voice,
                    anchor = place.anchor,
                    onAnchorShown = scope.onAnchorShown,
                    onOpenSub = { scope.onOpen(SettingsPlace(SettingsSection.Voice, it)) },
                )
            }
        }
        SettingsSection.Coding -> CodingPage(
            forges = rememberGitHostingActions(host),
            lsp = rememberCodeIntelligenceActions(host),
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.Memory -> MemoryPage(
            actions = rememberCuratorSettingsActions(host),
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
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
