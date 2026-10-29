package dev.supermux.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Default public manifest polled by native clients (and the broker). */
const val DEFAULT_VERSIONS_URL = "https://supermux.dev/versions.json"

/** One-shot GitHub fallback when versions.json is unreachable. */
const val DEFAULT_GITHUB_LATEST_URL =
    "https://api.github.com/repos/UstaLabs/supermux/releases/latest"

/** One release by tag (`…/tags/v0.16.1`) — where an available update's "What's new" is read. */
const val DEFAULT_GITHUB_RELEASE_BY_TAG_URL =
    "https://api.github.com/repos/UstaLabs/supermux/releases/tags/"

private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

// ─── Manifest models ──────────────────────────────────────────────────────────

@Serializable
data class VersionAsset(
    val url: String = "",
    val sha256: String = "",
)

@Serializable
data class ClientVersionInfo(
    val version: String = "",
    val versionCode: Int? = null,
    val build: Int? = null,
)

@Serializable
data class ClientsInfo(
    val android: ClientVersionInfo? = null,
    val desktop: ClientVersionInfo? = null,
    val ios: ClientVersionInfo? = null,
)

@Serializable
data class ChannelInfo(
    val version: String = "",
    val publishedAt: String = "",
    val notesUrl: String = "",
    val assets: Map<String, VersionAsset> = emptyMap(),
    val clients: ClientsInfo? = null,
)

@Serializable
data class ChannelsWrapper(
    val stable: ChannelInfo = ChannelInfo(),
    /** Opt-in prerelease train (vX.Y.Z-alpha.N tags); absent until the first alpha ships. */
    val alpha: ChannelInfo? = null,
)

@Serializable
data class VersionsManifest(
    val schemaVersion: Int = 1,
    val channels: ChannelsWrapper = ChannelsWrapper(),
)

// ─── Platform + status ────────────────────────────────────────────────────────

enum class ClientPlatform {
    ANDROID,
    DESKTOP_LINUX,
    DESKTOP_WINDOWS,
    DESKTOP_MACOS,
    IOS,
}

/** Asset key inside channels.stable.assets for one-click installers. Empty for iOS. */
fun ClientPlatform.assetKey(): String = when (this) {
    ClientPlatform.ANDROID -> "android"
    ClientPlatform.DESKTOP_LINUX -> "desktop-linux"
    ClientPlatform.DESKTOP_WINDOWS -> "desktop-windows"
    // Compose Multiplatform mac client — the only Mac client. The key is historical (it once sat
    // beside a `desktop-macos` entry for the retired native SwiftUI Supermux.app) and is kept so
    // installed clients keep finding their updates.
    ClientPlatform.DESKTOP_MACOS -> "compose-desktop-macos"
    ClientPlatform.IOS -> ""
}

/** GitHub release asset filename used by the fallback path. */
fun ClientPlatform.githubAssetName(): String = when (this) {
    ClientPlatform.ANDROID -> "supermux-android.apk"
    ClientPlatform.DESKTOP_LINUX -> "supermux-linux.deb"
    ClientPlatform.DESKTOP_WINDOWS -> "supermux-windows.msi"
    ClientPlatform.DESKTOP_MACOS -> "supermux-desktop-macos.dmg"
    ClientPlatform.IOS -> ""
}

/** Whether this platform supports one-click sideload install from a release asset. */
fun ClientPlatform.canSideloadInstall(): Boolean = this != ClientPlatform.IOS

data class ClientUpdateStatus(
    val currentVersion: String,
    val currentVersionCode: Int? = null,
    val latestVersion: String? = null,
    val latestVersionCode: Int? = null,
    val updateAvailable: Boolean = false,
    val notesUrl: String? = null,
    val downloadUrl: String? = null,
    val sha256: String? = null,
    val lastError: String? = null,
    /** False on iOS (App Store / TestFlight only). */
    val canInstall: Boolean = false,
    /**
     * "What's new" for [latestVersion]: the release notes' bullets, cleaned by
     * [parseReleaseHighlights]. Empty when there is no update or the notes could not be read —
     * the notes are a nicety, so a failed read never fails the check.
     */
    val highlights: List<String> = emptyList(),
)

// ─── Release notes → "What's new" ─────────────────────────────────────────────

/** `by @user in https://…/pull/42` — GitHub's generated-notes byline. */
private val GENERATED_BYLINE = Regex("""\s+by @[\w.-]+(\[bot])?\s+in\s+https?://\S+$""")

/** `(f1621945)` / `(abc1234, def5678)` — the commit shas a release PR's bullets end on. */
private val TRAILING_SHAS = Regex("""\s*\((?:[0-9a-f]{7,40}(?:,\s*)?)+\)$""")

