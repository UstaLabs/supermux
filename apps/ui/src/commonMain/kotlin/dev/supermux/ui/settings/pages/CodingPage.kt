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
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.settings_coding_code_intelligence
import dev.supermux.ui.resources.settings_coding_editor
import dev.supermux.ui.resources.settings_pages_couldnt_reach
import dev.supermux.ui.resources.settings_pages_couldnt_reach_this_machine
import dev.supermux.ui.resources.settings_pages_on_machine
import dev.supermux.ui.resources.settings_pages_on_this_computer
import dev.supermux.ui.resources.settings_pages_on_this_device
import dev.supermux.ui.resources.settings_pages_on_this_machine
import dev.supermux.ui.resources.settings_pages_on_this_phone
import dev.supermux.ui.settings.localizedSettingsTitle

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
    val machineNote = pagesMachineNote()
    // Another machine picked: its sections load afresh.
    val machineId = LocalSettingsMachines.current?.current?.id
    KitPage(
        localizedSettingsTitle(SettingsPlace(SettingsSection.Coding)),
        modifier,
        scope = { KitMixedScope() },
        anchor = anchor,
        onAnchorShown = onAnchorShown,
        tag = "coding_settings_screen",
    ) {
        KitSectionHead(
            "GitHub & GitLab", // i18n-ok: product names
            Modifier.kitAnchor("github").testTag("coding_github"),
            note = machineNote,
        )
        if (!online) KitOfflineNote() else key(machineId) { GitHubSection(forges) }

        KitSectionHead(localized(Res.string.settings_coding_editor), note = pagesDeviceNote())
        EditorSection()

        KitSectionHead(
            localized(Res.string.settings_coding_code_intelligence),
            Modifier.kitAnchor("code-intelligence").testTag("coding_code-intelligence"),
            note = machineNote,
        )
        if (!online) KitOfflineNote() else key(machineId) { CodeIntelligenceSection(lsp) }
    }
}

/** A section head's note for the machine's rows: "on ‹machine›", or "on this machine" when Settings knows no name. */
@Composable
internal fun pagesMachineNote(): String {
    val name = LocalSettingsMachines.current?.current?.name
    return if (name != null) localized(Res.string.settings_pages_on_machine, name) else localized(Res.string.settings_pages_on_this_machine)
}

/** A section head's note for this device's rows: "on this computer", "on this phone" or "on this device". */
@Composable
internal fun pagesDeviceNote(): String = localized(
    when (kitDeviceNoun()) {
        "computer" -> Res.string.settings_pages_on_this_computer
        "phone" -> Res.string.settings_pages_on_this_phone
        else -> Res.string.settings_pages_on_this_device
    },
)

/** "Couldn't reach ‹machine›." for a failed load, or "…this machine." when Settings knows no name. */
@Composable
internal fun pagesCouldntReach(): String {
    val name = LocalSettingsMachines.current?.current?.name
    return if (name != null) localized(Res.string.settings_pages_couldnt_reach, name) else localized(Res.string.settings_pages_couldnt_reach_this_machine)
}
