package dev.supermux.update

import io.ktor.client.HttpClient
import io.ktor.client.plugins.onDownload
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Default public manifest polled by native clients (and the broker). */
const val DEFAULT_VERSIONS_URL = "https://supermux.dev/versions.json"

/** One-shot GitHub fallback when versions.json is unreachable. */
const val DEFAULT_GITHUB_LATEST_URL =
    "https://api.github.com/repos/UstaLabs/supermux/releases/latest"

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
    // Compose Multiplatform mac client. (Native SwiftUI Supermux.app keeps the
    // historical `desktop-macos` → supermux-macos.dmg mapping for AppUpdateView.)
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
)

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
    return pa.prerelease.compareTo(pb.prerelease)
}

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
 * Comparison prefers client-specific marketing versions from `channels.stable.clients`
 * when present; otherwise falls back to the release tag (`channels.stable.version`).
 * Android also compares [currentVersionCode] against `clients.android.versionCode` when both set.
 */
class ClientUpdateChecker(
    private val http: HttpClient,
    private val versionsUrl: String = DEFAULT_VERSIONS_URL,
    private val fallbackUrl: String = DEFAULT_GITHUB_LATEST_URL,
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
        val stable = manifest.channels.stable
        val clientInfo = clientInfoFor(platform, stable.clients)
        val latestVersion = clientInfo?.version?.takeIf { it.isNotBlank() }
            ?: stable.version.takeIf { it.isNotBlank() }
        val latestCode = clientInfo?.versionCode
        val latestBuild = clientInfo?.build
        val assetKey = platform.assetKey()
        val asset = if (assetKey.isNotEmpty()) stable.assets[assetKey] else null
        val available = computeAvailable(
            platform = platform,
            currentVersion = currentVersion,
            currentVersionCode = currentVersionCode,
            currentBuild = currentBuild,
            latestVersion = latestVersion,
            latestVersionCode = latestCode,
            latestBuild = latestBuild,
        )
        return ClientUpdateStatus(
            currentVersion = currentVersion,
            currentVersionCode = currentVersionCode,
            latestVersion = latestVersion,
            latestVersionCode = latestCode,
            updateAvailable = available,
            notesUrl = stable.notesUrl.ifBlank { null },
            downloadUrl = asset?.url?.ifBlank { null },
            sha256 = asset?.sha256?.ifBlank { null },
            canInstall = platform.canSideloadInstall() && !asset?.url.isNullOrBlank(),
        )
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
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
private data class GitHubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
)
