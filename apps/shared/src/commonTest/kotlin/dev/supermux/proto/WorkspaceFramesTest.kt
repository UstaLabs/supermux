package dev.supermux.proto

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

class WorkspaceFramesTest {

    @Test
    fun decodesAGroupLayout() {
        val node = json.decodeFromString<LayoutNodeDto>(
            """{"type":"group","id":"g1","viewIds":["v1","v2"],"activeViewId":"v2"}"""
        )
        val group = node as LayoutNodeDto.Group
        assertEquals("g1", group.id)
        assertEquals(listOf("v1", "v2"), group.viewIds)
        assertEquals("v2", group.activeViewId)
    }

    @Test
    fun decodesANestedSplitLayout() {
        val node = json.decodeFromString<LayoutNodeDto>(
            """
            {"type":"split","direction":"row","sizes":[0.5,0.5],"children":[
              {"type":"group","id":"g1","viewIds":["v1"],"activeViewId":"v1"},
              {"type":"split","direction":"column","sizes":[0.6,0.4],"children":[
                {"type":"group","id":"g2","viewIds":["v2"],"activeViewId":"v2"},
                {"type":"group","id":"g3","viewIds":["v3"],"activeViewId":"v3"}
              ]}
            ]}
            """.trimIndent()
        )
        val split = node as LayoutNodeDto.Split
        assertEquals("row", split.direction)
        assertEquals(2, split.children.size)
        assertTrue(split.children[1] is LayoutNodeDto.Split)
    }

    @Test
    fun layoutRoundTrips() {
        val original: LayoutNodeDto = LayoutNodeDto.Split(
            direction = "column",
            sizes = listOf(0.3, 0.7),
            children = listOf(
                LayoutNodeDto.Group(id = "a", viewIds = listOf("v1"), activeViewId = "v1"),
                LayoutNodeDto.Group(id = "b", viewIds = listOf("v2"), activeViewId = "v2"),
            ),
        )
        assertEquals(original, json.decodeFromString<LayoutNodeDto>(json.encodeToString(original)))
    }

    @Test
    fun decodesAWorkspaceWithItsViews() {
        val w = json.decodeFromString<WorkspaceDto>(
            """
            {"id":"w1","name":"app","status":"active","workdir":"/w","repo_root":"/repo",
             "base_branch":"main","branch":"mux/x","name_locked":false,"sort_order":2,
             "created_at":"2026-08-06T00:00:00.000Z","active_view_id":"v1","primary_session_id":"s1",
             "layout":{"type":"group","id":"g1","viewIds":["v1"],"activeViewId":"v1"},
             "views":[{"id":"v1","workspace_id":"w1","kind":"chat","state":{"sessionId":"s1"}}]}
            """.trimIndent()
        )
        assertEquals("app", w.name)
        assertEquals("/repo", w.repoRoot)
        assertEquals("mux/x", w.branch)
        assertEquals(1, w.views.size)
        assertEquals("chat", w.views[0].kind)
        assertEquals("s1", w.views[0].chatSessionId())
    }

    @Test
    fun aTerminalViewReportsItsScopeAndId() {
        val v = json.decodeFromString<ViewDto>(
            """{"id":"v1","workspace_id":"w1","kind":"terminal","state":{"scope":"workspace","terminalId":"main"}}"""
        )
        assertEquals("workspace", v.stateString("scope"))
        assertEquals("main", v.stateString("terminalId"))
        assertEquals(null, v.chatSessionId())
    }

    @Test
    fun serverFramesDecodeByTypeTag() {
        fun decode(s: String) = json.decodeFromString<ServerFrame>(s)

        assertTrue(decode("""{"type":"workspace_removed","id":"w1"}""") is ServerFrame.WorkspaceRemoved)
        assertTrue(decode("""{"type":"workspaces_reordered","orderedIds":["a","b"]}""") is ServerFrame.WorkspacesReordered)
        assertTrue(decode("""{"type":"view_removed","workspaceId":"w1","viewId":"v1"}""") is ServerFrame.ViewRemoved)
        assertTrue(decode("""{"type":"view_moved","viewId":"v1","fromWorkspaceId":"a","toWorkspaceId":"b"}""") is ServerFrame.ViewMoved)
    }

    @Test
    fun snapshotWithoutWorkspacesDecodesToAnEmptyList() {
        // An old broker sends no workspaces key. A new client must not crash.
        val snap = json.decodeFromString<ServerFrame>("""{"type":"snapshot","sessions":[]}""")
        assertEquals(emptyList(), (snap as ServerFrame.Snapshot).workspaces)
    }
}
