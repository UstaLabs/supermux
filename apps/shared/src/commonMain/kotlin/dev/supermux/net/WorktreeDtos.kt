package dev.supermux.net

import kotlinx.serialization.Serializable

// Mirrors src/core/worktree/inventory.ts (broker). Field names are the JSON names.

@Serializable data class WorktreeOwnerDto(val id: String, val name: String, val status: String) {
    val isLive: Boolean get() = status == "live"
}

@Serializable data class IgnoredEntryDto(val name: String, val wellKnown: Boolean = false, val bytes: Long? = null)

@Serializable data class WorktreeSummaryDto(
    val id: String,
    val path: String,
    val repoRoot: String? = null,
    val repoName: String,
    val branch: String? = null,
    val owners: List<WorktreeOwnerDto> = emptyList(),
    val mtime: Long = 0,
    val uncommitted: Int = 0,
    val unmerged: Int? = null,
    val ignored: List<IgnoredEntryDto> = emptyList(),
    /** Conservative default: a summary without this field is never treated as "no changes". */
    val hasChanges: Boolean = true,
    val bytes: Long? = null,
    val error: String? = null,
) {
    val liveOwners: List<WorktreeOwnerDto> get() = owners.filter { it.isLive }
}

@Serializable data class WorktreeListDto(val root: String, val worktrees: List<WorktreeSummaryDto> = emptyList())

@Serializable data class WorktreeFileDto(val status: String, val path: String)
@Serializable data class WorktreeCommitDto(val sha: String, val subject: String)
@Serializable data class WorktreeTruncatedDto(val files: Int = 0, val commits: Int = 0, val ignored: Int = 0)

@Serializable data class WorktreeChangesDto(
    val id: String,
    val branch: String? = null,
    val baseRef: String? = null,
    val files: List<WorktreeFileDto> = emptyList(),
    val commits: List<WorktreeCommitDto> = emptyList(),
    val ignored: List<IgnoredEntryDto> = emptyList(),
    val truncated: WorktreeTruncatedDto = WorktreeTruncatedDto(),
    /** False when the broker found no base branch, so [commits] could not be listed (not "none").
     *  Conservative default false: a response without it never reads as verified. */
    val unmergedKnown: Boolean = false,
    /** Why the worktree couldn't be inspected ("repo gone", "not a git worktree", a git error).
     *  When set the lists are incomplete and must never be shown as "no changes". */
    val error: String? = null,
)

@Serializable data class WorktreeForWorkdirDto(
    val id: String,
    val branch: String? = null,
    val owners: List<WorktreeOwnerDto> = emptyList(),
    val changes: WorktreeChangesDto,
    val bytes: Long? = null,
)

@Serializable data class WorktreeDeleteResultDto(
    val id: String,
    val ok: Boolean,
    val error: String? = null,
    val inUseBy: List<String> = emptyList(),
)

@Serializable internal data class WorktreeDeleteBody(val ids: List<String>)
@Serializable internal data class WorktreeDeleteResponse(val results: List<WorktreeDeleteResultDto> = emptyList())
@Serializable internal data class ArchiveWithWorktreeResponse(val worktree: List<WorktreeDeleteResultDto> = emptyList())
