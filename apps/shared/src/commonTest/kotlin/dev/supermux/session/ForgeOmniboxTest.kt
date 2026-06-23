package dev.supermux.session

import dev.supermux.net.ForgeAccount
import dev.supermux.net.ForgeConnection
import dev.supermux.net.RemoteRepo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun proj(path: String, label: String = path.substringAfterLast('/')) = ProjectOption(path, label)

private fun repo(connId: String, owner: String, name: String) =
    RemoteRepo(connectionId = connId, owner = owner, name = name, fullName = "$owner/$name")

private fun conn(id: String, host: String, login: String) =
    ForgeConnection(id = id, host = host, account = ForgeAccount(login = login))

class ForgeOmniboxTest {
    private val projects = listOf(
        proj("/home/u/projects/supermux", "supermux"),
        proj("/home/u/projects/flight-track", "flight-track"),
    )
    private val conns = listOf(conn("c1", "github.com", "ahmet"))

    @Test fun blank_query_returns_all_locals_no_creates() {
        val out = buildOmniboxOptions("", projects, emptyList(), conns)
        assertEquals(2, out.filterIsInstance<OmniOption.Local>().size)
        assertTrue(out.none { it is OmniOption.Create }, "no create rows on a blank query")
    }

    @Test fun query_filters_locals_by_label_or_path() {
        val out = buildOmniboxOptions("flight", projects, emptyList(), conns)
        val locals = out.filterIsInstance<OmniOption.Local>()
        assertEquals(listOf("/home/u/projects/flight-track"), locals.map { it.path })
    }

    @Test fun cloud_repos_become_clone_options_in_order() {
        val repos = listOf(repo("c1", "ahmet", "alpha"), repo("c1", "ahmet", "beta"))
        val out = buildOmniboxOptions("al", projects, repos, conns)
        val cloud = out.filterIsInstance<OmniOption.Cloud>()
        assertEquals(listOf("ahmet/alpha", "ahmet/beta"), cloud.map { it.label })
        assertEquals("c1", cloud.first().connectionId)
    }

    @Test fun valid_new_name_offers_create_local_plus_one_per_connection() {
        val out = buildOmniboxOptions("brand-new", projects, emptyList(), conns)
        val creates = out.filterIsInstance<OmniOption.Create>()
        assertEquals(2, creates.size)
        assertEquals("local", creates[0].createTarget)
        assertEquals("c1", creates[1].createTarget)
        assertTrue(creates[1].label.contains("ahmet/brand-new"))
    }

    @Test fun exact_local_label_match_suppresses_create() {
        val out = buildOmniboxOptions("supermux", projects, emptyList(), conns)
        assertTrue(out.none { it is OmniOption.Create }, "exact known-project name → no create")
    }

    @Test fun exact_cloud_name_match_suppresses_create() {
        val repos = listOf(repo("c1", "ahmet", "widget"))
        val out = buildOmniboxOptions("widget", projects, repos, conns)
        assertTrue(out.none { it is OmniOption.Create }, "exact cloud repo name → no create")
    }

    @Test fun invalid_name_offers_no_create() {
        // A space and a leading dash both fail the repo-name pattern.
        assertTrue(buildOmniboxOptions("foo bar", projects, emptyList(), conns).none { it is OmniOption.Create })
        assertTrue(buildOmniboxOptions("-foo", projects, emptyList(), conns).none { it is OmniOption.Create })
    }

    @Test fun create_offered_even_with_no_connections() {
        val out = buildOmniboxOptions("solo", projects, emptyList(), emptyList())
        val creates = out.filterIsInstance<OmniOption.Create>()
        assertEquals(1, creates.size)
        assertEquals("local", creates[0].createTarget)
    }
}
