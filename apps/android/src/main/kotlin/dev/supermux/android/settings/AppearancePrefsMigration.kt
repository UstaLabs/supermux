// One-time move of the three appearance values out of Android's `cmux-editor-settings`
// SharedPreferences and into the shared [SettingsStore] (cluster E7).
//
// Everything else `UiPrefs` holds was allowed to reset once when it moved (spec: no migration).
// These three are not: losing someone's theme or text size is visible on every screen of the app,
// and both are set deliberately. So the old file is read once, on the first launch after the
// upgrade, and only for keys the shared store does not have yet — after that the SharedPreferences
// file is dead weight and nothing reads it again.
package dev.supermux.android.settings

import android.content.Context
import dev.supermux.state.SettingsKeys
import dev.supermux.state.SettingsStore
import dev.supermux.ui.prefs.TEXT_SCALE_DEFAULT
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.theme.AppearanceMode
import dev.supermux.ui.theme.TEXT_SCALE_MAX
import dev.supermux.ui.theme.TEXT_SCALE_MIN
import kotlinx.coroutines.flow.first

/** The pre-E7 values, as read out of `cmux-editor-settings`. Any of them may be absent. */
data class LegacyAppearancePrefs(
    /** `AppearanceMode.name`; anything unrecognised is dropped rather than migrated. */
    val appearance: String? = null,
    val dynamicColor: Boolean? = null,
    val textScale: Float? = null,
)

/** Reads the legacy SharedPreferences file without creating any state of its own. */
fun readLegacyAppearancePrefs(context: Context): LegacyAppearancePrefs {
    val prefs = context.applicationContext
        .getSharedPreferences("cmux-editor-settings", Context.MODE_PRIVATE)
    return LegacyAppearancePrefs(
        appearance = prefs.getString("appearance", null),
        dynamicColor = if (prefs.contains("dynamicColor")) {
            prefs.getBoolean("dynamicColor", false)
        } else {
            null
        },
        textScale = if (prefs.contains("textScale")) prefs.getFloat("textScale", 1f) else null,
    )
}

/**
 * Seed [settings] from [legacy] for whichever appearance keys are still unset.
 *
 * Idempotent and non-destructive: a key the shared store already holds is never overwritten, so a
 * user who has already changed the setting post-upgrade keeps their choice, and re-running this
 * (every launch, as MainActivity does) is a no-op. An unparsable stored mode is skipped, which
 * leaves the app on its own default rather than persisting garbage.
 */
suspend fun migrateAppearancePrefs(settings: SettingsStore, legacy: LegacyAppearancePrefs) {
    val mode = legacy.appearance?.let { name -> AppearanceMode.entries.firstOrNull { it.name == name } }
    if (mode != null && settings.string(SettingsKeys.APPEARANCE).first() == null) {
        settings.putString(SettingsKeys.APPEARANCE, mode.name)
    }
    val dynamic = legacy.dynamicColor
    if (dynamic != null && settings.string(SettingsKeys.DYNAMIC_COLOR).first() == null) {
        settings.putString(SettingsKeys.DYNAMIC_COLOR, dynamic.toString())
    }
    val scale = legacy.textScale
    if (scale != null && settings.string(SettingsKeys.TEXT_SCALE).first() == null) {
        settings.putString(
            SettingsKeys.TEXT_SCALE,
            scale.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX).toString(),
        )
    }
}

/** The values the very first composition needs, so no frame paints the wrong theme. */
data class AppearanceSeed(
    val appearance: AppearanceMode,
    val textScale: Float,
)

/**
 * Migrate (see [migrateAppearancePrefs]) and then READ the two values the root theme needs.
 *
 * `MainActivity` calls this from `onCreate`, before `setContent`, and hands the result to
 * `collectAsState` as its initial value. That is the whole point: DataStore reads are
 * asynchronous, so collecting with a hardcoded default would paint one or more frames in the
 * WRONG theme on every cold start (and flip the status-bar icon contrast with them) before the
 * stored value arrived — the SharedPreferences this replaced were read synchronously and never
 * did that. Blocking here costs the same single small disk read that `getSharedPreferences` did.
 */
suspend fun seedAppearancePrefs(
    settings: SettingsStore,
    legacy: LegacyAppearancePrefs,
    default: AppearanceMode = AppearanceMode.SYSTEM,
): AppearanceSeed {
    // Inside runCatching like the reads below: a corrupt/unreadable DataStore must degrade to the
    // app's own default, never crash `onCreate` (this runs under `runBlocking` before setContent).
    runCatching { migrateAppearancePrefs(settings, legacy) }
    val prefs = UiPrefs(settings)
    return AppearanceSeed(
        appearance = runCatching { prefs.appearance(default).first() }.getOrDefault(default),
        textScale = runCatching { prefs.textScale.first() }.getOrDefault(TEXT_SCALE_DEFAULT),
    )
}
