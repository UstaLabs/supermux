package dev.supermux.net

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

class WorkspaceApiTest {

    @Test
    fun workspacesResponseDecodes() {
        val r = json.decodeFromString<WorkspacesResponse>(
            """{"workspaces":[{"id":"w1","name":"a","workdir":"/w","views":[]}]}"""
        )
        assertEquals(1, r.workspaces.size)
        assertEquals("w1", r.workspaces[0].id)
    }

    @Test
    fun workspacesResponseDefaultsToEmpty() {
        assertEquals(emptyList(), json.decodeFromString<WorkspacesResponse>("{}").workspaces)
    }

    @Test
    fun createWorkspaceBodyEncodesOnlyWhatIsSet() {
        val body = CreateWorkspaceBody(workdir = "/w")
        assertEquals("""{"workdir":"/w"}""", json.encodeToString(body))
    }

    @Test
    fun createWorkspaceBodyCarriesTheWorktreeRequest() {
        val body = CreateWorkspaceBody(name = "app", workdir = "/w", worktree = true, baseBranch = "main")
        val encoded = json.encodeToString(body)
        assertEquals(true, encoded.contains("\"worktree\":true"))
        assertEquals(true, encoded.contains("\"baseBranch\":\"main\""))
    }

    @Test
    fun addViewBodyEncodesTheStateVerbatim() {
        val body = AddViewBody(
            kind = "terminal",
            state = json.parseToJsonElement("""{"scope":"workspace","terminalId":"main"}""").let { it as kotlinx.serialization.json.JsonObject },
        )
        val encoded = json.encodeToString(body)
        assertEquals(true, encoded.contains("\"terminalId\":\"main\""))
    }

    @Test
    fun patchWorkspaceBodyOmitsAbsentFields() {
        assertEquals("""{"name":"new"}""", json.encodeToString(PatchWorkspaceBody(name = "new")))
    }

    // ── Regression: a chat created inside a workspace must JOIN it ───────────
    // The broker has branched on workspaceId since Phase 1b (present → join via
    // addChatSession, absent → a brand-new workspace via createForSession), but
    // SpawnRequest had no such field, so the client could never say which. Every
    // chat started from a workspace silently produced a SECOND workspace — the
    // kind of bug you notice weeks later when the sidebar has doubled.

    @Test
    fun spawnRequestCarriesTheWorkspaceToJoin() {
        val body = SpawnRequest(workdir = "/w", workspaceId = "ws-1")
        assertEquals(true, json.encodeToString(body).contains("\"workspaceId\":\"ws-1\""))
    }

    @Test
    fun spawnRequestOmitsWorkspaceIdWhenThereIsNoneToJoin() {
        // Absent must stay absent on the wire: the broker reads its absence as
        // "make a new workspace", so an accidental null string would be a lie.
        assertEquals(false, json.encodeToString(SpawnRequest(workdir = "/w")).contains("workspaceId"))
    }
}
