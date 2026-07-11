package dev.supermux.desktop.editor

import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pure Flow-state-machine tests for [DesktopLspBridge] — no broker, no KCEF. Uses `runTest`'s
 * virtual clock so the 9s/1.5s/2s real-world timeouts in [DesktopLspBridge.queryStatus]/[DesktopLspBridge.open]
 * resolve instantly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLspBridgeTest {
    private fun bridge(
        status: MutableStateFlow<Map<String, ServerFrame.LspStatus>>,
        rpc: MutableSharedFlow<ServerFrame.LspRpcIn>,
        queries: MutableList<Pair<String, String>> = mutableListOf(),
        opens: MutableList<Pair<String, String>> = mutableListOf(),
        rpcOuts: MutableList<Triple<String, String, String>> = mutableListOf(),
    ) = DesktopLspBridge(
        sessionId = "s1",
        lspStatus = status,
        lspRpc = rpc,
        lspStatusQuery = { sid, path -> queries.add(sid to path) },
        lspOpen = { sid, serverId -> opens.add(sid to serverId) },
        lspRpcOut = { sid, serverId, msg -> rpcOuts.add(Triple(sid, serverId, msg)) },
    )

    @Test fun query_status_with_no_prior_waits_the_full_window_then_returns_unavailable() = runTest {
        val status = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(emptyMap())
        val queries = mutableListOf<Pair<String, String>>()
        val b = bridge(status, MutableSharedFlow(), queries = queries)
        val result = b.queryStatus("src/a.ts")
        assertEquals("unavailable", result.state)
        assertEquals(listOf("s1" to "src/a.ts"), queries)
    }

    @Test fun query_status_returns_a_fresh_value_that_arrives_before_the_window_closes() = runTest {
        // NOTE the runCurrent() before mutating: under TestScope's queued dispatcher, a bare
        // launch{} does NOT run synchronously — if the status update happened before the child
        // coroutine ever executes, queryStatus's `prior` snapshot would already see the "fresh"
        // value and this would (accidentally, for the wrong reason) still pass. runCurrent() lets
        // queryStatus reach its suspension point (prior == null captured) BEFORE we emit, so this
        // genuinely exercises the "fresh emission arrives while waiting" path.
        val status = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(emptyMap())
        val b = bridge(status, MutableSharedFlow())
        val result = async { b.queryStatus("src/a.ts") }
        runCurrent()
        status.value = mapOf(
            "s1|src/a.ts" to ServerFrame.LspStatus(
                session = "s1", path = "src/a.ts", state = "ready", serverId = "ts", supported = true,
            ),
        )
        assertEquals("ready", result.await().state)
    }

    @Test fun query_status_reuses_the_cached_value_when_the_broker_resends_an_identical_status() = runTest {
        // A re-response that's value-EQUAL to what's cached never re-emits on a StateFlow (dedup) —
        // queryStatus must fall back to the cached (correct) entry rather than mislabel it
        // "unavailable" after the short 1.5s window. See AndroidLspBridge.queryStatus's KDoc.
        val cached = ServerFrame.LspStatus(
            session = "s1", path = "src/a.ts", state = "ready", serverId = "ts", supported = true,
        )
        val status = MutableStateFlow(mapOf("s1|src/a.ts" to cached))
        val b = bridge(status, MutableSharedFlow())
        val result = b.queryStatus("src/a.ts")
        assertSame(cached, result)
    }

    @Test fun open_succeeds_when_no_failure_arrives_within_the_settle_window() = runTest {
        val status = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(
            mapOf("s1|src/a.ts" to ServerFrame.LspStatus(session = "s1", path = "src/a.ts", state = "ready", serverId = "ts")),
        )
        val opens = mutableListOf<Pair<String, String>>()
        val b = bridge(status, MutableSharedFlow(), opens = opens)
        val ok = b.open("ts")
        assertTrue(ok)
        assertEquals(listOf("s1" to "ts"), opens)
    }

    @Test fun open_fails_when_a_fresh_error_status_arrives_within_the_window() = runTest {
        // Same runCurrent()-before-mutate discipline as the queryStatus test above: open() must
        // snapshot `prior` (the ready entry, at the time of the call) BEFORE the error lands, or
        // the error would be mistaken for a pre-existing (stale) entry and open() would wrongly
        // return true.
        val status = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(
            mapOf("s1|src/a.ts" to ServerFrame.LspStatus(session = "s1", path = "src/a.ts", state = "ready", serverId = "ts")),
        )
        val b = bridge(status, MutableSharedFlow())
        val result = async { b.open("ts") }
        runCurrent()
        status.update {
            it + ("s1|src/a.ts" to ServerFrame.LspStatus(
                session = "s1", path = "src/a.ts", state = "error", serverId = "ts", error = "spawn failed",
            ))
        }
        assertFalse(result.await())
    }

    @Test fun open_ignores_a_stale_failure_that_was_already_present_before_the_call() = runTest {
        // A pre-existing error entry (from a PRIOR failed open) must not immediately fail a NEW open()
        // — only a failure that arrives AFTER open() is called (identity-checked, not value-equality).
        val staleError = ServerFrame.LspStatus(session = "s1", path = "src/a.ts", state = "error", serverId = "ts", error = "old")
        val status = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(mapOf("s1|src/a.ts" to staleError))
        val b = bridge(status, MutableSharedFlow())
        assertTrue(b.open("ts"))
    }

    @Test fun rpc_out_forwards_with_the_bridges_own_session_id() = runTest {
        val rpcOuts = mutableListOf<Triple<String, String, String>>()
        val b = bridge(MutableStateFlow(emptyMap()), MutableSharedFlow(), rpcOuts = rpcOuts)
        b.rpcOut("ts", "{\"jsonrpc\":\"2.0\"}")
        assertEquals(listOf(Triple("s1", "ts", "{\"jsonrpc\":\"2.0\"}")), rpcOuts)
    }

    @Test fun pump_rpc_in_filters_by_session_and_server_id() = runTest {
        // runCurrent() after launch: pumpRpcIn's `lspRpc.collect{}` must actually SUBSCRIBE before
        // we emit, or a replay=0 SharedFlow drops emissions with no active collector (they are NOT
        // buffered for a collector that subscribes later). A second runCurrent() after the emits
        // lets the (already-buffered-for-an-active-collector) resumptions actually run the deliver
        // callback before we assert.
        val rpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 8)
        val b = bridge(MutableStateFlow(emptyMap()), rpc)
        val delivered = mutableListOf<Pair<String, String>>()
        val job = launch { b.pumpRpcIn("ts") { sid, msg -> delivered.add(sid to msg) } }
        runCurrent()
        rpc.emit(ServerFrame.LspRpcIn(session = "OTHER", serverId = "ts", message = "drop-wrong-session"))
        rpc.emit(ServerFrame.LspRpcIn(session = "s1", serverId = "bash", message = "drop-wrong-server"))
        rpc.emit(ServerFrame.LspRpcIn(session = "s1", serverId = "ts", message = "keep-me"))
        runCurrent()
        assertEquals(listOf("ts" to "keep-me"), delivered)
        job.cancel()
    }
}
