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
        val names = listOf(
            "snapshot", "session_added", "session_removed", "session_renamed", "session_state",
            "agent_state", "agent_error", "message_append", "activity_append", "bg_tasks",
            "commands_changed", "finish_job", "session_git", "session_git_remote",
            "sessions_reordered", "session_read",
            "walkthrough_updated", "review_comment",
            "worktree_sizes", "worktrees_removed", "agent_models_changed", "host_requirements", "keep_awake",
            "fs_dir", "fs_gone", "fs_err",
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
                ServerFrame.AgentModelsChanged -> {}
                is ServerFrame.HostRequirementsChanged -> {}
                is ServerFrame.KeepAwakeChanged -> {}
                is ServerFrame.WalkthroughUpdated -> {}
                is ServerFrame.ReviewCommentFrame -> {}
                is ServerFrame.WorktreeSizes -> {}
                is ServerFrame.WorktreesRemoved -> {}
                is ServerFrame.FsDir -> {}
                is ServerFrame.FsGone -> {}
                is ServerFrame.FsErr -> {}
            }
        }
    }

    @Test fun fs_dir_unchanged_and_client_fs_frames_round_trip() {
        val f = json.decodeFromString<ServerFrame>("""{"type":"fs_dir","path":"/a","version":"x:1","unchanged":true}""")
        kotlin.test.assertEquals(ServerFrame.FsDir(path = "/a", version = "x:1", unchanged = true), f)
        val out = json.encodeToString(ClientFrame.serializer(), ClientFrame.FsSub("/a", since = "x:1"))
        kotlin.test.assertEquals("""{"type":"fs_sub","path":"/a","since":"x:1"}""", out)
        val un = json.encodeToString(ClientFrame.serializer(), ClientFrame.FsUnsub("/a"))
        kotlin.test.assertEquals("""{"type":"fs_unsub","path":"/a"}""", un)
    }

    @Test fun keep_awake_fixture_carries_the_denied_reason() {
        val f = json.decodeFromString<ServerFrame>(load("keep_awake")) as ServerFrame.KeepAwakeChanged
        kotlin.test.assertEquals(dev.supermux.net.KeepAwakeState.REASON_DENIED, f.keepAwake.reasonCode)
        kotlin.test.assertEquals(false, f.keepAwake.active)
        kotlin.test.assertEquals(true, f.keepAwake.retrying)
    }

    @Test fun fs_dir_carries_real() {
        val f = json.decodeFromString<ServerFrame>(load("fs_dir")) as ServerFrame.FsDir
        kotlin.test.assertEquals("/home/u/p/src", f.real)
    }
}
