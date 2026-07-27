package dev.supermux.session

import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultProjectTest {
    private fun s(
        id: String,
        workdir: String,
        repo: String? = null,
    ) = SessionInfo(
        id = id,
        name = id,
        workdir = workdir,
        agent = "claude",
        repo_root = repo,
    )

    @Test fun chooseDefault_empty_keeps_current() {
        assertEquals(
            "~",
            chooseDefaultProject(current = "~", recent = emptyList(), picked = false, composing = false),
        )
    }

    @Test fun chooseDefault_follows_most_recent() {
        assertEquals(
            "/first",
            chooseDefaultProject(current = "~", recent = listOf("/first"), picked = false, composing = false),
        )
        assertEquals(
            "/second",
            chooseDefaultProject(
                current = "/first",
                recent = listOf("/second", "/first"),
                picked = false,
                composing = false,
            ),
        )
    }

    @Test fun chooseDefault_freezes_when_picked() {
        assertEquals(
            "/chosen",
            chooseDefaultProject(
                current = "/chosen",
                recent = listOf("/latest"),
                picked = true,
                composing = false,
            ),
        )
    }

    @Test fun chooseDefault_freezes_when_composing() {
        assertEquals(
            "/first",
            chooseDefaultProject(
                current = "/first",
                recent = listOf("/second", "/first"),
                picked = false,
                composing = true,
            ),
        )
    }

    @Test fun recentWorkdirs_uses_repo_root_and_dedupes() {
        val sessions = listOf(
            s("a", workdir = "/home/u/.mux/worktrees/x", repo = "/home/u/projects/foo"),
            s("b", workdir = "/home/u/projects/foo"),
            s("c", workdir = "/home/u/projects/bar"),
        )
        assertEquals(
            listOf("/home/u/projects/foo", "/home/u/projects/bar"),
            recentWorkdirs(sessions),
        )
    }

    @Test fun orderProjectsByRecency_recent_first() {
        assertEquals(
            listOf("/b", "/a", "/c"),
            orderProjectsByRecency(
                recent = listOf("/b", "/a"),
                known = listOf("/a", "/b", "/c"),
            ),
        )
    }

    @Test fun sessionsByRecency_newest_first() {
        val a = s("a", "/a")
        val b = s("b", "/b")
        val c = s("c", "/c")
        val ts = mapOf(
            "a" to "2026-06-01T08:00:00Z",
            "b" to "2026-06-01T12:00:00Z",
            "c" to "2026-06-01T10:00:00Z",
        )
        assertEquals(
            listOf(b, c, a),
            sessionsByRecency(listOf(a, b, c)) { ts[it.id] ?: "" },
        )
    }
}
