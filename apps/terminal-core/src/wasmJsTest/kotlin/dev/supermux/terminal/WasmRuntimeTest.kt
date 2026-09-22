package dev.supermux.terminal

import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Run a suspend test body; kotlin-test on wasmJs awaits a returned Promise. */
private fun runAsync(block: suspend () -> Unit): Promise<JsAny?> = Promise { resolve, reject ->
    block.startCoroutine(
        Continuation(EmptyCoroutineContext) { result ->
            result.fold({ resolve(null) }, { reject(it.toJsReference()) })
        },
    )
}

/**
 * Browser binding specifics: the async runtime, typed startup failures, several engines in one
 * wasm instance across memory growth, and the ByteArray <-> Uint8Array bridge. The shared behaviour
 * is EngineContractTest (commonTest), which runs here unchanged.
 */
class WasmRuntimeTest {
    private val size = TerminalSize(80, 24, 8, 16)

    private suspend fun expectLoadFailure(url: String): TerminalEngineUnavailableException {
        try {
            loaderLoadRuntime(url).awaitLoad()
        } catch (e: TerminalEngineUnavailableException) {
            return e
        }
        fail("loading $url succeeded")
    }

    private fun TerminalViewport.rowText(row: Int): String =
        rows.first { it.index == row }.cells.filter { it.width != 0 }.joinToString("") { it.text.ifEmpty { " " } }.trimEnd()

    @Test fun runtimeWasInitializedBeforeTests() {
        assertNull(setupFailure, "terminal-test-setup.mjs failed to load the runtime")
        assertTrue(loaderCurrentRuntime() != null)
    }

    @Test fun initializeIsIdempotent() = runAsync {
        val before = loaderCurrentRuntime()
        TerminalRuntime.initialize()
        TerminalRuntime.initialize(null)
        assertTrue(before === loaderCurrentRuntime())
    }

    @Test fun initializeWithAnotherUrlAfterLoadIsATypedError() = runAsync {
        val e = assertFailsWith<TerminalEngineUnavailableException> {
            TerminalRuntime.initialize("/some/other/supermux-terminal.wasm")
        }
        assertEquals(TerminalEngineUnavailableException.Reason.INITIALIZATION_FAILED, e.reason)
        // The loaded runtime stays usable.
        createTerminalEngine(size, TerminalLimits()).close()
    }

    @Test fun missingBinaryIsTyped() = runAsync {
        val e = expectLoadFailure("/does-not-exist/supermux-terminal.wasm")
        assertEquals(TerminalEngineUnavailableException.Reason.MISSING_BINARY, e.reason, e.message)
    }

    @Test fun corruptBinaryIsTyped() = runAsync {
        // Served by karma.config.d/terminal-wasm.js: a JS file, not a wasm module.
        val e = expectLoadFailure("/terminal-test/not-a-module.wasm")
        assertEquals(TerminalEngineUnavailableException.Reason.CORRUPT_BINARY, e.reason, e.message)
    }

    @Test fun nonHttpUrlIsRejected() = runAsync {
        val e = expectLoadFailure("data:application/wasm;base64,AGFzbQEAAAA=")
        assertEquals(TerminalEngineUnavailableException.Reason.INITIALIZATION_FAILED, e.reason, e.message)
    }

    @Test fun createBeforeInitializeIsNotInitialized() {
        for (loading in listOf(false, true)) {
            val e = assertFailsWith<TerminalEngineUnavailableException> {
                createTerminalEngineOn(null, loading, null, size, TerminalLimits())
            }
            assertEquals(TerminalEngineUnavailableException.Reason.NOT_INITIALIZED, e.reason, e.message)
        }
    }

    @Test fun createAfterFailedInitializeReportsTheLoadFailure() = runAsync {
        val failure: JsAny = try {
            loaderLoadRuntime("/does-not-exist/supermux-terminal.wasm").then({ null }, { it })
                .awaitLoad() ?: fail("load succeeded")
        } catch (e: TerminalEngineUnavailableException) {
            fail("rejection was not captured: $e")
        }
        val e = assertFailsWith<TerminalEngineUnavailableException> {
            createTerminalEngineOn(null, false, failure, size, TerminalLimits())
        }
        assertEquals(TerminalEngineUnavailableException.Reason.MISSING_BINARY, e.reason, e.message)
    }

