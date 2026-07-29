package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "type" }

class ChatFramesTest {
    @Test fun parses_message_append_inbound_and_outbound() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"message_append","session":"s1","entry":{"id":"in:1","ts":"2026-06-01T00:00:00Z","direction":"inbound","channel":"web","chat_id":"web","message_id":"m1","text":"hi"}}""")
        assertTrue(f is ServerFrame.MessageAppend)
        assertEquals("inbound", (f as ServerFrame.MessageAppend).entry.direction)
        assertEquals("hi", f.entry.text)
    }
    @Test fun parses_activity_append_tool() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"activity_append","session":"s1","event":{"ts":"2026-06-01T00:00:00Z","kind":"tool","tool":"Bash","title":"Bash: ls","phase":"started","seq":1,"callId":"c1"}}""")
        assertTrue(f is ServerFrame.ActivityAppend)
        assertEquals("Bash", (f as ServerFrame.ActivityAppend).event.tool)
    }

    @Test fun parses_activity_append_tool_description() {
        // Wire field is `description` (human "why"). Apple clients must read it via SKIE
        // `description_` — not `.description` (KotlinBase/NSObject collision).
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"activity_append","session":"s1","event":{"ts":"2026-06-01T00:00:00Z","kind":"tool","tool":"Bash","title":"Bash: ls","description":"List workspace files","phase":"started","seq":1,"callId":"c1"}}""")
        assertTrue(f is ServerFrame.ActivityAppend)
        assertEquals("List workspace files", (f as ServerFrame.ActivityAppend).event.description)
    }
    @Test fun parses_snapshot_with_logs_activity_agentState() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","sessions":[],"logs":{"s1":[{"id":"o1","ts":"t","direction":"outbound","text":"hello"}]},"activity":{"s1":[{"ts":"t","kind":"thinking","title":"Thought for 3s","seq":0}]},"agentState":{"s1":{"phase":"working","since":1717200000000}}}""")
        assertTrue(f is ServerFrame.Snapshot)
        val snap = f as ServerFrame.Snapshot
        assertEquals("hello", snap.logs["s1"]!![0].text)
        assertEquals("thinking", snap.activity["s1"]!![0].kind)
        assertEquals("working", snap.agentState["s1"]!!.phase)
    }
    @Test fun encodes_send_frame() {
        val s = Json.encodeToString(ClientFrame.serializer(), ClientFrame.Send("s1", args = SendArgs("hello")))
        assertTrue(s.contains("\"type\":\"send\"") && s.contains("\"text\":\"hello\"") && s.contains("\"op\":\"reply\""))
    }

    @Test fun encodes_viewing_frame_with_session() {
        val s = Json.encodeToString(ClientFrame.serializer(), ClientFrame.Viewing("s1", visible = true))
        assertTrue(s.contains("\"type\":\"viewing\"") && s.contains("\"session\":\"s1\"") && s.contains("\"visible\":true"))
    }

    @Test fun encodes_viewing_frame_null_session_as_explicit_null() {
        // The broker rejects a viewing frame with a MISSING `session`; on the chat list the
        // session is null and MUST serialize as `"session":null` (regression guard for the
        // no-default field — a default would let kotlinx omit it).
        val s = Json.encodeToString(ClientFrame.serializer(), ClientFrame.Viewing(null, visible = false))
        assertTrue(s.contains("\"type\":\"viewing\"") && s.contains("\"session\":null") && s.contains("\"visible\":false"))
    }

    @Test fun parses_finish_job_running() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"finish_job","session":"x","job":{"sessionId":"x","status":"running","stage":"Merging…","action":"merge","startedAt":1}}""")
        assertTrue(f is ServerFrame.FinishJobFrame)
        val frame = f as ServerFrame.FinishJobFrame
        assertEquals("x", frame.session)
        assertEquals("running", frame.job?.status)
        assertEquals("Merging…", frame.job?.stage)
        assertEquals("merge", frame.job?.action)
        assertEquals(1.0, frame.job?.startedAt)
        assertEquals(null, frame.job?.outcome)
    }

    @Test fun parses_finish_job_done_with_outcome() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"finish_job","session":"x","job":{"sessionId":"x","action":"pr","status":"done","startedAt":1,"endedAt":2,
               |"outcome":{"status":"pr_opened","branch":"feat/x","prUrl":"https://gh/pr/1","draft":false,"verified":null}}}""".trimMargin())
        assertTrue(f is ServerFrame.FinishJobFrame)
        val job = (f as ServerFrame.FinishJobFrame).job!!
        assertEquals("done", job.status)
        assertEquals("pr", job.action)
        assertEquals(2.0, job.endedAt)
        assertEquals("pr_opened", job.outcome?.status)
        assertEquals("https://gh/pr/1", job.outcome?.prUrl)
        assertEquals(false, job.outcome?.draft)
    }

    @Test fun snapshot_session_carries_finish_job_and_branch() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","sessions":[{"id":"s1","name":"n","workdir":"/w","agent":"claude",
               |"session_branch":"feat/x","finish_job":{"sessionId":"s1","action":"merge","status":"failed","startedAt":1,
               |"outcome":{"status":"tests_failed","command":"bun test","output":"boom"}}}]}""".trimMargin())
        assertTrue(f is ServerFrame.Snapshot)
        val s = (f as ServerFrame.Snapshot).sessions[0]
        assertEquals("feat/x", s.session_branch)
        assertEquals("failed", s.finish_job?.status)
        assertEquals("tests_failed", s.finish_job?.outcome?.status)
        assertEquals("bun test", s.finish_job?.outcome?.command)
    }

    @Test fun parses_session_git_frame() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"session_git","session":"s1","git":{"mode":"base","compareRef":"main","ahead":2,"behind":1,"dirty":3,"computedAt":1}}""")
        assertTrue(f is ServerFrame.SessionGit)
        val frame = f as ServerFrame.SessionGit
        assertEquals("s1", frame.session)
        assertEquals("base", frame.git?.mode)
        assertEquals(2, frame.git?.ahead)
        assertEquals(3, frame.git?.dirty)
    }

    @Test fun snapshot_session_carries_git() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","sessions":[{"id":"s1","name":"n","workdir":"/w","agent":"claude",
               |"git":{"mode":"remote","compareRef":"origin/x","ahead":0,"behind":0,"dirty":0,"unpublished":true,"computedAt":1}}]}""".trimMargin())
        assertTrue(f is ServerFrame.Snapshot)
        val s = (f as ServerFrame.Snapshot).sessions[0]
        assertEquals("remote", s.git?.mode)
        assertEquals(true, s.git?.unpublished)
    }

    @Test fun parses_bg_tasks_frame() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"bg_tasks","session":"s1","tasks":[{"id":"b1","kind":"shell","label":"gradle build","startedAt":1000,"status":"running"},{"id":"a2","kind":"agent","label":"research","startedAt":2000,"status":"failed","endedAt":3000,"summary":"exit 1"}]}""")
        assertTrue(f is ServerFrame.BgTasks)
        val frame = f as ServerFrame.BgTasks
        assertEquals("s1", frame.session)
        assertEquals(2, frame.tasks.size)
        assertEquals("running", frame.tasks[0].status)
        assertEquals("exit 1", frame.tasks[1].summary)
    }

    @Test fun agent_state_decodes_waiting_fields() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"agent_state","session":"s1","phase":"idle","state":"idle","working":false,"waiting":true,"bgOpen":2,"since":5}""")
        assertTrue(f is ServerFrame.AgentState)
        val frame = f as ServerFrame.AgentState
        assertEquals(true, frame.waiting)
        assertEquals(2, frame.bgOpen)
    }

    @Test fun agent_state_without_waiting_fields_defaults_false_zero() {
        // Regression guard: frames from an older broker must decode with safe defaults.
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"agent_state","session":"s1","phase":"idle","state":"idle","working":false,"since":5}""")
        val frame = f as ServerFrame.AgentState
        assertEquals(false, frame.waiting)
        assertEquals(0, frame.bgOpen)
    }

    @Test fun parses_snapshot_bg_tasks_map() {
        val f = json.decodeFromString<ServerFrame>(
            """{"type":"snapshot","sessions":[],"bgTasks":{"s1":[{"id":"b1","kind":"shell","label":"x","startedAt":1,"status":"running"}]}}""")
        val snap = f as ServerFrame.Snapshot
        assertEquals(1, snap.bgTasks["s1"]?.size)
        assertEquals("shell", snap.bgTasks["s1"]!![0].kind)
    }
}
