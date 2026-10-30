package dev.supermux.webview.engine.mac

import dev.supermux.webview.PermissionKind
import dev.supermux.webview.engine.webkit.NavigationKind
import dev.supermux.webview.engine.webkit.navigationKindOf

/** WebKit's page as the glue saw it at an event: its URL, title, history (oldest first) and whether it loads. */
internal data class WebKitPage(
    val url: String?,
    val title: String?,
    val back: List<String>,
    val forward: List<String>,
    val loading: Boolean,
) {
    companion object {
        val Empty: WebKitPage = WebKitPage(null, null, emptyList(), emptyList(), loading = false)
    }
}

/** Which JavaScript dialog the page opened. */
internal enum class DialogKind { Alert, Confirm, Prompt }

/**
 * The glue's upcalls (D5), decoded from [MacGlue.onEvent]'s fields: Task 3's table, which `WVEvent` in
 * `native/macos/WVGlue.h` repeats. Change both together.
 */
internal sealed interface GlueEvent {
    /** A page the glue made: [handle] is what every call names it by. */
    data class Created(val handle: Long) : GlueEvent

    data class Started(val epoch: Int, val url: String?) : GlueEvent

    data class Committed(val epoch: Int, val page: WebKitPage, val progress: Float) : GlueEvent

    /** [floorKept]: the glue holds the floor's state, kept the first time the floor finished loading. */
    data class Finished(val epoch: Int, val page: WebKitPage, val floorKept: Boolean) : GlueEvent

    data class Failed(val epoch: Int, val url: String?, val domain: String, val code: Long, val description: String) : GlueEvent

    data class Policy(
        val question: Long,
        val url: String?,
        val mainFrame: Boolean,
        val opensWindow: Boolean,
        val download: Boolean,
        val kind: NavigationKind,
    ) : GlueEvent

    /** [status] only for an HTTP response; [length] only when known. */
    data class Response(
        val question: Long,
        val url: String?,
        val forMainFrame: Boolean,
        val canShow: Boolean,
        val status: Int?,
        val length: Long?,
        val mimeType: String?,
        val suggestedFilename: String?,
        val disposition: String?,
    ) : GlueEvent

    /** A key-value change of WebKit's URL, title, loading state or history, in the committed document's epoch. */
    data class State(val epoch: Int, val page: WebKitPage) : GlueEvent

    data class Relay(val epoch: Int, val mainFrame: Boolean, val body: String, val protocol: String, val host: String, val port: Int) : GlueEvent

    data class NewWindow(val url: String?) : GlueEvent

    data class Dialog(val question: Long, val kind: DialogKind, val message: String, val defaultText: String?, val frameUrl: String?) : GlueEvent

    data class Media(val question: Long, val kinds: Set<PermissionKind>, val protocol: String, val host: String, val port: Int) : GlueEvent

    data class OpenPanel(val question: Long, val multiple: Boolean, val directories: Boolean) : GlueEvent

    data class TaskStart(val task: Long, val url: String?) : GlueEvent

    data class TaskStop(val task: Long) : GlueEvent

    /** An evaluation's [json], a repaint, a saved state's [data], or a wipe: [ok] unless WebKit failed it. */
    class Result(val request: Long, val ok: Boolean, val json: String?, val data: ByteArray?) : GlueEvent

    /** [rgba]: [width] × [height] pixels, RGBA, premultiplied, top row first; null if WebKit gave no image. */
    class Snapshot(val request: Long, val width: Int, val height: Int, val rgba: ByteArray?) : GlueEvent

    /** A display tick; [inWindow]: the page's view was in a window, not hidden, at it. */
    data class Tick(val request: Long, val inWindow: Boolean) : GlueEvent

    data object Crashed : GlueEvent

    /** The page took a press at ([xPoints], [yPoints]) from its window content's top-left; −1, −1 for a key it took. */
    data class Pointer(val xPoints: Double, val yPoints: Double) : GlueEvent

    data object Memory : GlueEvent