/** `feat(chat):` / `fix!:` — a conventional-commit prefix (bold markers already removed). */
private val COMMIT_PREFIX = Regex("""^([a-z]+)(\([^)]*\))?!?:\s*""")

/** `Release v0.16.1:` — the release PR's own title when the notes are only GitHub's generated list. */
private val RELEASE_PREFIX = Regex("""^release\s+v?\d+\.\d+\.\d+\S*:?\s*""", RegexOption.IGNORE_CASE)

/** `#101:` — an issue number leading a bullet. */
private val ISSUE_PREFIX = Regex("""^#\d+:?\s*""")

/** `[text](url)` → `text`. */
private val MD_LINK = Regex("""\[([^\]]+)]\([^)]*\)""")

/** Commit kinds that say nothing to someone deciding whether to update. */
private val INTERNAL_KINDS = setOf("chore", "ci", "test", "tests", "build", "docs", "style", "refactor")

/**
 * The bullets of a release's notes, each cut down to what a user reads: no PR byline, no commit
 * shas, no `feat(scope):` / `#101:` / `Release v1.2.3:` prefix, no markdown, first letter upper
 * (unless the first word has its own capitals, like `iOS`).
 * Internal-only kinds (`chore:`, `ci:`, …) are dropped. Works on both shapes a release body has
 * had — GitHub's generated `* Title by @x in url` list and the release PR's `- **feat(x):** …`
 * bullets — in order, without repeats; when both are present only the first list counts.
 */
fun parseReleaseHighlights(body: String?): List<String> {
    if (body.isNullOrBlank()) return emptyList()
    val out = mutableListOf<String>()
    for (raw in body.lineSequence()) {
        val line = raw.trim()
        // The release job writes the PR's bullets under "What's new" ABOVE GitHub's generated
        // "What's Changed" list, which only repeats them as PR titles.
        if (out.isNotEmpty() && line.startsWith("#") && line.contains("what's changed", ignoreCase = true)) break
        if (!line.startsWith("- ") && !line.startsWith("* ")) continue
        cleanHighlight(line.substring(2))?.let { if (it !in out) out += it }
    }
    return out
}

private fun cleanHighlight(raw: String): String? {
    var s = raw.trim()
        .replace(GENERATED_BYLINE, "")
        .replace(TRAILING_SHAS, "")
        .replace(MD_LINK, "$1")
        .replace("**", "")
        .replace("`", "")
        .trim()
    COMMIT_PREFIX.find(s)?.let { m ->
        if (m.groupValues[1] in INTERNAL_KINDS) return null
        s = s.substring(m.range.last + 1)
    }
    s = s.replace(RELEASE_PREFIX, "").replace(ISSUE_PREFIX, "").trim().trimEnd('.')
    if (s.isEmpty()) return null
    // "the strip…" → "The strip…", but a word that already carries its own capitals (iOS) stays.
    val firstWord = s.substringBefore(' ')
    return if (firstWord.any { it.isUpperCase() }) s else s.replaceFirstChar { it.uppercaseChar() }
}

// ─── Semver-lite (mirrors src/core/update/versions.ts) ────────────────────────

private data class ParsedVersion(val core: List<Int>, val prerelease: String?)

private fun parseVersion(v: String): ParsedVersion? {
    val trimmed = v.trim()
    if (trimmed.isEmpty()) return null
    val dash = trimmed.indexOf('-')
    val coreStr = if (dash == -1) trimmed else trimmed.substring(0, dash)
    val prerelease = if (dash == -1) null else trimmed.substring(dash + 1)
    val core = coreStr.split('.').map { seg ->
        if (seg.isEmpty() || !seg.all { it.isDigit() }) return null
        seg.toInt()
    }
    if (core.isEmpty()) return null
    return ParsedVersion(core, prerelease)
}

/** Compare two version strings. Returns -1 / 0 / 1. Unparseable ranks lowest. */
fun compareVersions(a: String, b: String): Int {
    val pa = parseVersion(a)
    val pb = parseVersion(b)
    if (pa == null && pb == null) return 0
    if (pa == null) return -1
    if (pb == null) return 1
    val len = maxOf(pa.core.size, pb.core.size)
    for (i in 0 until len) {
        val av = pa.core.getOrElse(i) { 0 }
        val bv = pb.core.getOrElse(i) { 0 }
        if (av < bv) return -1
        if (av > bv) return 1
    }
    if (pa.prerelease == null && pb.prerelease == null) return 0
    if (pa.prerelease == null) return 1
    if (pb.prerelease == null) return -1
    return comparePrerelease(pa.prerelease, pb.prerelease)
}

