package dev.supermux.ui.browser

import dev.supermux.state.SettingsStore
import dev.supermux.webview.PermissionKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * One answer the user gave a site (#157): [origin] may, or may not ([allow]), use [kind] on this device. Data, not a
 * flag per site, so it can be listed, changed, forgotten — and later carry whose answer it was ([by]) when a device is
 * shared by a team; [at] is when, in epoch milliseconds.
 */
@Serializable
data class SiteDecision(
    val origin: String,
    val kind: String,
    val allow: Boolean,
    val at: Long,
    /** Who answered: null while a device has one user (today); a member's id once a team shares it. */
    val by: String? = null,
)

/**
 * The sites' permissions on this device (#157): asked once per site and kind, remembered here, and changed or forgotten
 * in Settings › Privacy. Per device, as a browser keeps them: a grant of the camera is this device's camera.
 *
 * Only the kinds a page can name are remembered ([remembered]); anything else is asked each time.
 */
class SitePermissions(private val settings: SettingsStore, private val now: () -> Long) {
    private val lock = Mutex()

    /** Every answer, newest first. */
    val decisions: Flow<List<SiteDecision>> = settings.string(KEY).map(::decode)

    /** The answer for [kind] at [origin], or null when the user hasn't given one. */
    suspend fun decisionOf(origin: String, kind: PermissionKind): Boolean? =
        decisions.first().firstOrNull { it.origin == origin && it.kind == kind.name }?.allow

    /** Remembers [allow] for each of [kinds] at [origin], replacing what was there. */
    suspend fun decide(origin: String, kinds: Set<PermissionKind>, allow: Boolean, by: String? = null) = lock.withLock {
        val names = kinds.filter { it in remembered }.map { it.name }.toSet()
        if (names.isEmpty()) return@withLock
        val at = now()
        val kept = decisions.first().filterNot { it.origin == origin && it.kind in names }
        val added = names.map { SiteDecision(origin, it, allow, at, by) }
        settings.putString(KEY, encode(added + kept))
    }

    /** Forgets [kind] at [origin] (or every kind there, with none): the site asks again. */
    suspend fun forget(origin: String, kind: String? = null) = lock.withLock {
        val kept = decisions.first().filterNot { it.origin == origin && (kind == null || it.kind == kind) }
        settings.putString(KEY, encode(kept).takeIf { kept.isNotEmpty() })
    }

    suspend fun forgetAll() = lock.withLock { settings.putString(KEY, null) }

    companion object {
        const val KEY = "browser:sitePermissions"

        /** What a site can be answered for once: what the page names. [PermissionKind.Other] is asked each time. */
        val remembered: Set<PermissionKind> = setOf(
            PermissionKind.Camera,
            PermissionKind.Microphone,
            PermissionKind.Geolocation,
            PermissionKind.Notifications,
            PermissionKind.ClipboardRead,
        )

        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(SiteDecision.serializer())

        private fun decode(text: String?): List<SiteDecision> =
            text?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }.orEmpty()

        private fun encode(list: List<SiteDecision>): String = json.encodeToString(serializer, list)
    }
}
