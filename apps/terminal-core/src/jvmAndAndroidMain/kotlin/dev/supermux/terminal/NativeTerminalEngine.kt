package dev.supermux.terminal

/**
 * [TerminalEngine] over the JNI st_* binding ([NativeTerminal]) + [ViewportCodec], for Android and
 * the desktop JVM.
 *
 * Thread safety (native/README.md "Threading"): calls on one st_* handle must be serialized,
 * including st_destroy, so every call takes this engine's [lock] and checks [closed] first. After
 * [close] every call throws [IllegalStateException]; [close] itself is idempotent. Different
 * engines never contend.
 */
internal class NativeTerminalEngine private constructor(private val handle: Int) : TerminalEngine {
    private val lock = Any()
    private var closed = false

    private inline fun <T> locked(op: String, block: (Int) -> T): T = synchronized(lock) {
        check(!closed) { "TerminalEngine is closed ($op)" }
        block(handle)
    }

    override fun feed(bytes: ByteArray, origin: OutputOrigin) = locked("feed") {
        val o = when (origin) { OutputOrigin.LIVE -> 0; OutputOrigin.REPLAY -> 1 }
        NativeStatus.check(NativeTerminal.feed(it, bytes, o), "st_feed")
    }

    override fun reset() = locked("reset") { NativeStatus.check(NativeTerminal.reset(it), "st_reset") }

    override fun resize(size: TerminalSize) = locked("resize") {
        NativeStatus.check(
            NativeTerminal.resize(it, size.columns, size.rows, size.cellWidthPx, size.cellHeightPx), "st_resize",
        )
    }

    override fun colors(colors: TerminalColors) = locked("colors") {
        NativeStatus.check(NativeTerminal.colors(it, NativeStatus.colorArray(colors)), "st_colors")
    }

    override fun viewport(forceFull: Boolean, breakHold: Boolean): TerminalViewport = locked("viewport") {
        val status = IntArray(1)
        val buf = NativeTerminal.readViewport(it, NativeStatus.readFlags(forceFull, breakHold), status)
        NativeStatus.check(status[0], "st_read_viewport")
        ViewportCodec.decodeViewport(buf ?: error("st_read_viewport returned no buffer"))
    }

    override fun acknowledge(generation: Long) = locked("acknowledge") {
        NativeStatus.check(NativeTerminal.acknowledge(it, generation), "st_acknowledge")
    }

    override fun scrollTo(row: Long) = locked("scrollTo") {
        NativeStatus.check(NativeTerminal.scrollTo(it, row), "st_scroll_to")
    }

    override fun key(key: TerminalKey) = locked("key") {
        NativeStatus.check(
            NativeTerminal.key(it, key.physicalCode, key.text.encodeToByteArray(), key.modifiers, key.action), "st_key",
        )
    }

    override fun mouse(mouse: TerminalMouse) = locked("mouse") {
        NativeStatus.check(
            NativeTerminal.mouse(it, mouse.column, mouse.row, mouse.button, mouse.modifiers, mouse.action), "st_mouse",
        )
    }

    override fun paste(text: String, allowUnsafe: Boolean): Boolean = locked("paste") {
        val flags = if (allowUnsafe) NativeStatus.PASTE_ALLOW_UNSAFE else 0
        when (val st = NativeTerminal.paste(it, text.encodeToByteArray(), flags)) {
            NativeStatus.REJECTED -> false
            else -> { NativeStatus.check(st, "st_paste"); true }
        }
    }

    override fun focus(focused: Boolean) = locked("focus") {
        NativeStatus.check(NativeTerminal.focus(it, focused), "st_focus")
    }

    override fun select(selection: TerminalSelection?) = locked("select") {
        val st = if (selection == null) {
            NativeTerminal.select(it, false, 0, 0, 0, 0)
        } else {
            NativeTerminal.select(
                it, true, selection.start.row, selection.start.column, selection.end.row, selection.end.column,
            )
        }
        NativeStatus.check(st, "st_select")
    }

    override fun selectedText(): String = locked("selectedText") {
        val status = IntArray(1)
        val buf = NativeTerminal.selectedText(it, status)
        NativeStatus.check(status[0], "st_selected_text")
        ViewportCodec.decodeSelectedText(buf ?: error("st_selected_text returned no buffer"))
    }

    override fun drainEffects(): List<TerminalEffect> = locked("drainEffects") {
        val status = IntArray(1)
        val buf = NativeTerminal.drainEffects(it, status)
        NativeStatus.check(status[0], "st_drain_effects")
        ViewportCodec.decodeEffects(buf ?: error("st_drain_effects returned no buffer"))
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            NativeTerminal.destroy(handle)
        }
    }

    companion object {
        /** Create a terminal; the library must already be loaded and ABI-checked. */
        fun open(size: TerminalSize, limits: TerminalLimits, abiVersion: Int = NativeStatus.ABI_VERSION): NativeTerminalEngine {
            val out = IntArray(1)
            val st = NativeTerminal.create(
                abiVersion, size.columns, size.rows, size.cellWidthPx, size.cellHeightPx,
                limits.historyLines, limits.historyBytes, out,
            )
            if (st != NativeStatus.OK) throw NativeStatus.createFailure(st)
            return NativeTerminalEngine(out[0])
        }
    }
}