/**
 * semver §11: dot-separated identifiers, numeric ones compared as numbers (alpha.2 < alpha.10),
 * numeric below alphanumeric, and a shorter list below a longer one it prefixes.
 */
private fun comparePrerelease(a: String, b: String): Int {
    val xs = a.split('.')
    val ys = b.split('.')
    for (i in 0 until minOf(xs.size, ys.size)) {
        val x = xs[i]
        val y = ys[i]
        val xn = x.toLongOrNull()?.takeIf { x.all(Char::isDigit) }
        val yn = y.toLongOrNull()?.takeIf { y.all(Char::isDigit) }
        val cmp = when {
            xn != null && yn != null -> xn.compareTo(yn)
            xn != null -> -1
            yn != null -> 1
            else -> x.compareTo(y)
        }
        if (cmp != 0) return if (cmp < 0) -1 else 1
    }
    return xs.size.compareTo(ys.size).coerceIn(-1, 1)
}

/** Does [version] carry a prerelease suffix (0.12.0-alpha.1)? False for "dev"/unparseable. */
fun isPrerelease(version: String): Boolean = parseVersion(version)?.prerelease != null

/**
 * The channel a build follows, decided by its OWN version: a prerelease build follows
 * `channels.alpha`, everything else follows `channels.stable`. Opting in to the alpha is
 * installing an alpha build; a stable build can never be offered one.
 */
fun VersionsManifest.channelFor(currentVersion: String): ChannelInfo =
    channels.alpha?.takeIf { isPrerelease(currentVersion) } ?: channels.stable

/**
 * Is [latest] strictly newer than [current]?
 * Always false when [current] is unparseable ("dev", etc.).
 */
fun isUpdateAvailable(current: String, latest: String): Boolean {
    if (parseVersion(current) == null) return false
    return compareVersions(latest, current) > 0
}

// ─── Checker ──────────────────────────────────────────────────────────────────

/**
 * Fetch versions.json (GitHub fallback) and decide whether a client update is available.
 *
 * Comparison prefers client-specific marketing versions from the build's channel (`channelFor`) `clients`
 * when present; otherwise falls back to that channel's release tag.
 * Android also compares [currentVersionCode] against `clients.android.versionCode` when both set.
 */
