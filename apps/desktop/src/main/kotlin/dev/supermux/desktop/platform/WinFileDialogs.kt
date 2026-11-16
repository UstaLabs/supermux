package dev.supermux.desktop.platform

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.lang.ref.Reference
import java.util.concurrent.CompletableFuture

/**
 * Windows' own open and save dialogs (IFileOpenDialog / IFileSaveDialog, #157), where AWT's FileDialog is the old
 * common dialog: no filter by type (a name filter is ignored there), the extension dropped from a suggested name, and
 * the process's working folder to start in. Each dialog runs on a thread of its own, in its own COM apartment.
 *
 * [Outcome.Failed] when COM or the shell said no: the caller falls back to AWT's dialog.
 */
internal object WinFileDialogs {
    /** One "Save as type" / "Files of type" entry: [name] as the user reads it, [patterns] such as `*.png;*.jpg`. */
    class FileType(val name: String, val patterns: String)

    sealed interface Outcome {
        class Picked(val paths: List<String>) : Outcome
        data object Cancelled : Outcome
        class Failed(val why: String) : Outcome
    }

    fun open(title: String, folder: String?, types: List<FileType>, multiple: Boolean): Outcome = onItsOwnThread {
        dialog(CLSID_FILE_OPEN_DIALOG, IID_FILE_OPEN_DIALOG) { d ->
            val options = FOS_FORCEFILESYSTEM or FOS_FILEMUSTEXIST or FOS_PATHMUSTEXIST or
                (if (multiple) FOS_ALLOWMULTISELECT else 0)
            val kept = prepare(d, title, folder, types, options)
            val shown = call(d, SHOW, null)
            Reference.reachabilityFence(kept)
            if (shown == CANCELLED) return@dialog Outcome.Cancelled
            check(shown, "Show")
            val items = PointerByReference()
            check(call(d, GET_RESULTS, items), "GetResults")
            val array = items.value
            try {
                val count = IntByReference()
                check(call(array, ARRAY_GET_COUNT, count), "GetCount")
                val paths = (0 until count.value).mapNotNull { i ->
                    val item = PointerByReference()
                    if (call(array, ARRAY_GET_ITEM_AT, i, item) != S_OK) return@mapNotNull null
                    pathOf(item.value).also { release(item.value) }
                }
                if (paths.isEmpty()) Outcome.Cancelled else Outcome.Picked(paths)
            } finally {
                release(array)
            }
        }
    }

    fun save(title: String, folder: String?, name: String, types: List<FileType>, extension: String?): Outcome = onItsOwnThread {
        dialog(CLSID_FILE_SAVE_DIALOG, IID_FILE_SAVE_DIALOG) { d ->
            val kept = prepare(d, title, folder, types, FOS_FORCEFILESYSTEM or FOS_OVERWRITEPROMPT or FOS_PATHMUSTEXIST)
            check(call(d, SET_FILE_NAME, WString(name)), "SetFileName")
            // The type's extension is added when the user types a name without one.
            if (extension != null) check(call(d, SET_DEFAULT_EXTENSION, WString(extension)), "SetDefaultExtension")
            val shown = call(d, SHOW, null)
            Reference.reachabilityFence(kept)
            if (shown == CANCELLED) return@dialog Outcome.Cancelled
            check(shown, "Show")
            val item = PointerByReference()
            check(call(d, GET_RESULT, item), "GetResult")
            try {
                pathOf(item.value)?.let { Outcome.Picked(listOf(it)) } ?: Outcome.Failed("no file-system path")
            } finally {
                release(item.value)
            }
        }
    }

    /** Title, options, types and folder; returns what must stay alive until the dialog closes (the types' strings). */
    private fun prepare(d: Pointer, title: String, folder: String?, types: List<FileType>, options: Int): List<Memory> {
        val keep = ArrayList<Memory>()
        val current = IntByReference()
        check(call(d, GET_OPTIONS, current), "GetOptions")
        check(call(d, SET_OPTIONS, current.value or options), "SetOptions")
        check(call(d, SET_TITLE, WString(title)), "SetTitle")
        if (types.isNotEmpty()) {
            // COMDLG_FILTERSPEC[]: two wide-string pointers each; the strings live until the dialog closes.
            val specs = Memory(types.size * 2L * Native.POINTER_SIZE).also(keep::add)
            types.forEachIndexed { i, type ->
                val name = wide(type.name).also(keep::add)
                val patterns = wide(type.patterns).also(keep::add)
                specs.setPointer(i * 2L * Native.POINTER_SIZE, name)
                specs.setPointer((i * 2L + 1) * Native.POINTER_SIZE, patterns)
            }
            check(call(d, SET_FILE_TYPES, types.size, specs), "SetFileTypes")
            check(call(d, SET_FILE_TYPE_INDEX, 1), "SetFileTypeIndex")
        }
        if (folder != null) {
            val item = PointerByReference()
            val made = shell32.getFunction("SHCreateItemFromParsingName")
                .invokeInt(arrayOf(WString(folder), null, guid(IID_SHELL_ITEM), item))
            // A folder that's gone: the dialog's own choice of start.
            if (made == S_OK) {
                try {
                    call(d, SET_FOLDER, item.value)
                } finally {
                    release(item.value)
                }
            }
        }
        return keep
    }

