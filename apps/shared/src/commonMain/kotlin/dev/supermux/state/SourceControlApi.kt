package dev.supermux.state

import dev.supermux.net.BrokerApi
import dev.supermux.net.ChangesResult
import dev.supermux.net.CommitRequest
import dev.supermux.net.DiscardRequest
import dev.supermux.net.GitBranches
import dev.supermux.net.GitHistory
import dev.supermux.net.GitOpResult
import dev.supermux.net.GitRemoteStatus
import dev.supermux.net.GitSwitchResult
import dev.supermux.net.GitWriteResult
import dev.supermux.net.LatestResult
import dev.supermux.net.PrCheckLog
import dev.supermux.net.PrCreateRequest
import dev.supermux.net.PrCreateResult
import dev.supermux.net.PrRead
import dev.supermux.net.PrText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * #72 The Source Control pane's broker calls for one workspace (its folder or the chat's own copy), and for
 * the chat in it ([sessionId]: Get latest, branches, "Ask the agent"), so the shell needs one seam, not
 * twenty. Every call answers or returns null when the host could not be reached; a refusal is an answer
 * (its `reason`), never an exception.
 */
class SourceControlApi(
    private val api: BrokerApi,
    val workspaceId: String,
    val sessionId: String?,
    /** The host's finish jobs (Merge into the base runs as one; its outcome arrives on the socket). */
    val finishJobs: kotlinx.coroutines.flow.StateFlow<Map<String, dev.supermux.proto.FinishJobDto>>? = null,
) {

    private suspend fun <T> call(op: String, block: suspend () -> T): T? =
        try {
            block()
        } catch (c: CancellationException) {
            currentCoroutineContext().ensureActive()
            println("[SourceControl] $op failed: ${c.message}")
            null
        } catch (e: Throwable) {
            println("[SourceControl] $op failed: $e")
            null
        }

    private val ws = "workspaces"

    /** What is not committed yet, against HEAD: staged, partly staged, not staged, conflicted. */
    suspend fun changes(): ChangesResult? = call("changes") { api.workspaceChanges(workspaceId, "head") }

    suspend fun stage(repo: String, paths: List<String>, staged: Boolean): Boolean =
        call("stage") { api.workspaceStage(workspaceId, repo, paths, staged); true } ?: false

    suspend fun discard(repo: String, paths: List<String>): GitWriteResult? =
        call("discard") { api.gitDiscard(ws, workspaceId, DiscardRequest(repo, paths = paths)) }

    suspend fun discardAll(repo: String): GitWriteResult? =
        call("discardAll") { api.gitDiscard(ws, workspaceId, DiscardRequest(repo, all = true)) }

    suspend fun commit(repo: String, message: String, all: Boolean, amend: Boolean): GitWriteResult? =
        call("commit") { api.gitWrite(ws, workspaceId, "commit", CommitRequest(repo, message = message, all = all, amend = amend)) }

    suspend fun uncommit(repo: String): GitWriteResult? = call("uncommit") { api.gitWrite(ws, workspaceId, "uncommit", CommitRequest(repo)) }

    /** side: mine | theirs | both */
    suspend fun resolve(repo: String, path: String, side: String): GitWriteResult? =
        call("resolve") { api.gitWrite(ws, workspaceId, "resolve", CommitRequest(repo, path = path, side = side)) }

    suspend fun abortMerge(repo: String): GitWriteResult? = call("abort") { api.gitWrite(ws, workspaceId, "abort", CommitRequest(repo)) }

    suspend fun status(): GitRemoteStatus? = call("status") { api.gitStatus(ws, workspaceId) }

    /** op: fetch | publish | push | pull */
    suspend fun sync(op: String): GitOpResult? = call(op) { api.gitOp(ws, workspaceId, op) }

    suspend fun history(repo: String): GitHistory? = call("history") { api.gitHistory(ws, workspaceId, repo) }

    suspend fun latest(): LatestResult? = sessionId?.let { s -> call("latest") { api.gitLatest(s) } }

    suspend fun undoLatest(before: String, after: String): GitWriteResult? =
        sessionId?.let { s -> call("undoLatest") { api.gitLatestUndo(s, before, after) } }

    suspend fun branches(): GitBranches? = sessionId?.let { s -> call("branches") { api.gitBranches(s) } }

    suspend fun switchBranch(name: String, create: Boolean): GitSwitchResult? =
        sessionId?.let { s -> call("switch") { api.gitSwitch(s, name, create) } }

    suspend fun pr(fresh: Boolean = false): PrRead? = call("pr") { api.gitPr(ws, workspaceId, fresh) }

    suspend fun createPr(title: String, body: String, target: String, draft: Boolean): PrCreateResult? =
        call("createPr") { api.gitPrCreate(ws, workspaceId, PrCreateRequest(title, body, target, draft)) }

    suspend fun prReady(number: Int): GitWriteResult? = call("prReady") { api.gitPrReady(ws, workspaceId, number) }

    /** The Create form's title and description (by the agent, else the commits'); [target] null = the chat's base. */
    suspend fun prText(target: String?): PrText? = call("prText") { api.gitPrText(ws, workspaceId, target) }

    suspend fun checkLog(checkUrl: String): PrCheckLog? = call("checkLog") { api.gitPrCheckLog(ws, workspaceId, checkUrl) }

    /** Merge into the chat's base: fast-forward, in your folder; the chat stays (keep). Follow [finishJobs]. */
    suspend fun mergeIntoBase(): Boolean = sessionId?.let { s -> call("mergeIntoBase") { api.finish(s, "merge", keep = true); true } } ?: false

    /** The Commit ▾ pick and the "no staged changes" answer, kept on the broker (the owner's, until users exist). */
    suspend fun prefs(): Pair<String?, String?>? = call("prefs") { api.getConfig().let { it.gitPostCommit to it.gitSmartCommit } }

    suspend fun savePrefs(postCommit: String? = null, smartCommit: String? = null): Boolean =
        call("savePrefs") { api.saveGitPrefs(postCommit, smartCommit); true } ?: false

    /** "Ask the agent to fix" / "Send to the agent": a message to the chat's agent. */
    suspend fun tellAgent(text: String): Boolean = sessionId?.let { s -> call("tellAgent") { api.sendMessage(s, text); true } } ?: false
}
