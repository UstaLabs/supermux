// What's new (#90): after an update, one quiet card offers the release's edition, a page of what this device can now
// do. An edition is one bundled HTML file per release (composeResources/files/whatsnew/<version>.html) whose
// `<script type="application/json" id="edition">` manifest lists its chapters and the platforms each exists on; the
// page renders each chapter's per-platform variant itself.
package dev.supermux.ui.whatsnew

import dev.supermux.state.SettingsStore
import dev.supermux.ui.resources.Res
import dev.supermux.update.compareVersions
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Which build of the app this is, as an edition names it (`on: ["mac", "win"]`). */
enum class ReleasePlatform(val id: String) {
    Mac("mac"), Windows("win"), Linux("linux"), Web("web"), Android("android"), IPhone("iphone"),
}

/** The releases that have an edition, oldest first. A release without one offers nothing. */
val EDITIONS: List<String> = listOf("0.17.7")

@Serializable
data class EditionChapter(val id: String, val on: List<String>? = null)

@Serializable
data class Edition(val version: String, val chapters: List<EditionChapter>) {
    /** The chapters [platform] has; where the platform isn't known, only those every platform has. */
    fun chaptersFor(platform: ReleasePlatform?): List<EditionChapter> =
        chapters.filter { c -> c.on == null || (platform != null && platform.id in c.on) }
}

private val MANIFEST = Regex(
    "<script[^>]*\\bid=\"edition\"[^>]*>(.*?)</script>",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val manifestJson = Json { ignoreUnknownKeys = true }

/** The edition's manifest in [page], or null when it has none. */
fun parseEdition(page: String): Edition? {
    val body = MANIFEST.find(page)?.groupValues?.get(1) ?: return null
    return runCatching { manifestJson.decodeFromString(Edition.serializer(), body.trim()) }.getOrNull()
}

/** [version]'s release notes on GitHub, the full list the edition picks from. */
fun releaseNotesUrl(version: String): String = "https://github.com/UstaLabs/supermux/releases/tag/v$version"

/** The bundled page of [version]'s edition. */
suspend fun loadEdition(version: String): String = Res.readBytes("files/whatsnew/$version.html").decodeToString()

/**
 * When a device is offered What's new: once per version, per device, after an update. A first install is offered
 * nothing (everything is new to it). Opening the page or dismissing the card settles it ([settle]).
 */
class WhatsNewTracker(
    private val settings: SettingsStore,
    private val currentVersion: String,
    private val editions: List<String> = EDITIONS,
) {
    /**
     * The editions to offer, newest first. [existingInstall]: the device already had the app (it has machines
     * paired), which tells an update from a first install the first time this runs.
     */
    suspend fun start(existingInstall: Boolean): List<String> {
        val seen = settings.string(SEEN_KEY).first()
        if (seen == null && !existingInstall) {
            settle()
            return emptyList()
        }
        return editions
            .filter { (seen == null || compareVersions(it, seen) > 0) && compareVersions(it, currentVersion) <= 0 }
            .sortedWith { a, b -> compareVersions(b, a) }
    }

    /** Opened or dismissed: nothing up to this version is offered again on this device. */
    suspend fun settle() {
        settings.putString(SEEN_KEY, currentVersion)
    }

    companion object {
        /** The newest version whose What's new this device has settled. */
        const val SEEN_KEY = "whatsNew:seen"
    }
}
