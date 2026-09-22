// Which directory a new workspace in a persistent project starts in (Persistent Projects, Task 10).
//
// Pure decisions only; the launcher owns the pickers. One location → it; several → the one the
// user last picked for that (host, project) if it still belongs to the project, else ask; none →
// the user has to add one first. A remembered path that left the project is simply ignored (and
// overwritten on the next pick), never trusted.
package dev.supermux.workspace

import dev.supermux.proto.ProjectDto

/** Which directory a new workspace in [project] starts in. */
sealed interface LaunchLocation {
    data class Chosen(val path: String) : LaunchLocation
    data class Choose(val paths: List<String>) : LaunchLocation
    data object NeedsLocation : LaunchLocation
}

fun launchLocation(project: ProjectDto, remembered: String?): LaunchLocation {
    val paths = project.locations.map { it.path }
    return when {
        paths.isEmpty() -> LaunchLocation.NeedsLocation
        paths.size == 1 -> LaunchLocation.Chosen(paths.single())
        remembered != null && remembered in paths -> LaunchLocation.Chosen(remembered)
        else -> LaunchLocation.Choose(paths)
    }
}

/**
 * The key a remembered launch location is stored under. Host-qualified: project ids are unique
 * per broker only, so the same id on another host is a different project.
 */
fun projectLocationKey(hostId: String, projectId: String): String = "$hostId/$projectId"

/** A host's catalog in the order the sidebar paints it: sortOrder, then name, then id. */
fun orderProjectCatalog(projects: List<ProjectDto>): List<ProjectDto> =
    projects.sortedWith(compareBy({ it.sortOrder }, { it.name }, { it.id }))

/** The project one of whose locations IS [path] (exact match — no prefix guessing), or null. */
fun projectOwning(projects: List<ProjectDto>, path: String): ProjectDto? =
    projects.firstOrNull { p -> p.locations.any { it.path == path } }
