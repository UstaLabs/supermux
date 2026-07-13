package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test

class ContractTest {
    // ignoreUnknownKeys = false → strict decode throws if the broker emits a
    // field the Kotlin type doesn't know. That strict direction is the drift guard.
    private val json = Json { ignoreUnknownKeys = false; classDiscriminator = "type" }

    private fun load(name: String): String =
        (this::class.java.getResourceAsStream("/frames/$name.json")
            ?: error("fixture $name.json not found in test resources")).readBytes().decodeToString()

    @Test fun every_broker_fixture_parses_into_a_ServerFrame() {
        val names = listOf("snapshot", "session_added", "session_removed", "session_state", "agent_state", "agent_error", "message_append", "activity_append", "bg_tasks", "commands_changed", "finish_job", "session_git", "session_git_remote")
        for (n in names) {
            val frame = json.decodeFromString<ServerFrame>(load(n))
            // Exhaustive when (no else): adding a new ServerFrame subtype will
            // fail to compile here until it's handled, forcing a matching fixture.
            when (frame) {
                is ServerFrame.Snapshot -> {}
                is ServerFrame.SessionAdded -> {}
                is ServerFrame.SessionRemoved -> {}
                is ServerFrame.SessionState -> {}
                is ServerFrame.AgentState -> {}
                is ServerFrame.AgentError -> {}
                is ServerFrame.MessageAppend -> {}
                is ServerFrame.ActivityAppend -> {}
                is ServerFrame.BgTasks -> {}
                is ServerFrame.CommandsChanged -> {}
                is ServerFrame.FsChanged -> {}
                is ServerFrame.LspStatus -> {}
                is ServerFrame.LspReady -> {}
                is ServerFrame.LspError -> {}
                is ServerFrame.LspRpcIn -> {}
                is ServerFrame.LspExit -> {}
                is ServerFrame.LspInstallProgress -> {}
                is ServerFrame.LspInstallDone -> {}
                is ServerFrame.DisplayAdded -> {}
                is ServerFrame.DisplayRemoved -> {}
                is ServerFrame.FinishJobFrame -> {}
                is ServerFrame.SessionGit -> {}
            }
        }
    }
}
