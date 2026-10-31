package dev.supermux.webview

public data class NavigationRequest(val url: String, val isMainFrame: Boolean, val isUserGesture: Boolean)

public enum class NavigationDecision { Allow, Block, OpenExternally }

/**
 * What to do with a page's request for a new window (`window.open`, `target=_blank`).
 * - [OpenHere] loads the URL in this view, but only if [WebClient.onNavigation] would allow it as a main-frame
 *   navigation.
 * - [OpenExternally] hands it to the OS, subject to the same scheme rules.
 */
public enum class NewWindowDecision { OpenHere, OpenExternally, Block }

public sealed interface WebDialog {
    public val message: String

    /** The URL of the document that raised the dialog, so the app can say who is asking. */
    public val url: String?

    public data class Alert(override val message: String, override val url: String? = null) : WebDialog
    public data class Confirm(override val message: String, override val url: String? = null) : WebDialog
    public data class Prompt(override val message: String, val defaultValue: String, override val url: String? = null) : WebDialog
    public data class BeforeUnload(override val message: String, override val url: String? = null) : WebDialog
}

public sealed interface DialogResult {
    /** Alert OK, Confirm/Prompt cancel, BeforeUnload "stay on the page". */
    public data object Dismissed : DialogResult
    /** Confirm OK, BeforeUnload "leave the page". */
    public data object Confirmed : DialogResult
    public data class Text(val value: String) : DialogResult
}

public data class FileChooserRequest(val acceptTypes: List<String>, val allowMultiple: Boolean)

public enum class PermissionKind { Camera, Microphone, Geolocation, Notifications, ClipboardRead, Other }

public data class PermissionRequest(val origin: String, val kinds: Set<PermissionKind>)

public data class DownloadRequest(val url: String, val suggestedFileName: String?, val mimeType: String?, val contentLength: Long?)

public data class NewWindowRequest(val url: String, val isUserGesture: Boolean)

/**
 * Answers the page's requests. Every default is the safe answer.
 *
 * [onNavigation] and [onNewWindow] are plain functions, because Android and WKWebView's window creation need the
 * answer synchronously. They may run off the main thread (on desktop, on the engine's own UI thread), so keep them
 * fast, pure and thread-safe. The others may suspend (to show a Compose dialog, say), because every engine can answer
 * them later.
 */
public interface WebClient {
    /**
     * A navigation the page started. [content] is what the view is showing. May run off the main thread: keep it fast,
     * pure and thread-safe.
     */
    public fun onNavigation(request: NavigationRequest, content: WebContent): NavigationDecision =
        defaultNavigationDecision(request, content)

    /** Alert, Confirm and Prompt are dismissed. BeforeUnload leaves the page, so a page can't trap the user. */
    public suspend fun onDialog(dialog: WebDialog): DialogResult =
        if (dialog is WebDialog.BeforeUnload) DialogResult.Confirmed else DialogResult.Dismissed

    /** The chosen files as platform URI strings, or null to cancel. */
    public suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null

    public suspend fun onPermission(request: PermissionRequest): Boolean = false

    public suspend fun onDownload(request: DownloadRequest): Boolean = false

    /** A request for a new window. May run off the main thread: keep it fast, pure and thread-safe. */
    public fun onNewWindow(request: NewWindowRequest): NewWindowDecision = NewWindowDecision.Block

    public companion object {
        public val Default: WebClient = object : WebClient {}
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
 * - A network page may not navigate into a local origin, and local content never hops to another.
 */
public fun defaultNavigationDecision(request: NavigationRequest, content: WebContent): NavigationDecision {
    if (!request.isMainFrame) return NavigationDecision.Allow
    val url = normalizeUrl(request.url)
    val leave = if (request.isUserGesture) NavigationDecision.OpenExternally else NavigationDecision.Block
    val local = content.localOrigin
    return when (schemeOf(url)) {
        "http", "https" -> {
            val host = hostOf(url)
            when {
                host == null -> NavigationDecision.Block
                isReservedOrNonCanonical(host) -> if (local != null && local.owns(url)) NavigationDecision.Allow else NavigationDecision.Block
                local != null -> leave
                else -> NavigationDecision.Allow
            }
        }
        "about" -> {
            val page = url.substringAfter(':').substringBefore('?').substringBefore('#').lowercase()
            if (page == "blank" || page == "srcdoc") NavigationDecision.Allow else NavigationDecision.Block
        }
        "blob" -> if (local == null || local.owns(url.substringAfter(':'))) NavigationDecision.Allow else NavigationDecision.Block
        in EXTERNAL_SCHEMES -> leave
        else -> NavigationDecision.Block
    }
}

/**
 * A host on the reserved `.invalid` TLD, or one no engine hands over for a real site: percent-escapes or non-ASCII.
 * Engines canonicalize hosts to ASCII/punycode, so this only ever catches tricks.
 */
private fun isReservedOrNonCanonical(host: String): Boolean {
    if (host.any { it == '%' || it.code > 0x7e }) return true
    val trimmed = host.trimEnd('.')
    return trimmed == WebOrigin.SUFFIX || trimmed.endsWith(".${WebOrigin.SUFFIX}")
}
