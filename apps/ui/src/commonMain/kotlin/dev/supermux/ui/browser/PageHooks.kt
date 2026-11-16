package dev.supermux.ui.browser

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.supermux.fs.fileNameOf
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.platform.DeviceGrant
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.platform.Platform
import dev.supermux.ui.prefs.InMemorySettingsStore
import dev.supermux.ui.prefs.LocalUiPrefsOrNull
import dev.supermux.ui.work.rememberDownloads
import dev.supermux.webview.DownloadFailure
import dev.supermux.webview.DownloadRequest
import dev.supermux.webview.DownloadTarget
import dev.supermux.webview.FileChooserRequest
import dev.supermux.webview.PermissionKind
import dev.supermux.webview.PermissionRequest
import dev.supermux.webview.WebDownload
import dev.supermux.webview.WebDownloadState
import dev.supermux.work.DownloadCenter
import dev.supermux.work.DownloadPurpose
import dev.supermux.work.DownloadSource
import dev.supermux.work.DownloadedFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.random.Random
import kotlin.time.Clock

/** Who a page's requests come from: a Browser tab, an applet. [label] names it where the user sees it. */
data class PageSource(val id: String, val label: String)

/** A permission a page asks for, waiting on the user's answer: [answer] it once. */
class PermissionPrompt internal constructor(val origin: String, val kinds: Set<PermissionKind>) {
    internal val answered = CompletableDeferred<Boolean>()

    fun answer(allow: Boolean) {
        answered.complete(allow)
    }
}

/**
 * A page's requests answered the app's way (#157), for every web view that shows the web: Browser tabs, applets.
 * - [download]: the engine saves it with the page's session into [Platform.page]'s folder, and the fleet's
 *   [DownloadCenter] lists it (#95): its progress, pause and resume, and the hand-over once it is done.
 * - [chooseFiles]: the system picker, with the input's types and multiple choice.
 * - [permission]: asked once per site and kind ([prompt], which the host shows), remembered in [sites], and backed by
 *   the OS's own grant.
 */
class PageHooks(
    private val platform: Platform,
    private val downloads: DownloadCenter,
    private val sites: SitePermissions,
    val source: PageSource,
) {
    private val prompts = Mutex()
    private val asking = MutableStateFlow<PermissionPrompt?>(null)

    /** The permission question the host shows now, or null. */
    val prompt: StateFlow<PermissionPrompt?> = asking.asStateFlow()

    suspend fun download(request: DownloadRequest): DownloadTarget? {
        val dir = platform.page.downloadsDir ?: return null
        val name = downloadName(request)
        val mime = request.mimeType?.substringBefore(';')?.trim()?.ifEmpty { null } ?: platform.files.probeMime(name)
        // A folder of its own: the file keeps its real name, and two downloads of one name never meet.
        val id = Random.nextLong().toULong().toString(16)
        val path = "${dir.trimEnd('/', '\\')}/$id/$name"
        return DownloadTarget(path) { transfer ->
            // Refused before a byte (a form's answer on Android): no Retry could fetch it, so the reason is all there is to say.
            val refused = transfer.state.value as? WebDownloadState.Failed
            if (refused != null) {
                platform.notices.show(failureText(refused))
                return@DownloadTarget
            }
            // Nobody waits for it here: its finish is announced (a notice), and the downloads list hands it over.
            downloads.start("page:${source.id}:$id", name, mime, DownloadPurpose.File, WebDownloadSource(transfer, name, mime)).release()
            // Said where the user is: a phone's tab fills the screen, and the downloads list is on Home.
            platform.notices.show(localized(Str.page_download_started, name))
        }
    }

    suspend fun chooseFiles(request: FileChooserRequest): List<String>? =
        platform.page.pickForPage(request.acceptTypes, request.allowMultiple, localized(Str.page_pick_title))
            ?.takeIf { it.isNotEmpty() }

    suspend fun permission(request: PermissionRequest): Boolean {
        val origin = request.origin
        val kinds = request.kinds
        if (kinds.isEmpty()) return false
        val known = kinds.associateWith { if (it in SitePermissions.remembered) sites.decisionOf(origin, it) else null }
        val allowed = when {
            known.values.any { it == false } -> return false
            known.values.all { it == true } -> true
            else -> prompts.withLock { ask(origin, kinds) }
        }
        if (!allowed) return false
        // The user said yes; the OS may still say no for the app (Android's runtime permissions): said where they are.
        if (kinds.all { kind -> deviceGrantOf(kind)?.let { platform.page.grantOs(it) } ?: true }) return true
        platform.notices.show(localized(Str.page_permission_os_denied))
        return false
    }

    /** One question at a time; the answer is remembered for every kind a site can be answered for. */
    private suspend fun ask(origin: String, kinds: Set<PermissionKind>): Boolean {
        // Answered meanwhile, by the question before this one.
        val known = kinds.map { if (it in SitePermissions.remembered) sites.decisionOf(origin, it) else null }
        if (known.any { it == false }) return false
        if (known.all { it == true }) return true
        val prompt = PermissionPrompt(origin, kinds)
        asking.value = prompt
        try {
            val allow = prompt.answered.await()
            sites.decide(origin, kinds, allow)
            return allow
        } finally {
            asking.compareAndSet(prompt, null)
        }
    }
}

