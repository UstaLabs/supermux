package dev.supermux.session

import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

private fun s(name: String, workdir: String) =
    SessionInfo(name = name, workdir = workdir, agent = "claude", status = "active")

class SessionGroupingTest {
    @Test fun groups_by_workdir() {
        val groups = groupSessions(listOf(
            s("dockie", "/home/user/claudehome"),
            s("memory", "/home/user/claudehome"),
            s("editor", "/home/user/projects/claudemux"),
        ), home = "/home/user")
        // distinct workdirs → distinct groups; same workdir collapses into one
        assertEquals(2, groups.size)
        val byWorkdir = groups.associateBy { it.workdir }
        assertEquals(2, byWorkdir.getValue("/home/user/claudehome").sessions.size)
        assertEquals(1, byWorkdir.getValue("/home/user/projects/claudemux").sessions.size)
        assertEquals(
            setOf("~/claudehome", "~/projects/claudemux"),
            groups.map { it.label }.toSet(),
        )
    }

    @Test fun sessions_within_group_sorted_newest_first() {
        val ts = mapOf(
            "old" to "2026-06-01T08:00:00Z",
            "new" to "2026-06-01T10:00:00Z",
            "mid" to "2026-06-01T09:00:00Z",
        )
        val groups = groupSessions(
            listOf(s("old", "/x/one"), s("new", "/x/one"), s("mid", "/x/one")),
            home = "/home/user",
            lastTs = { ts[it.name] ?: "" },
        )
        assertEquals(1, groups.size)
        assertEquals(listOf("new", "mid", "old"), groups[0].sessions.map { it.name })
    }

    @Test fun groups_ordered_by_most_recent_session_newest_first() {
        val ts = mapOf(
            "a1" to "2026-06-01T08:00:00Z", // group /x/a most-recent = 08:00
            "b1" to "2026-06-01T12:00:00Z", // group /x/b most-recent = 12:00
            "b2" to "2026-06-01T07:00:00Z",
            "c1" to "2026-06-01T10:00:00Z", // group /x/c most-recent = 10:00
        )
        val groups = groupSessions(
            listOf(s("a1", "/x/a"), s("b1", "/x/b"), s("b2", "/x/b"), s("c1", "/x/c")),
            home = "/home/user",
            lastTs = { ts[it.name] ?: "" },
        )
        // ordered by each group's max ts, newest first: b(12:00), c(10:00), a(08:00)
        assertEquals(listOf("/x/b", "/x/c", "/x/a"), groups.map { it.workdir })
    }

    @Test fun session_without_timestamp_sorts_last_within_group() {
        val ts = mapOf(
            "dated" to "2026-06-01T10:00:00Z",
            // "undated" intentionally absent → ""
        )
        val groups = groupSessions(
            listOf(s("undated", "/x/one"), s("dated", "/x/one")),
            home = "/home/user",
            lastTs = { ts[it.name] ?: "" },
        )
        assertEquals(listOf("dated", "undated"), groups[0].sessions.map { it.name })
    }

    @Test fun formatWorkdir_under_home() {
        assertEquals("~/projects/x", formatWorkdir("/home/user/projects/x", "/home/user"))
    }

    @Test fun formatWorkdir_exactly_home() {
        assertEquals("~", formatWorkdir("/home/user", "/home/user"))
    }

    @Test fun formatWorkdir_deep_non_home_shortens() {
        assertEquals(".../b/c", formatWorkdir("/var/www/a/b/c", "/home/user"))
    }

    @Test fun formatWorkdir_shallow_non_home_unchanged() {
        assertEquals("/etc/foo", formatWorkdir("/etc/foo", "/home/user"))
    }

    @Test fun formatWorkdir_empty_home_infers_from_workdir() {
        assertEquals("~/projects/x", formatWorkdir("/home/user/projects/x", ""))
    }
}
