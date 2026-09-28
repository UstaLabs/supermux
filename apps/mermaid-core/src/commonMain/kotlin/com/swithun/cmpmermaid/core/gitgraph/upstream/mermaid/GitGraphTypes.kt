package com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid

/**
 * Kotlin translation of Mermaid 12.0.0 gitGraphTypes.ts.
 */
internal enum class GitGraphCommitType(
    val upstreamValue: Int,
) {
    Normal(0),
    Reverse(1),
    Highlight(2),
    Merge(3),
    CherryPick(4),
}

internal enum class GitGraphDirection {
    LR,
    TB,
    BT,
}

internal data class GitGraphCommitInput(
    val message: String = "",
    val id: String = "",
    val type: GitGraphCommitType = GitGraphCommitType.Normal,
    val tags: List<String>? = null,
)

internal data class GitGraphBranchInput(
    val name: String,
    val order: Float = 0f,
)

internal data class GitGraphMergeInput(
    val branch: String,
    val id: String = "",
    val type: GitGraphCommitType? = null,
    val tags: List<String>? = null,
)

internal data class GitGraphCherryPickInput(
    val id: String = "",
    val targetId: String = "",
    val parent: String = "",
    val tags: List<String>? = null,
)

internal data class GitGraphCommit(
    val id: String,
    val message: String,
    val seq: Int,
    val type: GitGraphCommitType,
    val tags: List<String>,
    val parents: List<String>,
    val branch: String,
    val customType: GitGraphCommitType? = null,
    val customId: Boolean = false,
)

internal data class GitGraphBranch(
    val name: String,
    val order: Float?,
)
