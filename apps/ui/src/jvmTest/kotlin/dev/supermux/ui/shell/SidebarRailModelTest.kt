package dev.supermux.ui.shell

import dev.supermux.proto.AgentStatus
import dev.supermux.proto.LogEntry
import dev.supermux.proto.SessionInfo
import dev.supermux.proto.ViewDto
import dev.supermux.proto.WorkspaceDto
import dev.supermux.session.PA_GROUP_KEY
import dev.supermux.workspace.WorkspaceGroup
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import androidx.compose.ui.unit.dp

/**
 * The collapsed sidebar's model, read from the expanded list's own groups: who is an Agent and in
 * what state, which projects earn a tile, and what each workspace row shows.
 */
class SidebarRailModelTest {

    private val now = Instant.parse("2026-11-04T12:00:00Z").toEpochMilli()
    private fun ago(minutes: Long) = Instant.ofEpochMilli(now - minutes * 60_000).toString()

    private fun ws(id: String, sid: String, sort: Int = 0) = WorkspaceDto(
        id = id,
        name = "ws $id",
        workdir = "/w/$id",
        primarySessionId = sid,
        sortOrder = sort,
        views = listOf(ViewDto(id = "v$id", workspaceId = id, kind = "chat", state = JsonObject(mapOf("sessionId" to JsonPrimitive(sid))))),
    )
    private fun session(id: String, name: String = id, role: String? = null, status: String? = "active", userStatus: String? = null) =
        SessionInfo(id = id, name = name, workdir = "/w", agent = "claude", role = role, status = status, userStatus = userStatus)
    private fun said(ts: String) = LogEntry(id = "m$ts", ts = ts, direction = "outbound", text = "Done.\nsecond line")
    private fun busy() = AgentStatus(phase = "running", state = "working", working = true)

    @Test
    fun agentsComeFromTheAgentsGroupInTheirStates() {
        val groups = listOf(
            WorkspaceGroup(PA_GROUP_KEY, "Agents", listOf(ws("a1", "pa1"), ws("a2", "pa2"), ws("a3", "pa3"), ws("a4", "pa4"))),
        )
        val sessions = listOf(
            session("pa1", "Tally", role = "personal_assistant"),
            session("pa2", "Atlas", role = "personal_assistant"),
            session("pa3", "Dockie", role = "personal_assistant", status = "suspended"),
            session("pa4", "Bavul", role = "personal_assistant"),
        ).associateBy { it.id }
        val m = sidebarRailModel(
            groups,
            sessions,
            agentState = mapOf("pa1" to busy()),
            lastBySession = mapOf("pa1" to said(ago(1)), "pa2" to said(ago(2)), "pa4" to said(ago(90))),
            lastRead = mapOf("pa2" to ago(10), "pa4" to ago(80)),
            selectedSessionId = null,
            nowMs = now,
        )
        val byName = m.agents.associateBy { it.name }
        assertEquals(listOf("Tally", "Atlas", "Dockie", "Bavul"), m.agents.map { it.name }, "in the list's own order")
        assertTrue(byName.getValue("Tally").working)
        assertFalse(byName.getValue("Tally").unread, "a working Agent shows the work, not the unread")
        assertTrue(byName.getValue("Atlas").unread, "a message after the last read")
        assertFalse(byName.getValue("Atlas").dozing, "quiet for 2 minutes is still awake")
        assertFalse(byName.getValue("Dockie").online, "a suspended session is asleep")
        assertTrue(byName.getValue("Bavul").dozing, "read, and quiet for an hour and a half")
        assertTrue(m.inUse.isEmpty() && m.all.isEmpty(), "the Agents are never a project")
    }

