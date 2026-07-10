package dev.supermux.desktop.state

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.LogEntry
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Reducer-only tests for [DesktopAppState] — no live WebSocket. The constructor's
 * `connectOnInit = false` skips the frame-collect / client.run() / heartbeat launches, so
 * [DesktopAppState.reduce] and the send helpers can be exercised in isolation. Outbound
 * frames are captured through the injectable `sendFrameOverride` seam.
 *
 * Semantics mirror apps/android/.../AppViewModel.kt — where Android behaviour differs from
 * this task's original test sketch, Android wins (see notes on individual tests).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopAppStateReducerTest {
    private val sent = mutableListOf<ClientFrame>()

    private fun state() = DesktopAppState(
        baseUrl = "ws://test:9898",
        token = "t",
        scope = TestScope(UnconfinedTestDispatcher()),
        connectOnInit = false,
        sendFrameOverride = { sent.add(it) },
    )

    private fun session(id: String) =
        SessionInfo(id = id, name = "name-$id", workdir = "/w/$id", agent = "claude")

    @Test fun snapshot_populates_sessions_and_state() {
        val s = state()
        val entry = LogEntry(id = "m1", ts = "2026-01-01T00:00:00Z", direction = "outbound", text = "hi")
        s.reduce(
            ServerFrame.Snapshot(
                sessions = listOf(session("s1")),
                logs = mapOf("s1" to listOf(entry)),
                agentState = mapOf("s1" to AgentStatus(phase = "idle")),
            ),
        )
        assertEquals(listOf("s1"), s.sessions.value.map { it.id })
        assertEquals(listOf("m1"), s.messages.value["s1"]?.map { it.id })
        assertTrue(s.agentState.value.containsKey("s1"))
    }

    @Test fun message_append_dedups_local_echo() {
        val s = state()
        // Local echo is appended with direction "inbound" and a "local-" id (AppViewModel
        // appendOptimistic). The real inbound MessageAppend with the same text drops it.
        s.appendLocalEcho("s1", "hello world")
        assertEquals(1, s.messages.value["s1"]?.size)
        assertTrue(s.messages.value["s1"]!!.single().id.startsWith("local-"))

        s.reduce(
            ServerFrame.MessageAppend(
                session = "s1",
                entry = LogEntry(id = "real1", ts = "2026-01-01T00:00:01Z", direction = "inbound", text = "hello world"),
            ),
        )
        val msgs = s.messages.value["s1"]!!
        assertEquals(1, msgs.size)
        assertEquals("real1", msgs.single().id)
    }

    @Test fun agent_state_updates_map_and_clears_pending() {
        val s = state()
        s.markPendingSend("s1")
        assertTrue(s.pendingSend.value.contains("s1"))
        s.reduce(
            ServerFrame.AgentState(session = "s1", phase = "running", state = "working", working = true),
        )
        assertEquals("working", s.agentState.value["s1"]?.state)
        // Android tracks pendingSend as a Set and REMOVES the session on the first real
        // agent_state (it is not a nullable single value); assert the session is cleared.
        assertFalse(s.pendingSend.value.contains("s1"))
    }

    @Test fun session_removed_prunes() {
        val s = state()
        s.reduce(ServerFrame.Snapshot(sessions = listOf(session("s1"))))
        assertEquals(1, s.sessions.value.size)
        s.reduce(ServerFrame.SessionRemoved("s1"))
        assertTrue(s.sessions.value.isEmpty())
    }

    @Test fun snapshot_resets_viewing_dedup() {
        val s = state()
        s.updateViewing("s1", true)
        // First send captured; a duplicate updateViewing is deduped (no second frame).
        s.updateViewing("s1", true)
        assertEquals(1, sent.count { it is ClientFrame.Viewing })

        // A (re)connect snapshot resets the dedup cache and re-asserts viewing presence.
        s.reduce(ServerFrame.Snapshot(sessions = listOf(session("s1"))))
        val viewing = sent.filterIsInstance<ClientFrame.Viewing>()
        assertEquals(2, viewing.size)
        assertEquals(ClientFrame.Viewing("s1", true), viewing.last())
    }

    @Test fun guarded_swallows_errors_and_state_keeps_working() {
        val s = state()
        // The frame collector wraps reduce in guarded {} — one poison frame must drop one
        // update, not kill the collector. guarded must swallow (and log) the throw…
        s.guarded("reduce") { error("poison frame") }
        // …and subsequent reduces still work.
        s.reduce(ServerFrame.Snapshot(sessions = listOf(session("s1"))))
        assertEquals(listOf("s1"), s.sessions.value.map { it.id })
    }

    // ── M3 editor: fs_changed fold + editor lifecycle senders ─────────────────────────────
    @Test fun fs_changed_frame_is_broadcast_on_the_fs_changes_flow() {
        val s = state()
        val received = mutableListOf<ServerFrame.FsChanged>()
        // UnconfinedTestDispatcher runs the collector eagerly → it subscribes before the reduce,
        // so the replay-0 SharedFlow delivers the pulse.
        val job = kotlinx.coroutines.CoroutineScope(UnconfinedTestDispatcher()).launch {
            s.fsChanges.collect { received.add(it) }
        }
        s.reduce(ServerFrame.FsChanged(session = "s1", paths = listOf("src/a.kt", "src/b.kt")))
        assertEquals(1, received.size)
        assertEquals("s1", received.first().session)
        assertEquals(listOf("src/a.kt", "src/b.kt"), received.first().paths)
        job.cancel()
    }

    @Test fun editor_open_and_close_send_the_lifecycle_frames() {
        val s = state()
        s.editorOpen(session("s1"))
        s.editorClose(session("s1"))
        assertEquals(
            listOf(ClientFrame.EditorOpen("s1"), ClientFrame.EditorClose("s1")),
            sent.filter { it is ClientFrame.EditorOpen || it is ClientFrame.EditorClose },
        )
    }

    @Test fun close_cancels_owned_scope_so_no_more_outbound_sends() {
        val s = state()
        s.updateViewing("s1", true)
        assertEquals(1, sent.filterIsInstance<ClientFrame.Viewing>().size)

        s.close()
        // A CHANGED viewing state after close() would emit if the owned scope were still alive;
        // the cancelled stateScope must drop it (heartbeat and send launches are dead).
        s.updateViewing("s2", true)
        s.sendMessage("s2", "should not go out")
        assertEquals(1, sent.filterIsInstance<ClientFrame.Viewing>().size)
        assertTrue(sent.none { it is ClientFrame.Send })
    }

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

    // ── M4g-4 LSP settings: install-progress/install-done reducer fold ────────────────────
    @Test fun lsp_install_progress_appends_lines_in_order_for_the_matching_server() {
        val s = state()
        s.reduce(ServerFrame.LspInstallProgress(serverId = "pyright", line = "Fetching pyright…"))
        s.reduce(ServerFrame.LspInstallProgress(serverId = "pyright", line = "npm install -g pyright"))
        assertEquals(
            listOf("Fetching pyright…", "npm install -g pyright"),
            s.lspInstallLog.value["pyright"],
        )
    }

    @Test fun lsp_install_progress_for_one_server_does_not_touch_anothers_log() {
        val s = state()
        s.reduce(ServerFrame.LspInstallProgress(serverId = "pyright", line = "a"))
        s.reduce(ServerFrame.LspInstallProgress(serverId = "bash", line = "b"))
        assertEquals(listOf("a"), s.lspInstallLog.value["pyright"])
        assertEquals(listOf("b"), s.lspInstallLog.value["bash"])
    }

    @Test fun lsp_install_done_is_recorded_by_server_id_ok_and_error() {
        val s = state()
        s.reduce(ServerFrame.LspInstallDone(serverId = "pyright", ok = true))
        s.reduce(ServerFrame.LspInstallDone(serverId = "bash", ok = false, error = "not found"))
        assertEquals(true, s.lspInstallDone.value["pyright"]?.ok)
        assertEquals(false, s.lspInstallDone.value["bash"]?.ok)
        assertEquals("not found", s.lspInstallDone.value["bash"]?.error)
    }
}
