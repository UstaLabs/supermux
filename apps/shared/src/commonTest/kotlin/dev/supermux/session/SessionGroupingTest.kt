package dev.supermux.session

import dev.supermux.net.ArchivedDto
import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test fun sessions_within_group_sorted_by_sort_order_not_recency() {
        val ts = mapOf(
            "old" to "2026-06-01T08:00:00Z",
            "new" to "2026-06-01T10:00:00Z",
            "mid" to "2026-06-01T09:00:00Z",
        )
        val groups = groupSessions(
            listOf(
                SessionInfo(id = "old", name = "old", workdir = "/x/one", agent = "claude", sortOrder = 0),
                SessionInfo(id = "new", name = "new", workdir = "/x/one", agent = "claude", sortOrder = 2),
                SessionInfo(id = "mid", name = "mid", workdir = "/x/one", agent = "claude", sortOrder = 1),
            ),
            home = "/home/user",
            lastTs = { ts[it.name] ?: "" },
        )
        assertEquals(1, groups.size)
        // sortOrder wins; newer messages must not jump rows
        assertEquals(listOf("old", "mid", "new"), groups[0].sessions.map { it.name })
    }

    @Test fun groups_ordered_by_label_stable() {
        val ts = mapOf(
            "a1" to "2026-06-01T08:00:00Z",
            "b1" to "2026-06-01T12:00:00Z",
            "b2" to "2026-06-01T07:00:00Z",
            "c1" to "2026-06-01T10:00:00Z",
        )
        val groups = groupSessions(
            listOf(s("a1", "/x/a"), s("b1", "/x/b"), s("b2", "/x/b"), s("c1", "/x/c")),
            home = "/home/user",
            lastTs = { ts[it.name] ?: "" },
        )
        // Project cards stay label-stable; message recency must not reorder groups.
        assertEquals(listOf("/x/a", "/x/b", "/x/c"), groups.map { it.workdir })
    }

    @Test fun session_order_ignores_message_timestamps_within_group() {
        val ts = mapOf(
            "dated" to "2026-06-01T10:00:00Z",
        )
        val groups = groupSessions(
            listOf(
                SessionInfo(id = "undated", name = "undated", workdir = "/x/one", agent = "claude", sortOrder = 0),
                SessionInfo(id = "dated", name = "dated", workdir = "/x/one", agent = "claude", sortOrder = 1),
            ),
            home = "/home/user",
            lastTs = { ts[it.name] ?: "" },
        )
        assertEquals(listOf("undated", "dated"), groups[0].sessions.map { it.name })
    }

    @Test fun in_progress_does_not_use_recency_as_tiebreaker() {
        val list = listOf(
            SessionInfo(id = "a", name = "a", workdir = "/p", agent = "claude", userStatus = "in_progress", sortOrder = 0),
            SessionInfo(id = "b", name = "b", workdir = "/p", agent = "claude", userStatus = "in_progress", sortOrder = 0),
        )
        val ts = mapOf("a" to "2026-06-01T00:00:00Z", "b" to "2026-06-09T00:00:00Z")
        val sections = buildTaskSections(list) { ts[it.id] ?: "" }
        // Equal sortOrder → stable by id; newer message on b must not float it above a.
        assertEquals(listOf("a", "b"), sections[0].sessions.map { it.name })
    }

    @Test fun sessionsByUserOrder_sorts_by_sort_order() {
        val list = listOf(
            SessionInfo(id = "c", name = "c", workdir = "/p", agent = "claude", sortOrder = 2),
            SessionInfo(id = "a", name = "a", workdir = "/p", agent = "claude", sortOrder = 0),
            SessionInfo(id = "b", name = "b", workdir = "/p", agent = "claude", sortOrder = 1),
        )
        assertEquals(listOf("a", "b", "c"), sessionsByUserOrder(list).map { it.name })
    }

    @Test fun sessionsByUserOrder_puts_negative_sort_order_first_new_session_at_top() {
        // Broker assigns min(peers)-1 on register so a brand-new session sorts above existing ones.
        val list = listOf(
            SessionInfo(id = "old", name = "old", workdir = "/p", agent = "claude", sortOrder = 0),
            SessionInfo(id = "new", name = "new", workdir = "/p", agent = "claude", sortOrder = -1),
        )
        assertEquals(listOf("new", "old"), sessionsByUserOrder(list).map { it.name })
        val sections = buildTaskSections(list) { "" }
        assertEquals(listOf("new", "old"), sections[0].sessions.map { it.name })
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

    @Test fun groupSessions_hides_projects_with_only_settled_sessions() {
        val live = listOf(
            SessionInfo(id = "live", name = "live", workdir = "/home/user/active", agent = "claude", userStatus = "in_progress"),
        )
        val archived = listOf(
            ArchivedDto(id = "old-a", name = "old-a", workdir = "/home/user/settled-only", agent = "claude"),
            ArchivedDto(id = "old-b", name = "old-b", workdir = "/home/user/active", agent = "claude"),
        )
        val groups = groupSessions(live, home = "/home/user", archived = archived)
        // Only the project with live work gets a group card.
        assertEquals(listOf("/home/user/active"), groups.map { it.workdir })
        assertEquals(
            listOf("old-b"),
            groups[0].sections.first { it.key == SectionKey.SETTLED }.sessions.map { it.name },
        )
    }

    @Test fun archived_status_counts_as_settled() {
        val s = SessionInfo(id = "x", name = "x", workdir = "/p", agent = "claude", status = "archived")
        assertEquals(SectionKey.SETTLED, s.sectionKey())
    }

    /// [buildTaskSections] used `compareByDescending { lastTs(it) }`, which re-evaluates its
    /// selector on EVERY comparison — ~2·N·log2(N) calls for an N-item Settled bucket. On Apple
    /// that selector is a Kotlin/Native → Swift callback that scans the fleet for the owning
    /// host, so a 700-row archive turned one sidebar render into ~13,000 bridge crossings and
    /// made `SessionsListView.body` the most expensive view in the macOS app (measured: ~24% of
    /// all main-thread work). The key must be computed exactly once per element.
    @Test fun settled_recency_key_is_evaluated_once_per_session() {
        val live = (0 until 64).map {
            SessionInfo(id = "s$it", name = "s$it", workdir = "/p", agent = "claude", userStatus = "settled")
        }
        var calls = 0
        buildTaskSections(live, lastTs = { calls++; "" })
        assertTrue(calls <= live.size, "lastTs called $calls times for ${live.size} sessions")
    }

    /// Precomputing the key must not change the resulting order. Equal keys (the common case —
    /// archived rows have no resolvable timestamp) keep input order, which is the server's
    /// `killed_at DESC`; distinct keys still sort newest-first.
    @Test fun settled_orders_newest_first_and_is_stable_for_equal_keys() {
        val ts = mapOf("b" to "2026-06-01T10:00:00Z", "c" to "2026-06-01T12:00:00Z")
        val sessions = listOf("a", "b", "c", "d").map {
            SessionInfo(id = it, name = it, workdir = "/p", agent = "claude", userStatus = "settled")
        }
        val settled = buildTaskSections(sessions, lastTs = { ts[it.id] ?: "" })
            .first { it.key == SectionKey.SETTLED }
        // c (newest) then b; a and d share the empty key and keep their input order.
        assertEquals(listOf("c", "b", "a", "d"), settled.sessions.map { it.name })
    }

    @Test fun moveId_reorders() {
        assertEquals(listOf("b", "a", "c"), moveId(listOf("a", "b", "c"), 0, 1))
        assertEquals(listOf("a", "c", "b"), moveId(listOf("a", "b", "c"), 1, 2))
        assertEquals(listOf("a", "b", "c"), moveId(listOf("a", "b", "c"), 1, 1))
    }
}
