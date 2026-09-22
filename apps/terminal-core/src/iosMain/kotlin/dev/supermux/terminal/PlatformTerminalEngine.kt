@file:OptIn(ExperimentalForeignApi::class, ExperimentalNativeApi::class)

package dev.supermux.terminal

import dev.supermux.terminal.cinterop.st_abi_version
import dev.supermux.terminal.cinterop.st_acknowledge
import dev.supermux.terminal.cinterop.st_colors
import dev.supermux.terminal.cinterop.st_create
import dev.supermux.terminal.cinterop.st_destroy
import dev.supermux.terminal.cinterop.st_drain_effects
import dev.supermux.terminal.cinterop.st_feed
import dev.supermux.terminal.cinterop.st_focus
import dev.supermux.terminal.cinterop.st_free_buffer
import dev.supermux.terminal.cinterop.st_key
import dev.supermux.terminal.cinterop.st_mouse
import dev.supermux.terminal.cinterop.st_paste
import dev.supermux.terminal.cinterop.st_read_viewport
import dev.supermux.terminal.cinterop.st_reset
import dev.supermux.terminal.cinterop.st_resize
import dev.supermux.terminal.cinterop.st_scroll_to
import dev.supermux.terminal.cinterop.st_select
import dev.supermux.terminal.cinterop.st_selected_text
import kotlin.concurrent.AtomicInt
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.ref.Cleaner
import kotlin.native.ref.createCleaner
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.Foundation.NSLock

// iOS actual: cinterop against the static libsupermux_terminal.a (st_* wrapper + libghostty-vt),
// linked into the klib. Deliberately no Kotlin fallback emulator.
actual fun createTerminalEngine(size: TerminalSize, limits: TerminalLimits): TerminalEngine {
    val abi = st_abi_version().toInt()
    if (abi != NativeStatus.ABI_VERSION) {
        throw TerminalEngineUnavailableException(
            "linked libsupermux_terminal.a implements st_* ABI $abi, this binding needs ${NativeStatus.ABI_VERSION}",
            reason = TerminalEngineUnavailableException.Reason.ABI_MISMATCH,
        )
    }
    return IosTerminalEngine.open(size, limits)
}

/**
 * The st_* handle plus its destroyed flag, shared by the engine and its [Cleaner] (a cleaner must
 * not capture the engine). [destroy] runs st_destroy exactly once, whoever gets there first.
 */
private class NativeHandle(val value: UInt) {
    private val destroyed = AtomicInt(0)

    fun destroy() {
        if (destroyed.compareAndSet(0, 1)) st_destroy(value)
    }
}

/**
 * [TerminalEngine] over the st_* cinterop binding + [ViewportCodec]. Same threading contract as
 * the JNI binding: every call takes the engine's [lock] and checks [closed]; after [close] every
 * call throws [IllegalStateException]; [close] is idempotent. An engine that is garbage-collected
 * without [close] still has its handle destroyed by a [Cleaner]. Buffers returned by st_* are
 * copied into a [ByteArray] and freed with st_free_buffer before the decoder runs; Kotlin arrays
 * are pinned only for the duration of the synchronous native call.
 */
internal class IosTerminalEngine private constructor(private val handle: NativeHandle) : TerminalEngine {
    private val lock = NSLock()
    private var closed = false

    @Suppress("unused")
    private val cleaner: Cleaner = createCleaner(handle) { it.destroy() }

    private inline fun <T> locked(op: String, block: (UInt) -> T): T {
        lock.lock()
        try {
            check(!closed) { "TerminalEngine is closed ($op)" }
            return block(handle.value)
        } finally {
            lock.unlock()
        }
    }

    /** Calls [call] with a pointer to [bytes] (null when empty) pinned only for that call. */
    private inline fun <T> withBytes(bytes: ByteArray, call: (CPointer<UByteVar>?, UInt) -> T): T =
        if (bytes.isEmpty()) {
            call(null, 0u)
        } else {
            bytes.usePinned { call(it.addressOf(0).reinterpret(), bytes.size.toUInt()) }
        }

    /** st_* reader → copied envelope; the native buffer is freed on every path. */
    private inline fun readBuffer(
        op: String,
        call: (CPointer<CPointerVar<UByteVar>>, CPointer<UIntVar>) -> Int,
    ): ByteArray = memScoped {
        val buf = alloc<CPointerVar<UByteVar>>()
        val len = alloc<UIntVar>()
        val st = call(buf.ptr, len.ptr)
        NativeStatus.check(st, op)
        val p = buf.value ?: error("$op returned no buffer")
        try {
            p.readBytes(len.value.toInt())
        } finally {
            st_free_buffer(p)
        }
    }

