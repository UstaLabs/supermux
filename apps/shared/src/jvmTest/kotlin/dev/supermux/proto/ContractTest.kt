package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ContractTest {
    // ignoreUnknownKeys = false → strict decode throws if the broker emits a
    // field the Kotlin type doesn't know. That strict direction is the drift guard.
    private val json = Json { ignoreUnknownKeys = false; classDiscriminator = "type" }

    private fun load(name: String): String =
        (this::class.java.getResourceAsStream("/frames/$name.json")
            ?: error("fixture $name.json not found in test resources")).readBytes().decodeToString()

    @Test fun every_broker_fixture_parses_into_a_ServerFrame() {
        val names = listOf(
            "snapshot", "session_added", "session_removed", "session_renamed", "session_state",
            "agent_state", "agent_error", "message_append", "activity_append", "bg_tasks",
            "commands_changed", "finish_job", "session_git", "session_git_remote",
            "sessions_reordered", "session_read",
            "walkthrough_updated", "review_comment",
            "request_open", "request_closed", "error",
        )
        for (n in names) {
            val frame = json.decodeFromString<ServerFrame>(load(n))
            // Exhaustive when (no else): adding a new ServerFrame subtype will
            // fail to compile here until it's handled, forcing a matching fixture.
            when (frame) {
                is ServerFrame.Snapshot -> {}
                is ServerFrame.SessionAdded -> {}
                is ServerFrame.SessionRemoved -> {}
                is ServerFrame.SessionRenamed -> {}
                is ServerFrame.SessionsReordered -> {}
                is ServerFrame.WorkspaceAdded -> {}
                is ServerFrame.WorkspaceRemoved -> {}
                is ServerFrame.WorkspaceChanged -> {}
                is ServerFrame.WorkspacesReordered -> {}
                is ServerFrame.ViewAdded -> {}
                is ServerFrame.ViewRemoved -> {}
                is ServerFrame.ViewChanged -> {}
                is ServerFrame.ViewMoved -> {}
                is ServerFrame.SessionState -> {}
                is ServerFrame.AgentState -> {}
                is ServerFrame.AgentError -> {}
                is ServerFrame.MessageAppend -> {}
                is ServerFrame.SessionRead -> {}
                is ServerFrame.ActivityAppend -> {}
                is ServerFrame.BgTasks -> {}
                is ServerFrame.CommandsChanged -> {}
                is ServerFrame.FsChanged -> {}
                is ServerFrame.WalkthroughUpdated -> {}
                is ServerFrame.ReviewCommentFrame -> {}
                is ServerFrame.LspStatus -> {}
                is ServerFrame.LspReady -> {}
                is ServerFrame.LspError -> {}
                is ServerFrame.LspRpcIn -> {}
                is ServerFrame.LspExit -> {}
                is ServerFrame.LspInstallProgress -> {}
                is ServerFrame.LspInstallDone -> {}
                is ServerFrame.DisplayAdded -> {}
                is ServerFrame.DisplayRemoved -> {}
                is ServerFrame.UsageUpdated -> {}
                is ServerFrame.FinishJobFrame -> {}
                is ServerFrame.SessionGit -> {}
                is ServerFrame.ProjectsChanged -> {}
                is ServerFrame.WalkthroughUpdated -> {}
                is ServerFrame.ReviewCommentFrame -> {}
                is ServerFrame.RequestOpen -> {}
                // The chosen answer has to survive the wire — the transcript line quotes it.
                is ServerFrame.RequestClosed -> assertEquals("Allow always", frame.answerLabel)
                is ServerFrame.Error -> {}
            }
        }
    }

    @Test fun client_prompt_frames_round_trip() {
        val set = json.decodeFromString<ClientFrame>(load("set_permission_mode"))
        assertTrue(set is ClientFrame.SetPermissionMode)
        assertEquals("s1", (set as ClientFrame.SetPermissionMode).session)
        assertEquals("ask", set.mode)
        val respond = json.decodeFromString<ClientFrame>(load("request_respond"))
        assertTrue(respond is ClientFrame.RequestRespond)
        assertEquals("r1", (respond as ClientFrame.RequestRespond).requestId)
        assertTrue(respond.answer["optionId"].toString().contains("allow_once"))
    }
}
