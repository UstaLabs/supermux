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
import dev.supermux.state.FleetStore
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.settings.pages.CodingPage
import dev.supermux.ui.settings.pages.GeneralPage
import dev.supermux.ui.settings.pages.GlossaryPage
import dev.supermux.ui.settings.pages.InterimMachinePage
import dev.supermux.ui.settings.pages.KeyboardShortcutsPage
import dev.supermux.ui.settings.pages.MemoryPage
import dev.supermux.ui.settings.pages.rememberCuratorSettingsActions
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.settings.pages.PrivacyLabsPage
import dev.supermux.ui.settings.pages.ProviderDetailPage
import dev.supermux.ui.settings.pages.ProvidersPage
import dev.supermux.ui.settings.pages.rememberProvidersState
import dev.supermux.ui.accounts.rememberAccountsActions
import dev.supermux.ui.settings.pages.VoicePage
import dev.supermux.ui.settings.pages.rememberVoiceSettingsActions

/**
 * Renders [place] against the fleet's active host. Called from the hub's `content` slot.
 *
 * #97: every section is its page; the machine's own screens stay on the interim machine page until Step 2. Accounts
 * live on each provider's page (`Providers/‹agent›`, where Usage's and the composer's "Sign in again" land) and Git
 * hosting is Coding's `github` anchor.
 */
@Composable
fun FleetSettingsSection(
    place: SettingsPlace,
    scope: SettingsSlotScope,
    fleet: FleetStore,
) {
    when (place.section) {
        SettingsSection.General -> GeneralPage(
            // What these hosts paint when nothing is stored: the system's choice (spec §10.6).
            defaultAppearance = AppearanceMode.SYSTEM,
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.KeyboardShortcuts -> KeyboardShortcutsPage(
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.Providers -> {
            val agents = rememberAgentSettingsActions(fleet)
            val accounts = rememberAccountsActions(fleet)
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
            val voice = rememberVoiceSettingsActions(fleet)
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
            forges = rememberGitHostingActions(fleet),
            lsp = rememberCodeIntelligenceActions(fleet),
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.Memory -> MemoryPage(
            actions = rememberCuratorSettingsActions(fleet),
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.PrivacyLabs -> PrivacyLabsPage(
            system = rememberSystemSettingsActions(fleet),
            anchor = place.anchor,
            onAnchorShown = scope.onAnchorShown,
        )
        SettingsSection.Machine -> when (place.sub) {
            MachineSub.STATUS -> SystemSettingsScreen(
                actions = rememberSystemSettingsActions(fleet),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            // Hosting is desktop-only (`Caps.localBroker` hides its row everywhere this renderer is used), so a
            // `Machine/computer` link lands on the machine page below rather than on a blank one.
            // Devices and Proxies are standalone routes too (Route.Devices / Route.Proxies) — the same
            // screens, which paint their own chrome there via `standalone = true`.
            MachineSub.DEVICES -> DevicesSettingsScreen(
                actions = rememberDevicesSettingsActions(fleet),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            MachineSub.LINKS -> ProxiesSettingsScreen(
                actions = rememberProxiesSettingsActions(fleet),
                onBack = scope.onClose,
                topBarShown = scope.topBarShown,
            )
            MachineSub.WORKTREES -> WorktreesSettingsScreen(
                actions = rememberWorktreesSettingsActions(fleet),
                onBack = scope.onClose,
                // Always: the hub (phone) or the rail (wide) owns the title, never this screen (inventory bug 7).
                topBarShown = true,
            )
            else -> InterimMachinePage(
                onOpen = { scope.onOpen(SettingsPlace(SettingsSection.Machine, it)) },
                showComputer = LocalPlatform.current.caps.localBroker,
                anchor = place.anchor,
                onAnchorShown = scope.onAnchorShown,
            )
        }
    }
}
