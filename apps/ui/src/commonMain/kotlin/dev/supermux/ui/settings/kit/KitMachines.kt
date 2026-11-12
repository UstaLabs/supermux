// The machines Settings knows (#97): the model the hub builds, the scope lines and the machine pages share.
package dev.supermux.ui.settings.kit

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.resources.Res
import dev.supermux.ui.resources.machine_fallback_name
import dev.supermux.ui.resources.machine_kit_offline
import dev.supermux.ui.resources.machine_kit_offline_seen

/**
 * A paired machine as Settings shows it. [local] = it runs on this computer (desktop). [status] is the card's line
 * ("Running", "Offline · last seen 2h ago", "Not hosting"); [lastSeen] is `formatLastSeen`'s text or "".
 */
@Immutable
data class KitMachine(
    val id: String,
    val name: String,
    val local: Boolean,
    val online: Boolean,
    val status: String,
    val lastSeen: String = "",
)

fun KitMachine.glyph(): ImageVector = if (local) KitGlyphs.Laptop else KitGlyphs.Server

/**
 * The card a computer that can host gets while no host running on it is paired (spec §11): it opens this
 * computer's hosting page without picking a machine.
 */
const val THIS_COMPUTER_ID = "this-computer"

/** What Settings calls the machine it is about when it knows no name for it. */
const val MACHINE_FALLBACK_NAME = "This machine" // i18n-ok: kept for SettingsPlace; shown text reads machine_fallback_name

/** Every machine Settings knows, the one its machine pages are about ([currentId]), and how to switch it. */
@Immutable
class SettingsMachines(val all: List<KitMachine>, val currentId: String?, val onPick: (String) -> Unit) {
    val current: KitMachine? get() = all.firstOrNull { it.id == currentId } ?: all.firstOrNull()
}

/** Provided by the hub; null in previews and in tests that don't set it. */
val LocalSettingsMachines = staticCompositionLocalOf<SettingsMachines?> { null }

/** The name machine pages use in their copy ("‹machine›'s local time"). */
@Composable
fun currentMachineName(): String = LocalSettingsMachines.current?.current?.name ?: localized(Res.string.machine_fallback_name)

/** False when the machine Settings is about is offline: its sections show [KitOfflineNote] instead of content. */
@Composable
@ReadOnlyComposable
fun currentMachineOnline(): Boolean = LocalSettingsMachines.current?.current?.online ?: true

/**
 * "studio-mini is offline · last seen 2h ago". Shown INSTEAD of a machine section's rows: `FleetStore.activeApp()`
 * falls through to another connected host when this one is offline, so loading anyway would show that host's
 * values under this one's name (spec §7, §10.2).
 */
@Composable
fun KitOfflineNote(modifier: Modifier = Modifier) {
    val m = LocalSettingsMachines.current?.current
    val name = m?.name ?: localized(Res.string.machine_fallback_name)
    val seen = m?.lastSeen?.takeIf { it.isNotEmpty() }
    val text = if (seen != null) localized(Res.string.machine_kit_offline_seen, name, seen) else localized(Res.string.machine_kit_offline, name)
    KitNote(text, modifier.testTag("settings_offline"))
}