    companion object {
        const val CREATED: Int = 1
        const val STARTED: Int = 2
        const val COMMITTED: Int = 3
        const val FINISHED: Int = 4
        const val FAILED: Int = 5
        const val POLICY: Int = 6
        const val RESPONSE: Int = 7
        const val STATE: Int = 8
        const val RELAY: Int = 9
        const val NEW_WINDOW: Int = 10
        const val DIALOG: Int = 11
        const val MEDIA: Int = 12
        const val OPEN_PANEL: Int = 13
        const val TASK_START: Int = 14
        const val TASK_STOP: Int = 15
        const val RESULT: Int = 16
        const val SNAPSHOT: Int = 17
        const val TICK: Int = 18
        const val CRASHED: Int = 19
        const val POINTER: Int = 20
        const val MEMORY: Int = 21

        /** `COMMITTED`, `FINISHED`, `STATE`: WebKit is loading. */
        const val LOADING: Int = 1

        /** `FINISHED`: the glue holds the floor's state. */
        const val FLOOR_KEPT: Int = 2

        /** The event the glue sent, or null for a type this library doesn't know. */
        fun decode(
            type: Int,
            epoch: Int,
            id: Long,
            flags: Int,
            x: Double,
            y: Double,
            a: String?,
            b: String?,
            c: String?,
            d: String?,
            data: ByteArray?,
        ): GlueEvent? = when (type) {
            CREATED -> Created(id)
            STARTED -> Started(epoch, a)
            COMMITTED -> Committed(epoch, page(flags, a, b, c, d), x.toFloat())
            FINISHED -> Finished(epoch, page(flags, a, b, c, d), floorKept = flags and FLOOR_KEPT != 0)
            FAILED -> Failed(epoch, a, b.orEmpty(), x.toLong(), c.orEmpty())
            POLICY -> Policy(
                question = id,
                url = a,
                mainFrame = flags and 1 != 0,
                opensWindow = flags and 2 != 0,
                download = flags and 4 != 0,
                kind = navigationKindOf(((flags shr 8) - 1).toLong()),
            )
            RESPONSE -> Response(
                question = id,
                url = a,
                forMainFrame = flags and 1 != 0,
                canShow = flags and 2 != 0,
                status = if (flags and 4 != 0) flags shr 8 else null,
                length = if (x >= 0) x.toLong() else null,
                mimeType = b,
                suggestedFilename = c,
                disposition = d,
            )
            STATE -> State(epoch, page(flags, a, b, c, d))
            RELAY -> Relay(epoch, flags and 1 != 0, a.orEmpty(), b.orEmpty(), c.orEmpty(), x.toInt())
            NEW_WINDOW -> NewWindow(a)
            DIALOG -> Dialog(
                question = id,
                kind = when (flags) {
                    1 -> DialogKind.Confirm
                    2 -> DialogKind.Prompt
                    else -> DialogKind.Alert
                },
                message = a.orEmpty(),
                defaultText = b,
                frameUrl = c,
            )
            MEDIA -> Media(
                question = id,
                kinds = when (flags) {
                    0 -> setOf(PermissionKind.Camera)
                    1 -> setOf(PermissionKind.Microphone)
                    2 -> setOf(PermissionKind.Camera, PermissionKind.Microphone)
                    else -> setOf(PermissionKind.Other)
                },
                protocol = a.orEmpty(),
                host = b.orEmpty(),
                port = x.toInt(),
            )
            OPEN_PANEL -> OpenPanel(id, multiple = flags and 1 != 0, directories = flags and 2 != 0)
            TASK_START -> TaskStart(id, a)
            TASK_STOP -> TaskStop(id)
            RESULT -> Result(id, ok = flags and 1 != 0, json = a, data = data)
            SNAPSHOT -> Snapshot(id, x.toInt(), y.toInt(), data)
            TICK -> Tick(id, inWindow = flags and 1 != 0)
            CRASHED -> Crashed
            POINTER -> Pointer(x, y)
            MEMORY -> Memory
            else -> null
        }

        private fun page(flags: Int, url: String?, back: String?, forward: String?, title: String?): WebKitPage =
            WebKitPage(url, title, lines(back), lines(forward), loading = flags and LOADING != 0)

        /** URLs joined by `\n` (a URL never holds a raw newline), oldest first. */
        private fun lines(joined: String?): List<String> = if (joined.isNullOrEmpty()) emptyList() else joined.split('\n')
    }
}