    @Test
    fun whichProjectsAreInUse() {
        val groups = listOf(
            WorkspaceGroup("p:h:fresh", "Fresh", listOf(ws("f1", "sf1"))),
            WorkspaceGroup("p:h:old", "Old", listOf(ws("o1", "so1"))),
            WorkspaceGroup("p:h:waiting", "Waiting", listOf(ws("w1", "sw1"))),
            WorkspaceGroup("p:h:busy", "Busy", listOf(ws("b1", "sb1"))),
            WorkspaceGroup("p:h:open", "Open", listOf(ws("x1", "sx1"))),
            WorkspaceGroup("p:h:empty", "Empty", emptyList()),
        )
        val sessions = listOf("sf1", "so1", "sw1", "sb1", "sx1").map { session(it) }.associateBy { it.id }
        val m = sidebarRailModel(
            groups,
            sessions,
            agentState = mapOf("sb1" to busy()),
            lastBySession = mapOf(
                "sf1" to said(ago(60 * 5)),
                "so1" to said(ago(60 * 24 * 3)),
                "sw1" to said(ago(60 * 24 * 4)),
                "sb1" to said(ago(60 * 24 * 5)),
                "sx1" to said(ago(60 * 24 * 6)),
            ),
            lastRead = mapOf("sf1" to ago(0), "so1" to ago(0), "sb1" to ago(0), "sx1" to ago(0)),
            selectedSessionId = "sx1",
            nowMs = now,
        )
        assertEquals(listOf("Fresh", "Old", "Waiting", "Busy", "Open"), m.all.map { it.name }, "every project with a workspace, in order")
        assertEquals(
            listOf("Fresh", "Waiting", "Busy", "Open"),
            m.all.filter { it.key in m.inUse }.map { it.name },
            "used today, unread, working, or holding the open workspace — not one quiet for 3 days",
        )
        assertEquals(1, m.all.first { it.name == "Waiting" }.unread)
        assertTrue(m.all.first { it.name == "Busy" }.working)
        assertTrue(m.all.first { it.name == "Open" }.active)
    }

    @Test
    fun theOpenWorkspaceIsNeverUnreadAndSettledRowsAreMarked() {
        val groups = listOf(WorkspaceGroup("p:h:s", "Supermux", listOf(ws("w1", "s1", sort = 1), ws("w2", "s2", sort = 2))))
        val sessions = listOf(session("s1"), session("s2", userStatus = "settled")).associateBy { it.id }
        val m = sidebarRailModel(
            groups,
            sessions,
            agentState = emptyMap(),
            lastBySession = mapOf("s1" to said(ago(1)), "s2" to said(ago(1))),
            lastRead = emptyMap(),
            selectedSessionId = "s1",
            nowMs = now,
        )
        val rows = m.all.single().rows
        assertTrue(rows[0].active)
        assertFalse(rows[0].unread, "the open workspace is being read")
        assertTrue(rows[1].unread)
        assertTrue(rows[1].settled)
        assertEquals("s2", rows[1].sessionId, "a click opens its chat")
        assertEquals(1, m.all.single().unread)
    }

    @Test
    fun theRailFitsAsManyProjectTilesAsItsHeightHolds() {
        // 8 Agents: 16 padding + (+ 34, rule 13, 8 × 34, rule 13) + 10 gaps of 2 = 368 dp before
        // the first project; each project tile then takes 36 dp.
        assertEquals(17, railProjectSlots(1000.dp, agents = 8, total = 17), "all 17 fit exactly: no All button")
        assertEquals(16, railProjectSlots(1000.dp, agents = 8, total = 18), "one short: the last slot is All projects")
        assertEquals(16, railProjectSlots(1000.dp, agents = 8, total = 30))
        assertEquals(0, railProjectSlots(300.dp, agents = 8, total = 30), "no room at all")
        assertEquals(3, railProjectSlots(400.dp, agents = 0, total = 3))
    }

    @Test
    fun projectsInUseAlwaysShowAndTheRestFillByRecencyInTheListsOrder() {
        fun p(key: String, ts: String) = RailProject(key, key, listOf(RailWorkspace("w$key", key, "s$key", false, false, false, false, ts)))
        val all = listOf(p("a", "2026-11-01"), p("b", "2026-11-05"), p("c", "2026-11-09"), p("d", "2026-10-01"), p("e", "2026-11-07"))
        val inUse = setOf("a", "d")
        assertEquals(listOf("a", "c", "d"), railProjectsShown(all, inUse, 3).map { it.key }, "the most recent other one, in list order")
        assertEquals(listOf("a", "c", "d", "e"), railProjectsShown(all, inUse, 4).map { it.key })
        assertEquals(listOf("a", "d"), railProjectsShown(all, inUse, 1).map { it.key }, "in use beats the room left")
        assertEquals(all, railProjectsShown(all, inUse, 5))
    }

    @Test
    fun projectInitials() {
        assertEquals("SW", projectInitials("Supermux Website"))
        assertEquals("Su", projectInitials("Supermux"))
        assertEquals("Op", projectInitials("…/local/optikokuyucu"))
        assertEquals("M9", projectInitials("mobileapp-942"))
        assertEquals("CA", projectInitials("composer-at-mentions"))
        assertEquals("?", projectInitials(""))
    }

    @Test
    fun projectInitialsOfAWindowsFolderLabel() {
        assertEquals("Op", projectInitials("…\\local\\optikokuyucu"))
    }
}
