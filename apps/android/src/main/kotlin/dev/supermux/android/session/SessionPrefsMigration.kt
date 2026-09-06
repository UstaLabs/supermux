// One-time move of the session list's collapsed project groups out of Android's
// `cmux-session-list` SharedPreferences and into the shared [SettingsStore] (cluster F1).
//
// Same contract as `settings/AppearancePrefsMigration.kt`: read the old file once, write ONLY the
// key the shared store does not have yet, never destroy anything. A user who collapsed a project
// group months ago keeps it collapsed after the upgrade; a user who changed it post-upgrade keeps
// the new choice; re-running this on every launch is a no-op.
//
// The launcher's own prefs/draft need no migration on this platform — Android already stored them
// under `SettingsKeys.LAUNCHER_PREFS` / `LAUNCHER_DRAFT`, which is exactly where `UiPrefs` reads.
// Only desktop's `launcher-state.json` has to be drained (`AppShell` → `UiPrefs.seedLauncher`).
package dev.supermux.android.session

import android.content.Context
import dev.supermux.state.SettingsStore
import dev.supermux.ui.prefs.UiPrefs
import dev.supermux.ui.prefs.seedCollapsedProjectPaths

/** The pre-F1 collapsed set, as read out of `cmux-session-list`. Empty when there was none. */
fun readLegacyCollapsedPaths(context: Context): Set<String> =
    context.applicationContext
        .getSharedPreferences(COLLAPSE_PREFS, Context.MODE_PRIVATE)
        .getStringSet(COLLAPSE_KEY, emptySet())
        ?.toSet()
        .orEmpty()

/**
 * Migrate (see [seedCollapsedProjectPaths]) and then READ the collapsed set the list needs.
 *
 * `MainActivity` calls this from `onCreate`, before `setContent`, for the same reason the
 * appearance seed is blocking there: a DataStore read is asynchronous, so collecting it with an
 * empty default would paint the first frames of the list with every project group EXPANDED before
 * the stored set arrived — the SharedPreferences this replaced were read synchronously inside
 * `remember` and never did that. Failures degrade to "nothing collapsed" rather than taking
 * `onCreate` down with them.
 */
suspend fun seedSessionListPrefs(settings: SettingsStore, legacyCollapsed: Set<String>): Set<String> =
    runCatching { UiPrefs(settings).seedCollapsedProjectPaths(legacyCollapsed) }.getOrDefault(emptySet())
