package dev.supermux.session

import dev.supermux.net.ArchivedDto
import kotlin.test.Test
import kotlin.test.assertEquals

class ArchivedProjectsTest {
    private val HOME = "/home/ahmet"

    private fun a(workdir: String, repo: String? = null, killed: String? = null) =
        ArchivedDto(id = "x", name = "n", workdir = workdir, agent = "claude", killed_at = killed, repo_root = repo)

    @Test
    fun dedupes_and_counts() {
        assertEquals(
            listOf(ArchivedProject("/home/ahmet/projects/foo", "…/projects/foo", 2)),
            archivedProjects(
                listOf(
                    a("/home/ahmet/projects/foo", killed = "2026-06-01T00:00:00Z"),
                    a("/home/ahmet/projects/foo", killed = "2026-06-02T00:00:00Z"),
                ),
                HOME,
            ),
        )
    }

    @Test
    fun groups_worktree_under_repo_root() {
        assertEquals(
            listOf(ArchivedProject("/home/ahmet/projects/foo", "…/projects/foo", 2)),
            archivedProjects(
                listOf(
                    a("/home/ahmet/.mux/worktrees/x/abc", repo = "/home/ahmet/projects/foo", killed = "2026-06-01T00:00:00Z"),
                    a("/home/ahmet/projects/foo", killed = "2026-06-02T00:00:00Z"),
                ),
                HOME,
            ),
        )
    }

    @Test
    fun orders_most_recent_first() {
        assertEquals(
            listOf("…/projects/new", "…/projects/old"),
            archivedProjects(
                listOf(
                    a("/home/ahmet/projects/old", killed = "2026-06-01T00:00:00Z"),
                    a("/home/ahmet/projects/new", killed = "2026-06-10T00:00:00Z"),
                ),
                HOME,
            ).map { it.label },
        )
    }

    @Test
    fun empty_input_yields_empty() {
        assertEquals(emptyList<ArchivedProject>(), archivedProjects(emptyList(), HOME))
    }

    @Test
    fun label_shortens_non_home_path() {
        assertEquals(
            listOf("…/www/acme"),
            archivedProjects(listOf(a("/srv/www/acme", killed = "2026-06-01T00:00:00Z")), HOME).map { it.label },
        )
    }

    @Test
    fun ties_break_alphabetically_by_label() {
        assertEquals(
            listOf("…/projects/alpha", "…/projects/beta"),
            archivedProjects(
                listOf(
                    a("/home/ahmet/projects/beta", killed = "2026-06-05T00:00:00Z"),
                    a("/home/ahmet/projects/alpha", killed = "2026-06-05T00:00:00Z"),
                ),
                HOME,
            ).map { it.label },
        )
    }

    @Test
    fun null_killed_at_sorts_last() {
        assertEquals(
            listOf("…/projects/recent", "…/projects/null-killed"),
            archivedProjects(
                listOf(
                    a("/home/ahmet/projects/null-killed", killed = null),
                    a("/home/ahmet/projects/recent", killed = "2026-06-10T00:00:00Z"),
                ),
                HOME,
            ).map { it.label },
        )
    }

    @Test
    fun filter_matches_by_key() {
        val sessions = listOf(a("/home/ahmet/projects/foo"), a("/home/ahmet/projects/bar"))
        assertEquals(
            listOf(a("/home/ahmet/projects/foo")),
            filterArchivedByProject(sessions, "/home/ahmet/projects/foo"),
        )
    }

    @Test
    fun filter_matches_worktree_by_repo_root() {
        val wt = a("/home/ahmet/.mux/worktrees/x/abc", repo = "/home/ahmet/projects/foo")
        assertEquals(listOf(wt), filterArchivedByProject(listOf(wt), "/home/ahmet/projects/foo"))
    }

    @Test
    fun filter_null_returns_all() {
        val sessions = listOf(a("/home/ahmet/projects/foo"), a("/home/ahmet/projects/bar"))
        assertEquals(sessions, filterArchivedByProject(sessions, null))
    }
}
