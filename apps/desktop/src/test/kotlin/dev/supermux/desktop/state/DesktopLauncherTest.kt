package dev.supermux.desktop.state

import dev.supermux.desktop.session.StagedUpload
import dev.supermux.net.BrokerApi
import dev.supermux.net.ByteArrayChunkSource
import dev.supermux.net.SpawnRequest
import dev.supermux.net.SpawnResponse
import dev.supermux.proto.ServerFrame
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.ByteArrayContent
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Launcher + spawn wrappers for [DesktopAppState] (M4a Task 3). Two layers:
 *
 *  1. [resolveSpawnId] — the pure id-resolution helper — is unit-tested directly (no broker).
 *  2. [DesktopAppState.createSessionWithFirstMessage] is exercised against a real [BrokerApi] built
 *     over a ktor [MockEngine] (BrokerApi is final — the `apiOverride` seam takes a real instance,
 *     never a mock subclass). The engine records every request path + body so the test can assert
 *     the /sessions request SHAPE and that uploads fire strictly AFTER spawn.
 *
 * Live-only (verified in M4a Task 6, not here): the launcher UI wiring, drag-and-drop staging, and
 * a real broker's blank-id → post-register session_added timing. The blank-id fallback's *logic* is
 * covered purely by [resolveSpawnId] below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DesktopLauncherTest {

    // ── resolveSpawnId (pure) ──────────────────────────────────────────────────────

    private fun resp(id: String, name: String = "feat-x") =
        SpawnResponse(id = id, name = name, workdir = "/w", agent = "claude")

    private fun session(id: String, name: String) =
        SessionInfo(id = id, name = name, workdir = "/w/$id", agent = "claude")

    @Test fun resolve_spawn_id_passes_through_a_non_blank_id() {
        assertEquals("sess-1", resolveSpawnId(resp("sess-1"), emptyList()))
    }

    @Test fun resolve_spawn_id_blank_falls_back_to_name_match() {
        val sessions = listOf(session("other", "other-name"), session("sess-9", "feat-x"))
        assertEquals("sess-9", resolveSpawnId(resp("", name = "feat-x"), sessions))
    }

    @Test fun resolve_spawn_id_blank_with_no_name_match_is_null() {
        val sessions = listOf(session("other", "other-name"))
        assertNull(resolveSpawnId(resp("", name = "feat-x"), sessions))
    }

    // ── createSessionWithFirstMessage (MockEngine BrokerApi) ────────────────────────

    private val json = Json { ignoreUnknownKeys = true }

    /** One recorded outbound request: its path and decoded body text (JSON or empty for octet-stream). */
    private data class Rec(val path: String, val body: String)

    private fun bodyText(content: Any?): String = when (content) {
        is TextContent -> content.text
        is ByteArrayContent -> "<${content.bytes().size} bytes>"
        else -> ""
    }

    /** DesktopAppState whose BrokerApi answers /paths/validate, /sessions and /upload, appending
     *  each request to [recorded] in order. [validateOk]/[resolvedPath] shape the validation reply;
     *  [spawnId] is the id the spawn returns (blank → the reducer-seeded session is matched by name). */
    private fun appRecording(
        recorded: MutableList<Rec>,
        validateOk: Boolean = true,
        resolvedPath: String = "/resolved/dir",
        spawnId: String = "sess-1",
        spawnName: String = "feat-x",
    ): DesktopAppState {
        val engine = MockEngine { req ->
            val path = req.url.encodedPath
            recorded.add(Rec(path, bodyText(req.body)))
            val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
            when (path) {
                "/paths/validate" -> respond(
                    """{"ok":$validateOk,"path":${if (validateOk) "\"$resolvedPath\"" else "null"}}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                "/sessions" -> respond(
                    """{"id":"$spawnId","name":"$spawnName","workdir":"$resolvedPath","agent":"claude"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                "/upload" -> respond(
                    """{"file_id":"file-${recorded.count { it.path == "/upload" }}"}""",
                    HttpStatusCode.OK, jsonHeaders,
                )
                else -> respond(ByteReadChannel("{}"), HttpStatusCode.OK, jsonHeaders)
            }
        }
        val api = BrokerApi("ws://test:9898", "t", HttpClient(engine))
        return DesktopAppState(
            baseUrl = "ws://test:9898",
            token = "t",
            scope = CoroutineScope(Dispatchers.Default), // real clock: BrokerApi.spawn uses withTimeout
            connectOnInit = false,
            apiOverride = api,
        )
    }

    private fun staged(name: String, bytes: Int) =
        StagedUpload(ByteArrayChunkSource(ByteArray(bytes) { 1 }), name, "text/plain")

    @Test fun create_session_spawns_with_the_launcher_field_shape() = runBlocking {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)

        val id = app.createSessionWithFirstMessage(
            workdir = "~/proj",
            agent = "codex",
            model = "gpt-5",
            reasoningLevel = "high",
            text = "first message",
            staged = emptyList(),
            worktree = true,
            baseBranch = "main",
        )

        assertEquals("sess-1", id)
        // Validate ran, then spawn — order matters (validate gates spawn).
        assertEquals("/paths/validate", recorded[0].path)
        assertEquals("/sessions", recorded[1].path)
        // Spawn body carries the RESOLVED path + every launcher field.
        val req = json.decodeFromString<SpawnRequest>(recorded[1].body)
        assertEquals("/resolved/dir", req.workdir)   // resolved, not the raw "~/proj"
        assertEquals("codex", req.agent)
        assertEquals("gpt-5", req.model)
        assertEquals(true, req.worktree)
        assertEquals("main", req.baseBranch)
        assertEquals("high", req.reasoningLevel)
    }

    @Test fun create_session_uploads_staged_files_after_spawn_and_stashes_ids() = runBlocking {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)

        val id = app.createSessionWithFirstMessage(
            workdir = "/proj", agent = "claude", model = null, reasoningLevel = null,
            text = "hi", staged = listOf(staged("a.txt", 4), staged("b.txt", 8)),
            worktree = false, baseBranch = null,
        )

        assertEquals("sess-1", id)
        // Sequencing: validate → spawn → the two uploads, strictly after spawn.
        assertEquals(listOf("/paths/validate", "/sessions", "/upload", "/upload"), recorded.map { it.path })
        // The uploaded file_ids are stashed for the caller's first-message send, then consumed once.
        assertEquals(listOf("file-1", "file-2"), app.consumeFirstUploads("sess-1"))
        assertTrue(app.consumeFirstUploads("sess-1").isEmpty()) // single-shot: cleared after consume
    }

    @Test fun create_session_null_worktree_and_model_are_omitted_from_the_spawn_body() = runBlocking {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)

        app.createSessionWithFirstMessage(
            workdir = "/proj", agent = "claude", model = "  ", reasoningLevel = "  ",
            text = "hi", staged = emptyList(), worktree = false, baseBranch = "  ",
        )

        // explicitNulls=false in BrokerApi drops null fields — blank model/baseBranch/reasoning
        // map to null (…?.ifBlank { null }); worktree=false → null (not `false`).
        val body = recorded.first { it.path == "/sessions" }.body
        assertTrue("model" !in body, "blank model must be omitted, got: $body")
        assertTrue("worktree" !in body, "worktree=false must be omitted, got: $body")
        assertTrue("baseBranch" !in body, "blank baseBranch must be omitted, got: $body")
        assertTrue("reasoningLevel" !in body, "blank reasoning must be omitted, got: $body")
    }

    @Test fun create_session_returns_null_when_the_path_is_invalid() = runBlocking {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, validateOk = false)

        val id = app.createSessionWithFirstMessage(
            workdir = "/nope", agent = "claude", model = null, reasoningLevel = null,
            text = "hi", staged = listOf(staged("a.txt", 4)), worktree = false, baseBranch = null,
        )

        assertNull(id)
        // Invalid path short-circuits: validate ran, but spawn + uploads never did.
        assertEquals(listOf("/paths/validate"), recorded.map { it.path })
    }

    @Test fun continue_conversation_spawns_chosen_agent_model_and_inherit_from() = runBlocking {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded)
        val source = SessionInfo(
            id = "src-1",
            name = "feat-x",
            workdir = "/proj",
            agent = "claude",
            model = "sonnet",
            reasoningLevel = "high",
        )
        app.reduce(
            ServerFrame.Snapshot(
                workspaces = listOf(
                    WorkspaceDto(
                        id = "ws-src",
                        name = "feat-x",
                        workdir = "/proj",
                        views = listOf(
                            ViewDto(
                                id = "v-src",
                                workspaceId = "ws-src",
                                kind = "chat",
                                state = JsonObject(mapOf("sessionId" to JsonPrimitive("src-1"))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val id = app.continueConversation(
            source = source,
            message = "pick up here",
            agent = "grok",
            model = "grok-4",
            reasoningLevel = "low",
        )

        assertEquals("sess-1", id)
        val req = json.decodeFromString<SpawnRequest>(recorded.first { it.path == "/sessions" }.body)
        assertEquals("/resolved/dir", req.workdir)
        assertEquals("grok", req.agent)
        assertEquals("grok-4", req.model)
        assertEquals("low", req.reasoningLevel)
        assertEquals("src-1", req.inheritFrom)
        assertEquals("feat-x", req.name)
        assertEquals("ws-src", req.workspaceId)
        assertEquals("pick up here", req.firstMessage)
        assertNull(req.worktree)
    }

    @Test fun create_session_resolves_a_blank_spawn_id_by_name() = runBlocking {
        val recorded = mutableListOf<Rec>()
        val app = appRecording(recorded, spawnId = "", spawnName = "feat-x")
        // The blank-id fallback matches resp.name against the live session list — seed it.
        app.reduce(ServerFrame.Snapshot(sessions = listOf(session("live-42", "feat-x"))))

        val id = app.createSessionWithFirstMessage(
            workdir = "/proj", agent = "claude", model = null, reasoningLevel = null,
            text = "hi", staged = emptyList(), worktree = false, baseBranch = null,
        )

        assertEquals("live-42", id)
    }
}
