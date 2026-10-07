package dev.supermux.session

import dev.supermux.proto.SessionInfo
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectActivityTest {
    private fun s(id: String, workdir: String, repoRoot: String? = null) =
        SessionInfo(id = id, name = id, workdir = workdir, agent = "claude", repo_root = repoRoot)

    @Test fun groups_by_project_path_and_keeps_the_latest_time() {
        val sessions = listOf(
            s("a", "/p/app"),
            s("b", "/p/app/sub", repoRoot = "/p/app"),
            s("c", "/p/other"),
        )
        val ts = mapOf("a" to 1_000L, "b" to 5_000L)
        val out = projectActivity(sessions) { ts[it.id] }
        assertEquals(ProjectActivity(2, 5_000L), out["/p/app"])
        assertEquals(ProjectActivity(1, null), out["/p/other"])
    }

    @Test fun ages_bucket_like_the_rest_of_the_app() {
        val now = 100L * 86_400_000L
        assertEquals("now", formatAgoShort(now, now - 30_000L))
        assertEquals("5m", formatAgoShort(now, now - 5 * 60_000L))
        assertEquals("3h", formatAgoShort(now, now - 3 * 3_600_000L))
        assertEquals("2d", formatAgoShort(now, now - 2 * 86_400_000L))
        assertEquals("3w", formatAgoShort(now, now - 21 * 86_400_000L))
        assertEquals("now", formatAgoShort(now, now + 5_000L), "clock skew never goes negative")
    }
}