    @Test fun twoEnginesShareOneInstanceAcrossMemoryGrowth() {
        val rt = loaderCurrentRuntime() ?: fail("runtime not initialized")
        val a = createTerminalEngine(size, TerminalLimits())
        val b = createTerminalEngine(TerminalSize(40, 10, 8, 16), TerminalLimits())
        try {
            a.feed("alpha terminal\u001b[6n".encodeToByteArray(), OutputOrigin.LIVE)
            b.feed("bravo\r\nsecond line".encodeToByteArray(), OutputOrigin.LIVE)
            val bBefore = b.viewport(forceFull = true)

            val memBefore = rt.memoryBytes()
            val line = "x".repeat(78) + "\r\n"
            val big = (line.repeat((2 shl 20) / line.length) + "last line of A").encodeToByteArray()
            val t0 = kotlin.time.TimeSource.Monotonic.markNow()
            a.feed(big, OutputOrigin.LIVE)
            println("fed ${big.size} bytes through the Kotlin bridge in ${t0.elapsedNow()}")
            assertTrue(rt.growMemory(48 shl 20))
            assertTrue(rt.memoryBytes() > memBefore, "linear memory grew (${rt.memoryBytes()} > $memBefore)")

            val aAfter = a.viewport(forceFull = true)
            assertEquals("last line of A", aAfter.rowText(23))
            assertEquals("x".repeat(78), aAfter.rowText(22))
            val bAfter = b.viewport(forceFull = true)
            assertEquals("bravo", bAfter.rowText(0))
            assertEquals("second line", bAfter.rowText(1))
            assertEquals(bBefore.rows, bAfter.rows, "B's screen is untouched by A's feed and the growth")

            b.feed("\u001b[2;4H\u001b[6n".encodeToByteArray(), OutputOrigin.LIVE)
            assertEquals(
                listOf(TerminalEffect.Response("\u001b[1;15R".encodeToByteArray())),
                a.drainEffects().filterIsInstance<TerminalEffect.Response>(),
            )
            assertEquals(
                listOf(TerminalEffect.Response("\u001b[2;4R".encodeToByteArray())),
                b.drainEffects().filterIsInstance<TerminalEffect.Response>(),
            )
            assertEquals(0, rt.liveBuffers())
        } finally {
            a.close()
            b.close()
        }
    }

    @Test fun closedEngineThrowsAndCloseIsIdempotent() {
        val engine = createTerminalEngine(size, TerminalLimits())
        engine.close()
        engine.close()
        assertFailsWith<IllegalStateException> { engine.feed("x".encodeToByteArray(), OutputOrigin.LIVE) }
        assertFailsWith<IllegalStateException> { engine.viewport() }
        // A later engine gets a fresh handle and works.
        createTerminalEngine(size, TerminalLimits()).use { it.feed("ok".encodeToByteArray(), OutputOrigin.LIVE) }
    }

    @Test fun byteBridgeRoundTripsEveryByteAndLength() {
        for (n in listOf(0, 1, 2, 3, 4, 5, 7, 8, 255, 256, 1027)) {
            val bytes = ByteArray(n) { (it * 37 + 11).toByte() }
            assertContentEquals(bytes, bytes.toUint8Array().toByteArray(), "length $n")
        }
        val all = ByteArray(256) { it.toByte() }
        assertContentEquals(all, all.toUint8Array().toByteArray())
        val mib = ByteArray(1 shl 20) { (it * 31).toByte() }
        val t0 = kotlin.time.TimeSource.Monotonic.markNow()
        val there = mib.toUint8Array()
        val t1 = t0.elapsedNow()
        val back = there.toByteArray()
        println("1 MiB ByteArray->Uint8Array in $t1, back in ${t0.elapsedNow() - t1} (development build)")
        assertContentEquals(mib, back)
    }

    @Test fun nonAsciiRoundTripsThroughTheEngine() {
        createTerminalEngine(size, TerminalLimits()).use { engine ->
            engine.feed("héllo 世界 🙂".encodeToByteArray(), OutputOrigin.LIVE)
            assertEquals("héllo 世界 🙂", engine.viewport(forceFull = true).rowText(0))
        }
    }
}
