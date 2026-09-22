package dev.supermux.terminal

// Browser actual: the st_* ABI of supermux-terminal.wasm through wasm/terminal-loader.mjs (shipped
// as resources of this artifact). The module loads asynchronously, so a host app awaits
// TerminalRuntime.initialize() before the first createTerminalEngine(); until then (or after a
// failed load) createTerminalEngine throws the typed TerminalEngineUnavailableException.
// Deliberately no Kotlin fallback emulator.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine =
    createTerminalEngineOn(loaderCurrentRuntime(), loaderIsLoading(), loaderLastFailure(), size, limits)

/** [createTerminalEngine] against explicit loader state (null [runtime] = not initialized). */
internal fun createTerminalEngineOn(
    runtime: LoaderRuntime?,
    loading: Boolean,
    failure: JsAny?,
    size: TerminalSize,
    limits: TerminalLimits,
): TerminalEngine {
    if (runtime == null) throw notInitialized(loading, failure)
    return WasmTerminalEngine.open(runtime, size, limits)
}

private fun notInitialized(loading: Boolean, failure: JsAny?): TerminalEngineUnavailableException {
    return when {
        loading -> TerminalEngineUnavailableException(
            "terminal wasm runtime is still loading: await TerminalRuntime.initialize() first",
            reason = TerminalEngineUnavailableException.Reason.NOT_INITIALIZED,
        )
        // The last initialize() failed: report why (MISSING_BINARY, CORRUPT_BINARY, ...).
        failure != null -> loadFailure(failure)
        else -> TerminalEngineUnavailableException(
            "terminal wasm runtime not initialized: await TerminalRuntime.initialize() first",
            reason = TerminalEngineUnavailableException.Reason.NOT_INITIALIZED,
        )
    }
}

actual object TerminalRuntime {
    actual suspend fun initialize(wasmUrl: String?) {
        loaderInitialize(wasmUrl).awaitLoad()
    }
}

/**
 * [TerminalEngine] over one st_* handle of a [LoaderRuntime] + [ViewportCodec]. Kotlin/Wasm in the
 * browser is single-threaded, so the per-engine [closed] flag is the whole threading story (no
 * lock): after [close] every call throws [IllegalStateException]; [close] is idempotent and
 * destroys the handle exactly once. Every buffer arrives as an owned copy that the loader has
 * already freed with st_free_buffer. An engine dropped without [close] keeps its handle (and wasm
 * memory) until the page goes away — owners must close it.
 */
internal class WasmTerminalEngine private constructor(
    private val rt: LoaderRuntime,
    private val handle: Int,
) : TerminalEngine {
    private var closed = false

    private inline fun <T> live(op: String, block: (Int) -> T): T {
        check(!closed) { "TerminalEngine is closed ($op)" }
        return block(handle)
    }

    private fun buffer(result: BufferResult, op: String): ByteArray {
        NativeStatus.check(result.status, op)
        return (result.bytes ?: error("$op returned no buffer")).toByteArray()
    }

    override fun feed(bytes: ByteArray, origin: OutputOrigin) = live("feed") {
        val o = when (origin) { OutputOrigin.LIVE -> 0; OutputOrigin.REPLAY -> 1 }
        NativeStatus.check(rt.feed(it, bytes.toUint8Array(), o), "st_feed")
    }

    override fun reset() = live("reset") { NativeStatus.check(rt.reset(it), "st_reset") }

    override fun resize(size: TerminalSize) = live("resize") {
        NativeStatus.check(rt.resize(it, size.columns, size.rows, size.cellWidthPx, size.cellHeightPx), "st_resize")
    }

    override fun colors(colors: TerminalColors) = live("colors") {
        val values = NativeStatus.colorArray(colors)
        val bytes = ByteArray(values.size * 8)
        values.forEachIndexed { i, v -> for (b in 0 until 8) bytes[i * 8 + b] = (v ushr (8 * b)).toByte() }
        NativeStatus.check(rt.colors(it, bytes.toUint8Array()), "st_colors")
    }

    override fun viewport(forceFull: Boolean, breakHold: Boolean): TerminalViewport = live("viewport") {
        ViewportCodec.decodeViewport(
            buffer(rt.readViewport(it, NativeStatus.readFlags(forceFull, breakHold)), "st_read_viewport"),
        )
    }

    override fun acknowledge(generation: Long) = live("acknowledge") {
        NativeStatus.check(rt.acknowledge(it, generation), "st_acknowledge")
    }

    override fun scrollTo(row: Long) = live("scrollTo") { NativeStatus.check(rt.scrollTo(it, row), "st_scroll_to") }

    override fun key(key: TerminalKey) = live("key") {
        NativeStatus.check(
            rt.key(it, key.physicalCode, key.text.encodeToByteArray().toUint8Array(), key.modifiers, key.action), "st_key",
        )
    }

    override fun mouse(mouse: TerminalMouse) = live("mouse") {
        NativeStatus.check(
            rt.mouse(it, mouse.column, mouse.row, mouse.button, mouse.modifiers, mouse.action), "st_mouse",
        )
    }

    override fun paste(text: String, allowUnsafe: Boolean): Boolean = live("paste") {
        val flags = if (allowUnsafe) NativeStatus.PASTE_ALLOW_UNSAFE else 0
        when (val st = rt.paste(it, text.encodeToByteArray().toUint8Array(), flags)) {
            NativeStatus.REJECTED -> false
            else -> { NativeStatus.check(st, "st_paste"); true }
        }
    }

    override fun focus(focused: Boolean) = live("focus") { NativeStatus.check(rt.focus(it, focused), "st_focus") }

    override fun select(selection: TerminalSelection?) = live("select") {
        val st = if (selection == null) {
            rt.select(it, 0, 0L, 0, 0L, 0)
        } else {
            // u32 columns in the ABI: a negative Int would arrive as a huge column.
            require(selection.start.column >= 0 && selection.end.column >= 0) { "negative selection column" }
            rt.select(it, 1, selection.start.row, selection.start.column, selection.end.row, selection.end.column)
        }
        NativeStatus.check(st, "st_select")
    }

    override fun selectedText(): String = live("selectedText") {
        ViewportCodec.decodeSelectedText(buffer(rt.selectedText(it), "st_selected_text"))
    }

    override fun drainEffects(): List<TerminalEffect> = live("drainEffects") {
        ViewportCodec.decodeEffects(buffer(rt.drainEffects(it), "st_drain_effects"))
    }

    override fun close() {
        if (closed) return
        closed = true
        rt.destroy(handle)
    }

    companion object {
        fun open(rt: LoaderRuntime, size: TerminalSize, limits: TerminalLimits): WasmTerminalEngine {
            val r = rt.create(
                size.columns, size.rows, size.cellWidthPx, size.cellHeightPx, limits.historyLines, limits.historyBytes,
            )
            if (r.status != NativeStatus.OK) throw NativeStatus.createFailure(r.status)
            return WasmTerminalEngine(rt, r.handle)
        }
    }
}