    private fun pathOf(item: Pointer): String? {
        val text = PointerByReference()
        if (call(item, ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, text) != S_OK) return null
        return try {
            text.value.getWideString(0)
        } finally {
            ole32.getFunction("CoTaskMemFree").invokeVoid(arrayOf(text.value))
        }
    }

    private fun dialog(clsid: String, iid: String, use: (Pointer) -> Outcome): Outcome {
        val made = PointerByReference()
        val hr = ole32.getFunction("CoCreateInstance")
            .invokeInt(arrayOf(guid(clsid), null, CLSCTX_INPROC_SERVER, guid(iid), made))
        if (hr != S_OK) return Outcome.Failed("CoCreateInstance 0x%08x".format(hr))
        return try {
            use(made.value)
        } catch (failed: ComFailure) {
            Outcome.Failed(failed.message ?: "COM")
        } finally {
            release(made.value)
        }
    }

    /** A thread with a single-threaded apartment, as the shell's dialogs need; the caller waits for the user. */
    private fun onItsOwnThread(body: () -> Outcome): Outcome {
        val result = CompletableFuture<Outcome>()
        Thread({
            val init = ole32.getFunction("CoInitializeEx").invokeInt(arrayOf(null, COINIT_APARTMENTTHREADED))
            try {
                result.complete(runCatching(body).getOrElse { Outcome.Failed(it.toString()) })
            } finally {
                if (init == S_OK || init == S_FALSE) ole32.getFunction("CoUninitialize").invokeVoid(emptyArray())
            }
        }, "supermux-file-dialog").apply { isDaemon = true }.start()
        return result.get()
    }

    private class ComFailure(message: String) : Exception(message)

    private fun check(hr: Int, what: String) {
        if (hr != S_OK) throw ComFailure("$what 0x%08x".format(hr))
    }

    /** Calls the COM method at [slot] of [self]'s vtable. */
    private fun call(self: Pointer, slot: Int, vararg args: Any?): Int {
        val vtable = self.getPointer(0)
        val method = Function.getFunction(vtable.getPointer(slot.toLong() * Native.POINTER_SIZE))
        return method.invokeInt(arrayOf(self, *args))
    }

    private fun release(self: Pointer?) {
        if (self != null) call(self, RELEASE)
    }

    private fun wide(text: String): Memory {
        val chars = text.toCharArray()
        return Memory((chars.size + 1) * 2L).apply {
            write(0, chars, 0, chars.size)
            setChar(chars.size * 2L, '\u0000')
        }
    }

    /** A GUID's 16 bytes from its text, `{xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx}`. */
    internal fun guidBytes(text: String): ByteArray {
        val hex = text.trim('{', '}').split('-')
        val bytes = ByteArray(16)
        val data1 = hex[0].toLong(16)
        for (i in 0 until 4) bytes[i] = (data1 shr (8 * i)).toByte()
        val data2 = hex[1].toInt(16)
        bytes[4] = data2.toByte(); bytes[5] = (data2 shr 8).toByte()
        val data3 = hex[2].toInt(16)
        bytes[6] = data3.toByte(); bytes[7] = (data3 shr 8).toByte()
        val rest = hex[3] + hex[4]
        for (i in 0 until 8) bytes[8 + i] = rest.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return bytes
    }

    private fun guid(text: String): Memory = Memory(16).apply { write(0, guidBytes(text), 0, 16) }

    private val ole32 by lazy { NativeLibrary.getInstance("ole32") }
    private val shell32 by lazy { NativeLibrary.getInstance("shell32") }

    private const val S_OK = 0
    private const val S_FALSE = 1
    private const val CANCELLED = 0x800704C7.toInt() // HRESULT_FROM_WIN32(ERROR_CANCELLED)
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()

    private const val FOS_OVERWRITEPROMPT = 0x2
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_ALLOWMULTISELECT = 0x200
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000

    private const val CLSID_FILE_OPEN_DIALOG = "{DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7}"
    private const val CLSID_FILE_SAVE_DIALOG = "{C0B4E2F3-BA21-4773-8DBA-335EC946EB8B}"
    private const val IID_FILE_OPEN_DIALOG = "{D57C7288-D4AD-4768-BE02-9D969532D960}"
    private const val IID_FILE_SAVE_DIALOG = "{84BCCD23-5FDE-4CDB-AEA4-AF64B83D78AB}"
    private const val IID_SHELL_ITEM = "{43826D1E-E718-42EE-BC55-A1E261C37BFE}"

    // Vtable slots: IUnknown 0–2, IModalWindow 3, IFileDialog 4–26, IFileOpenDialog 27–28.
    private const val RELEASE = 2
    private const val SHOW = 3
    private const val SET_FILE_TYPES = 4
    private const val SET_FILE_TYPE_INDEX = 5
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_FOLDER = 12
    private const val SET_FILE_NAME = 15
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20
    private const val SET_DEFAULT_EXTENSION = 22
    private const val GET_RESULTS = 27

    // IShellItem: GetDisplayName 5. IShellItemArray: GetCount 7, GetItemAt 8.
    private const val ITEM_GET_DISPLAY_NAME = 5
    private const val ARRAY_GET_COUNT = 7
    private const val ARRAY_GET_ITEM_AT = 8
}
