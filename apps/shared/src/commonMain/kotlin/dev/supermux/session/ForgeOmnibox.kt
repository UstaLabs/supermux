package dev.supermux.session

import dev.supermux.net.ForgeConnection
import dev.supermux.net.RemoteRepo

/** A known local project fed into the picker omnibox (absolute [path] + display [label]). */
data class ProjectOption(val path: String, val label: String)

/**
 * One option in the project picker omnibox. Mirrors src/web-app/src/lib/forge-omnibox.ts:
 *  - [Local]  an existing known project workdir
 *  - [Cloud]  a remote repo on a connected forge (offer to clone)
 *  - [Create] create a brand-new repo, locally or on a forge
 */
sealed interface OmniOption {
    val label: String

    data class Local(override val label: String, val path: String) : OmniOption
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
 *  - local projects filtered by query (label OR path contains, case-insensitive);
 *    the full list when the query is blank
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
): List<OmniOption> {
    val q = query.trim()
    val ql = q.lowercase()

    val local = localProjects
        .filter { q.isEmpty() || it.label.lowercase().contains(ql) || it.path.lowercase().contains(ql) }
        .map { OmniOption.Local(it.label, it.path) }

    val cloud = cloudRepos.map { OmniOption.Cloud(it.fullName, it.connectionId, it) }

    val exact = q.isNotEmpty() && (
        local.any { it.label.lowercase() == ql } ||
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
