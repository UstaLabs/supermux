package dev.supermux.webview.engine.linux

import dev.supermux.webview.engine.webkit.NavigationKind

/** The glue's events (wv.h), decoded. */
internal sealed interface WpeEvent {
    data class Frame(val seq: Long) : WpeEvent
    data class Load(val event: Long, val uri: String?) : WpeEvent
    data class LoadFailed(val code: Long, val cancelled: Boolean, val tls: Boolean, val policy: Boolean, val cannotShow: Boolean, val uri: String?, val message: String?) : WpeEvent
    data class Title(val title: String?) : WpeEvent
    data class Uri(val uri: String?) : WpeEvent
    data class Progress(val progress: Float) : WpeEvent
    /** [back] oldest first, [forward] nearest first (the glue's order). */
    data class History(val canGoBack: Boolean, val canGoForward: Boolean, val back: List<String>, val forward: List<String>) : WpeEvent
    data class Policy(val decision: Long, val url: String?, val kind: NavigationKind, val newWindow: Boolean, val gesture: Boolean, val redirect: Boolean) : WpeEvent
    data class Response(val decision: Long, val url: String?, val mainFrame: Boolean, val canShow: Boolean, val attachment: Boolean, val status: Int, val mime: String?, val filename: String?) : WpeEvent
    data class Relay(val body: String) : WpeEvent
    data class Eval(val request: Long, val ok: Boolean, val json: String?) : WpeEvent
    data class Crashed(val reason: Long) : WpeEvent
    data class Scheme(val request: Long, val uri: String) : WpeEvent
    data class ImeFocus(val focused: Boolean) : WpeEvent
    data class ImeCursor(val x: Int, val y: Int, val width: Int, val height: Int) : WpeEvent
    data class Cursor(val name: String?) : WpeEvent
    data class DataCleared(val request: Long, val ok: Boolean) : WpeEvent

    /** A script dialog WebKit waits on; [type] is a `WpeNative.DIALOG_*`. */
    data class Dialog(val request: Long, val type: Int, val message: String?, val defaultText: String?) : WpeEvent

    /** A permission WebKit asks for: [kinds] are `WpeNative.PERM_*` bits; [uri] is the page's. */
    data class Permission(val request: Long, val kinds: Long, val uri: String?) : WpeEvent

    data class FileChooser(val request: Long, val multiple: Boolean, val mimeTypes: List<String>) : WpeEvent

    /** The page asked for a window; it got none. */
    data class NewWindow(val url: String?, val gesture: Boolean) : WpeEvent

    /** WebKit found the page's web process unresponsive (it didn't answer in 3 s), or answering again. */
    data class Responsive(val responsive: Boolean) : WpeEvent

    companion object {
        fun decode(kind: Int, a: Long, b: Long, s1: String?, s2: String?): WpeEvent? = when (kind) {
            WpeNative.FRAME -> Frame(a)
            WpeNative.LOAD -> Load(a, s1)
            WpeNative.LOAD_FAILED -> LoadFailed(a, (b and WpeNative.FAIL_CANCELLED) != 0L, (b and WpeNative.FAIL_TLS) != 0L,
                (b and WpeNative.FAIL_POLICY) != 0L, (b and WpeNative.FAIL_CANNOT_SHOW) != 0L, s1, s2)
            WpeNative.TITLE -> Title(s1)
            WpeNative.URI -> Uri(s1)
            WpeNative.PROGRESS -> Progress(a / 1000f)
            WpeNative.HISTORY -> History(a != 0L, b != 0L, lines(s1), lines(s2))
            WpeNative.POLICY -> Policy(a, s1, kindOf(((b shr 4) and 0xf).toInt()), (b and WpeNative.POLICY_NEW_WINDOW) != 0L,
                (b and WpeNative.POLICY_GESTURE) != 0L, (b and WpeNative.POLICY_REDIRECT) != 0L)
            WpeNative.RESPONSE -> {
                val mime = s2?.substringBefore('\n')?.takeIf { it.isNotEmpty() }
                val name = s2?.substringAfter('\n', "")?.takeIf { it.isNotEmpty() }
                Response(a, s1, (b and WpeNative.RESPONSE_MAIN) != 0L, (b and WpeNative.RESPONSE_CAN_SHOW) != 0L,
                    (b and WpeNative.RESPONSE_ATTACHMENT) != 0L, (b shr 8).toInt(), mime, name)
            }
            WpeNative.RELAY -> s1?.let { Relay(it) }
            WpeNative.EVAL -> Eval(a, b == 1L, s1)
            WpeNative.CRASHED -> Crashed(a)
            WpeNative.SCHEME -> s1?.let { Scheme(a, it) }
            WpeNative.IME_FOCUS -> ImeFocus(a != 0L)
            WpeNative.IME_CURSOR -> ImeCursor((a shr 32).toInt(), (a and 0xffffffffL).toInt(), (b shr 32).toInt(), (b and 0xffffffffL).toInt())
            WpeNative.CURSOR -> Cursor(s1)
            WpeNative.DATA_CLEARED -> DataCleared(a, b == 1L)
            WpeNative.DIALOG -> Dialog(a, b.toInt(), s1, s2)
            WpeNative.PERMISSION -> Permission(a, b, s1)
            WpeNative.FILE_CHOOSER -> FileChooser(a, b == 1L, lines(s1))
            WpeNative.NEW_WINDOW -> NewWindow(s1, a == 1L)
            WpeNative.RESPONSIVE -> Responsive(a != 0L)
            else -> null
        }

        /** WebKitNavigationType as NavigationRules' kind (W3). */
        fun kindOf(type: Int): NavigationKind = when (type) {
            WpeNative.NAV_LINK -> NavigationKind.Link
            WpeNative.NAV_FORM -> NavigationKind.FormSubmit
            WpeNative.NAV_BACK_FORWARD -> NavigationKind.BackForward
            WpeNative.NAV_RELOAD -> NavigationKind.Reload
            WpeNative.NAV_FORM_RESUBMIT -> NavigationKind.FormResubmit
            else -> NavigationKind.Other
        }

        private fun lines(text: String?): List<String> = if (text.isNullOrEmpty()) emptyList() else text.split('\n')
    }
}
