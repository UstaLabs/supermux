package dev.supermux.webview

data class NavigationRequest(val url: String, val isMainFrame: Boolean, val isUserGesture: Boolean)

enum class NavigationDecision { Allow, Block, OpenExternally }

sealed interface WebDialog {
    val message: String

    data class Alert(override val message: String) : WebDialog
    data class Confirm(override val message: String) : WebDialog
    data class Prompt(override val message: String, val defaultValue: String) : WebDialog
    data class BeforeUnload(override val message: String) : WebDialog
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
 * Answers the page's requests. Every method may suspend (to show a Compose dialog, say), and every default is the
 * safe answer.
 */
interface WebClient {
    /** A main-frame or subframe navigation the page started. [content] is what the view is showing. */
    suspend fun onNavigation(request: NavigationRequest, content: WebContent): NavigationDecision =
        defaultNavigationDecision(request, content)

    suspend fun onDialog(dialog: WebDialog): DialogResult = DialogResult.Dismissed

    /** The chosen files as platform URI strings, or null to cancel. */
    suspend fun onFileChooser(request: FileChooserRequest): List<String>? = null

    suspend fun onPermission(request: PermissionRequest): Boolean = false

    suspend fun onDownload(request: DownloadRequest): Boolean = false

    suspend fun onNewWindow(request: NewWindowRequest): NavigationDecision = NavigationDecision.Block

    companion object {
        val Default: WebClient = object : WebClient {}
    }
}

/**
 * The default navigation policy (spec §9).
 * - Subframes are the document's business.
 * - `javascript:` is blocked.
 * - Non-web schemes open outside the app.
 * - Local content (Html, Bundle) stays on its own origin; anything else opens outside.
 */
fun defaultNavigationDecision(request: NavigationRequest, content: WebContent): NavigationDecision {
    if (!request.isMainFrame) return NavigationDecision.Allow
    return when (request.url.substringBefore(':', "").lowercase()) {
        "about", "data", "blob" -> NavigationDecision.Allow
        "javascript" -> NavigationDecision.Block
        "http", "https" -> when (content) {
            is WebContent.Url -> NavigationDecision.Allow
            is WebContent.Html -> if (content.origin.owns(request.url)) NavigationDecision.Allow else NavigationDecision.OpenExternally
            is WebContent.Bundle -> if (content.origin.owns(request.url)) NavigationDecision.Allow else NavigationDecision.OpenExternally
        }
        else -> NavigationDecision.OpenExternally
    }
}
