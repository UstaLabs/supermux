package dev.supermux.state

import dev.supermux.proto.ContextUsageDto
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** #144: how full a session's context window is arrives on the snapshot and in `context_usage` frames. */
class HostReducerContextUsageTest {
    private val session = SessionInfo(id = "s1", name = "fix", workdir = "/w", agent = "claude")

    @Test fun a_context_usage_frame_sets_the_session_s_context_and_leaves_others_alone() {
        var state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(session))
        state = reduceHostFrame(state, ServerFrame.SessionAdded(session.copy(id = "s2")))
        state = reduceHostFrame(state, ServerFrame.ContextUsage(session = "s1", used = 26955, size = 1_000_000))
        assertEquals(ContextUsageDto(26955, 1_000_000), state.sessions.first { it.id == "s1" }.context)
        assertNull(state.sessions.first { it.id == "s2" }.context)
    }

    @Test fun the_broker_s_frame_and_snapshot_row_decode() {
        val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }
        val frame = json.decodeFromString<ServerFrame>("""{"type":"context_usage","session":"s1","used":154000,"size":300000}""")
        assertEquals(ServerFrame.ContextUsage("s1", 154000, 300000), frame)
        val row = json.decodeFromString<SessionInfo>("""{"id":"s1","name":"n","workdir":"/w","agent":"codex","context":{"used":21851,"size":258400}}""")
        assertEquals(ContextUsageDto(21851, 258400), row.context)
    }
}

/** #144: a compaction's start and end, the "Compacted" line on a log entry, and the Compact flags on a session. */
class HostReducerCompactionTest {
    private val session = dev.supermux.proto.SessionInfo(id = "s1", name = "fix", workdir = "/w", agent = "claude")
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

    @Test fun a_compaction_runs_from_its_start_to_its_end() {
        var state = reduceHostFrame(HostState(), ServerFrame.SessionAdded(session))
        state = reduceHostFrame(state, ServerFrame.Compaction(session = "s1", id = "c1", status = "in_progress"))
        assertEquals(mapOf("s1" to "c1"), state.compacting)
        state = reduceHostFrame(state, ServerFrame.Compaction(session = "s1", id = "c1", status = "completed"))
        assertEquals(emptyMap(), state.compacting)
    }

    @Test fun the_compacted_line_and_the_flags_decode() {
        val entry = json.decodeFromString<dev.supermux.proto.LogEntry>(
            """{"id":"m","ts":"t","direction":"outbound","op":"compaction","text":"","compaction":{"status":"completed","before":262000,"after":20113,"trigger":"manual","note":"keep it","summary":"S"}}""",
        )
        assertEquals(dev.supermux.proto.CompactionDto("completed", 262000, 20113, "manual", "keep it", "S"), entry.compaction)
        val row = json.decodeFromString<dev.supermux.proto.SessionInfo>(
            """{"id":"s1","name":"n","workdir":"/w","agent":"cursor","capabilities":{"hasAgentTerminal":false,"supportsLiveConfigChange":true,"compact":false,"compactNote":false}}""",
        )
        assertEquals(dev.supermux.proto.SessionCapabilitiesDto(compact = false, compactNote = false), row.capabilities)
    }
}
