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

    @Test fun query_matches_folder_name_fuzzily() {
        val out = buildOmniboxOptions("smx", projects, emptyList(), conns)
        val locals = out.filterIsInstance<OmniOption.Local>()
        assertEquals(listOf("/home/u/projects/supermux"), locals.map { it.path })
        assertEquals(listOf(0, 5, 7), locals.single().nameHits)
    }

    @Test fun better_match_ranks_first_regardless_of_recency_order() {
        val recentFirst = listOf(
            proj("/home/u/work/team-tracker", "team-tracker"),
            proj("/home/u/projects/tracker", "tracker"),
        )
        val out = buildOmniboxOptions("tracker", recentFirst, emptyList(), conns)
        assertEquals(
            listOf("/home/u/projects/tracker", "/home/u/work/team-tracker"),
            out.filterIsInstance<OmniOption.Local>().map { it.path },
        )
    }

    @Test fun fuzzy_does_not_match_scattered_letters_across_the_whole_path() {
        // "hup" is a subsequence of "/home/u/projects/supermux" but not of its name or label.
        assertTrue(buildOmniboxOptions("hup", projects, emptyList(), conns).none { it is OmniOption.Local })
    }

    @Test fun a_tilde_path_lists_the_projects_under_it() {
        val out = buildOmniboxOptions("~/pro", projects, emptyList(), conns, home = "/home/u")
        assertEquals(
            listOf("/home/u/projects/supermux", "/home/u/projects/flight-track"),
            out.filterIsInstance<OmniOption.Local>().map { it.path },
        )
        assertTrue(out.none { it is OmniOption.Create }, "a path is not a repo name")
    }

    @Test fun path_detection() {
        assertTrue(looksLikePath("~/x"))
        assertTrue(looksLikePath("/opt"))
        assertTrue(looksLikePath("./rel"))
        assertTrue(looksLikePath("a/b"))
        assertTrue(!looksLikePath("supermux"))
        assertTrue(!looksLikePath("new-thing"))
    }

    @Test fun letters_scattered_across_the_parent_folder_do_not_match() {
        // t-e-r-m is a subsequence of "…/projects/supermux", but not of "supermux".
        val labelled = listOf(proj("/home/u/projects/supermux", "…/projects/supermux"))
        assertTrue(buildOmniboxOptions("term", labelled, emptyList(), conns).none { it is OmniOption.Local })
        // The parent folder still matches as a word.
        assertEquals(1, buildOmniboxOptions("projects", labelled, emptyList(), conns).filterIsInstance<OmniOption.Local>().size)
    }
}