    override fun feed(bytes: ByteArray, origin: OutputOrigin) = locked("feed") { h ->
        val o = when (origin) { OutputOrigin.LIVE -> 0u; OutputOrigin.REPLAY -> 1u }
        NativeStatus.check(withBytes(bytes) { p, n -> st_feed(h, p, n, o) }, "st_feed")
    }

    override fun reset() = locked("reset") { NativeStatus.check(st_reset(it), "st_reset") }

    override fun resize(size: TerminalSize) = locked("resize") {
        NativeStatus.check(
            st_resize(it, size.columns.toUInt(), size.rows.toUInt(), size.cellWidthPx.toUInt(), size.cellHeightPx.toUInt()),
            "st_resize",
        )
    }

    override fun colors(colors: TerminalColors) = locked("colors") { h ->
        val values = NativeStatus.colorArray(colors)
        val st = values.usePinned { st_colors(h, it.addressOf(0).reinterpret<ULongVar>(), values.size.toUInt()) }
        NativeStatus.check(st, "st_colors")
    }

    override fun viewport(forceFull: Boolean, breakHold: Boolean): TerminalViewport = locked("viewport") { h ->
        val flags = NativeStatus.readFlags(forceFull, breakHold).toUInt()
        ViewportCodec.decodeViewport(readBuffer("st_read_viewport") { buf, len -> st_read_viewport(h, flags, buf, len, null) })
    }

    override fun acknowledge(generation: Long) = locked("acknowledge") {
        NativeStatus.check(st_acknowledge(it, generation), "st_acknowledge")
    }

    override fun scrollTo(row: Long) = locked("scrollTo") { NativeStatus.check(st_scroll_to(it, row), "st_scroll_to") }

    override fun key(key: TerminalKey) = locked("key") { h ->
        val st = withBytes(key.text.encodeToByteArray()) { p, n ->
            st_key(h, key.physicalCode.toUInt(), p, n, key.modifiers.toUInt(), key.action.toUInt())
        }
        NativeStatus.check(st, "st_key")
    }

    override fun mouse(mouse: TerminalMouse) = locked("mouse") {
        NativeStatus.check(
            st_mouse(it, mouse.column, mouse.row, mouse.button.toUInt(), mouse.modifiers.toUInt(), mouse.action.toUInt()),
            "st_mouse",
        )
    }

    override fun paste(text: String, allowUnsafe: Boolean): Boolean = locked("paste") { h ->
        val flags = if (allowUnsafe) NativeStatus.PASTE_ALLOW_UNSAFE.toUInt() else 0u
        when (val st = withBytes(text.encodeToByteArray()) { p, n -> st_paste(h, p, n, flags) }) {
            NativeStatus.REJECTED -> false
            else -> { NativeStatus.check(st, "st_paste"); true }
        }
    }

    override fun focus(focused: Boolean) = locked("focus") {
        NativeStatus.check(st_focus(it, if (focused) 1u else 0u), "st_focus")
    }

    override fun select(selection: TerminalSelection?) = locked("select") { h ->
        val st = if (selection == null) {
            st_select(h, 0u, 0, 0u, 0, 0u)
        } else {
            require(selection.start.column >= 0 && selection.end.column >= 0) { "negative selection column" }
            st_select(
                h, 1u, selection.start.row, selection.start.column.toUInt(), selection.end.row, selection.end.column.toUInt(),
            )
        }
        NativeStatus.check(st, "st_select")
    }

    override fun selectedText(): String = locked("selectedText") { h ->
        ViewportCodec.decodeSelectedText(readBuffer("st_selected_text") { buf, len -> st_selected_text(h, buf, len) })
    }

    override fun drainEffects(): List<TerminalEffect> = locked("drainEffects") { h ->
        ViewportCodec.decodeEffects(readBuffer("st_drain_effects") { buf, len -> st_drain_effects(h, buf, len) })
    }

    override fun close() {
        lock.lock()
        try {
            if (closed) return
            closed = true
            handle.destroy()
        } finally {
            lock.unlock()
        }
    }

    companion object {
        fun open(size: TerminalSize, limits: TerminalLimits): IosTerminalEngine = memScoped {
            val out = alloc<UIntVar>()
            val st = st_create(
                NativeStatus.ABI_VERSION.toUInt(), size.columns.toUInt(), size.rows.toUInt(),
                size.cellWidthPx.toUInt(), size.cellHeightPx.toUInt(),
                limits.historyLines.toUInt(), limits.historyBytes.toULong(), null, out.ptr,
            )
            if (st != NativeStatus.OK) throw NativeStatus.createFailure(st)
            IosTerminalEngine(NativeHandle(out.value))
        }
    }
}
