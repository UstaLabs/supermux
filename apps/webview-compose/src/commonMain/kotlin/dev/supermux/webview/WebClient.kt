package dev.supermux.webview

data class NavigationRequest(val url: String, val isMainFrame: Boolean, val isUserGesture: Boolean)

enum class NavigationDecision { Allow, Block, OpenExternally }

/** What to do with a page's request for a new window (`window.open`, `target=_blank`). */
enum class NewWindowDecision { OpenHere, OpenExternally, Block }

sealed interface WebDialog {
    val message: String

    /** The URL of the document that raised the dialog, so the app can say who is asking. */
    val url: String?

    data class Alert(override val message: String, override val url: String? = null) : WebDialog
    data class Confirm(override val message: String, override val url: String? = null) : WebDialog
    data class Prompt(override val message: String, val defaultValue: String, override val url: String? = null) : WebDialog
    data class BeforeUnload(override val message: String, override val url: String? = null) : WebDialog
}

sealed interface DialogResult {
    /** Alert OK, Confirm/Prompt cancel, BeforeUnload "stay on the page". */
    data object Dismissed : DialogResult
    /** Confirm OK, BeforeUnload "leave the page". */
    data object Confirmed : DialogResult
    data class Text(val value: String) : DialogResult
}

data class FileChooserRequest(val acceptTypes: List<String>, val allowMultiple: Boolean)

enum class PermissionKind { Camera, Microphone, Geolocation, Notifications, ClipboardRead, Other }

data class PermissionRequest(val origin: String, val kinds: Set<PermissionKind>)

data class DownloadRequest(val url: String, val suggestedFileName: String?, val mimeType: String?, val contentLength: Long?)

data class NewWindowRequest(val url: String, val isUserGesture: Boolean)

/**
 * Answers the page's requests. Every default is the safe answer.
 *
 * [onNavigation] and [onNewWindow] are plain functions, because Android and WKWebView's window creation need the
 * answer synchronously. The others may suspend (to show a Compose dialog, say), because every engine can answer
 * them later.
 */
interface WebClient {
    /** A navigation the page started. [content] is what the view is showing. */
    fun onNavigation(request: NavigationRequest, content: WebContent): NavigationDecision =
        defaultNavigationDecision(request, content)

    /** Alert, Confirm and Prompt are dismissed. BeforeUnload leaves the page, so a page can't trap the user. */
    suspend fun onDialog(dialog: WebDialog): DialogResult =
        if (dialog is WebDialog.BeforeUnload) DialogResult.Confirmed else DialogResult.Dismissed

    /** The chosen files as platform URI strings, or null to cancel. */
    suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null

    suspend fun onPermission(request: PermissionRequest): Boolean = false

    suspend fun onDownload(request: DownloadRequest): Boolean = false

    fun onNewWindow(request: NewWindowRequest): NewWindowDecision = NewWindowDecision.Block

    companion object {
        val Default: WebClient = object : WebClient {}
    }
}

/** Schemes the default policy hands to the OS, and only on a user gesture. Every other non-web scheme is blocked. */
internal val EXTERNAL_SCHEMES: Set<String> = setOf("mailto", "tel", "sms")

/**
 * The default navigation policy (spec §9). It parses URLs the way a browser does.
 * - Subframes are the document's business.
 * - Leaving the app (OpenExternally) needs a user gesture, and only http(s), mailto, tel and sms ever leave.
 * - `javascript:`, `data:`, `file:`, scheme-less URLs and unknown schemes are blocked.
 * - Local content (Html, Bundle) stays on its own origin, its own blob: URLs and about:blank/srcdoc.
 * - A network page may not navigate into a local origin.
 */
fun defaultNavigationDecision(request: NavigationRequest, content: WebContent): NavigationDecision {
    if (!request.isMainFrame) return NavigationDecision.Allow
    val url = normalizeUrl(request.url)
    val leave = if (request.isUserGesture) NavigationDecision.OpenExternally else NavigationDecision.Block
    val local = content.localOrigin
    return when (schemeOf(url)) {
        "http", "https" -> when {
            local != null -> if (local.owns(url)) NavigationDecision.Allow else leave
            hostOf(url)?.endsWith(".${WebOrigin.SUFFIX}") == true -> NavigationDecision.Block
            else -> NavigationDecision.Allow
        }
        "about" ->
            if (url.equals("about:blank", ignoreCase = true) || url.equals("about:srcdoc", ignoreCase = true)) {
                NavigationDecision.Allow
            } else {
                NavigationDecision.Block
            }
        "blob" -> if (local == null || local.owns(url.substringAfter(':'))) NavigationDecision.Allow else NavigationDecision.Block
        in EXTERNAL_SCHEMES -> leave
        else -> NavigationDecision.Block
    }
}