class ClientUpdateChecker(
    private val http: HttpClient,
    private val versionsUrl: String = DEFAULT_VERSIONS_URL,
    private val fallbackUrl: String = DEFAULT_GITHUB_LATEST_URL,
    private val releaseByTagUrl: String = DEFAULT_GITHUB_RELEASE_BY_TAG_URL,
) {
    suspend fun check(
        platform: ClientPlatform,
        currentVersion: String,
        currentVersionCode: Int? = null,
        currentBuild: Int? = null,
    ): ClientUpdateStatus {
        return try {
            checkPrimary(platform, currentVersion, currentVersionCode, currentBuild)
        } catch (primary: Throwable) {
            try {
                checkFallback(platform, currentVersion, currentVersionCode, currentBuild)
            } catch (fb: Throwable) {
                ClientUpdateStatus(
                    currentVersion = currentVersion,
                    currentVersionCode = currentVersionCode,
                    lastError = "${primary.message ?: primary}; fallback: ${fb.message ?: fb}",
                    canInstall = platform.canSideloadInstall(),
                )
            }
        }
    }

    /**
     * Download [url] fully into memory. Installer sizes are modest (APK/deb/msi/dmg).
     *
     * [onProgress] is invoked as bytes stream in: `(bytesReceived, contentLength?)`.
     * `contentLength` is null when the server omits Content-Length.
     */
    suspend fun download(
        url: String,
        onProgress: (suspend (bytesReceived: Long, contentLength: Long?) -> Unit)? = null,
    ): ByteArray {
        val resp = http.get(url) {
            if (onProgress != null) {
                onDownload { bytesSentTotal, contentLength ->
                    onProgress(bytesSentTotal, contentLength)
                }
            }
        }
        if (!resp.status.isSuccess()) error("download HTTP ${resp.status.value}")
        return resp.bodyAsBytes()
    }

    private suspend fun checkPrimary(
        platform: ClientPlatform,
        currentVersion: String,
        currentVersionCode: Int?,
        currentBuild: Int?,
    ): ClientUpdateStatus {
        val text = http.get(versionsUrl).let { resp ->
            if (!resp.status.isSuccess()) error("versions.json HTTP ${resp.status.value}")
            resp.bodyAsText()
        }
        val manifest = json.decodeFromString(VersionsManifest.serializer(), text)
        val channel = manifest.channelFor(currentVersion)
        val clientInfo = clientInfoFor(platform, channel.clients)
        val latestVersion = clientInfo?.version?.takeIf { it.isNotBlank() }
            ?: channel.version.takeIf { it.isNotBlank() }
        val latestCode = clientInfo?.versionCode
        val latestBuild = clientInfo?.build
        val assetKey = platform.assetKey()
        val asset = if (assetKey.isNotEmpty()) channel.assets[assetKey] else null
        val available = computeAvailable(
            platform = platform,
            currentVersion = currentVersion,
            currentVersionCode = currentVersionCode,
            currentBuild = currentBuild,
            latestVersion = latestVersion,
            latestVersionCode = latestCode,
            latestBuild = latestBuild,
        )
        // The notes live on the release TAG (channel.version), which a client-specific marketing
        // version (iOS 1.5) is not.
        val highlights = if (available) releaseHighlights(channel.version) else emptyList()
        return ClientUpdateStatus(
            currentVersion = currentVersion,
            currentVersionCode = currentVersionCode,
            latestVersion = latestVersion,
            latestVersionCode = latestCode,
            updateAvailable = available,
            notesUrl = channel.notesUrl.ifBlank { null },
            downloadUrl = asset?.url?.ifBlank { null },
            sha256 = asset?.sha256?.ifBlank { null },
            canInstall = platform.canSideloadInstall() && !asset?.url.isNullOrBlank(),
            highlights = highlights,
        )
    }

    /** The release's "What's new", or nothing — never a reason for the check itself to fail. */
    private suspend fun releaseHighlights(tag: String): List<String> {
        if (tag.isBlank()) return emptyList()
        return try {
            val resp = http.get(releaseByTagUrl + "v" + tag.removePrefix("v")) {
                header("Accept", "application/vnd.github+json")
            }
            if (!resp.status.isSuccess()) return emptyList()
            parseReleaseHighlights(json.decodeFromString(GitHubLatest.serializer(), resp.bodyAsText()).body)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private suspend fun checkFallback(
        platform: ClientPlatform,
        currentVersion: String,
        currentVersionCode: Int?,
        currentBuild: Int?,
    ): ClientUpdateStatus {
        val text = http.get(fallbackUrl) {
            header("Accept", "application/vnd.github+json")
        }.let { resp ->
            if (!resp.status.isSuccess()) error("fallback HTTP ${resp.status.value}")
            resp.bodyAsText()
        }
        val body = json.decodeFromString(GitHubLatest.serializer(), text)
        val tag = body.tagName?.removePrefix("v")?.takeIf { it.isNotBlank() }
            ?: error("fallback missing tag_name")
        val assetName = platform.githubAssetName()
        val assetUrl = if (assetName.isNotEmpty()) {
            body.assets.firstOrNull { it.name == assetName }?.browserDownloadUrl
        } else null
        val available = isUpdateAvailable(currentVersion, tag)
        return ClientUpdateStatus(
            currentVersion = currentVersion,
            currentVersionCode = currentVersionCode,
            latestVersion = tag,
            updateAvailable = available,
            notesUrl = body.htmlUrl
                ?: "https://github.com/UstaLabs/supermux/releases/tag/v$tag",
            downloadUrl = assetUrl,
            canInstall = platform.canSideloadInstall() && !assetUrl.isNullOrBlank(),
            highlights = if (available) parseReleaseHighlights(body.body) else emptyList(),
        )
    }

    private fun clientInfoFor(platform: ClientPlatform, clients: ClientsInfo?): ClientVersionInfo? {
        if (clients == null) return null
        return when (platform) {
            ClientPlatform.ANDROID -> clients.android
            ClientPlatform.DESKTOP_LINUX,
            ClientPlatform.DESKTOP_WINDOWS,
            ClientPlatform.DESKTOP_MACOS -> clients.desktop
            ClientPlatform.IOS -> clients.ios
        }
    }

    private fun computeAvailable(
        platform: ClientPlatform,
        currentVersion: String,
        currentVersionCode: Int?,
        currentBuild: Int?,
        latestVersion: String?,
        latestVersionCode: Int?,
        latestBuild: Int?,
    ): Boolean {
        if (latestVersion == null) return false
        // Prefer integer build numbers when both sides have them (Android versionCode / iOS build).
        if (platform == ClientPlatform.ANDROID &&
            currentVersionCode != null && latestVersionCode != null
        ) {
            return latestVersionCode > currentVersionCode
        }
        if (platform == ClientPlatform.IOS &&
            currentBuild != null && latestBuild != null
        ) {
            return latestBuild > currentBuild
        }
        return isUpdateAvailable(currentVersion, latestVersion)
    }
}

@Serializable
private data class GitHubLatest(
    @SerialName("tag_name") val tagName: String? = null,
    @SerialName("html_url") val htmlUrl: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)
