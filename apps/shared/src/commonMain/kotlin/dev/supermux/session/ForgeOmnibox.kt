package dev.supermux.session

import dev.supermux.net.ForgeConnection
import dev.supermux.net.RemoteRepo

/**
 * A known local project fed into the picker omnibox (absolute [path] + display [label]).
 *
 * A catalog project also carries its [projectId], its own [name] (matched instead of the folder
 * name) and every one of its [locations] (a path query matches any of them). Its [path] is then
 * only the option's key.
 */
data class ProjectOption(
    val path: String,
    val label: String,
    val name: String? = null,
    val projectId: String? = null,
    val locations: List<String> = emptyList(),
)

/**
 * One option in the project picker omnibox. Mirrors the retired Vue PWA's forge omnibox
 * (retired Vue PWA; see git history before 2026-09-12):
 *  - [Local]  an existing known project workdir
 *  - [Cloud]  a remote repo on a connected forge (offer to clone)
 *  - [Create] create a brand-new repo, locally or on a forge
 */
sealed interface OmniOption {
    val label: String

    /** [nameHits] are the fuzzy-matched characters of the path's last segment, for highlighting. */
    data class Local(
        override val label: String,
        val path: String,
        val nameHits: List<Int> = emptyList(),
        /** A catalog project's name and id; null for a plain folder. */
        val name: String? = null,
        val projectId: String? = null,
    ) : OmniOption
    data class Cloud(override val label: String, val connectionId: String, val repo: RemoteRepo) : OmniOption
    /** [createTarget] is "local" for a local `git init`, otherwise a forge connection id. */
    data class Create(
        override val label: String,
        val createTarget: String,
        val connection: ForgeConnection? = null,
    ) : OmniOption
}

private val VALID_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")

/**
 * Build the ordered omnibox option list from the typed [query], the known
 * [localProjects], the debounced forge [cloudRepos], and the configured
 * [connections]. A faithful port of the web `buildOmniboxOptions`:
 *  - a path-like query ([looksLikePath]) lists the known projects under that path prefix
 *  - otherwise local projects fuzzy-matched on the folder name ([fuzzyMatch]), then the label as a substring, with a
 *    plain path substring as the last resort; best match first, ties keep the given (recency)
 *    order. The full list, in the given order, when the query is blank
 *  - every cloud repo becomes a clone option
 *  - "Create" rows (local + one per connection) appear only when the query is a
 *    valid repo name and doesn't already exactly match a known project label or
 *    a cloud repo name/fullName
 */
fun buildOmniboxOptions(
    query: String,
    localProjects: List<ProjectOption>,
    cloudRepos: List<RemoteRepo>,
    connections: List<ForgeConnection>,
    home: String = "",
): List<OmniOption> {
    val q = query.trim()
    val ql = q.lowercase()

    fun ProjectOption.option(hits: List<Int> = emptyList()) = OmniOption.Local(label, path, hits, name, projectId)
    val local = if (q.isEmpty()) {
        localProjects.map { it.option() }
    } else if (looksLikePath(q)) {
        // A path query lists the known projects under it ("~/pro" → everything in ~/projects),
        // in recency order; "~" expands to [home].
        val prefix = (if (q.startsWith("~") && home.isNotEmpty()) home + q.drop(1) else q).lowercase()
        localProjects
            .filter { p -> p.locations.ifEmpty { listOf(p.path) }.any { it.lowercase().startsWith(prefix) } }
            .map { it.option() }
    } else {
        localProjects.mapNotNull { p ->
            val name = p.name ?: projectFolderName(p.path)
            val nameHit = fuzzyMatch(q, name)
            // The label ("…/parent/name") and path count only as contiguous substrings: letters
            // scattered across "projects/…" would otherwise match nearly every project.
            val score = when {
                nameHit != null -> nameHit.score
                p.label.lowercase().contains(ql) -> 20
                p.locations.ifEmpty { listOf(p.path) }.any { it.lowercase().contains(ql) } -> 1
                else -> return@mapNotNull null
            }
            score to p.option(nameHit?.indices.orEmpty())
        }
            // sortedByDescending is stable, so equal scores keep the recency order.
            .sortedByDescending { it.first }
            .map { it.second }
    }

    val cloud = cloudRepos.map { OmniOption.Cloud(it.fullName, it.connectionId, it) }

    val exact = q.isNotEmpty() && (
        local.any { (it.name ?: projectFolderName(it.path)).lowercase() == ql || it.label.lowercase() == ql } ||
            cloud.any { it.repo.name.lowercase() == ql || it.repo.fullName.lowercase() == ql }
        )

    val creates: List<OmniOption.Create> =
        if (q.isNotEmpty() && VALID_NAME.matches(q) && !exact) {
            buildList {
                add(OmniOption.Create("Create locally — $q", "local"))
                connections.forEach { c ->
                    add(OmniOption.Create("Create on ${c.host} — ${c.account.login}/$q", c.id, c))
                }
            }
        } else {
            emptyList()
        }

    return local + cloud + creates
}

/** The last path segment — what a project is called in the picker ("~" stays "~"). */
fun projectFolderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }

/** Whether the omnibox should treat [query] as a folder path rather than a name to search. */
fun looksLikePath(query: String): Boolean {
    val q = query.trim()
    return q.startsWith("/") || q.startsWith("~") || q.startsWith(".") || q.contains('/')
}
