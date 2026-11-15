// #152: the launcher's row under the composer, as data. Where the session works (the project folder itself, or its
// own worktree), on which branch, and what the broker is asked for at Start. Pure, so every state is unit-tested;
// LauncherWorkRow.kt draws it.
package dev.supermux.ui.session

import dev.supermux.net.GitBranches
import dev.supermux.net.GitLocalBranch
import dev.supermux.net.GitOpenIn
import dev.supermux.net.GitRemoteBranch
import dev.supermux.net.RepoInfo
import dev.supermux.net.SessionSourceBody

/** Where the new session works. Worktree is the default; the last pick is remembered per project, per user. */
enum class WorkIn(val wire: String) {
    Local("local"),
    Worktree("worktree");

    companion object {
        fun of(wire: String?): WorkIn = if (wire == Local.wire) Local else Worktree
    }
}

/** How a worktree gets its branch: the picker's tabs. */
enum class BranchMode { New, Existing }

/** What is picked under the composer. */
data class WorkPick(
    val workIn: WorkIn = WorkIn.Worktree,
    val mode: BranchMode = BranchMode.New,
    /** New: the base as listed ("main", or "origin/main" for a branch only origin has); "" = the default branch. */
    val base: String = "",
    /** New: the name typed after the prefix; "" = automatic, from the first message. */
    val name: String = "",
    /** Existing: the branch as listed (a local name, or "origin/x"). */
    val branch: String? = null,
    /** Local: the branch the folder switches to at Start; null = it stays where it is. */
    val switchTo: String? = null,
)

/** One branch as a picker lists it. [name] is what gets picked: a local name, or "origin/x" for one only origin has. */
data class BranchRow(
    val name: String,
    val local: Boolean,
    val isDefault: Boolean,
    val upstream: String?,
    val ahead: Int,
    val openIn: GitOpenIn?,
    val author: String,
    val at: Long,
) {
    /** The project folder has it checked out: "this" seen from the folder, "folder" from a linked worktree. */
    val inFolder: Boolean get() = openIn?.kind == "this" || openIn?.kind == "folder"
    val inSession: Boolean get() = openIn?.kind == "session"
    /** Checked out somewhere already (the folder, a session's copy, a worktree no session owns): git keeps a branch in one place. */
    val held: Boolean get() = openIn != null
    /** As a list shows it: a remote's branch under its remote's heading, without the remote's name. */
    val shown: String get() = if (local) name else name.substringAfter('/')
}

/** The default first, then the most recently changed. */
val BRANCH_ORDER: Comparator<BranchRow> = compareByDescending<BranchRow> { it.isDefault }.thenByDescending { it.at }

/** The local branches, then the ones only a remote has; each in [BRANCH_ORDER]. */
fun branchRows(b: GitBranches?): Pair<List<BranchRow>, List<BranchRow>> {
    if (b == null) return emptyList<BranchRow>() to emptyList()
    val order = BRANCH_ORDER
    val local = b.local.map { BranchRow(it.name, true, it.isDefault, it.upstream, it.ahead, it.openIn, it.author, it.at) }.sortedWith(order)
    val names = local.map { it.name }.toSet()
    val remote = b.remote
        .filter { it.name.substringAfter('/') !in names }
        .map { BranchRow(it.name, false, it.isDefault, null, 0, null, it.author, it.at) }
        .sortedWith(order)
    return local to remote
}

/** A broker older than #152 lists branch names only (GET /repos/info): enough for a base to start from. */
fun legacyBranches(info: RepoInfo): GitBranches = GitBranches(
    current = info.currentBranch,
    local = info.branches?.local.orEmpty().map { GitLocalBranch(it) },
    remote = info.branches?.remote.orEmpty().map { GitRemoteBranch(it) },
)

/** [rows] whose name contains [query], ignoring case. */
fun filterRows(rows: List<BranchRow>, query: String): List<BranchRow> {
    val q = query.trim().lowercase()
    return if (q.isEmpty()) rows else rows.filter { it.name.lowercase().contains(q) }
}

/** The default branch's name, else the folder's, else the first local one. */
fun defaultBase(b: GitBranches?, folderBranch: String?): String =
    b?.local?.firstOrNull { it.isDefault }?.name ?: folderBranch ?: b?.local?.firstOrNull()?.name ?: ""

/** The remote pushes go to: the one the default branch tracks, else the first one listed; null with no remote. */
fun remoteName(b: GitBranches?): String? =
    (b?.local?.firstOrNull { it.isDefault }?.upstream ?: b?.local?.firstNotNullOfOrNull { it.upstream } ?: b?.remote?.firstOrNull()?.name)
        ?.substringBefore('/')?.takeIf { it.isNotEmpty() }

