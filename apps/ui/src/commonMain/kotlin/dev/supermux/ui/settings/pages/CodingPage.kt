// Coding (#97 spec §2.2, §10.8, §11): GitHub & GitLab and Code intelligence are the machine's, Editor & terminal this
// device's. A mixed page: each section head says whose it is, and only with two or more machines is there a page
// scope line ("Machine parts on ‹picker›"). An offline machine's sections show its offline line instead of loading
// (another host would answer for it); the device's rows keep working.
package dev.supermux.ui.settings.pages

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.nav.SettingsSection
import dev.supermux.ui.settings.CodeIntelligenceActions
import dev.supermux.ui.settings.CodeIntelligenceSection
import dev.supermux.ui.settings.EditorSection
import dev.supermux.ui.settings.GitHostingActions
import dev.supermux.ui.settings.GitHubSection
import dev.supermux.ui.settings.SettingsPlace
import dev.supermux.ui.settings.kit.KitMixedScope
import dev.supermux.ui.settings.kit.KitOfflineNote
import dev.supermux.ui.settings.kit.KitPage
import dev.supermux.ui.settings.kit.KitSectionHead
import dev.supermux.ui.settings.kit.LocalSettingsMachines
import dev.supermux.ui.settings.kit.currentMachineOnline
import dev.supermux.ui.settings.kit.kitAnchor
import dev.supermux.ui.settings.kit.kitDeviceNoun
import dev.supermux.ui.settings.settingsTitle

@Composable
fun CodingPage(
    forges: GitHostingActions,
    lsp: CodeIntelligenceActions,
    modifier: Modifier = Modifier,
    anchor: String? = null,
    onAnchorShown: () -> Unit = {},
) {
    val online = currentMachineOnline()
    // Mid-sentence: "on studio-mini", or "on this machine" when Settings knows no name.
    val machine = LocalSettingsMachines.current?.current?.name ?: "this machine"
    // Another machine picked: its sections load afresh.
    val machineId = LocalSettingsMachines.current?.current?.id
    KitPage(
        settingsTitle(SettingsPlace(SettingsSection.Coding)),
        modifier,
        scope = { KitMixedScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "coding_settings_screen",
    ) {
        KitSectionHead(
            "GitHub & GitLab",
            Modifier.kitAnchor("github").testTag("coding_github"),
            note = "on $machine",
        )
        if (!online) KitOfflineNote() else key(machineId) { GitHubSection(forges) }

        KitSectionHead("Editor & terminal", note = "on this ${kitDeviceNoun()}")
        EditorSection()

        KitSectionHead(
            "Code intelligence",
            Modifier.kitAnchor("code-intelligence").testTag("coding_code-intelligence"),
            note = "on $machine",
        )
        if (!online) KitOfflineNote() else key(machineId) { CodeIntelligenceSection(lsp) }
    }
}
