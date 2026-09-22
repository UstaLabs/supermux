package dev.supermux.terminal

import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The JNI glue (native/src/terminal_jni.c) on its failure paths: every st_* buffer it takes is
 * freed exactly once and every Get<Type>ArrayElements is released, even when a Java exception is
 * raised mid-call; no terminal is created when the out-array is unusable.
 */
class JniBindingTest {
    @BeforeTest fun load() = JvmNativeLibrary.ensureLoaded()

    /** [taken, freed, acquired, released] */
    private fun counters() = NativeTerminal.debugCounters()

    private fun assertBalanced(before: LongArray, expectBuffers: Long? = null) {
        val after = counters()
        val taken = after[0] - before[0]
        assertEquals(taken, after[1] - before[1], "every st_* buffer taken is freed (taken $taken)")
        assertEquals(after[2] - before[2], after[3] - before[3], "every array acquired is released")
        if (expectBuffers != null) assertEquals(expectBuffers, taken)
    }

    private fun newHandle(): Int {
        val out = IntArray(1)
        assertEquals(NativeStatus.OK, NativeTerminal.create(1, 80, 24, 8, 16, 100, 1L shl 20, out))
        assertTrue(out[0] != 0)
        return out[0]
    }

    @Test fun normalCallsBalance() {
        val before = counters()
        val h = newHandle()
        try {
            assertEquals(NativeStatus.OK, NativeTerminal.feed(h, "\u001b[31mred\u001b[6n".encodeToByteArray(), 0))
            assertEquals(NativeStatus.OK, NativeTerminal.feed(h, ByteArray(0), 0))
            assertEquals(NativeStatus.OK, NativeTerminal.colors(h, NativeStatus.colorArray(TestFixtures.fixtureColors())))
            assertEquals(NativeStatus.OK, NativeTerminal.key(h, TerminalKeys.A, "a".encodeToByteArray(), 0, 0))
            assertEquals(NativeStatus.OK, NativeTerminal.paste(h, "x".encodeToByteArray(), 0))
            val status = IntArray(1)
            val frame = NativeTerminal.readViewport(h, NativeStatus.READ_FORCE_FULL, status)!!
            assertEquals(NativeStatus.OK, status[0])
            assertEquals("red", ViewportCodec.decodeViewport(frame).rows[0].cells.take(3).joinToString("") { it.text })
            val effects = ViewportCodec.decodeEffects(NativeTerminal.drainEffects(h, status)!!)
            assertEquals(TerminalEffect.Response("\u001b[1;4R".encodeToByteArray()), effects.first())
            assertEquals("", ViewportCodec.decodeSelectedText(NativeTerminal.selectedText(h, status)!!))
        } finally {
            assertEquals(NativeStatus.OK, NativeTerminal.destroy(h))
        }
        assertBalanced(before, expectBuffers = 3)
    }

    @Test fun errorStatusesReturnNoBuffer() {
        val before = counters()
        val h = newHandle()
        NativeTerminal.destroy(h)
        val status = IntArray(1)
        assertEquals(null, NativeTerminal.readViewport(h, 0, status))
        assertEquals(NativeStatus.INVALID_HANDLE, status[0])
        assertEquals(null, NativeTerminal.drainEffects(h, status))
        assertEquals(NativeStatus.INVALID_HANDLE, status[0])
        assertEquals(NativeStatus.INVALID_HANDLE, NativeTerminal.feed(h, byteArrayOf(1), 0))
        assertEquals(NativeStatus.INVALID_HANDLE, NativeTerminal.destroy(h))
        val live = newHandle()
        try {
            assertEquals(NativeStatus.INVALID_ARGUMENT, NativeTerminal.colors(live, LongArray(3)))
        } finally {
            NativeTerminal.destroy(live)
        }
        assertBalanced(before, expectBuffers = 0)
    }

    @Test fun javaExceptionAfterBufferTakenStillFreesIt() {
        val h = newHandle()
        try {
            NativeTerminal.feed(h, "hello\u0007".encodeToByteArray(), 0)
            val before = counters()
            // status[0] cannot be written: the buffer was already taken from st_read_viewport /
            // st_drain_effects and must be freed before the exception propagates.
            assertFailsWith<ArrayIndexOutOfBoundsException> { NativeTerminal.readViewport(h, 1, IntArray(0)) }
            assertFailsWith<ArrayIndexOutOfBoundsException> { NativeTerminal.drainEffects(h, IntArray(0)) }
            assertFailsWith<ArrayIndexOutOfBoundsException> { NativeTerminal.selectedText(h, IntArray(0)) }
            assertBalanced(before, expectBuffers = 3)
            // The drained effects are gone (drain is atomic in C and succeeded), the engine is usable.
            val status = IntArray(1)
            assertEquals(emptyList(), ViewportCodec.decodeEffects(NativeTerminal.drainEffects(h, status)!!))
        } finally {
            NativeTerminal.destroy(h)
        }
    }

    @Test fun byteArrayAllocationFailureStillFreesBuffer() {
        val h = newHandle()
        try {
            NativeTerminal.feed(h, "abc".encodeToByteArray(), 0)
            val before = counters()
            NativeTerminal.debugFailNextArray(true)
            val status = IntArray(1)
            assertFailsWith<OutOfMemoryError> { NativeTerminal.readViewport(h, 1, status) }
            assertEquals(NativeStatus.OK, status[0])
            assertBalanced(before, expectBuffers = 1)
            val frame = ViewportCodec.decodeViewport(NativeTerminal.readViewport(h, 1, status)!!)
            assertEquals("abc", frame.rows[0].cells.take(3).joinToString("") { it.text })
        } finally {
            NativeTerminal.debugFailNextArray(false)
            NativeTerminal.destroy(h)
        }
    }

    @Test fun byteArrayAllocationFailureThroughEngineKeepsEngineUsable() {
        val engine = createTerminalEngine(TerminalSize(20, 4, 8, 16), TerminalLimits())
        try {
            engine.feed("\u0007".encodeToByteArray(), OutputOrigin.LIVE)
            val before = counters()
            NativeTerminal.debugFailNextArray(true)
            assertFailsWith<OutOfMemoryError> { engine.drainEffects() }
            assertBalanced(before, expectBuffers = 1)
            engine.feed("x".encodeToByteArray(), OutputOrigin.LIVE)
            assertEquals("x", engine.viewport(forceFull = true).rows[0].cells[0].text)
        } finally {
            NativeTerminal.debugFailNextArray(false)
            engine.close()
        }
    }

    @Test fun unusableOutHandleCreatesNothing() {
        val before = counters()
        assertFailsWith<IllegalArgumentException> { NativeTerminal.create(1, 80, 24, 8, 16, 0, 0, IntArray(0)) }
        assertBalanced(before)
        // ABI mismatch from st_create itself is typed through the engine factory path.
        val e = assertFailsWith<TerminalEngineUnavailableException> {
            NativeTerminalEngine.open(TerminalSize(80, 24, 8, 16), TerminalLimits(), abiVersion = 2)
        }
        assertEquals(TerminalEngineUnavailableException.Reason.ABI_MISMATCH, e.reason)
    }

    @Test fun inputsAreNotWrittenBack() {
        val h = newHandle()
        try {
            val data = "\u001b[?2004hpaste".encodeToByteArray()
            val copy = data.copyOf()
            NativeTerminal.feed(h, data, 0)
            NativeTerminal.paste(h, data, 1)
            assertContentEquals(copy, data)
        } finally {
            NativeTerminal.destroy(h)
        }
    }
}