/** The OS's guard over [kind], where it has one. */
private fun deviceGrantOf(kind: PermissionKind): DeviceGrant? = when (kind) {
    PermissionKind.Camera -> DeviceGrant.Camera
    PermissionKind.Microphone -> DeviceGrant.Microphone
    PermissionKind.Geolocation -> DeviceGrant.Location
    else -> null
}

/** A file name for [request]: the engine's, else the URL's last part, never a path or an empty name. */
internal fun downloadName(request: DownloadRequest): String {
    val fromUrl = request.url.substringBefore('?').substringBefore('#').substringAfterLast('/') // platform-ok: url
        .takeIf { !request.url.startsWith("data:") && !request.url.startsWith("blob:") }
    val raw = request.suggestedFileName?.ifBlank { null } ?: fromUrl?.ifBlank { null } ?: "download"
    val clean = fileNameOf(raw)
        .filter { it.code >= 0x20 && it !in "<>:\"|?*" }
        .trim().trimStart('.')
    return clean.ifEmpty { "download" }.take(200)
}

/**
 * A page's download as the [DownloadCenter] fetches one: the engine's transfer, already running. The list's Pause
 * pauses it ([cancel]), Retry resumes it ([fetch] again), Cancel ([dismiss]) stops it and throws away what arrived.
 */
internal class WebDownloadSource(
    private val transfer: WebDownload,
    private val name: String,
    private val mime: String,
) : DownloadSource {
    /** Whether the list has fetched it before: only a Retry resumes it, never the first fetch of one that failed at once. */
    private var fetched = false

    override suspend fun fetch(progress: (received: Long, total: Long?) -> Unit): DownloadedFile? {
        val now = transfer.state.value
        if (fetched && (now is WebDownloadState.Paused || now is WebDownloadState.Failed)) transfer.resume()
        fetched = true
        val end = transfer.state
            .onEach { progress(it.received, it.total) }
            .first { it is WebDownloadState.Done || it is WebDownloadState.Failed || it is WebDownloadState.Cancelled }
        return when (end) {
            is WebDownloadState.Done -> DownloadedFile(name, mime, end.received, transfer.path)
            is WebDownloadState.Failed -> throw PageDownloadFailed(failureText(end))
            else -> null
        }
    }

    override fun cancel() = transfer.pause()

    override fun dismiss(running: Boolean) = transfer.cancel()
}

private class PageDownloadFailed(message: String) : Exception(message)

/** Why [failed] failed, in the app's words where the library names the reason, else the engine's. */
internal fun failureText(failed: WebDownloadState.Failed): String = when (failed.reason) {
    DownloadFailure.FormPost -> localized(Str.page_download_form_post)
    else -> failed.message
}

/**
 * [PageHooks] for the page of [source], with the fleet's downloads and this device's site permissions: what a Browser
 * tab or an applet answers its page with.
 */
@Composable
fun rememberPageHooks(source: PageSource): PageHooks {
    val platform = LocalPlatform.current
    val downloads = rememberDownloads()
    val sites = rememberSitePermissions()
    return remember(source.id, platform, downloads, sites) { PageHooks(platform, downloads, sites, source) }
}

/** This device's [SitePermissions], in the app's stored settings (in memory where there are none: a test). */
@Composable
fun rememberSitePermissions(): SitePermissions {
    val prefs = LocalUiPrefsOrNull.current
    return remember(prefs) { SitePermissions(prefs?.settings ?: InMemorySettingsStore()) { Clock.System.now().toEpochMilliseconds() } }
}
