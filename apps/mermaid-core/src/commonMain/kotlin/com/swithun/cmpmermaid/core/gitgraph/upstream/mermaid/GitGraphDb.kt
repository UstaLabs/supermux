package com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Kotlin translation of Mermaid 12.0.0 gitGraphAst.ts.
 */
internal class GitGraphDb(
    mainBranchName: String = "main",
    mainBranchOrder: Float = 0f,
    diagramTitle: String? = null,
) {
    private val commits = linkedMapOf<String, GitGraphCommit>()
    private val branchConfig = linkedMapOf(
        mainBranchName to GitGraphBranch(mainBranchName, mainBranchOrder),
    )
    private val branches = linkedMapOf<String, String?>(mainBranchName to null)
    private var head: GitGraphCommit? = null
    private var currentBranch = mainBranchName
    private var direction = GitGraphDirection.LR
    private var sequence = 0

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun setDirection(value: GitGraphDirection) {
        direction = value
    }

    fun commit(input: GitGraphCommitInput): GMResult<Unit, MermaidError> {
        // Mermaid: gitGraphAst.ts -> commit
        val id = sanitize(input.id).ifEmpty { generatedId(sequence) }
        val commit = GitGraphCommit(
            id = id,
            message = sanitize(input.message),
            seq = sequence++,
            type = input.type,
            tags = input.tags.orEmpty().map(::sanitize),
            parents = head?.let { parent -> listOf(parent.id) }.orEmpty(),
            branch = currentBranch,
        )
        head = commit
        commits[id] = commit
        branches[currentBranch] = id
        return GMResult.Ok(Unit)
    }

    fun branch(input: GitGraphBranchInput): GMResult<Unit, MermaidError> {
        // Mermaid: gitGraphAst.ts -> branch
        val name = sanitize(input.name)
        if (branches.containsKey(name)) {
            return semanticError(
                "Trying to create an existing branch. " +
                    "(Help: Either use a new name if you want create a new branch or try using " +
                    "\"checkout $name\")",
            )
        }
        branches[name] = head?.id
        branchConfig[name] = GitGraphBranch(name, input.order)
        return checkout(name)
    }

    fun merge(input: GitGraphMergeInput): GMResult<Unit, MermaidError> {
        // Mermaid: gitGraphAst.ts -> merge
        val otherBranch = sanitize(input.branch)
        val customId = sanitize(input.id)
        val currentCommit = branches[currentBranch]?.let(commits::get)
        val otherBranchHead = branches[otherBranch]
        val otherCommit = otherBranchHead?.let(commits::get)

        if (currentCommit != null && otherCommit != null && currentCommit.branch == otherBranch) {
            return semanticError("Cannot merge branch '$otherBranch' into itself.")
        }
        if (currentBranch == otherBranch) {
            return semanticError(
                "Incorrect usage of \"merge\". Cannot merge a branch to itself",
            )
        }
        if (currentCommit == null) {
            return semanticError(
                "Incorrect usage of \"merge\". Current branch ($currentBranch)has no commits",
            )
        }
        if (!branches.containsKey(otherBranch)) {
            return semanticError(
                "Incorrect usage of \"merge\". Branch to be merged " +
                    "($otherBranch) does not exist",
            )
        }
        if (otherCommit == null) {
            return semanticError(
                "Incorrect usage of \"merge\". Branch to be merged " +
                    "($otherBranch) has no commits",
            )
        }
        if (currentCommit == otherCommit) {
            return semanticError(
                "Incorrect usage of \"merge\". Both branches have same head",
            )
        }
        if (customId.isNotEmpty() && commits.containsKey(customId)) {
            return semanticError(
                "Incorrect usage of \"merge\". Commit with id:$customId already exists, " +
                    "use different custom id",
            )
        }

        val commit = GitGraphCommit(
            id = customId.ifEmpty { generatedId(sequence) },
            message = "merged branch $otherBranch into $currentBranch",
            seq = sequence++,
            parents = listOf(currentCommit.id, otherCommit.id),
            branch = currentBranch,
            type = GitGraphCommitType.Merge,
            customType = input.type,
            customId = customId.isNotEmpty(),
            tags = input.tags.orEmpty().map(::sanitize),
        )
        head = commit
        commits[commit.id] = commit
        branches[currentBranch] = commit.id
        return GMResult.Ok(Unit)
    }

    fun cherryPick(input: GitGraphCherryPickInput): GMResult<Unit, MermaidError> {
        // Mermaid: gitGraphAst.ts -> cherryPick
        val sourceId = sanitize(input.id)
        val targetId = sanitize(input.targetId)
        val parentCommitId = sanitize(input.parent)
        val sourceCommit = commits[sourceId]
            ?: return semanticError(
                "Incorrect usage of \"cherryPick\". Source commit id should exist and provided",
            )

        if (parentCommitId.isNotEmpty() && parentCommitId !in sourceCommit.parents) {
            return semanticError(
                "Invalid operation: The specified parent commit is not an immediate parent " +
                    "of the cherry-picked commit.",
            )
        }
        if (sourceCommit.type == GitGraphCommitType.Merge && parentCommitId.isEmpty()) {
            return semanticError(
                "Incorrect usage of cherry-pick: If the source commit is a merge commit, " +
                    "an immediate parent commit must be specified.",
            )
        }

        // Mermaid currently only creates a commit when targetId is absent or unknown.
        if (targetId.isNotEmpty() && commits.containsKey(targetId)) {
            return GMResult.Ok(Unit)
        }
        if (sourceCommit.branch == currentBranch) {
            return semanticError(
                "Incorrect usage of \"cherryPick\". Source commit is already on current branch",
            )
        }
        val currentCommitId = branches[currentBranch]
            ?: return semanticError(
                "Incorrect usage of \"cherry-pick\". Current branch " +
                    "($currentBranch)has no commits",
            )
        val currentCommit = commits[currentCommitId]
            ?: return semanticError(
                "Incorrect usage of \"cherry-pick\". Current branch " +
                    "($currentBranch)has no commits",
            )
        val tags = input.tags?.map(::sanitize)?.filter(String::isNotEmpty)
            ?: listOf(
                buildString {
                    append("cherry-pick:")
                    append(sourceCommit.id)
                    if (sourceCommit.type == GitGraphCommitType.Merge) {
                        append("|parent:")
                        append(parentCommitId)
                    }
                },
            )
        val commit = GitGraphCommit(
            id = generatedId(sequence),
            message = "cherry-picked ${sourceCommit.message} into $currentBranch",
            seq = sequence++,
            parents = listOf(currentCommit.id, sourceCommit.id),
            branch = currentBranch,
            type = GitGraphCommitType.CherryPick,
            tags = tags,
        )
        head = commit
        commits[commit.id] = commit
        branches[currentBranch] = commit.id
        return GMResult.Ok(Unit)
    }

    fun checkout(branch: String): GMResult<Unit, MermaidError> {
        // Mermaid: gitGraphAst.ts -> checkout
        val sanitized = sanitize(branch)
        if (!branches.containsKey(sanitized)) {
            return semanticError(
                "Trying to checkout branch which is not yet created. " +
                    "(Help try using \"branch $sanitized\")",
            )
        }
        currentBranch = sanitized
        head = branches[sanitized]?.let(commits::get)
        return GMResult.Ok(Unit)
    }

    fun getBranchesAsArray(): List<GitGraphBranch> = branchConfig.values
        .mapIndexed { index, branch ->
            if (branch.order != null) {
                branch
            } else {
                branch.copy(order = index.toFloat())
            }
        }
        .sortedBy { branch -> branch.order }

    fun getBranches(): Map<String, String?> = branches.toMap()

    fun getCommits(): Map<String, GitGraphCommit> = commits.toMap()

    fun getCommitsArray(): List<GitGraphCommit> = commits.values.sortedBy(GitGraphCommit::seq)

    fun getCurrentBranch(): String = currentBranch

    fun getDirection(): GitGraphDirection = direction

    fun getHead(): GitGraphCommit? = head

    fun setDiagramTitle(value: String) {
        diagramTitle = sanitize(value)
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = sanitize(value)
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = sanitize(value)
    }

    private fun generatedId(sequence: Int): String {
        // Mermaid's suffix is random. A stable seven-character suffix preserves its public
        // ID shape while keeping native screenshots and tests reproducible.
        var value = (sequence.toLong() + 1L) * 2_654_435_761L
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
        return buildString {
            append(sequence)
            append('-')
            repeat(7) {
                val index = (value and 0x7fffffffL).rem(alphabet.length).toInt()
                append(alphabet[index])
                value = value * 1_103_515_245L + 12_345L
            }
        }
    }

    private fun sanitize(value: String): String =
        MermaidPreprocessor.decodeEntities(value)

    private fun <T> semanticError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(
            MermaidError.Parse(
                line = 1,
                column = 1,
                message = message,
            ),
        )
}
