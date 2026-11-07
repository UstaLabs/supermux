package dev.supermux.webview.engine.windows

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The glue's events (wv2.h Ev), decoded. `epoch` is the native stamp: the navigation's, the document's or the secret's. */
internal sealed interface WinEvent {
    data class Created(val ok: Boolean, val hresult: Long, val detail: String?) : WinEvent
    data class Environment(val request: Long, val hresult: Long, val version: String?) : WinEvent
    data class NavStarting(val epoch: Int, val id: Long, val url: String?, val mainFrame: Boolean, val redirect: Boolean, val user: Boolean, val kind: Int, val verdict: Int) : WinEvent
    /** [frameSeq]: the capture's newest frame at the commit; the frames after it are the new document's. */
    data class Committed(val epoch: Int, val id: Long, val errorPage: Boolean, val url: String?, val frameSeq: Long = -1) : WinEvent
    data class SourceChanged(val epoch: Int, val newDocument: Boolean, val url: String?) : WinEvent
    data class History(val epoch: Int, val canGoBack: Boolean, val canGoForward: Boolean) : WinEvent
    data class Title(val epoch: Int, val title: String?) : WinEvent
    data class DomLoaded(val epoch: Int, val id: Long) : WinEvent
    data class Completed(val epoch: Int, val id: Long, val success: Boolean, val webError: Int, val httpStatus: Int, val url: String?) : WinEvent
    /** [body]: what follows the secret, `<16 hex> <kind>[ <payload>]` (RelayMessage's format). */
    data class Message(val epoch: Int, val body: String, val arrivedNs: Long = 0L) : WinEvent
    data class Result(val request: Long, val ok: Boolean, val json: String?) : WinEvent
    data class Frame(val seq: Long) : WinEvent
    data class Focus(val focused: Boolean) : WinEvent
    data class MoveFocus(val reason: Int) : WinEvent
    /** [systemId]: an `IDC_*` value, 0 for [custom]. */
    data class Cursor(val systemId: Int, val custom: CustomCursor?) : WinEvent
    data class Resource(val epoch: Int, val request: Long, val url: String, val method: String) : WinEvent
    data class Dialog(val epoch: Int, val question: Long, val kind: Int, val message: String?, val defaultText: String?, val uri: String?) : WinEvent
    data class Permission(val epoch: Int, val question: Long, val kind: Int, val user: Boolean, val uri: String?) : WinEvent
    data class Download(val epoch: Int, val url: String?, val mime: String?, val fileName: String?) : WinEvent
    data class NewWindow(val epoch: Int, val url: String?, val user: Boolean) : WinEvent
    data class ProcessFailed(val kind: Int) : WinEvent
    data class DevToolsEvent(val epoch: Int, val name: String, val json: String?) : WinEvent
    data object Memory : WinEvent
    data object BrowserExited : WinEvent
    data class ScriptReady(val epoch: Int, val hresult: Long) : WinEvent
    data class Log(val line: String) : WinEvent

    /** A CSS `url()` cursor's pixels, ARGB, top row first. */
    class CustomCursor(val width: Int, val height: Int, val hotX: Int, val hotY: Int, val argb: IntArray)

    companion object {
        const val IDC_ARROW: Int = 32512

        fun decode(type: Int, epoch: Int, id: Long, flags: Long, a: String?, b: String?, c: String?, data: ByteArray?): WinEvent? = when (type) {
            WinGlue.CREATED -> Created(id == 1L, flags, a)
            WinGlue.ENVIRONMENT -> Environment(id, flags, a)
            WinGlue.NAV_STARTING -> NavStarting(
                epoch, id, a,
                mainFrame = flags and WinGlue.NAV_MAIN_FRAME.toLong() != 0L,
                redirect = flags and WinGlue.NAV_REDIRECT.toLong() != 0L,
                user = flags and WinGlue.NAV_USER.toLong() != 0L,
                kind = (((flags shr 4) and 0xF) - 1).toInt(),
                verdict = ((flags shr 16) and 0xF).toInt(),
            )
            WinGlue.COMMITTED -> Committed(epoch, id, flags and 1L != 0L, a, flags shr 1)
            WinGlue.SOURCE_CHANGED -> SourceChanged(epoch, flags and 1L != 0L, a)
            WinGlue.HISTORY -> History(epoch, flags and 1L != 0L, flags and 2L != 0L)
            WinGlue.TITLE -> Title(epoch, a)
            WinGlue.DOM_LOADED -> DomLoaded(epoch, id)
            WinGlue.COMPLETED -> Completed(epoch, id, flags and 1L != 0L, ((flags shr 8) and 0xFF).toInt(), ((flags shr 16) and 0xFFFF).toInt(), a)
            WinGlue.MESSAGE -> a?.let { Message(epoch, it, arrivedNs = id) }
            WinGlue.RESULT -> Result(id, flags and 1L != 0L, a)
            WinGlue.FRAME -> Frame(id)
            WinGlue.FOCUS -> Focus(flags and 1L != 0L)
            WinGlue.MOVE_FOCUS -> MoveFocus(flags.toInt())
            WinGlue.CURSOR -> if (flags != 0L) Cursor(flags.toInt(), null) else customCursor(data)
            WinGlue.RESOURCE -> if (a != null && b != null) Resource(epoch, id, a, b) else null
            WinGlue.DIALOG -> Dialog(epoch, id, flags.toInt(), a, b, c)
            WinGlue.PERMISSION -> Permission(epoch, id, (flags and 0xFF).toInt(), flags and 0x100L != 0L, a)
            WinGlue.DOWNLOAD -> Download(epoch, a, b, c)
            WinGlue.NEW_WINDOW -> NewWindow(epoch, a, flags and 1L != 0L)
            WinGlue.PROCESS_FAILED -> ProcessFailed(flags.toInt())
            WinGlue.DEVTOOLS_EVENT -> a?.let { DevToolsEvent(epoch, it, b) }
            WinGlue.MEMORY -> Memory
            WinGlue.BROWSER_EXITED -> BrowserExited
            WinGlue.SCRIPT_READY -> ScriptReady(epoch, flags)
            WinGlue.LOG -> Log(a.orEmpty())
            else -> null
        }

        /** The glue's packing: width, height, hotX, hotY, then the ARGB pixels, int32 little-endian; the arrow if it doesn't fit. */
        private fun customCursor(data: ByteArray?): Cursor {
            if (data == null || data.size < 16) return Cursor(IDC_ARROW, null)
            val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
            val width = buffer.int
            val height = buffer.int
            val hotX = buffer.int
            val hotY = buffer.int
            if (width <= 0 || height <= 0 || width > 256 || height > 256 || buffer.remaining() != width * height * 4) return Cursor(IDC_ARROW, null)
            val argb = IntArray(width * height) { buffer.int }
            return Cursor(0, CustomCursor(width, height, hotX, hotY, argb))
        }
    }
}
