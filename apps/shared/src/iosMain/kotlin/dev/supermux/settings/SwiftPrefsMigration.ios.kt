package dev.supermux.settings

import dev.supermux.util.toByteArray
import platform.Foundation.NSData
import platform.Foundation.NSUserDefaults

/**
 * Copy the SwiftUI app's preferences onto the shared [dev.supermux.state.SettingsKeys] once
 * (cluster H2).
 *
 * Both shells store their preferences in the same `NSUserDefaults` domain under DIFFERENT keys, so
 * an upgrade to the Compose shell would otherwise open with the default theme, the default chat
 * density, no collapsed groups and an empty launcher — every one of which the user can see. The
 * pairing itself is not migrated here; that is the Keychain's job
 * ([dev.supermux.host.KeychainHostPersistence]) and it needs no migration at all because Kotlin
 * reads the very items Swift wrote.
 *
 * Three properties, matching the seeding both other hosts already do:
 *  - **Once.** [MARKER_KEY] is set afterwards, so a user who changes a setting and relaunches does
 *    not get the Swift value copied back over it.
 *  - **Non-destructive.** A shared key that already holds something is never overwritten, so even a
 *    marker lost to a reinstall-over cannot undo a post-upgrade choice.
 *  - **One-way.** Nothing is deleted from the Swift keys. The SwiftUI shell that wrote them is
 *    gone (cluster H6), but a downgrade to a build that still had it must keep working.
 *
 * Reading is here; the MAPPING is [swiftPrefMigrations] in commonMain, where it is unit-tested
 * against the exact payloads the Swift app writes.
 *
 * @return how many keys were written (0 on every launch after the first).
 */
fun migrateSwiftPrefsOnce(defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults): Int {
    if (defaults.boolForKey(MARKER_KEY)) return 0
    var written = 0
    for ((key, value) in swiftPrefMigrations(readLegacySwiftPrefs(defaults))) {
        // The shared SettingsStore on iOS IS this defaults domain, so "already set" is a plain
        // object lookup — no suspension, and therefore safe to run before the first frame.
        if (defaults.objectForKey(key) == null) {
            defaults.setObject(value, forKey = key)
            written++
        }
    }
    defaults.setBool(true, forKey = MARKER_KEY)
    return written
}

/**
 * The SwiftUI app's stored values, read out of [defaults] in the shapes Swift wrote them.
 *
 * Each read distinguishes "absent" from "false"/"0": `boolForKey` on a missing key returns false and
 * `doubleForKey` returns 0, either of which would be silently migrated as if the user had chosen it
 * — a sidebar width of zero, or soft wrap turned off for someone who never touched it. Hence the
 * `objectForKey(...) != null` guard in front of every primitive.
 */
internal fun readLegacySwiftPrefs(defaults: NSUserDefaults): LegacySwiftPrefs {
    fun present(key: String) = defaults.objectForKey(key) != null
    return LegacySwiftPrefs(
        appearance = defaults.stringForKey("appearance"),
        chatDetailLevel = defaults.stringForKey("chatDetailLevel"),
        drafts = readDrafts(defaults),
        editorFontSize = if (present("cmux:editor:fontSize")) defaults.integerForKey("cmux:editor:fontSize").toInt() else null,
        editorLineWrap = if (present("cmux:editor:lineWrap")) defaults.boolForKey("cmux:editor:lineWrap") else null,
        sidebarWidthDp = if (present("ipad.sidebar.width")) defaults.doubleForKey("ipad.sidebar.width") else null,
        sidebarCollapsed = if (present("ipad.sidebar.collapsed")) defaults.boolForKey("ipad.sidebar.collapsed") else null,
        // Swift stored a plain array of workdir strings; anything else in there is not ours.
        collapsedPaths = defaults.arrayForKey("cmux:collapsed-paths")?.filterIsInstance<String>(),
        hostFilter = defaults.stringForKey("fleet_host_filter"),
        // `Data`, not `String` — Swift's JSONEncoder wrote the blob straight into defaults.
        launcherPrefsJson = (defaults.objectForKey("cmux:launcher-prefs") as? NSData)?.toByteArray()?.decodeToString(),
        launcherDraftJson = (defaults.objectForKey("cmux:launcher-draft") as? NSData)?.toByteArray()?.decodeToString(),
    )
}

/**
 * Every `cmux:draft:<sessionId>` in the domain, keyed by session id.
 *
 * The whole domain has to be enumerated because the session ids are not knowable in advance — there
 * is no index of which chats have an unsent draft. `dictionaryRepresentation` also returns the
 * values inherited from the argument/global domains, which is harmless here: nothing outside this
 * app writes a `cmux:draft:` key.
 */
private fun readDrafts(defaults: NSUserDefaults): Map<String, String> =
    defaults.dictionaryRepresentation()
        .entries
        .mapNotNull { (key, value) ->
            val name = key as? String ?: return@mapNotNull null
            if (!name.startsWith(DRAFT_PREFIX)) return@mapNotNull null
            val text = value as? String ?: return@mapNotNull null
            name.removePrefix(DRAFT_PREFIX) to text
        }
        .toMap()

private const val DRAFT_PREFIX = "cmux:draft:"

/** Set once the copy has run. Namespaced `kmp:` so it cannot collide with a Swift key. */
internal const val MARKER_KEY = "kmp:prefsMigrated"
