package dev.supermux.session

import dev.supermux.net.ArchivedDto
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
            setOf("~/claudehome", "…/projects/claudemux"),
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

    @Test fun formatWorkdir_under_home_shows_last_two_segments() {
        assertEquals("…/projects/x", formatWorkdir("/home/user/projects/x", "/home/user"))
        assertEquals("…/c/d", formatWorkdir("/home/user/a/b/c/d", "/home/user"))
    }

    @Test fun formatWorkdir_one_level_under_home_keeps_tilde() {
        assertEquals("~/foo", formatWorkdir("/home/user/foo", "/home/user"))
    }

    @Test fun formatWorkdir_exactly_home() {
        assertEquals("~", formatWorkdir("/home/user", "/home/user"))
    }

    @Test fun formatWorkdir_deep_non_home_shortens() {
        assertEquals("…/b/c", formatWorkdir("/var/www/a/b/c", "/home/user"))
    }

    @Test fun formatWorkdir_shallow_non_home_two_levels() {
        assertEquals("etc/foo", formatWorkdir("/etc/foo", "/home/user"))
    }

    @Test fun formatWorkdir_single_segment_unchanged() {
        assertEquals("/acme", formatWorkdir("/acme", "/home/user"))
    }

    @Test fun formatWorkdir_empty_home_infers_from_workdir() {
        assertEquals("…/projects/x", formatWorkdir("/home/user/projects/x", ""))
    }


    @Test fun task_sections_split_by_user_status_and_sort_order() {
        val list = listOf(
            SessionInfo(id = "a", name = "a", workdir = "/p", agent = "claude", userStatus = "in_progress", sortOrder = 2),
            SessionInfo(id = "b", name = "b", workdir = "/p", agent = "claude", userStatus = "in_progress", sortOrder = 0),
            SessionInfo(id = "d", name = "d", workdir = "/p", agent = "claude", userStatus = "draft", sortOrder = 0),
            SessionInfo(id = "s", name = "s", workdir = "/p", agent = "claude", userStatus = "settled", sortOrder = 9),
        )
        val sections = buildTaskSections(list) { "" }
        assertEquals(listOf(SectionKey.IN_PROGRESS, SectionKey.DRAFT, SectionKey.SETTLED), sections.map { it.key })
        assertEquals(listOf("b", "a"), sections[0].sessions.map { it.name })
        assertEquals(listOf("d"), sections[1].sessions.map { it.name })
        assertEquals(listOf("s"), sections[2].sessions.map { it.name })
    }

    @Test fun groupSessions_merges_archived_into_settled() {
        val live = listOf(
            SessionInfo(id = "live", name = "live", workdir = "/home/user/proj", agent = "claude", userStatus = "in_progress"),
        )
        val archived = listOf(
            ArchivedDto(id = "old", name = "old", workdir = "/home/user/proj", agent = "claude"),
        )
        val groups = groupSessions(live, home = "/home/user", archived = archived)
        assertEquals(1, groups.size)
        val settled = groups[0].sections.first { it.key == SectionKey.SETTLED }
        assertEquals(listOf("old"), settled.sessions.map { it.name })
        val progress = groups[0].sections.first { it.key == SectionKey.IN_PROGRESS }
        assertEquals(listOf("live"), progress.sessions.map { it.name })
    }

    @Test fun archived_status_counts_as_settled() {
        val s = SessionInfo(id = "x", name = "x", workdir = "/p", agent = "claude", status = "archived")
        assertEquals(SectionKey.SETTLED, s.sectionKey())
    }

    @Test fun moveId_reorders() {
        assertEquals(listOf("b", "a", "c"), moveId(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "c", "b"), moveId(listOf("a", "b", "c"), 1, 2))
        assertEquals(listOf("a", "b", "c"), moveId(listOf("a", "b", "c"), 1, 1))
    }
}
