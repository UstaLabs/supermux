# Windows/Linux Desktop Client — Milestone 4g-3 (LSP In-Editor Connect Flow) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Wire the full LSP round-trip for the desktop editor — cm6's `@codemirror/lsp-client` (running inside the KCEF-hosted bundle) connects to a broker-managed language server over the shared WS transport, so a file opened in the desktop editor gets real diagnostics/hover/completion against the broker's already-ready `typescript` LSP server. This replaces the `DesktopEditorEngine.kt:287-289` log-and-drop of `BridgeEvent.LspOut` with a real connect-sequencing pipeline, ported from `AndroidLspBridge.kt` + `EditorScreen.kt`.

**Architecture:** "LSP is a control-plane (`lsp_status_query`/`lsp_open`/`lsp_ready`/`lsp_error`/`lsp_exit`) plus a dumb JSON-RPC relay (`lsp_rpc`, wire-tagged for BOTH directions by frame type)" — the broker never understands the LSP protocol itself; the real `initialize`/`didOpen`/`completion`/`hover` exchange runs entirely inside cm6's `LSPClient` in the browser. The desktop port has four layers, each a near-verbatim port of an already-proven Android layer:
1. **`DesktopAppState`** folds inbound `lsp_status`/`lsp_ready`/`lsp_error`/`lsp_exit`/`lsp_rpc` frames into two flows (`lspStatus: StateFlow<Map<"session|path", LspStatus>>`, `lspRpc: SharedFlow<LspRpcIn>`) and exposes four outbound-frame senders — port of `AppViewModel.kt`'s reducer branches + `lspStatusQuery/lspOpen/lspRpcOut/lspClose`.
2. **`DesktopLspBridge`** (new file) is a pure Flow state machine — query status with a StateFlow-dedup-aware timeout, open a server and watch for a fresh failure, pump inbound RPC filtered by session+serverId — a near-verbatim port of `AndroidLspBridge.kt`, unit-testable with fake `MutableStateFlow`/`MutableSharedFlow` (no broker, no KCEF).
3. **`DesktopEditorEngine`** gets a real `onLspOut` callback (parsing cm6's outbound `{serverId,message}` JSON with kotlinx.serialization, not `org.json`) plus `lspConnect`/`lspMessage`/`lspDisconnect` JS-push methods — port of `EditorEngine.kt:247-262`, split so the JS-string construction is a pure, unit-tested function (`EditorBridgeShims.kt`) and only the actual `executeJavaScript` call is untested engine glue (this module's established KCEF-can't-run-in-unit-tests discipline).
4. **`EditorPanel`**'s connect-sequencing `LaunchedEffect` — disconnect on tab/mode change, bail on diff/preview/no-tab, wait for the engine's REAL `ready: StateFlow<Boolean>` (an upgrade over Android's blind `delay(1200)`), query status, open the server, pump inbound RPC, then `lspConnect` with `file://` URIs built from `workdir` — port of `EditorScreen.kt:192-212` + its URI helpers, wired through a new `EditorLspHandle` seam (mirrors the existing `EditorScrollReader` pattern) so `EditorSurface` keeps sole ownership of the KCEF engine.

Everything below the WS transport (`ClientFrame.LspStatusQuery/LspOpen/LspRpcOut/LspClose`, `ServerFrame.LspStatus/LspReady/LspError/LspRpcIn/LspExit` in `apps/shared/.../proto/Frames.kt`) and `BrokerClient.frames`/`.send()` already exists — **no commonMain work in this plan**. The LSP *settings* screen (install/enable/disable servers, `lsp_install*` frames) is **OUT OF SCOPE** — that's M4g-4.

**Tech Stack:** Compose Desktop, KCEF (`dev.datlag.kcef`) hosting the committed cm6 bundle (`@codemirror/lsp-client`), shared `BrokerClient`/`ClientFrame`/`ServerFrame` (`apps/shared/src/commonMain/kotlin/dev/supermux/proto/Frames.kt`), kotlinx.coroutines Flow (`StateFlow`/`SharedFlow`/`withTimeoutOrNull`), kotlinx.serialization (`Json`, not `org.json` — desktop convention).

---

## Ground rules

All prior-milestone rules hold: standard gradle invocation with `/home/ahmet/.cache` logs + TMPDIR; Xvfb `:77` + `SKIKO_RENDER_API=SOFTWARE`; paired config at `/home/ahmet/.cache/smx-test-config`; xwd+Pillow for screenshots; **NO xdotool** — everything is driven through off-by-default env hooks (`SM_*`); **never restart the broker**; snake_case test method names; commit trailer `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`; touch **ONLY** `apps/desktop/src` — **NEVER** `apps/shared` or the broker (the WS transport is already built and proven). Suite baseline: **re-read the actual current desktop/shared-jvmTest/android-compile counts at the start of this milestone** — whatever M4g-1 (executed) and M4g-2 (may or may not be executed yet) leave in place; do not hardcode a stale number here.

Milestone-specific gotchas, confirmed during research — read before touching code:

- **The `lsp_rpc` wire tag is dual-direction.** `ClientFrame.LspRpcOut` (outbound, session+serverId+message) and `ServerFrame.LspRpcIn` (inbound, same shape) BOTH serialize as `@SerialName("lsp_rpc")`. They are distinguished by which sealed interface they belong to (`ClientFrame` vs `ServerFrame`), never by a field. Do not add any logic that inspects a raw `"lsp_rpc"` string to decide direction — always work through the typed `ClientFrame.LspRpcOut` / `ServerFrame.LspRpcIn` classes kotlinx.serialization already resolves correctly.
- **LSP servers are keyed per-WS-connection on the broker.** `lsp_open`/`lsp_status_query` etc. are scoped to `session` — never let a `DesktopLspBridge` instance answer for a different session's frames (this is why `queryStatus`/`open`/`pumpRpcIn` all filter by `sessionId` + `serverId`, exactly like `AndroidLspBridge`; do not "simplify" that filtering away).
- **The ready-gate replaces Android's blind `delay(1200)`.** Desktop's `DesktopEditorEngine` already exposes a REAL `ready: StateFlow<Boolean>` (cm6 first-paint gate) that Android's WebView bridge doesn't have. Use it instead of a fixed delay — this is a deliberate improvement, not a deviation to apologize for. Key the connect effect on `engineReady` (mirrors Android's `engine.failed` re-key at `EditorScreen.kt:195`). NOTE: KCEF renderer-crash recovery is NOT automatic (DesktopEditorEngine.kt:213-217 — `_ready` stays false after a crash, no auto-reload; a future retry affordance would need a fresh `load()`), exactly like Android's `engine.failed` which is also never reset. So keying on `engineReady` correctly re-triggers the connect sequence on the normal `false→true` first-paint gate; it does NOT (and is not claimed to) provide live crash recovery on either platform.
- **Port the `AndroidLspBridge` KDoc comments describing the StateFlow value-equality reasoning verbatim (adapted only for desktop naming).** The `queryStatus`/`open` timeout logic depends on subtle identity-vs-value-equality behavior of `StateFlow` dedup (a `lsp_status` re-send with an unchanged value never re-emits; a state FLIP does) — these comments are load-bearing documentation of *why* the timeouts + `===` checks are shaped the way they are, not decoration. Do not paraphrase them away.
- **KCEF cannot run inside `kotlin.test`/JUnit.** Every pure/flow piece (the reducer, `DesktopLspBridge`, `parseLspOut`, the `file://` URI helpers, the LSP JS-string builders) gets a real unit test. `DesktopEditorEngine`'s `applyEvent` dispatch of `BridgeEvent.LspOut` and the actual `browser.executeJavaScript(...)` calls in `lspConnect`/`lspMessage`/`lspDisconnect` are thin one-line adapters over the tested pure pieces and are **left unverified by automation** — this is the module's existing, established discipline (see `EditorBridgeShims.kt`'s own header comment), not a gap introduced here.
- If M4g-2 (DiffView, `editor.showDiff`) has **not** landed by the time this plan executes, `EditorState` has no `showDiff` field yet — Task 5's connect-effect bail condition drops the `editor.showDiff` term (keep `showPreview`/`tab == null`/`workdir.isEmpty()`/`!engineReady`) and a one-line TODO(M4g-2) is added to re-include it once that field exists. Check `apps/desktop/.../editor/EditorState.kt` for `var showDiff` at the start of Task 5 to decide which form to write.

---

### Task 1: `DesktopAppState` — LSP reducer fold + outbound control-plane senders (TDD)

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/state/DesktopAppStateReducerTest.kt`

Port of `AppViewModel.kt:163-171` (the two flows), `AppViewModel.kt:290-295` (the reducer branches), `AppViewModel.kt:345-359` (`markLspState`), and `AppViewModel.kt:832-843` (the outbound senders) — **excluding** `LspInstallProgress`/`LspInstallDone`/`ClientFrame.LspInstall` (those back the M4g-4 install screen, out of scope here; they keep falling through the existing `else -> {}` branch).

- [x] **Step 1: Write the failing reducer + sender tests.** Add to `DesktopAppStateReducerTest.kt`, after the existing `// ── M3 editor …` section:

```kotlin
    // ── M4g-3 LSP: reducer fold + outbound control-plane senders ──────────────────────────
    @Test fun lsp_status_frame_is_folded_by_session_and_path() {
        val s = state()
        s.reduce(
            ServerFrame.LspStatus(
                session = "s1", path = "src/a.ts", supported = true, serverId = "ts", state = "ready",
            ),
        )
        val entry = s.lspStatus.value["s1|src/a.ts"]
        assertEquals("ready", entry?.state)
        assertEquals("ts", entry?.serverId)
    }

    @Test fun lsp_ready_flips_matching_entries_to_ready() {
        val s = state()
        s.reduce(
            ServerFrame.LspStatus(
                session = "s1", path = "src/a.ts", supported = true, serverId = "ts", state = "installing",
            ),
        )
        s.reduce(ServerFrame.LspReady(session = "s1", serverId = "ts"))
        assertEquals("ready", s.lspStatus.value["s1|src/a.ts"]?.state)
    }

    @Test fun lsp_error_sets_state_and_error_message_on_matching_entries_only() {
        val s = state()
        s.reduce(ServerFrame.LspStatus(session = "s1", path = "src/a.ts", supported = true, serverId = "ts", state = "ready"))
        s.reduce(ServerFrame.LspStatus(session = "s1", path = "src/b.js", supported = true, serverId = "bash", state = "ready"))
        s.reduce(ServerFrame.LspError(session = "s1", serverId = "ts", error = "spawn failed"))
        val ts = s.lspStatus.value["s1|src/a.ts"]
        val bash = s.lspStatus.value["s1|src/b.js"]
        assertEquals("error", ts?.state)
        assertEquals("spawn failed", ts?.error)
        assertEquals("ready", bash?.state) // a different serverId must not be touched
    }

    @Test fun lsp_exit_marks_state_exited() {
        val s = state()
        s.reduce(ServerFrame.LspStatus(session = "s1", path = "src/a.ts", supported = true, serverId = "ts", state = "ready"))
        s.reduce(ServerFrame.LspExit(session = "s1", serverId = "ts"))
        assertEquals("exited", s.lspStatus.value["s1|src/a.ts"]?.state)
    }

    @Test fun lsp_rpc_in_frame_is_broadcast_on_the_lsp_rpc_flow() {
        val s = state()
        val received = mutableListOf<ServerFrame.LspRpcIn>()
        // UnconfinedTestDispatcher runs the collector eagerly → it subscribes before the reduce.
        val job = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()).launch {
            s.lspRpc.collect { received.add(it) }
        }
        s.reduce(ServerFrame.LspRpcIn(session = "s1", serverId = "ts", message = "{\"id\":1}"))
        assertEquals(1, received.size)
        assertEquals("ts", received.first().serverId)
        assertEquals("{\"id\":1}", received.first().message)
        job.cancel()
    }

    @Test fun lsp_control_plane_senders_send_the_right_frames() {
        val s = state()
        val sess = session("s1")
        s.lspStatusQuery(sess, "src/a.ts")
        s.lspOpen(sess, "ts")
        s.lspRpcOut(sess, "ts", "{\"id\":2}")
        s.lspClose(sess, "ts")
        assertEquals(
            listOf(
                ClientFrame.LspStatusQuery("s1", "src/a.ts"),
                ClientFrame.LspOpen("s1", "ts"),
                ClientFrame.LspRpcOut("s1", "ts", "{\"id\":2}"),
                ClientFrame.LspClose("s1", "ts"),
            ),
            sent.filter {
                it is ClientFrame.LspStatusQuery || it is ClientFrame.LspOpen ||
                    it is ClientFrame.LspRpcOut || it is ClientFrame.LspClose
            },
        )
    }
```

- [x] **Step 2: Run the tests to see them fail on missing symbols.**

Run: `cd apps/desktop && GRADLE_USER_HOME=/home/ahmet/.cache/gradle TMPDIR=/home/ahmet/.cache/tmp ./../../gradlew :desktop:test --tests "dev.supermux.desktop.state.DesktopAppStateReducerTest" 2>&1 | tail -60`
Expected: compile failure — `lspStatus`, `lspRpc`, `lspStatusQuery`, `lspOpen`, `lspRpcOut`, `lspClose` are unresolved references on `DesktopAppState`.

- [x] **Step 3: Add the two flows.** In `DesktopAppState.kt`, right after the `_fsChanges`/`fsChanges` block (~line 151):

```kotlin
    // ── LSP (M4g-3) ─────────────────────────────────────────────────────────────────
    // lsp_status keyed "session|path" (mirrors AppViewModel:163-166); lsp_ready/lsp_error/lsp_exit
    // patch matching entries via [markLspState] since they only carry session+serverId. lsp_rpc
    // (inbound) is a raw relay SharedFlow — DesktopLspBridge (Task 2) filters it by session+serverId.
    private val _lspStatus = MutableStateFlow<Map<String, ServerFrame.LspStatus>>(emptyMap())
    val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = _lspStatus

    private val _lspRpc = MutableSharedFlow<ServerFrame.LspRpcIn>(extraBufferCapacity = 256)
    val lspRpc: SharedFlow<ServerFrame.LspRpcIn> = _lspRpc.asSharedFlow()
```

- [x] **Step 4: Add the reducer branches.** In `reduce()`, replace the comment `// Out of M1/M3/M4b scope — reduced in later milestones: agent_error, display_*, lsp_*` line (it currently reads `agent_error, display_*, lsp_*`) with just `agent_error, display_*` and insert the new branches directly above the `else -> {}`:

```kotlin
            is ServerFrame.LspStatus ->
                _lspStatus.update { it + ("${frame.session}|${frame.path}" to frame) }
            is ServerFrame.LspReady -> markLspState(frame.session, frame.serverId, "ready")
            is ServerFrame.LspError -> markLspState(frame.session, frame.serverId, "error", frame.error)
            is ServerFrame.LspRpcIn -> _lspRpc.tryEmit(frame)
            is ServerFrame.LspExit -> markLspState(frame.session, frame.serverId, "exited")
            // Out of scope here (M4g-4 LSP settings screen owns install progress/results):
            // lsp_install_progress, lsp_install_done. Still fall through to `else` below.
```

- [x] **Step 5: Add `markLspState`.** Port of `AppViewModel.kt:345-359`, placed right after `reduce()`:

```kotlin
    /** Patch the `state` (and optionally `error`) of every [ServerFrame.LspStatus] entry matching
     *  [session] + [serverId]; used by the lsp_ready/lsp_error/lsp_exit frames, which only carry
     *  session+serverId while [_lspStatus] is keyed by "session|path" (AppViewModel:345-359 port). */
    private fun markLspState(session: String?, serverId: String?, state: String, error: String? = null) {
        if (serverId == null) return
        _lspStatus.update { map ->
            map.mapValues { (_, status) ->
                if (status.session == session && status.serverId == serverId) {
                    status.copy(state = state, error = error ?: status.error)
                } else {
                    status
                }
            }
        }
    }
```

- [x] **Step 6: Add the outbound senders**, mirroring the `editorOpen`/`editorClose` idiom right below them (~line 573):

```kotlin
    // ── LSP control-plane senders (M4g-3; mirrors AppViewModel.lspStatusQuery/lspOpen/lspRpcOut/
    //    lspClose:832-843) ───────────────────────────────────────────────────────────────────────
    // lspClose is threaded for parity but NOT called by the connect flow in this milestone (Android
    // doesn't call it either — EditorScreen only ever calls engine.lspDisconnect() on the JS side);
    // reserved for a future explicit-teardown / settings-screen path.

    fun lspStatusQuery(session: SessionInfo, path: String) {
        stateScope.launch { runApi("lspStatusQuery") { sendFrame(ClientFrame.LspStatusQuery(session.id, path)) } }
    }

    fun lspOpen(session: SessionInfo, serverId: String) {
        stateScope.launch { runApi("lspOpen") { sendFrame(ClientFrame.LspOpen(session.id, serverId)) } }
    }

    fun lspRpcOut(session: SessionInfo, serverId: String, message: String) {
        stateScope.launch { runApi("lspRpcOut") { sendFrame(ClientFrame.LspRpcOut(session.id, serverId, message)) } }
    }

    fun lspClose(session: SessionInfo, serverId: String) {
        stateScope.launch { runApi("lspClose") { sendFrame(ClientFrame.LspClose(session.id, serverId)) } }
    }
```

- [x] **Step 7: Run the tests again.**

Run: same command as Step 2.
Expected: all 6 new tests PASS; full `DesktopAppStateReducerTest` class still green.

- [x] **Step 8: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/state/DesktopAppState.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/state/DesktopAppStateReducerTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop): fold LSP frames into DesktopAppState + add outbound LSP senders (M4g-3 T1)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 2: `DesktopLspBridge` — port of `AndroidLspBridge.kt` (TDD, pure)

**Files:**
- Create: `apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/DesktopLspBridge.kt`
- Test: Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/DesktopLspBridgeTest.kt`

This is pure Flow logic (`StateFlow`/`SharedFlow`/`withTimeoutOrNull`) with zero KCEF/Android dependency, so it ports unchanged apart from the package + doc references. Read `apps/android/src/main/kotlin/dev/supermux/android/editor/AndroidLspBridge.kt` in full before writing — every comment in it (the StateFlow value-equality reasoning) is load-bearing and must survive the port.

- [x] **Step 1: Write the failing tests.**

```kotlin
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
```

- [x] **Step 2: Run to confirm the compile failure** (no `DesktopLspBridge` class exists yet).

Run: `./gradlew :desktop:test --tests "dev.supermux.desktop.editor.DesktopLspBridgeTest"`
Expected: compile error — `Unresolved reference: DesktopLspBridge`.

- [x] **Step 3: Write `DesktopLspBridge.kt`** — a near-verbatim port of `AndroidLspBridge.kt` (only the package + a doc reference from "Android counterpart" to "desktop counterpart" change):

```kotlin
package dev.supermux.desktop.editor

import dev.supermux.proto.ServerFrame
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Flow-based LSP control-plane + relay — the desktop counterpart to Android `AndroidLspBridge.kt`
 * / iOS `LspBridge.swift` / the web `stores/lsp.ts`. The broker is a dumb JSON-RPC pipe; the real
 * LSP protocol (initialize, didOpen, completion, hover…) runs inside cm6's `LSPClient` in the
 * KCEF-hosted bundle.
 *
 * [DesktopAppState] already folds every inbound frame into app-wide flows ([lspStatus] keyed
 * "session|path", [lspRpc] a SharedFlow) — so this bridge just sends the outbound control frames
 * and awaits the corresponding flow transition with the Android/iOS timeouts. It is constructed
 * per editor panel from session-bound lambdas; all RPC is filtered by `session` (the flows are
 * app-wide — never cross-wire another session's server).
 */
class DesktopLspBridge(
    private val sessionId: String,
    private val lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>>,
    private val lspRpc: SharedFlow<ServerFrame.LspRpcIn>,
    private val lspStatusQuery: (sessionId: String, path: String) -> Unit,
    private val lspOpen: (sessionId: String, serverId: String) -> Unit,
    private val lspRpcOut: (sessionId: String, serverId: String, message: String) -> Unit,
) {
    private fun statusKey(path: String) = "$sessionId|$path"

    /**
     * Query the language-server status for [path]: send `lsp_status_query`, then await the
     * `lsp_status` frame the broker replies with (9s timeout → "unavailable", parity
     * LspBridge.swift:36-48 / AndroidLspBridge.kt:37-53). Skips a stale retained value so we wait
     * for a fresh response.
     */
    suspend fun queryStatus(path: String): ServerFrame.LspStatus {
        val key = statusKey(path)
        val prior = lspStatus.value[key]
        lspStatusQuery(sessionId, path)
        // Wait for a status OBJECT that is not the one held when we asked (=== identity).
        // If nothing is cached yet, wait the full 9s for the first response (parity iOS).
        // If a value IS cached, the broker's re-response may be value-equal → StateFlow
        // dedups and never re-emits, so wait only briefly for a *change* and otherwise reuse
        // the cached entry (the correct answer) instead of mislabelling it "unavailable".
        val window = if (prior == null) 9_000L else 1_500L
        val fresh = withTimeoutOrNull(window) {
            lspStatus.first { map -> map[key]?.let { it !== prior } == true }[key]
        }
        return fresh
            ?: prior
            ?: ServerFrame.LspStatus(session = sessionId, path = path, state = "unavailable")
    }

    /**
     * Open the server [serverId]: send `lsp_open`, then confirm it didn't fail (parity
     * LspBridge.swift:50-61 / AndroidLspBridge.kt:72-83 — both await lsp_ready/lsp_error).
     *
     * The broker spawns the process and replies `lsp_ready` (or `lsp_error`/`lsp_exit`)
     * synchronously — see src/core/lsp/bridge.ts:onOpen. These fold into [lspStatus] via
     * `markLspState` ([DesktopAppState]). BUT queryStatus already left the matching entries at
     * state="ready" (server *available*), and `lsp_ready` re-applies state="ready" → a
     * value-equal map that StateFlow DEDUPS, so a "ready" flip never emits. A failure, however,
     * flips state to "error"/"exited" → that DOES emit.
     *
     * So: caller has already confirmed status.state=="ready" (installed) and a non-empty
     * workdir (the only two failure modes in onOpen), making success the expected outcome. We
     * therefore treat "no fresh error within a short settle window" as ready, while still
     * catching a real lsp_error/lsp_exit (which emits) and returning false. Identity (===) not
     * value-equality, since lsp_error yields a copy().
     */
    suspend fun open(serverId: String): Boolean {
        fun matches(s: ServerFrame.LspStatus) = s.session == sessionId && s.serverId == serverId
        val prior = lspStatus.value.values.filter(::matches)
        fun isFreshFailure(s: ServerFrame.LspStatus) =
            matches(s) && prior.none { it === s } && (s.state == "error" || s.state == "exited")
        lspOpen(sessionId, serverId)
        // Returns the failure entry if one arrives within the window, else null (→ ready).
        val failure = withTimeoutOrNull(2_000) {
            lspStatus.map { map -> map.values.firstOrNull(::isFreshFailure) }.first { it != null }
        }
        return failure == null
    }

    /** Send an outbound JSON-RPC message from the cm6 LSP client to the broker. */
    fun rpcOut(serverId: String, message: String) = lspRpcOut(sessionId, serverId, message)

    /**
     * Inbound RPC pump — collect [lspRpc] filtered to this session (and a single server),
     * delivering each message into the cm6 client via [deliver]. Suspends until cancelled (the
     * caller runs it in a child coroutine of the connect LaunchedEffect, so a tab switch tears
     * it down). Filtering by session + serverId prevents cross-wiring.
     */
    suspend fun pumpRpcIn(serverId: String, deliver: (serverId: String, message: String) -> Unit) {
        lspRpc.collect { f ->
            if (f.session == sessionId && f.serverId == serverId) deliver(f.serverId, f.message)
        }
    }
}
```

- [x] **Step 4: Run the tests.**

Run: `./gradlew :desktop:test --tests "dev.supermux.desktop.editor.DesktopLspBridgeTest"`
Expected: all 8 tests PASS.

- [x] **Step 5: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/DesktopLspBridge.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/DesktopLspBridgeTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop): add DesktopLspBridge, a pure port of AndroidLspBridge (M4g-3 T2)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 3: `parseLspOut` + LSP JS-string builders + `DesktopEditorEngine` wiring (TDD pure pieces)

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/EditorBridgeShims.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/DesktopEditorEngine.kt:75-78, 287-289`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/EditorBridgeShimsTest.kt`

Splits cleanly into a pure half (parse + JS-string construction — tested) and a one-line engine glue half (calling `browser.executeJavaScript` — untestable, per the ground rules).

- [x] **Step 1: Write the failing pure tests.** Add to `EditorBridgeShimsTest.kt`:

```kotlin
    // ── parseLspOut (M4g-3) ──────────────────────────────────────────────────

    @Test
    fun parse_lsp_out_extracts_server_id_and_message() {
        val payload = """{"serverId":"ts","message":"{\"jsonrpc\":\"2.0\",\"id\":1}"}"""
        val (serverId, message) = parseLspOut(payload) ?: error("expected a parsed pair")
        assertEquals("ts", serverId)
        assertEquals("{\"jsonrpc\":\"2.0\",\"id\":1}", message)
    }

    @Test
    fun parse_lsp_out_returns_null_for_malformed_json() {
        assertNull(parseLspOut("not json"))
        assertNull(parseLspOut("{"))
    }

    @Test
    fun parse_lsp_out_returns_null_when_server_id_is_missing_or_blank() {
        assertNull(parseLspOut("""{"message":"hi"}"""))
        assertNull(parseLspOut("""{"serverId":"","message":"hi"}"""))
    }

    @Test
    fun parse_lsp_out_defaults_a_missing_message_to_empty_string() {
        val (serverId, message) = parseLspOut("""{"serverId":"ts"}""") ?: error("expected a parsed pair")
        assertEquals("ts", serverId)
        assertEquals("", message)
    }

    // ── LSP JS-statement builders (M4g-3; pure — mirrors EditorPushPlanner's cmSet* builders) ──

    @Test
    fun lsp_connect_js_quotes_all_four_arguments() {
        val js = lspConnectJs("ts", "file:///root/", "file:///root/a.ts", "typescript")
        assertEquals(
            "window.cmLspConnect(\"ts\",\"file:///root/\",\"file:///root/a.ts\",\"typescript\")",
            js,
        )
    }

    @Test
    fun lsp_connect_js_escapes_a_uri_containing_quotes_or_spaces() {
        val js = lspConnectJs("ts", "file:///my project/", "file:///my \"weird\" file.ts", "typescript")
        assertTrue(js.contains("\\\"weird\\\""), "interior quote not escaped: $js")
        assertTrue(js.contains("my project"))
    }

    @Test
    fun lsp_message_js_quotes_both_arguments() {
        val js = lspMessageJs("ts", "{\"id\":1}")
        assertEquals("window.cmLspMessage(\"ts\",\"{\\\"id\\\":1}\")", js)
    }

    @Test
    fun lsp_disconnect_js_is_a_guarded_call() {
        assertEquals("window.cmLspDisconnect && window.cmLspDisconnect()", lspDisconnectJs())
    }
```

- [x] **Step 2: Run to confirm failure.**

Run: `./gradlew :desktop:test --tests "dev.supermux.desktop.editor.EditorBridgeShimsTest"`
Expected: compile errors — `parseLspOut`, `lspConnectJs`, `lspMessageJs`, `lspDisconnectJs` unresolved.

- [x] **Step 3: Implement in `EditorBridgeShims.kt`.** Add near `parseBridgeEvent` (after its closing brace, before `@kotlinx.serialization.Serializable private data class BridgePayload`):

```kotlin
/**
 * Parse cm6's outbound `{serverId,message}` JSON payload (posted via the shim's `lspOut` hook — see
 * [bridgeShimJs]) → (serverId, message). Uses kotlinx.serialization (NOT `org.json`, unlike Android's
 * `parseLspOut` in `EditorScreen.kt:586-591` — desktop convention throughout this module). Returns
 * null for malformed JSON or a missing/blank `serverId`; a missing `message` defaults to "".
 */
internal fun parseLspOut(payload: String): Pair<String, String>? {
    val parsed = try {
        bridgeJson.decodeFromString<LspOutPayload>(payload)
    } catch (_: SerializationException) {
        return null
    } catch (_: IllegalArgumentException) {
        return null
    }
    return if (parsed.serverId.isEmpty()) null else parsed.serverId to parsed.message
}

@kotlinx.serialization.Serializable
private data class LspOutPayload(val serverId: String = "", val message: String = "")

// ── LSP JS-statement builders (pure — mirrors EditorPushPlanner's cmSet* builders; the engine
//    forwards these strings verbatim to `browser.executeJavaScript`) ──────────────────────────

/** JS to connect cm6's LSP client for the active file (port of Android EditorEngine.kt:247-252). */
internal fun lspConnectJs(serverId: String, rootUri: String, fileUri: String, languageId: String): String =
    "window.cmLspConnect(${jsQuote(serverId)},${jsQuote(rootUri)},${jsQuote(fileUri)},${jsQuote(languageId)})"

/** JS to deliver an inbound JSON-RPC message string to the cm6 LSP client for [serverId]. */
internal fun lspMessageJs(serverId: String, message: String): String =
    "window.cmLspMessage(${jsQuote(serverId)},${jsQuote(message)})"

/** JS to tear down all cm6 LSP connections and revert to a plain editor. Guarded (`&&`) exactly
 *  like Android's `EditorEngine.lspDisconnect` — `window.cmLspDisconnect` may not exist if cm6
 *  never finished booting the LSP client machinery. */
internal fun lspDisconnectJs(): String = "window.cmLspDisconnect && window.cmLspDisconnect()"
```

- [x] **Step 4: Run the pure tests.**

Run: same command as Step 2.
Expected: all new tests PASS.

- [x] **Step 5: Wire `DesktopEditorEngine.kt`.** Add the callback var alongside `onChange`/`onSave`/`onReady`/`onFontSize` (~line 75-78):

```kotlin
    var onChange: (String) -> Unit = {}
    var onSave: () -> Unit = {}
    var onReady: () -> Unit = {}
    var onFontSize: (Int) -> Unit = {}
    /** Outbound LSP JSON-RPC from cm6's `LSPClient`, parsed to (serverId, message). */
    var onLspOut: (serverId: String, message: String) -> Unit = { _, _ -> }
```

Replace the `LspOut` branch in `applyEvent` (lines 287-289):

```kotlin
            // Outbound LSP JSON-RPC — parse {serverId,message} and forward to the bridge (M4g-3).
            is BridgeEvent.LspOut -> {
                val parsed = parseLspOut(event.payload)
                if (parsed == null) {
                    println("[DesktopEditorEngine] ignoring malformed lspOut payload (${event.payload.take(200)})")
                } else {
                    onLspOut(parsed.first, parsed.second)
                }
            }
```

Add the three JS-push methods right before `dispose()` (~line 163):

```kotlin
    // ── LSP bridge (mirrors setDocument/revealLine — drives the cm6 LSP client over the shim) ────

    /** Connect the cm6 LSP client for the active file (port of Android EditorEngine.kt:247-252).
     *  A no-op before the browser exists; cm6's own `window.cmLspConnect` guards against running
     *  before its init (cm6-entry.mjs), so it's safe to call even before [ready]. */
    fun lspConnect(serverId: String, rootUri: String, fileUri: String, languageId: String) {
        val b = browser ?: return
        b.executeJavaScript(lspConnectJs(serverId, rootUri, fileUri, languageId), b.url ?: "", 0)
    }

    /** Deliver an inbound JSON-RPC message string to the cm6 LSP client for [serverId]. */
    fun lspMessage(serverId: String, message: String) {
        val b = browser ?: return
        b.executeJavaScript(lspMessageJs(serverId, message), b.url ?: "", 0)
    }

    /** Tear down all cm6 LSP connections and revert to a plain editor. */
    fun lspDisconnect() {
        val b = browser ?: return
        b.executeJavaScript(lspDisconnectJs(), b.url ?: "", 0)
    }
```

- [x] **Step 6: Compile the whole module** (no engine-level unit test is possible here — KCEF can't boot in `kotlin.test`; this step just proves it compiles and the existing suite still passes).

Run: `./gradlew :desktop:compileKotlin :desktop:test 2>&1 | tail -80`
Expected: BUILD SUCCESSFUL; full desktop test suite green (baseline + Task 1/2/3 additions, no regressions).

- [x] **Step 7: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/EditorBridgeShims.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/DesktopEditorEngine.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/EditorBridgeShimsTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop): parse LSP outbound payloads + push LSP JS into the KCEF engine (M4g-3 T3)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 4: `WebCodeEditor.kt` — thread LSP through `EditorSurface` via a new `EditorLspHandle` seam

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/WebCodeEditor.kt`

`EditorSurface` is the sole owner of the live `DesktopEditorEngine` (`EditorPanel` never sees it directly — a deliberate encapsulation boundary already established by `EditorScrollReader`, ~line 85-100). This task adds a second, LSP-shaped seam of the same kind so `EditorPanel`'s connect effect (Task 5) can drive `lspConnect`/`lspMessage`/`lspDisconnect` and observe engine-ready, without `EditorSurface` giving up engine ownership. No new automated test — this is pure Compose wiring around the existing (untested) KCEF glue; Task 5's pure URI-helper tests plus a full recompile are the verification for this task.

- [x] **Step 1: Add the `EditorLspHandle` class**, right after `EditorScrollReader` (~line 88):

```kotlin
/**
 * Seam letting the panel drive the live engine's LSP bridge (connect/message/disconnect) without
 * owning the engine itself (which stays encapsulated in [EditorSurface]) — mirrors
 * [EditorScrollReader]. Before an engine is attached (KCEF not Ready yet) or after one is disposed,
 * every call is a harmless no-op; [EditorSurface] rebinds the real engine calls into this each time
 * its engine identity changes (see its `SideEffect`).
 */
class EditorLspHandle {
    // NOTE: the bindable fields are named onConnect/onMessage/onDisconnect (not connect/message/
    // disconnect) — a property and a member function CANNOT share one name in the same Kotlin
    // class (it's a "conflicting declarations" compile error, not an overload), so the public
    // call-surface below needs distinct backing-field names.
    internal var onConnect: (serverId: String, rootUri: String, fileUri: String, languageId: String) -> Unit =
        { _, _, _, _ -> }
    internal var onMessage: (serverId: String, message: String) -> Unit = { _, _ -> }
    internal var onDisconnect: () -> Unit = {}

    fun connect(serverId: String, rootUri: String, fileUri: String, languageId: String) =
        onConnect(serverId, rootUri, fileUri, languageId)
    fun message(serverId: String, message: String) = onMessage(serverId, message)
    fun disconnect() = onDisconnect()
}
```

- [x] **Step 2: Add `onLspOut`, `onEngineReadyChange`, `lspHandle` parameters to `EditorSurface`** (~line 112-131, alongside the existing `scrollReader` param):

```kotlin
@Composable
fun EditorSurface(
    kcefState: KcefState,
    content: String,
    filename: String,
    lineWrap: Boolean,
    fontSize: Int,
    scrollTop: Int,
    revealLine: Pair<Int, Int?>?,
    onChange: (String) -> Unit,
    onSave: () -> Unit,
    onRevealConsumed: () -> Unit,
    onFontSize: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onEnsureInit: (CoroutineScope) -> Unit = { KcefRuntime.ensureInit(it) },
    indexUrlProvider: () -> String? = { defaultIndexUrl() },
    engineFactory: (String, Boolean, Int) -> DesktopEditorEngine = { url, lw, fs ->
        DesktopEditorEngine(url, lw, fs)
    },
    scrollReader: EditorScrollReader? = null,
    // LSP (M4g-3): forward cm6's outbound JSON-RPC to the caller's bridge, report the engine's
    // ready-gate so the caller can wait for it, and bind the caller's [EditorLspHandle] to the
    // live engine's push methods — same non-ownership seam pattern as [scrollReader].
    onLspOut: (serverId: String, message: String) -> Unit = { _, _ -> },
    onEngineReadyChange: (Boolean) -> Unit = {},
    lspHandle: EditorLspHandle? = null,
) {
```

- [x] **Step 3: Bind the handle + callback in the existing `SideEffect`** (~line 157-164):

```kotlin
    SideEffect {
        if (engine != null) {
            engine.onChange = onChange
            engine.onSave = onSave
            engine.onFontSize = onFontSize
            engine.onLspOut = onLspOut
            scrollReader?.read = { cb -> engine.getScrollTop(cb) }
            lspHandle?.onConnect = engine::lspConnect
            lspHandle?.onMessage = engine::lspMessage
            lspHandle?.onDisconnect = engine::lspDisconnect
        }
    }
```

- [x] **Step 4: Report engine-ready to the caller.** Right after the existing `val engineReady by (engine?.ready ?: remember { MutableStateFlow(false) }).collectAsState()` line (~166):

```kotlin
    val engineReady by (engine?.ready ?: remember { MutableStateFlow(false) }).collectAsState()
    LaunchedEffect(engineReady) { onEngineReadyChange(engineReady) }
```

- [x] **Step 5: Compile.**

Run: `./gradlew :desktop:compileKotlin`
Expected: BUILD SUCCESSFUL (no call sites break — every new param has a default).

- [x] **Step 6: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/WebCodeEditor.kt
git commit -m "$(cat <<'EOF'
feat(desktop): add EditorLspHandle seam threading LSP push/ready through EditorSurface (M4g-3 T4)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 5: `EditorPanel.kt` connect-sequencing effect + URI helpers + `SessionDetail.kt` threading (TDD pure helpers; port the effect)

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/EditorPanel.kt`
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/SessionDetail.kt:113-148`
- Test: `apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/EditorPanelMarkdownTest.kt` (or a new `EditorPanelLspTest.kt` — either is fine; put the pure URI tests next to `isMarkdownPath`'s test if that file is still small, otherwise a new file keeps it focused)

Before writing the effect, check `apps/desktop/.../editor/EditorState.kt` for `var showDiff` — if M4g-2 hasn't landed yet, drop the `editor.showDiff` term from the bail condition below and leave a `// TODO(M4g-2): AND in !editor.showDiff once it exists` comment instead.

- [x] **Step 1: Write the failing URI-helper tests.** Create `apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/EditorPanelLspUriTest.kt`:

```kotlin
package dev.supermux.desktop.editor

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pure `file://` URI construction for the LSP connect flow — port of Android
 *  `EditorScreen.kt:595-603`'s `joinPath`/`pathToUri`/`dirUri`. */
class EditorPanelLspUriTest {

    @Test fun join_path_puts_exactly_one_slash_between_dir_and_relative_path() {
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj/", "src/a.ts"))
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj", "src/a.ts"))
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj", "/src/a.ts"))
        assertEquals("/home/user/proj/src/a.ts", joinPath("/home/user/proj/", "/src/a.ts"))
    }

    @Test fun path_to_uri_percent_encodes_everything_except_slash() {
        assertEquals("file:///home/user/proj/a.ts", pathToUri("/home/user/proj/a.ts"))
        assertEquals("file:///home/user/my%20project/a.ts", pathToUri("/home/user/my project/a.ts"))
    }

    @Test fun path_to_uri_encodes_special_characters_in_a_segment() {
        val uri = pathToUri("/home/user/weird#name/a b.ts")
        // '#' and the space must be percent-encoded so the string is a legal URI; '/' is preserved.
        assertEquals("file:///home/user/weird%23name/a%20b.ts", uri)
    }

    @Test fun dir_uri_always_ends_with_a_trailing_slash() {
        assertEquals("file:///home/user/proj/", dirUri("/home/user/proj"))
        assertEquals("file:///home/user/proj/", dirUri("/home/user/proj/"))
    }
}
```

- [x] **Step 2: Run to confirm the compile failure.**

Run: `./gradlew :desktop:test --tests "dev.supermux.desktop.editor.EditorPanelLspUriTest"`
Expected: compile error — `joinPath`, `pathToUri`, `dirUri` unresolved.

- [x] **Step 3: Implement the URI helpers in `EditorPanel.kt`**, near the existing `isMarkdownPath`/`editorPreviewGate` helpers at the bottom of the file:

```kotlin
// ─── LSP file:// URI helpers (M4g-3; port of Android EditorScreen.kt:595-603) ─────────────────

/** Join a directory and a workdir-relative path with exactly one '/' between them. */
internal fun joinPath(dir: String, rel: String): String {
    val d = dir.removeSuffix("/")
    val r = rel.removePrefix("/")
    return "$d/$r"
}

/**
 * `file://` URI for an absolute path, percent-encoding every path SEGMENT except the `/`
 * separators — the JVM equivalent of Android's `android.net.Uri.encode(abs, "/")`
 * (`EditorScreen.kt:601`). [java.net.URLEncoder] is form-encoding (encodes space as `+`, not
 * `%20`), so each segment is encoded individually and `+` is repaired to `%20` before rejoining.
 */
internal fun pathToUri(abs: String): String {
    val encoded = abs.split("/").joinToString("/") { segment ->
        java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
    }
    return "file://$encoded"
}

/** Directory URI for a workdir — always trailing-slash (Android `EditorScreen.kt:603` parity). */
internal fun dirUri(workdir: String): String = pathToUri(workdir.removeSuffix("/")) + "/"
```

- [x] **Step 4: Run the URI tests.**

Run: same command as Step 2.
Expected: all 4 URI tests PASS.

- [x] **Step 5: Add the LSP-flow parameters to `EditorPanel`'s signature**, mirroring Android's `EditorScreen.kt:94-103` shape (the panel takes flows + sessionId-echoing lambdas — the panel constructs its own `DesktopLspBridge`, mirroring how Android's `EditorScreen` builds its own `AndroidLspBridge`). Add right after the existing `fsChanges` param (~line 104):

```kotlin
    fsChanges: SharedFlow<ServerFrame.FsChanged> = MutableSharedFlow(),
    // LSP (M4g-3) — app-wide flows + outbound senders, mirrors Android EditorScreen.kt:94-103.
    // EditorPanel builds its own DesktopLspBridge from these (same non-owning-the-flows shape as
    // fsChanges above); DesktopEditorPanel/SessionDetail wires the real DesktopAppState.lsp*.
    lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>> = MutableStateFlow(emptyMap()),
    lspRpc: SharedFlow<ServerFrame.LspRpcIn> = MutableSharedFlow(),
    lspStatusQuery: (String, String) -> Unit = { _, _ -> },
    lspOpen: (String, String) -> Unit = { _, _ -> },
    lspRpcOut: (String, String, String) -> Unit = { _, _, _ -> },
```

Add the needed imports at the top of the file: `import kotlinx.coroutines.flow.MutableStateFlow` and `import kotlinx.coroutines.flow.StateFlow` (check they aren't already imported before adding — `StateFlow` already is, from `scrollReader`'s type; `MutableStateFlow` is new).

- [x] **Step 6: Build the bridge + handle + ready state inside `EditorPanel`**, right after the existing `val reader = scrollReader ?: remember { EditorScrollReader() }` line (~line 130):

```kotlin
    val reader = scrollReader ?: remember { EditorScrollReader() }

    // LSP (M4g-3): the bridge drives the control-plane flows; the handle is EditorSurface's
    // non-owning seam for pushing lspConnect/lspMessage/lspDisconnect into the live engine
    // (mirrors `reader` above); engineReady mirrors the live engine's cm6-first-paint gate.
    val bridge = remember(sessionId, lspStatus, lspRpc) {
        DesktopLspBridge(
            sessionId = sessionId,
            lspStatus = lspStatus,
            lspRpc = lspRpc,
            lspStatusQuery = lspStatusQuery,
            lspOpen = lspOpen,
            lspRpcOut = lspRpcOut,
        )
    }
    val lspHandle = remember(sessionId) { EditorLspHandle() }
    var engineReady by remember(sessionId) { mutableStateOf(false) }
```

- [x] **Step 7: Thread the new params into the `EditorSurface(...)` call** (~line 355-370): add `onLspOut = { serverId, message -> bridge.rpcOut(serverId, message) }`, `onEngineReadyChange = { engineReady = it }`, `lspHandle = lspHandle`.

- [x] **Step 8: Add the connect-sequencing `LaunchedEffect`.** IMPORTANT placement: it reads `showPreview`, a `val` that isn't computed until `~line 221-223` (`val previewGate = editorPreviewGate(...)`, `val showPreview = previewGate.showPreview`) — well AFTER the `fsChanges` effect and `revealFile`/`pendingOpen` block earlier in the function. Insert it AFTER that `showPreview` line and BEFORE the `Box(modifier.fillMaxSize()) {` that starts the UI tree (~line 225), not up near the other early effects. This is the port of `EditorScreen.kt:192-212`:

```kotlin
    // LSP connect sequencing (M4g-3; port of Android EditorScreen.kt:192-212). Re-runs whenever the
    // session, active file, preview mode, or the live engine's ready-gate changes; LaunchedEffect
    // cancellation tears down the prior connection on a fast tab switch — same pattern as Android's
    // engine.failed re-key.
    // NOTE — `sessionId` MUST be in the key list. Desktop reuses ONE SessionDetail/EditorPanel across
    // session switches (WorkspaceRoot renders SessionDetail WITHOUT key(session.id) — see
    // DesktopAppState.kt:130-135's comment), unlike Android which recreates EditorScreen via NavHost.
    // Without `sessionId` here, two sessions whose (activeTabPath, showPreview, engineReady) tuple
    // coincides across a switch (realistic: same relative path, both no-tab, or both mid-load with
    // engineReady=false — which it ALWAYS is right after a switch) would NOT relaunch the effect, so
    // the coroutine keeps running with the previous session's stale bridge/lspHandle/workdir closures
    // and drives LSP for the wrong session. `bridge`/`lspHandle` are already remember(sessionId){}
    // (Step 6) for the same reason; the effect key must match. (Android's EditorScreen.kt:195 omits it
    // only because its NavHost lifecycle makes it structurally unnecessary there.)
    LaunchedEffect(sessionId, editor.activeTabPath, showPreview, engineReady) {
        lspHandle.disconnect()
        val tab = editor.activeTab
        if (showPreview || tab == null || workdir.isEmpty() || !engineReady) {
            return@LaunchedEffect
        }
        val status = bridge.queryStatus(tab.path)
        val serverId = status.serverId
        // Status.isReady: supported && serverId != null && state == "ready" (LspBridge.swift:18 /
        // AndroidLspBridge parity).
        if (!status.supported || serverId == null || status.state != "ready") {
            println("[lsp] '${tab.path}' not ready for LSP (state=${status.state}, supported=${status.supported})")
            return@LaunchedEffect
        }
        // Pump inbound RPC for this server in a child coroutine (cancelled with this effect).
        launch {
            bridge.pumpRpcIn(serverId) { sid, msg ->
                println("[lsp] rpc_in $sid ${msg.take(120)}")
                lspHandle.message(sid, msg)
            }
        }
        if (!bridge.open(serverId)) {
            println("[lsp] open($serverId) failed for '${tab.path}'")
            return@LaunchedEffect
        }
        val rootUri = dirUri(workdir)
        val fileUri = pathToUri(joinPath(workdir, tab.path))
        println("[lsp] connecting $serverId root=$rootUri file=$fileUri lang=${status.languageId}")
        lspHandle.connect(serverId, rootUri, fileUri, status.languageId ?: "")
    }
```

(If `editor.showDiff` exists at implementation time — check per the task preamble — add `editor.showDiff ||` to the bail condition and key the effect on `editor.showDiff` too, matching Android's exact five-way key list.)

- [x] **Step 9: Thread the LSP params through `DesktopEditorPanel` in `SessionDetail.kt`** (~line 113-148), adding to the `EditorPanel(...)` call right after the existing `fsChanges = app.fsChanges,` line:

```kotlin
        fsChanges = app.fsChanges,
        // LSP (M4g-3): the panel builds its own DesktopLspBridge from these — see EditorPanel's
        // lspStatus/lspRpc/lspStatusQuery/... params. The sessionId argument these lambdas receive
        // is always this same `session.id` (DesktopLspBridge echoes its own constructor sessionId
        // back through them, mirroring Android's AndroidLspBridge) — ignored here since `session`
        // is already captured.
        lspStatus = app.lspStatus,
        lspRpc = app.lspRpc,
        lspStatusQuery = { _, path -> app.lspStatusQuery(session, path) },
        lspOpen = { _, serverId -> app.lspOpen(session, serverId) },
        lspRpcOut = { _, serverId, message -> app.lspRpcOut(session, serverId, message) },
```

- [x] **Step 10: Compile + run the full desktop suite.**

Run: `./gradlew :desktop:compileKotlin :desktop:test 2>&1 | tail -100`
Expected: BUILD SUCCESSFUL; all tests green (baseline + Task 1/2/3/5 additions).

- [x] **Step 11: Commit.**

```bash
git add apps/desktop/src/main/kotlin/dev/supermux/desktop/editor/EditorPanel.kt \
        apps/desktop/src/main/kotlin/dev/supermux/desktop/workspace/SessionDetail.kt \
        apps/desktop/src/test/kotlin/dev/supermux/desktop/editor/EditorPanelLspUriTest.kt
git commit -m "$(cat <<'EOF'
feat(desktop): wire the LSP connect-sequencing effect into EditorPanel (M4g-3 T5)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

### Task 6: Live verification (`SM_LSP` hook) + full suite run + report

**Files:**
- Modify: `apps/desktop/src/main/kotlin/dev/supermux/desktop/Main.kt` (env-hook catalog comment + the hook itself)

- [x] **Step 1: Add the `SM_LSP` hook.** In the env-hook catalog comment block near the top of `Main.kt` (~line 33-76), add a line after the `SM_OPEN_FILE`/`SM_EDITOR_PREVIEW` entries:

```
//   SM_LSP="name|path"            — open a file and let the real LSP connect flow run (M4g-3) [main]
```

Add the hook itself, right after the existing `SM_EDITOR_PREVIEW` block (~line 362, before the `SM_LAUNCH_TEST` block):

```kotlin
                    // Headless LSP-connect verification hook (M4g-3): SM_LSP="<session-name>|<file-path>"
                    // resolves the named session, SELECTS it, flips its editor pane on, and opens
                    // <file-path> via the SAME externalOpen chain SM_OPEN_FILE/SM_EDITOR_PREVIEW use
                    // above. Opening the file is enough — EditorPanel's OWN connect-sequencing
                    // LaunchedEffect (Task 5) then drives the real lsp_status_query → lsp_open →
                    // cmLspConnect round trip against the broker's live language server once the file
                    // becomes the active tab and the KCEF engine reports ready; no further driving is
                    // needed from here. Point <file-path> at a file extension the broker's LSP config
                    // covers (GET /settings/editor lists supported extensions per server, e.g. the
                    // typescript server covers .ts/.tsx/.js/...). Off by default; harmless in production.
                    val lspTest = System.getenv("SM_LSP")?.takeIf { it.isNotBlank() }
                    if (lspTest != null) {
                        LaunchedEffect(app) {
                            val sep = lspTest.indexOf('|')
                            if (sep <= 0) {
                                println("[lsp] bad SM_LSP (expected <session-name>|<file-path>)")
                                return@LaunchedEffect
                            }
                            val name = lspTest.substring(0, sep)
                            val path = lspTest.substring(sep + 1)
                            var target = app.sessions.value.firstOrNull { it.name == name }
                            val deadline = System.currentTimeMillis() + 30_000
                            while (target == null && System.currentTimeMillis() < deadline) {
                                delay(500)
                                target = app.sessions.value.firstOrNull { it.name == name }
                            }
                            val t = target
                            if (t == null) {
                                println("[lsp] session '$name' not found in snapshot after 30s")
                                return@LaunchedEffect
                            }
                            ui.selectedId = t.id
                            ui.layout.setPanes(t.id, ui.layout.panesFor(t.id).copy(editor = true))
                            ui.externalOpen = t.id to dev.supermux.ui.FilePathRef(path, null)
                            println(
                                "[lsp] requested '$path' in ${t.name} (${t.id}) — " +
                                    "EditorPanel's connect effect drives the LSP round trip from here",
                            )
                        }
                    }
```

- [x] **Step 2: Compile.**

Run: `./gradlew :desktop:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [x] **Step 3: Set up a throwaway workdir with a deliberate TypeScript error.**

```bash
mkdir -p /tmp/m4g3-lsp-verify
cat > /tmp/m4g3-lsp-verify/broken.ts <<'EOF'
function double(n: number): number {
  return n * 2;
}
const oops: number = double("not a number");
EOF
cd /tmp/m4g3-lsp-verify && git init -q && git add -A && git commit -q -m "seed"
```

Spawn a throwaway session pointed at that workdir through the broker's normal spawn path (same mechanism the other M4-series live-verification tasks use — e.g. via the paired broker's `/sessions` endpoint or an existing `SM_LAUNCH_TEST` run) so a real session with `workdir=/tmp/m4g3-lsp-verify` exists before launching the app. Name it something identifiable, e.g. `m4g3-lsp-check`.

- [x] **Step 4: Launch the app under Xvfb with the hook, screenshot, and inspect logs.**

```bash
export DISPLAY=:77
export SKIKO_RENDER_API=SOFTWARE
export SM_LSP="m4g3-lsp-check|broken.ts"
mkdir -p /home/ahmet/.cache/m4g3v-shots
# (use the paired config at /home/ahmet/.cache/smx-test-config per the standing ground rules)
# ... launch the desktop app with the above env, wait ~10-15s for the connect sequence to settle ...
xwd -root -display :77 -out /home/ahmet/.cache/m4g3v-shots/lsp-connect.xwd
python3 -c "from PIL import Image; Image.open('/home/ahmet/.cache/m4g3v-shots/lsp-connect.xwd').save('/home/ahmet/.cache/m4g3v-shots/lsp-connect.png')"
```

Confirm from the captured stdout log (grep for the `[lsp]` tag) that: (a) `[lsp] requested 'broken.ts' ...` fired, (b) `[lsp] connecting ts root=... file=... lang=typescript` fired (not the `not ready for LSP` branch — if it IS that branch, the status never reached `state=="ready"`; check the broker's `GET /settings/editor` output for the typescript server's live state before debugging further), (c) at least one `[lsp] rpc_in ts ...` line appears (the inbound JSON-RPC — most likely `textDocument/publishDiagnostics` for the deliberate type error, but any inbound message proves the pump is live). Confirm from the screenshot that the editor renders `broken.ts` with a visible diagnostic marker (squiggle/gutter dot) under the `double("not a number")` line — the strongest visual proof the diagnostic round-tripped all the way from `tsserver` through the broker back into cm6.

- [x] **Step 5: Clean up.** Kill the throwaway session (never restart the broker), remove `/tmp/m4g3-lsp-verify`, and reset any `ui-state.json` selection changes the run made if this shares a persistent profile with manual testing.

- [x] **Step 6: Run the full suites (`--rerun-tasks`).**

```bash
cd apps/desktop && ../../gradlew :desktop:test --rerun-tasks 2>&1 | tail -100
cd ../.. && ./gradlew :shared:jvmTest --rerun-tasks 2>&1 | tail -100
./gradlew :android:compileDebugKotlin 2>&1 | tail -60
```

Expected: desktop suite green at (pre-M4g-3 baseline + ~26 new tests: 6 reducer + 8 bridge + 8 shims-pure + 4 URI = 26, adjust for the exact count written); shared jvmTest unchanged (no shared code touched); android compile green (no android code touched).

- [x] **Step 7: Tick every checkbox in this plan, then commit + report.**

```bash
git add docs/superpowers/plans/2026-07-10-desktop-m4g3-lsp-connect.md
git commit -m "$(cat <<'EOF'
docs(desktop): M4g-3 LSP connect-flow plan executed

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

Report should cover: the desktop LSP connect flow now fully wired (status query → open → connect → bidirectional RPC pump), the live-verification evidence (log lines + screenshot path), the exact final test count added, and explicitly flag what M4g-4 inherits (the LSP settings screen: `lsp_install*` frames, `ClientFrame.LspInstall`, install/enable/disable UI — none of which this plan touched).

**LIVE-VERIFICATION EVIDENCE (2026-07-10, executed):** The full LSP round-trip rendered live over REAL KCEF against the live broker (`127.0.0.1:9898`, typescript server `ready`). Throwaway session `m4g3-lsp-check` (workdir `/tmp/m4g3-lsp-verify`, `worktree:false`) opened `broken.ts` (`const oops: number = double("not a number");`). The app log (`/home/ahmet/.cache/desktop-run.log`) showed the exact sequence: `[lsp] requested 'broken.ts'` → `rx LspStatus` → `rx LspReady` → `[lsp] connecting typescript root=file:///tmp/m4g3-lsp-verify/ file=file:///tmp/m4g3-lsp-verify/broken.ts lang=typescript` → four `[lsp] rpc_in typescript` frames (the `initialize` result `id:1` with capabilities, `$/typescriptVersion` 6.0.3 bundled, `window/logMessage`, and **two `textDocument/publishDiagnostics` for `broken.ts`**). No `LspError`/`LspExit`/`not ready`/`open() failed` at any point. Screenshot `/home/ahmet/.cache/m4g3v-shots/m4g3v-diagnostics.png` (+ zoom `m4g3v-diagnostics-zoom.png`) shows `broken.ts` in cm6 with a red diagnostic gutter dot on line 4 and a red squiggle under `"not a number"` — proving the diagnostic round-tripped editor→broker→tsserver→broker→editor→cm6. Watch items: (a) `languageId` was non-empty (`lang=typescript`); (b) `open()` returned true via the 2s no-fresh-error settle window (by design — `lsp_ready` re-applies `state="ready"`, value-equal → StateFlow dedups, so it never triggers an early return), and this was a *legitimate* ready — a real `lsp_ready` frame was observed (`rx LspReady`, logged BEFORE `connecting`) and diagnostics actually rendered. `SM_LSP` hook: commit `10931a4`. Suite green at 527 (`:desktop:test --rerun-tasks`). Throwaway session deleted, temp dir removed, self-spawned Xvfb :77 killed, `ui-state.json` reset.

## Self-review notes

**Spec coverage:** every element of the requested data flow is a task — outbound relay (`BridgeEvent.LspOut` → `parseLspOut` → `bridge.rpcOut` → `ClientFrame.LspRpcOut`, Tasks 1+3), inbound relay (`ServerFrame.LspRpcIn` → `lspRpc` SharedFlow → `bridge.pumpRpcIn` → `lspHandle.message` → `cmLspMessage`, Tasks 1+2+4+5), and the full connect sequence (disconnect → bail-gates → `queryStatus` → `open` → pump-launch → `lspConnect` with `file://` URIs, Task 5) — with the ready-gate-over-delay(1200) upgrade and the `rendererLost`/engine-recovery re-trigger both called out explicitly in Task 5's effect keys. The M4g-4 boundary (LSP settings/install screen) is named as out-of-scope in the Goal, the Ground rules, Task 1's step 4 comment, and the Task 6 report ask — four separate places a reviewer can catch scope creep.

**Placeholder scan:** every task step that touches code shows the actual code (no "add appropriate handling"); every test shows real assertions with concrete expected values, not descriptions of what to assert. Task 6's live-verification bash is illustrative shell (spawn/screenshot/grep) rather than a fully scripted one-liner because the exact spawn/launch invocation depends on which paired-broker/launch convention is live at execution time (same as every prior M4-series plan's live-verification task) — Step 3/4 spell out precisely what must exist and what to check, which is the actionable content.

**Type consistency:** `DesktopLspBridge`'s constructor shape (`sessionId`, `lspStatus: StateFlow<Map<String, ServerFrame.LspStatus>>`, `lspRpc: SharedFlow<ServerFrame.LspRpcIn>`, three `(String, String[, String]) -> Unit` lambdas) is defined once in Task 2 and referenced identically in Task 5's `EditorPanel` wiring. `EditorLspHandle`'s `connect`/`message`/`disconnect` method names match what Task 5's `LaunchedEffect` calls (`lspHandle.disconnect()`, `lspHandle.message(sid, msg)`, `lspHandle.connect(serverId, rootUri, fileUri, languageId)`) and what Task 4's `EditorSurface` `SideEffect` binds to the distinct backing fields (`lspHandle?.onConnect = engine::lspConnect`, etc.) — the public call surface (`connect`/`message`/`disconnect`) is consistent everywhere the panel calls it. `DesktopAppState`'s four sender signatures (`lspStatusQuery(session: SessionInfo, path: String)` etc.) match both the Task 1 test calls and the Task 5 `SessionDetail.kt` wiring (`{ _, path -> app.lspStatusQuery(session, path) }`).

**Design choices flagged for the reviewer:** (1) extracting `lspConnectJs`/`lspMessageJs`/`lspDisconnectJs` as pure functions (Task 3) rather than inlining the JS strings directly in `DesktopEditorEngine` — this is a deliberate improvement over Android's shape (whose `EditorEngine.kt` inlines the JS string construction directly in the JS-push methods) so the string construction is unit-tested rather than being pure untested engine glue; flag it as a backport candidate if Android's discipline gets reviewed later. (2) `EditorLspHandle` is a NEW seam type, not a reuse of `EditorScrollReader`'s exact shape, because it needs THREE bound functions (connect/message/disconnect) vs. `EditorScrollReader`'s one (`read`) — kept as a separate small class rather than overloading `EditorScrollReader` with unrelated concerns. (3) `lspClose` is threaded end-to-end (frame, sender, bridge param slot) but never actually CALLED by the connect flow, exactly mirroring Android's own dangling `lspClose` param in `EditorScreen.kt` — this is intentional parity, not an oversight, and is called out in Task 1 Step 6's comment so a reviewer doesn't "fix" it into scope creep.
