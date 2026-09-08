package dev.supermux.settings

import dev.supermux.state.LauncherDraft
import dev.supermux.state.LauncherPrefs
import dev.supermux.state.SettingsKeys
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The SwiftUI app's `UserDefaults` values, read out verbatim (cluster H2).
 *
 * Everything here is already typed the way `NSUserDefaults` stored it — `ipad.sidebar.width` is a
 * number, `cmux:collapsed-paths` is an array, `cmux:launcher-prefs` is JSON `Data`. Reading is the
 * iOS half's job ([readLegacySwiftPrefs] in `iosMain`); deciding what any of it MEANS is
 * [swiftPrefMigrations], which is pure and therefore testable on the JVM.
 *
 * A field is null when the key was absent. Absent ≠ default: a key the user never touched must not
 * be written into the shared store at all, or the shared default could never change again.
 */
data class LegacySwiftPrefs(
    /** `appearance`: "system" / "light" / "dark". */
    val appearance: String? = null,
    /** `chatDetailLevel`: "low" / "medium" / "high" — already the shared wire form. */
    val chatDetailLevel: String? = null,
    /** `cmux:draft:<sessionId>` → the unsent text, keyed by session id (prefix already stripped). */
    val drafts: Map<String, String> = emptyMap(),
    /** `cmux:editor:fontSize`. */
    val editorFontSize: Int? = null,
    /** `cmux:editor:lineWrap`. */
    val editorLineWrap: Boolean? = null,
    /** `ipad.sidebar.width`. */
    val sidebarWidthDp: Double? = null,
    /** `ipad.sidebar.collapsed`. */
    val sidebarCollapsed: Boolean? = null,
    /** `cmux:collapsed-paths`, as the array of workdir strings Swift stored. */
    val collapsedPaths: List<String>? = null,
    /** `fleet_host_filter`; Swift writes `""` for "no filter". */
    val hostFilter: String? = null,
    /** `cmux:launcher-prefs`, as the UTF-8 of the `Data` blob Swift's `JSONEncoder` wrote. */
    val launcherPrefsJson: String? = null,
    /** `cmux:launcher-draft`, likewise. */
    val launcherDraftJson: String? = null,
)

/**
 * What to write into the shared [SettingsKeys] store, given what the SwiftUI app left behind.
 *
 * Returns `key to value` pairs in no particular order; the caller writes each one only when the
 * shared store has nothing under that key yet (the same one-way, idempotent, non-destructive rule
 * Android's `seedAppearancePrefs` and desktop's `ui-state.json` drain follow — a value the user
 * changed after the upgrade always wins).
 *
 * Three deliberate omissions:
 *
 *  - **`appearance` is uppercased.** Swift stored the SwiftUI-facing lowercase form; the shared key
 *    holds `AppearanceMode.name`. `chatDetailLevel` is NOT uppercased — `ChatDetailLevel.wire` is
 *    already lowercase, so the two spellings only look inconsistent.
 *  - **An unrecognised value is dropped, not passed through.** A garbage `appearance` written into
 *    the shared key would read back as "never chosen" anyway, but it would also permanently block
 *    this migration from running again for that key.
 *  - **The launcher blobs are PARSED before they are copied.** Swift's `LauncherPrefs`/`LauncherDraft`
 *    are field-for-field the Kotlin ones, so the JSON is expected to be identical — but "expected"
 *    is not a guarantee across two independently maintained `Codable`/`@Serializable` pairs, and
 *    silently storing something the Kotlin decoder cannot read would leave the launcher looking
 *    empty with no way to tell why. Round-tripping proves it first; anything that fails is dropped.
 */
fun swiftPrefMigrations(legacy: LegacySwiftPrefs): List<Pair<String, String>> = buildList {
    legacy.appearance?.let { raw ->
        when (raw.lowercase()) {
            "system" -> "SYSTEM"
            "light" -> "LIGHT"
            "dark" -> "DARK"
            else -> null
        }?.let { add(SettingsKeys.APPEARANCE to it) }
    }
    legacy.chatDetailLevel?.let { raw ->
        if (raw in CHAT_DETAIL_WIRE) add(SettingsKeys.CHAT_DETAIL_LEVEL to raw)
    }
    // A blank draft is not a draft. Swift left the key behind after the composer was cleared.
    for ((sessionId, text) in legacy.drafts) {
        if (sessionId.isNotEmpty() && text.isNotEmpty()) add(SettingsKeys.draft(sessionId) to text)
    }
    // Both editor values are Strings in the shared store even though they are an Int and a Bool
    // here — `SettingsStore` is string-valued, and `UiPrefs` parses and clamps on the way back out.
    legacy.editorFontSize?.let { add(SettingsKeys.EDITOR_FONT_SIZE to it.toString()) }
    legacy.editorLineWrap?.let { add(SettingsKeys.EDITOR_LINE_WRAP to it.toString()) }
    legacy.sidebarWidthDp?.let { add(SettingsKeys.SHELL_SIDEBAR_WIDTH to it.toFloat().toString()) }
    legacy.sidebarCollapsed?.let { add(SettingsKeys.SHELL_SIDEBAR_COLLAPSED to it.toString()) }
    legacy.collapsedPaths?.let { paths ->
        // Sorted, matching `UiPrefs.putCollapsedProjectPaths` — the same set must never produce two
        // different byte strings, or every launch would look like a change to the store.
        add(SettingsKeys.SESSION_LIST_COLLAPSED_PATHS to json.encodeToString(stringList, paths.distinct().sorted()))
    }
    // "" is Swift's "no filter"; the shared store expresses that as an absent key.
    legacy.hostFilter?.takeIf { it.isNotBlank() }?.let { add(SettingsKeys.HOST_FILTER to it) }
    legacy.launcherPrefsJson
        ?.let { raw -> runCatching { json.decodeFromString<LauncherPrefs>(raw) }.getOrNull()?.let { raw } }
        ?.let { add(SettingsKeys.LAUNCHER_PREFS to it) }
    legacy.launcherDraftJson
        ?.let { raw -> runCatching { json.decodeFromString<LauncherDraft>(raw) }.getOrNull() }
        // An empty draft clears the key on the shared side rather than storing `{}`; carrying one
        // over would be storing emptiness, so it is simply not migrated.
        ?.takeIf { it != LauncherDraft() }
        ?.let { add(SettingsKeys.LAUNCHER_DRAFT to json.encodeToString(it)) }
}

/** `ChatDetailLevel.wire` lives in `:ui`, which `:shared` cannot see; the three values do not move. */
private val CHAT_DETAIL_WIRE = setOf("low", "medium", "high")

/** Configured exactly like `UiPrefs`' own `prefsJson`, so what this writes is what that reads. */
private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private val stringList = ListSerializer(String.serializer())