private val FOLD = mapOf(
    'ç' to "c", 'ğ' to "g", 'ı' to "i", 'ö' to "o", 'ş' to "s", 'ü' to "u", 'à' to "a", 'á' to "a", 'â' to "a",
    'ã' to "a", 'ä' to "a", 'å' to "a", 'è' to "e", 'é' to "e", 'ê' to "e", 'ë' to "e", 'ì' to "i", 'í' to "i",
    'î' to "i", 'ï' to "i", 'ñ' to "n", 'ò' to "o", 'ó' to "o", 'ô' to "o", 'õ' to "o", 'ù' to "u", 'ú' to "u",
    'û' to "u", 'ß' to "ss", 'ø' to "o", 'æ' to "ae", 'œ' to "oe",
)

/** A new branch's automatic name: [prefix] and the first four words of the message ("Fix the login bug" → "fix-the-login-bug"). */
fun autoBranchName(prefix: String, message: String): String {
    val folded = message.lowercase().map { c -> FOLD[c] ?: c.toString() }.joinToString("")
    val words = folded.split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.take(4)
    return prefix + words.joinToString("-").ifEmpty { "session" }
}

/** Where the work lands, once there is enough picked to say. */
sealed interface WorkTarget {
    /** The project folder, on [branch]; switched from [from] at Start when that is set ([track]: the remote branch it follows). */
    data class Here(val branch: String?, val from: String?, val track: String? = null) : WorkTarget
    /** The folder's pick has a worktree already: the session works in that session's copy. */
    data class Reuse(val branch: String, val sessionId: String, val sessionName: String) : WorkTarget
    /** A worktree on a new branch [name] from [base]; [auto] = named from the message. */
    data class NewBranch(val name: String, val auto: Boolean, val base: String) : WorkTarget
    /** A worktree on [branch] itself; [track] when only that remote branch exists, so a local one is made to follow it. */
    data class OnBranch(val branch: String, val track: String?) : WorkTarget
}

/** What [pick] means for this folder, or null when the pick isn't complete (no existing branch chosen yet). */
fun workTarget(pick: WorkPick, branches: GitBranches?, folderBranch: String?, prefix: String, message: String): WorkTarget? {
    if (pick.workIn == WorkIn.Local) {
        val to = pick.switchTo?.takeIf { it != folderBranch }
        val held = to?.let { n -> branches?.local?.firstOrNull { it.name == n }?.openIn }
        val heldId = held?.id
        if (to != null && held?.kind == "session" && heldId != null) return WorkTarget.Reuse(to, heldId, held?.name.orEmpty())
        // A branch only a remote has: the folder gets a local one that follows it.
        val remoteOnly = to != null && branches?.local.orEmpty().none { it.name == to } && branches?.remote.orEmpty().any { it.name == to }
        if (to != null && remoteOnly) return WorkTarget.Here(to.substringAfter('/'), folderBranch, track = to)
        return WorkTarget.Here(to ?: folderBranch, if (to != null) folderBranch else null)
    }
    return when (pick.mode) {
        BranchMode.New -> {
            val typed = pick.name.trim()
            WorkTarget.NewBranch(if (typed.isEmpty()) autoBranchName(prefix, message) else prefix + typed, typed.isEmpty(), pick.base.ifEmpty { defaultBase(branches, folderBranch) })
        }
        BranchMode.Existing -> pick.branch?.let { b ->
            val locals = branches?.local.orEmpty().map { it.name }.toSet()
            val onRemote = branches?.remote.orEmpty().any { it.name == b }
            val localName = b.substringAfter('/')
            when {
                b in locals -> WorkTarget.OnBranch(b, null)
                onRemote && localName !in locals -> WorkTarget.OnBranch(localName, b)
                onRemote -> WorkTarget.OnBranch(localName, null)
                else -> WorkTarget.OnBranch(b, null)
            }
        }
    }
}

/**
 * A branch git keeps somewhere else, so a worktree can't have it: the project folder, or another session's copy.
 * Null when the target is free. A new branch is always free.
 */
fun heldBranch(target: WorkTarget?, branches: GitBranches?): GitOpenIn? {
    val name = (target as? WorkTarget.OnBranch)?.branch ?: return null
    return branches?.local?.firstOrNull { it.name == name }?.openIn
}

/** What the broker is asked for at Start; null while the pick is incomplete (Start stays off). */
fun sessionSource(target: WorkTarget?): SessionSourceBody? = when (target) {
    null -> null
    is WorkTarget.Here -> SessionSourceBody(kind = "local", switchTo = if (target.from != null) target.track ?: target.branch else null)
    is WorkTarget.Reuse -> SessionSourceBody(kind = "reuse", sessionId = target.sessionId)
    is WorkTarget.NewBranch -> SessionSourceBody(kind = "new", base = target.base.ifEmpty { null }, name = target.name, auto = if (target.auto) true else null)
    is WorkTarget.OnBranch -> SessionSourceBody(kind = "existing", branch = target.track ?: target.branch)
}
