// #72 v4: the Changes pane as VS Code's Source Control, literally (Ahmet: "don't invent anything new").
// What is not committed yet, in VS Code's three groups: Merge Changes (conflicts), Staged Changes (what the next
// commit holds) and Changes (the rest; a partly staged file is in both). Each file row ALWAYS shows its + (stage)
// or − (unstage), as Ahmet asked; Open File and Discard come on hover (always on a phone). Each group header has a
// labelled "Stage All" / "Unstage All". Discard asks VS Code's question INSIDE the pane, under the row it is for:
// nothing floats over the chat. Clicking a row opens its diff. The commit box, sync and the rest are later cars.
package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.text.withLink
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.automirrored.filled.CallSplit
import androidx.compose.runtime.collectAsState
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.RateReview
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.net.ChangedFile
import dev.supermux.net.ROUTE_MISSING
import dev.supermux.net.RepoChanges
import dev.supermux.state.SourceControlApi
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.theme.LocalSemantics
import dev.supermux.ui.platform.LocalPlatform
import dev.supermux.ui.theme.MonoFontFamily
import dev.supermux.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

/**
 * One file's diff tab, as VS Code opens it: "(Working Tree)" = what is staged (or HEAD) → the file;
 * "(Index)" = HEAD → what is staged. [base] / [work] are blobs; null base = HEAD's, null work = the file.
 */
data class ScmDiffTab(val repo: String, val path: String, val side: String, val base: String?, val work: String?)

/** The tab a click in [group] opens for [f]: Staged → "(Index)"; Changes / Merge → "(Working Tree)". */
fun scmDiffTab(repo: String, f: ChangedFile, group: ScmGroup): ScmDiffTab = when (group) {
    ScmGroup.Staged -> ScmDiffTab(repo, f.path, "index", base = null, work = f.indexBlob)
    else -> ScmDiffTab(repo, f.path, "worktree", base = if (f.staged == "partial") f.indexBlob else null, work = null)
}

/** The Create Pull Request form: target (the base by default), the agent's title and description, draft. */
data class PrForm(val target: String, val title: String = "", val body: String = "", val draft: Boolean = false, val by: String? = null, val loading: Boolean = true,
    /** The title or description was typed in: a new target no longer rewrites them. */
    val edited: Boolean = false,
    /** The target the title and description were written for (a new target writes them again, once). */
    val textFor: String? = null)

/** VS Code's groups, in its order. */
enum class ScmGroup { Merge, Staged, Changes }

/** A file under a group: [staged] all|partial puts it in Staged, none|partial|untracked in Changes, conflict in Merge. */
fun scmGroups(files: List<ChangedFile>): Map<ScmGroup, List<ChangedFile>> {
    val merge = files.filter { it.staged == "conflict" }
    val staged = files.filter { it.staged == "all" || it.staged == "partial" }
    val changes = files.filter { it.staged == null || it.staged == "none" || it.staged == "partial" }
    return linkedMapOf(ScmGroup.Merge to merge, ScmGroup.Staged to staged, ScmGroup.Changes to changes).filterValues { it.isNotEmpty() }
}

/** VS Code's letter on the right of a row: U untracked, A added, M modified, D deleted, R renamed, C conflict. */
fun scmLetter(f: ChangedFile, group: ScmGroup): String = when {
    group == ScmGroup.Merge -> "!"
    f.status == "added" && f.baseBlob == null && group == ScmGroup.Changes && f.staged == "none" -> "U"
    f.status == "added" -> "A"
    f.status == "deleted" -> "D"
    f.status == "renamed" -> "R"
    else -> "M"
}

/** The question shown in the pane before an irreversible discard: under the row, or under the Changes header. */
sealed interface ScmAsk {
    data class Discard(val repo: String, val path: String) : ScmAsk
    data class DiscardAll(val repo: String, val count: Int) : ScmAsk
    /** VS Code's: nothing staged — stage all and commit? Yes / Always / Never / Cancel. */
    data object Smart : ScmAsk
    /** VS Code's: please provide a commit message. */
    data object NoMessage : ScmAsk
    /** Ours: Merge N commits into <base>? */
    data object MergeInto : ScmAsk
    /** Switch this chat to [branch]? (what moves with it is said) */
    data class Switch(val branch: String) : ScmAsk
}

/** VS Code's Commit ▾: what the button does after committing (postCommitCommands). */
enum class ScmPost(val wire: String) { Commit("commit"), Push("push"), Sync("sync");
    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: Commit }
}

/** gh's / glab's words for two branches with no commit in common. */
private val UNRELATED = Regex("no history in common|no common ancestor|unrelated histories", RegexOption.IGNORE_CASE)

/** A repository name GitHub and GitLab both take (the broker checks the same). */
private val SCM_REPO_NAME = Regex("^[A-Za-z0-9._-]+$")

/** "That didn't work: <why>"; a host whose broker predates the route says to update supermux there, not "not found". */
fun scmFailed(message: String): String =
    if (message == ROUTE_MISSING) localized(Str.scm_needs_newer_host) else localized(Str.scm_failed, message)

/** #72: the composer footer's ☁ Publish on a project with no remote opens Source Control with this chat's Publish pick. */
object ScmRequests {
    /** The chat (session id) whose pane should publish, and when it was asked: the pane clears it; a stale ask is dropped. */
    val publish = kotlinx.coroutines.flow.MutableStateFlow<Pair<String, Long>?>(null)

    fun askPublish(sessionId: String) { publish.value = sessionId to kotlin.time.Clock.System.now().toEpochMilliseconds() }
}

/** "Update App.jsx", "Update App.jsx and server.ts", "Update App.jsx and 3 more files": the broker's own rule. */
fun scmMessageFromFiles(paths: List<String>): String {
    val names = paths.map { it.substringAfterLast('/') }.distinct() // platform-ok: a git path, repo-relative, always / on every OS
    return when (names.size) {
        0 -> "Update files" // i18n-ok: a commit message is git content, written as the broker writes it
        1 -> "Update ${names[0]}" // i18n-ok: a commit message is git content, written as the broker writes it
        2 -> "Update ${names[0]} and ${names[1]}" // i18n-ok: a commit message is git content, written as the broker writes it
        else -> "Update ${names[0]} and ${names.size - 1} more files" // i18n-ok: a commit message is git content, written as the broker writes it
    }
}

/** What the pane holds: the uncommitted files per repo, the open question, and the last thing that went wrong. */
class SourceControlState(val api: SourceControlApi) {
    var repos by mutableStateOf<List<RepoChanges>?>(null)
    var offline by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
    /** The tool's own words behind a failure (gh's, git's), shown under "Details": (the problem they explain, the text). */
    var details by mutableStateOf<Pair<String, String>?>(null)
    var ask by mutableStateOf<ScmAsk?>(null)
    val collapsed = mutableStateMapOf<String, Boolean>()

    // ── the commit box (U2) ──
    var message by mutableStateOf("")
    var amend by mutableStateOf(false)
    var post by mutableStateOf(ScmPost.Commit)
    var smart by mutableStateOf("ask")
    var busy by mutableStateOf(false)
    /** A short "Committed 2 files." under the button; cleared by the next action. */
    var note by mutableStateOf<String?>(null)
    /** When the user last acted: a finished merge job's outcome line shows only until then (like [note]). */
    var actedAt by mutableStateOf(0.0)

    /** The start of any action: the last note and a finished job's outcome make way for what comes next. */
    private fun acting() { note = null; actedAt = kotlin.time.Clock.System.now().toEpochMilliseconds().toDouble() }
    var status by mutableStateOf<dev.supermux.net.GitRemoteStatus?>(null)
    /** The last commit on this chat's branch (for Amend), and whether origin already has it. */
    var last by mutableStateOf<dev.supermux.net.LogCommit?>(null)
    /** The chat's base by its name (dev, main, …), never a guess; null: a folder chat has none. */
    var base by mutableStateOf<String?>(null)
    /** This chat's own commits (not in its base), for "Merge N commits into <base>". */
    var ownCommits by mutableStateOf(0)
    /** The Graph (U6): this chat's commits, the base's, and what the base has that this chat doesn't. */
    var history by mutableStateOf<dev.supermux.net.GitHistory?>(null)
    var graphOpen by mutableStateOf(true)
    // ── the pull / merge request (U7) ──
    var pr by mutableStateOf<dev.supermux.net.PrRead?>(null)
    var prOpen by mutableStateOf(true)
    var prForm by mutableStateOf<PrForm?>(null)
    /** Comments already sent to the agent (by their text), so each says "Sent". */
    val sentComments = mutableStateMapOf<String, Boolean>()
    /** The last clean Get latest, for "Undo Get latest" right after it. */
    var latest by mutableStateOf<dev.supermux.net.LatestResult?>(null)
    // ── the branch picker (U5) ──
    var picking by mutableStateOf(false)
    var pickFilter by mutableStateOf("")
    var branches by mutableStateOf<dev.supermux.net.GitBranches?>(null)
    /** A switch refused because another chat has the branch: that chat (id, name), for "Open that chat". */
    var heldBy by mutableStateOf<Pair<String, String>?>(null)
    private var prefsRead = false

    /** The repo the commit box acts on: the chat's own (""), else the first with changes. */
    val repo: String get() = repos?.firstOrNull { it.repo == "" }?.repo ?: repos?.firstOrNull { it.files.isNotEmpty() }?.repo ?: ""
    private val files: List<ChangedFile> get() = repos?.firstOrNull { it.repo == repo }?.files.orEmpty()
    val stagedCount: Int get() = files.count { it.staged == "all" || it.staged == "partial" }
    val changedCount: Int get() = files.size
    val merging: Boolean get() = repos?.firstOrNull { it.repo == repo }?.merging == true
    val conflicts: Int get() = files.count { it.staged == "conflict" }
    val lastPushed: Boolean get() = last?.pushed == true
    /** The branch has a commit to publish (unknown until the Graph loads: assume so). */
    val hasCommits: Boolean get() = history == null || last != null || history?.baseCommits?.isNotEmpty() == true
    /** VS Code's Publish Branch: a branch with commits and no upstream (with or without a remote yet). */
    val publishDue: Boolean get() = status?.let { it.upstream == null && it.branch != null } == true && last != null

    suspend fun refresh() {
        val r = api.changes()
        offline = r == null
        if (r != null) repos = r.repos
        status = api.status() ?: status
        val h = api.history(repo)
        if (h != null) history = h
        last = h?.chat?.firstOrNull()
        base = h?.base
        ownCommits = h?.chat?.size ?: 0
        api.pr()?.let { pr = it }
        prForm?.target?.takeIf { it.isNotEmpty() }?.let { t -> api.prBase(t)?.let { prFormCheck = it } }
        if (!prefsRead) api.prefs()?.let { (p, s, push) -> prefsRead = true; post = ScmPost.of(p); smart = s ?: "ask"; prPush = push ?: "ask" }
        if (amend && (last == null || lastPushed)) amend = false
    }

    /** A failure in plain words; the tool's own text stays behind "Details". An older host says to update it. */
    private fun failed(raw: String, plain: String? = null): String {
        if (raw == ROUTE_MISSING) return localized(Str.scm_needs_newer_host)
        val p = plain ?: localized(Str.scm_failed_plain)
        details = raw.trim().takeIf { it.isNotEmpty() }?.let { p to it }
        return p
    }

    /** ✨: the message written from what goes in (the staged files, else every changed file); amending, the commit's own. */
    fun generate() {
        message = if (amend) last?.subject.orEmpty()
        else scmMessageFromFiles(files.filter { it.staged == "all" || it.staged == "partial" }.ifEmpty { files }.map { it.path })
    }

    fun toggleAmend() {
        amend = !amend
        if (amend && message.isBlank()) message = last?.subject.orEmpty()
        else if (!amend && message == last?.subject) message = ""
    }

    /** The button: VS Code's rules. Nothing staged asks (or follows Always / Never); no message asks; then the ▾ pick. */
    suspend fun commit(all: Boolean = false) {
        acting()
        note = null
        if (!merging && !amend && changedCount == 0) { note = localized(Str.scm_no_changes_to_commit); return }
        if (!merging && !amend && stagedCount == 0 && !all) {
            when (smart) {
                "always" -> return commit(all = true)
                "never" -> { problem = localized(Str.scm_nothing_staged); return }
                else -> { ask = ScmAsk.Smart; return }
            }
        }
        if (!merging && !amend && message.isBlank()) { ask = ScmAsk.NoMessage; return }
        busy = true
        val r = api.commit(repo, message.trim(), all = all, amend = amend)
        busy = false
        when {
            r == null -> problem = localized(Str.scm_offline)
            !r.ok -> problem = failed(r.message ?: r.reason ?: "")
            else -> {
                problem = null; ask = null
                note = localized(if (amend) Str.scm_amended else Str.scm_committed, r.subject ?: "")
                message = ""; amend = false
                when (post) {
                    ScmPost.Push -> sync(if (status?.upstream == null) "publish" else "push")
                    ScmPost.Sync -> syncChanges()
                    ScmPost.Commit -> Unit
                }
            }
        }
        refresh()
    }

    /**
     * Pull / push / publish / fetch. With no remote, Publish asks where to; with several, which one. A failure says what
     * failed (VS Code's words where it has them), the tool's own text under Details. Returns whether it went through.
     */
    suspend fun sync(op: String): Boolean {
        acting()
        if ((op == "publish" || op == "push") && status?.hasRemote == false) { openPublish(); return false }
        // Several remotes: which one (VS Code's quick pick); one: straight there.
        if (op == "publish" && status?.upstream == null && (status?.remotes?.size ?: 0) > 1) { picking = false; closePublish(); pickingRemote = true; return false }
        busy = true; note = null
        val r = api.sync(op)
        busy = false
        val up = status?.upstream ?: status?.branch.orEmpty()
        problem = when {
            r == null -> localized(Str.scm_offline)
            r.status in setOf("pushed", "up_to_date", "clean", "ok", "fetched", "") -> null
            r.status == "conflict" -> withDetails(localized(Str.scm_merge_conflicts), r.files.joinToString("\n").ifEmpty { null })
            r.status == "dirty" -> withDetails(localized(Str.scm_pull_dirty), r.files.joinToString("\n").ifEmpty { null })
            r.status == "rejected_non_ff" -> failed(r.message ?: r.status, localized(Str.scm_push_rejected))
            r.status == "auth_failed" -> failed(r.message ?: r.status, localized(Str.scm_auth_failed))
            else -> failed(r.message ?: r.status, when (op) {
                "pull" -> localized(Str.scm_pull_failed, up)
                "push" -> localized(Str.scm_push_failed, up)
                "publish" -> localized(Str.scm_publish_branch_failed, status?.branch.orEmpty())
                else -> localized(Str.scm_fetch_failed)
            })
        }
        return problem == null
    }

    /** VS Code's Sync Changes: pull, then push only when the pull went through (a conflict stops it there). */
    suspend fun syncChanges() {
        if (sync("pull")) sync(if (status?.upstream == null) "publish" else "push")
        refresh()
    }

    // ── Publish Branch with no remote: VS Code's quick pick (GitHub / GitLab, private / public, or Add Remote…) ──
    /** The pick is open at the top of the pane; [publishUrl]: it asks for a remote's URL (Add Remote…). */
    var publishing by mutableStateOf(false)
    var publishUrl by mutableStateOf(false)
    /** The new repository's name (the project's folder to start with) or, after Add Remote…, the remote's URL. */
    var publishInput by mutableStateOf("")
    /** The choice running now ("github:private", "remote"), and why the last one failed: said in the pick, where it was clicked. */
    var publishRunning by mutableStateOf<String?>(null)
    var publishError by mutableStateOf<String?>(null)
    var publishDetails by mutableStateOf<String?>(null)
    /** The open form's target, checked (its own: the pane's poll never clears it); null: not checked yet, so Create is off. */
    var prFormCheck by mutableStateOf<dev.supermux.net.PrBaseCheck?>(null)
    /** Before Create, the extension's question ("publish" or "push", with how many commits); null: none open. */
    var prAsk by mutableStateOf<dev.supermux.net.PrCreateResult?>(null)
    /** "Always" to that question, remembered on the host (the owner's, until users exist). */
    var prPush by mutableStateOf("ask")
    /** Publish Branch with several remotes: the pick of which (VS Code's "Pick a remote to publish the branch … to"). */
    var pickingRemote by mutableStateOf(false)
    /** A PR action's failure, said in the PR section where it was clicked (its tool text under Details). */
    var prProblem by mutableStateOf<String?>(null)

    fun openPublish() {
        picking = false; ask = null; problem = null
        publishing = true; publishUrl = false; publishError = null; publishRunning = null
        publishInput = status?.project.orEmpty()
    }

    fun closePublish() { publishing = false; publishUrl = false; publishError = null; publishDetails = null; publishRunning = null }

    /** Add Remote… ⇄ back to the list: the input becomes the URL, or the repository name again. */
    fun publishByUrl(on: Boolean) {
        publishUrl = on; publishError = null; publishDetails = null
        publishInput = if (on) "" else status?.project.orEmpty()
    }

    /** Publish Branch to the remote picked. */
    suspend fun publishToRemote(remote: String) {
        acting()
        pickingRemote = false
        busy = true; note = null
        val r = api.publishTo(remote)
        busy = false
        problem = when {
            r == null -> localized(Str.scm_offline)
            r.status in setOf("pushed", "up_to_date") -> null
            else -> failed(r.message ?: r.status)
        }
        refresh()
    }

    /** VS Code's "Publish to GitHub": a new repository on the forge, made from this folder, and the branch pushed to it. */
    suspend fun publishTo(forge: String, visibility: String) {
        acting()
        val name = publishInput.trim()
        val host = if (forge == "gitlab") "GitLab" else "GitHub" // i18n-ok: product names
        if (name.isNotEmpty() && !SCM_REPO_NAME.matches(name)) { publishError = localized(Str.scm_publish_bad_name); return }
        publishRunning = "$forge:$visibility"; publishError = null; publishDetails = null; note = null
        val r = api.publishNew(forge, visibility, name.ifEmpty { null })
        publishRunning = null
        val cli = if (forge == "gitlab") "glab" else "gh" // i18n-ok: command names
        when {
            r == null -> publishError = localized(Str.scm_offline)
            r.ok -> { closePublish(); problem = null; note = localized(Str.scm_published_to, r.url.orEmpty()) }
            r.message == ROUTE_MISSING -> publishError = localized(Str.scm_needs_newer_host)
            r.reason == "no_cli" -> publishError = localized(Str.scm_publish_no_cli, host, cli)
            r.reason == "not_authed" -> publishError = localized(Str.scm_publish_not_authed, host, cli)
            r.reason == "bad" -> publishError = localized(Str.scm_publish_bad_name)
            r.reason == "name_taken" -> { publishError = localized(Str.scm_publish_name_taken, host, name.ifEmpty { status?.project.orEmpty() }); publishDetails = r.message }
            // Made, but the push failed: the repo exists now (said with its URL); the tool's words under Details.
            r.url != null -> { publishError = localized(Str.scm_publish_partial, r.url.orEmpty()); publishDetails = r.message }
            else -> { publishError = localized(Str.scm_publish_failed_plain, host); publishDetails = r.message ?: r.reason }
        }
        refresh()
    }

    /** Add Remote…: origin at the URL typed; then Publish Branch pushes there. */
    suspend fun addRemote() {
        acting()
        val url = publishInput.trim()
        if (url.isEmpty()) return
        publishRunning = "remote"; publishError = null; publishDetails = null
        val r = api.addRemote("origin", url)
        publishRunning = null
        when {
            r == null -> publishError = localized(Str.scm_offline)
            r.ok -> { closePublish(); refresh(); sync("publish") }
            r.message == ROUTE_MISSING -> publishError = localized(Str.scm_needs_newer_host)
            r.reason == "exists" -> publishError = localized(Str.scm_remote_exists)
            r.reason == "bad" -> publishError = localized(Str.scm_remote_bad_url)
            else -> { publishError = localized(Str.scm_remote_failed_plain); publishDetails = r.message }
        }
        refresh()
    }

    suspend fun answerSmart(choice: String) {
        ask = null
        when (choice) {
            "always" -> { smart = "always"; api.savePrefs(smartCommit = "always"); commit(all = true) }
            "never" -> { smart = "never"; api.savePrefs(smartCommit = "never") }
            "yes" -> commit(all = true)
        }
    }

    suspend fun pick(p: ScmPost) {
        post = p
        api.savePrefs(postCommit = p.wire)
        commit()
    }

    // ── the base (U4): Get latest, its conflicts, Merge into the base ──

    suspend fun getLatest() {
        acting()
        note = null; busy = true
        val r = api.latest()
        busy = false
        latest = null
        when {
            r == null -> problem = localized(Str.scm_offline)
            r.status == "clean" -> { problem = null; latest = r; note = localizedPlural(Plurals.scm_latest_got, r.commits, r.commits, base ?: r.from.orEmpty()) }
            r.status == "up_to_date" -> { problem = null; note = localized(Str.scm_latest_none, base ?: r.from.orEmpty()) }
            r.status == "conflict" -> problem = null
            else -> problem = failed(r.message ?: r.status)
        }
        refresh()
    }

    suspend fun undoLatest() {
        acting()
        val l = latest ?: return
        val r = api.undoLatest(l.before ?: return, l.after ?: return)
        latest = null
        when {
            r == null -> problem = localized(Str.scm_offline)
            r.ok -> note = localized(Str.scm_latest_undone)
            r.reason == "moved" -> problem = localized(Str.scm_latest_moved)
            else -> problem = failed(r.message ?: r.reason ?: "")
        }
        refresh()
    }

    /** VS Code's Accept Current / Incoming / Both, for the whole file (side: mine | theirs | both); then it is staged. */
    suspend fun resolve(path: String, side: String) {
        acting()
        note = null
        val r = api.resolve(repo, path, side)
        problem = if (r == null) localized(Str.scm_offline) else if (r.ok) null else failed(r.message ?: r.reason ?: "")
        refresh()
    }

    suspend fun abortMerge() {
        acting()
        val r = api.abortMerge(repo)
        problem = if (r == null) localized(Str.scm_offline) else if (r.ok) null else failed(r.message ?: r.reason ?: "")
        if (r?.ok == true) note = localized(Str.scm_merge_aborted)
        refresh()
    }

    suspend fun mergeIntoBase() {
        ask = null; note = null
        if (!api.mergeIntoBase()) problem = localized(Str.scm_offline)
    }

    suspend fun openPicker() {
        picking = !picking; pickFilter = ""; heldBy = null
        if (picking) closePublish()
        if (picking) branches = api.branches()
    }

    /** Switch (or, [create], make and switch to) a branch; the chat follows it. Each refusal is said plainly. */
    suspend fun switchTo(name: String, create: Boolean) {
        acting()
        ask = null; picking = false; heldBy = null; note = null
        val r = api.switchBranch(name, create)
        problem = when (r?.status) {
            null -> localized(Str.scm_offline)
            "switched" -> { note = localized(Str.scm_switched, r.branch ?: name); null }
            "clobber" -> localized(Str.scm_switch_clobber, r.files.joinToString(", "))
            "checked_out_elsewhere" -> {
                r.openIn?.takeIf { it.kind == "session" }?.let { heldBy = (it.id ?: "") to (it.name ?: "") }
                localized(Str.scm_switch_elsewhere, name, r.openIn?.name ?: r.path ?: "")
            }
            "merge_in_progress" -> localized(Str.scm_switch_merging)
            "invalid_name" -> r.message ?: failed(name)
            else -> failed(r.message ?: r.status)
        }
        refresh()
    }

    /**
     * Create Pull Request, as the GitHub Pull Requests extension opens it: on the default target (the branch this one
     * was made from, else the remote's default; the host works it out), changeable. Its two checks show in the form.
     */
    suspend fun openPrForm() {
        prProblem = null; prAsk = null
        val t = api.prBase(null)
        prFormCheck = t
        val target = t?.target.orEmpty()
        prForm = PrForm(target = target, loading = target.isNotEmpty(), textFor = target.ifEmpty { null })
        if (target.isNotEmpty()) writePrText(target)
    }

    /** The agent's title and description for [target] (else from the commits); kept once typed in. */
    private suspend fun writePrText(target: String) {
        val t = api.prText(target)
        prForm = prForm?.takeIf { it.target == target && !it.edited }?.copy(title = t?.title.orEmpty(), body = t?.body.orEmpty(), by = t?.by, loading = false, textFor = target) ?: prForm?.copy(loading = false)
    }

    suspend fun rewritePr() {
        val f = prForm ?: return
        if (f.target.isEmpty()) return
        prForm = f.copy(loading = true, edited = false)
        writePrText(f.target)
    }

    /**
     * Create. The host asks the extension's questions first (publish the branch / push N commits): shown by Create;
     * [publish] / [push] / [skipPush] are the answers, "Always" ([prPush]) answers them for good.
     */
    suspend fun createPr(publish: Boolean = false, push: Boolean = false, skipPush: Boolean = false) {
        acting()
        val f = prForm ?: return
        if (f.title.isBlank()) return
        val always = prPush == "always"
        busy = true; prProblem = null; prAsk = null; note = null
        val r = api.createPr(f.title.trim(), f.body, f.target, f.draft, publish = publish || always, push = push || always, skipPush = skipPush)
        busy = false
        when (r?.status) {
            null -> prProblem = localized(Str.scm_offline)
            "ask" -> prAsk = r
            "opened", "exists" -> { prForm = null; prProblem = null; pr = api.pr(fresh = true) ?: pr }
            "publish_failed" -> prProblem = failed(r.message ?: "", localized(Str.scm_pr_publish_failed, status?.branch.orEmpty()))
            "push_failed" -> prProblem = failed(r.message ?: "", localized(Str.scm_pr_push_failed))
            else -> prProblem = prUnavailable(r.reason, r.message)
        }
        refresh()
    }

    /** "Always Publish Branch" / "Always Push": remembered, then Create goes on. */
    suspend fun alwaysPushForPr() {
        prPush = "always"
        api.savePrefs(prPush = "always")
        createPr()
    }

    /** The form's target changed: check it, and write the text for it (unless typed in). */
    suspend fun checkPrTarget(target: String) {
        prFormCheck = null
        if (target.isEmpty() || target == status?.branch) return
        prFormCheck = api.prBase(target)
        val f = prForm ?: return
        if (!f.edited && f.textFor != target && prFormCheck?.let { it.onRemote && it.ahead != 0 } == true) {
            prForm = f.copy(loading = true); writePrText(target)
        }
    }

    suspend fun prReady(number: Int) {
        val r = api.prReady(number)
        problem = if (r?.ok == true) null else failed(r?.message ?: "")
        pr = api.pr(fresh = true) ?: pr
    }

    /** "Ask the agent to fix": the check's name and the end of its log, sent to this chat. */
    suspend fun askFix(check: dev.supermux.net.PrCheck) {
        val log = check.url?.let { api.checkLog(it) }?.log.orEmpty()
        val url = pr?.pr?.url.orEmpty()
        val text = localized(Str.scm_fix_check_message, check.name, url) + if (log.isNotBlank()) "\n\n```\n${log.takeLast(8_000)}\n```" else ""
        note = if (api.tellAgent(text)) localized(Str.scm_sent_fix, check.name) else localized(Str.scm_offline)
    }

    /** "Send to the agent": a review comment, its file and line, sent to this chat. */
    suspend fun sendComment(c: dev.supermux.net.PrComment) {
        val where = listOfNotNull(c.file, c.line?.toString()).joinToString(":")
        val text = localized(Str.scm_comment_message, c.who, where.ifEmpty { pr?.pr?.url.orEmpty() }, c.body)
        if (api.tellAgent(text)) sentComments[c.body] = true else problem = localized(Str.scm_offline)
    }

    private fun prUnavailable(reason: String?, message: String?): String = when (reason) {
        "no_cli" -> localized(Str.scm_pr_no_cli)
        "not_authed" -> localized(Str.scm_pr_not_authed)
        "no_branch" -> localized(Str.scm_pr_no_branch)
        "base_missing" -> withDetails(localized(Str.scm_pr_base_not_on_remote, prForm?.target.orEmpty(), forgeName()), message)
        // (A broker before "unrelated" sends it as an error: its words say so.)
        "unrelated" -> unrelated(message)
        "error" -> if (message != null && UNRELATED.containsMatchIn(message)) unrelated(message) else failed(message.orEmpty(), localized(if (pr?.forge == "gitlab") Str.scm_mr_create_failed else Str.scm_pr_create_failed))
        "no_commits" -> withDetails(localized(Str.scm_pr_no_commits_between, prForm?.target.orEmpty(), status?.branch.orEmpty()), message)
        else -> failed(message ?: reason ?: "", localized(if (pr?.forge == "gitlab") Str.scm_mr_create_failed else Str.scm_pr_create_failed))
    }

    private fun unrelated(message: String?): String = withDetails(localized(Str.scm_pr_unrelated, status?.branch.orEmpty(), prForm?.target.orEmpty()), message)

    private fun withDetails(plain: String, raw: String?): String { details = raw?.let { plain to it }; return plain }

    private fun forgeName(): String = if (pr?.forge == "gitlab" || pr?.pr?.forge == "gitlab") "GitLab" else "GitHub" // i18n-ok: product names

    suspend fun undoLastCommit() {
        acting()
        val r = api.uncommit(repo)
        when {
            r == null -> problem = localized(Str.scm_offline)
            r.ok -> { message = r.subject.orEmpty(); note = localized(Str.scm_undone, r.subject ?: "") }
            r.reason == "pushed" -> problem = localized(Str.scm_undo_pushed)
            else -> problem = failed(r.message ?: r.reason ?: "")
        }
        refresh()
    }

    suspend fun stage(repo: String, paths: List<String>, staged: Boolean) {
        if (!api.stage(repo, paths, staged)) problem = localized(Str.scm_offline)
        refresh()
    }

    suspend fun discard(repo: String, path: String) {
        ask = null
        report(api.discard(repo, listOf(path)))
        refresh()
    }

    suspend fun discardAll(repo: String) {
        ask = null
        report(api.discardAll(repo))
        refresh()
    }

    private fun report(r: dev.supermux.net.GitWriteResult?) {
        problem = when {
            r == null -> localized(Str.scm_offline)
            r.ok -> null
            r.reason == "conflict" -> localized(Str.scm_conflict_first, r.paths.joinToString(", "))
            else -> failed(r.message ?: r.error ?: r.reason ?: "")
        }
    }
}

/** How often the open pane reads git again: the agent edits files while it is open. */
private const val SCM_POLL_MS = 5_000L

@Composable
fun SourceControlPane(
    state: SourceControlState,
    /** A file's diff: [keep] false = a preview tab (a single click), true = kept (a double click). */
    onOpenDiff: (repo: String, file: ChangedFile, group: ScmGroup, keep: Boolean) -> Unit,
    /** The file itself, in the editor. */
    onOpenFile: (repo: String, path: String) -> Unit,
    modifier: Modifier = Modifier,
    /** The review of everything since the chat started (comments, base, walkthrough); null: none. */
    onReview: (() -> Unit)? = null,
    /** Another chat (by id): "Open that chat" in the branch picker. */
    onOpenChat: (String) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val onBranches: () -> Unit = { scope.launch { state.openPicker() } }
    val cs = MaterialTheme.colorScheme
    // A phone has no hover: every row action stays shown.
    val touch = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // Real time, off the UI's frame clock: a poll on it would keep a UI test from ever going idle.
    // The footer's ☁ Publish asked: with no remote that is the pick, else the branch is published. Collected, not keyed:
    // clearing the ask (so another pane doesn't take it) must not cancel the work it starts.
    LaunchedEffect(state) {
        ScmRequests.publish.collect { wanted ->
            val (id, at) = wanted ?: return@collect
            if (state.api.sessionId != null && state.api.sessionId != id) return@collect
            if (kotlin.time.Clock.System.now().toEpochMilliseconds() - at > 10_000) return@collect
            ScmRequests.publish.value = null
            if (state.status == null) state.refresh()
            state.sync("publish")
            state.refresh()
        }
    }
    LaunchedEffect(state) {
        withContext(Dispatchers.Default) {
            while (true) {
                state.refresh()
                delay(SCM_POLL_MS)
            }
        }
    }
    // The host's tag and this pane's on separate nodes: Compose merges two tags on one chain, hiding one (as DiffPane says).
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().background(cs.surfaceContainer).testTag("scm_pane")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(start = Space.md, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localized(Str.scm_title_caps), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.44.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            ScmIcon(Icons.Filled.Check, localized(Str.scm_commit), "scm_title_commit", hint = localized(Str.scm_hint_commit)) { scope.launch { state.commit() } }
            if (onReview != null) ScmIcon(Icons.Outlined.RateReview, localized(Str.scm_review), "scm_review", hint = localized(Str.scm_review_hint), onClick = onReview)
            ScmIcon(Icons.Filled.Refresh, localized(Str.scm_refresh), "scm_refresh", hint = localized(Str.scm_hint_refresh)) { scope.launch { state.refresh() } }
            MoreMenu(state, scope, onBranches)
        }
        WhereLine(state, scope, onBranches)
        if (state.picking) BranchPicker(state, scope, onOpenChat)
        if (state.publishing) PublishPicker(state, scope)
        if (state.pickingRemote) RemotePicker(state, scope)
        state.problem?.let { p ->
            FailText(p, state.details?.takeIf { it.first == p }?.second, Modifier.padding(horizontal = Space.md, vertical = Space.xs), "scm_problem")
        }
        val repos = state.repos
        when {
            repos == null && state.offline -> Note(localized(Str.scm_offline))
            repos == null -> Unit
            else -> LazyColumn(Modifier.fillMaxSize()) {
                // Nothing to commit and nothing to amend: no message box (the prototype's clean state).
                // (Something to push or pull is not "nothing": the button is Sync Changes then.)
                val syncDue = state.status?.let { it.upstream != null && (it.ahead > 0 || it.behind > 0) } == true
                val nothing = repos.all { it.files.isEmpty() } && state.last == null && !state.merging && state.ask == null && !state.publishDue && !syncDue
                if (!nothing) item(key = "commit") { CommitBox(state, scope) }
                item(key = "banners") { Banners(state, scope) }
                if (repos.all { it.files.isEmpty() } && !state.merging) item(key = "empty") {
                    val st = state.status
                    // Out of sync or unpublished: the button above already says what to do.
                    if (st?.upstream != null && st.ahead == 0 && st.behind == 0) Note(mono(localized(Str.scm_no_changes_synced, st.upstream!!), st.upstream!!))
                    else if (st?.upstream == null && st?.hasRemote != false && state.last == null) Note(localized(Str.scm_no_changes))
                }
                val multi = repos.count { it.files.isNotEmpty() } > 1
                for (repo in repos) {
                    if (repo.files.isEmpty()) continue
                    if (multi) item(key = "repo:${repo.repo}") { RepoTitle(repo.repo) }
                    for ((group, files) in scmGroups(repo.files)) {
                        val key = "${repo.repo}:$group"
                        item(key = "head:$key") {
                            GroupHeader(state, repo.repo, group, files, touch, scope)
                        }
                        if (state.collapsed[key] == true) continue
                        files.forEach { f ->
                            item(key = "row:$key:${f.path}") {
                                FileRow(state, repo.repo, group, f, touch, scope, onOpenDiff, onOpenFile)
                            }
                        }
                    }
                }
                item(key = "pr") { PrSection(state, scope) }
                item(key = "graph") { Graph(state, scope) }
            }
        }
    }
    }
}

/** VS Code's message box on top (⌘↵ / Ctrl+Enter commits), JetBrains' Amend checkbox, and one Commit button with its ▾. */
@Composable
private fun CommitBox(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    val branch = state.status?.branch ?: state.status?.detachedSha ?: "HEAD"
    val keys = if (dev.supermux.editor.compose.isApplePlatform) "⌘↵" else "Ctrl+Enter"
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(top = 2.dp, bottom = 4.dp)) {
        // The message, with ✨ inside it.
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerLow)
                // The prototype's #272a25: the theme's outlineVariant is too faint on this pane.
                .border(1.dp, cs.onSurfaceVariant.copy(alpha = 0.18f), RoundedCornerShape(8.dp)),
        ) {
            androidx.compose.foundation.text.BasicTextField(
                value = state.message,
                onValueChange = { state.message = it; if (state.ask == ScmAsk.NoMessage) state.ask = null },
                textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 13.sp, lineHeight = 18.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp).padding(start = 10.dp, end = 32.dp, top = 8.dp, bottom = 8.dp)
                    .onPreviewKeyEvent { e ->
                        val mod = if (dev.supermux.editor.compose.isApplePlatform) e.isMetaPressed else e.isCtrlPressed
                        if (e.type == androidx.compose.ui.input.key.KeyEventType.KeyDown && e.key == androidx.compose.ui.input.key.Key.Enter && mod) {
                            scope.launch { state.commit() }; true
                        } else false
                    }
                    .testTag("scm_message"),
                decorationBox = { inner ->
                    if (state.message.isEmpty()) Text(localized(Str.scm_message_placeholder, keys, branch), fontSize = 13.sp, color = cs.onSurfaceVariant.copy(alpha = 0.75f))
                    inner()
                },
            )
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp)) {
                ScmIcon(Icons.Filled.AutoAwesome, localized(Str.scm_generate), "scm_generate", hint = localized(Str.scm_hint_generate), main = true) { state.generate() }
            }
        }
        // JetBrains' Amend: always says which commit, and what goes into it.
        val last = state.last
        if (last != null && !state.merging) {
            val off = state.lastPushed
            Row(
                Modifier.padding(top = 8.dp).then(if (off) Modifier else Modifier.clickable { state.toggleAmend() }).testTag("scm_amend"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(15.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (state.amend) cs.primary else Color.Transparent)
                        .border(1.5.dp, if (state.amend) cs.primary else cs.onSurfaceVariant.copy(alpha = if (off) 0.45f else 1f), RoundedCornerShape(3.dp)),
                    contentAlignment = Alignment.Center,
                ) { if (state.amend) Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp)) }
                Text(localized(Str.scm_amend), fontSize = 12.5.sp, color = if (off) cs.onSurfaceVariant else cs.onSurface, modifier = Modifier.padding(start = 8.dp))
                if (off) Text(" · " + localized(Str.scm_already_pushed), fontSize = 11.5.sp, color = cs.onSurfaceVariant)
            }
            if (state.amend) {
                Column(
                    Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.primary.copy(alpha = 0.12f))
                        .padding(horizontal = 10.dp, vertical = 8.dp).testTag("scm_amending"),
                ) {
                    Text(localized(Str.scm_amending, last.sha.take(7), last.subject), fontSize = 12.sp, color = cs.onSurface)
                    Text(
                        if (state.stagedCount > 0) localizedPlural(Plurals.scm_amend_adds, state.stagedCount, state.stagedCount) else localized(Str.scm_amend_message_only),
                        fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
        // One button whose label follows the state (actionButton.ts), and its ▾ (postCommitCommands).
        // Nothing to commit: the button publishes or syncs instead, as VS Code's does.
        val st = state.status
        val idle = !state.merging && !state.amend && state.changedCount == 0
        val publish = idle && st != null && state.publishDue
        val syncN = idle && st != null && st.upstream != null && (st.ahead > 0 || st.behind > 0)
        val label = when {
            state.merging -> localized(Str.scm_continue)
            state.amend -> localized(Str.scm_amend_commit)
            publish -> localized(Str.scm_publish_branch)
            syncN -> localized(Str.scm_sync_changes) + listOfNotNull(st!!.behind.takeIf { it > 0 }?.let { " $it↓" }, st.ahead.takeIf { it > 0 }?.let { " $it↑" }).joinToString("")
            else -> localized(when (state.post) { ScmPost.Commit -> Str.scm_commit; ScmPost.Push -> Str.scm_commit_push; ScmPost.Sync -> Str.scm_commit_sync })
        }
        val act: suspend () -> Unit = when {
            publish -> { -> state.sync("publish"); state.refresh() }
            syncN -> { -> state.syncChanges() }
            else -> { -> state.commit() }
        }
        // VS Code's button: off while committing, while a merge still has conflicts, and with nothing to commit.
        val nothing = !state.merging && !state.amend && state.changedCount == 0 && !publish && !syncN
        val enabled = !state.busy && !(state.merging && state.conflicts > 0) && !nothing
        var menu by remember { mutableStateOf(false) }
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))
                .background(if (enabled) cs.primary else cs.primary.copy(alpha = 0.5f)),
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().then(if (enabled) Modifier.clickable { scope.launch { act() } } else Modifier).testTag("scm_commit"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (publish) Icons.Filled.CloudUpload else if (syncN) Icons.Filled.Sync else Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onPrimary, modifier = Modifier.padding(start = 6.dp))
            }
            if (!state.merging && !publish && !syncN) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(cs.onPrimary.copy(alpha = 0.25f)))
                Box(Modifier.width(30.dp).fillMaxHeight().clickable { menu = true }.testTag("scm_commit_menu"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.KeyboardArrowDown, localized(Str.scm_commit_options), tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                    ScmMenu(expanded = menu, onDismiss = { menu = false }, tag = "scm_commit_menu_list") {
                        PostItem(localized(Str.scm_commit), state.post == ScmPost.Commit) { menu = false; scope.launch { state.pick(ScmPost.Commit) } }
                        PostItem(localized(Str.scm_commit_amend), false, enabled = state.last != null && !state.lastPushed) { menu = false; if (!state.amend) state.toggleAmend() }
                        PostItem(localized(Str.scm_commit_push), state.post == ScmPost.Push) { menu = false; scope.launch { state.pick(ScmPost.Push) } }
                        PostItem(localized(Str.scm_commit_sync), state.post == ScmPost.Sync) { menu = false; scope.launch { state.pick(ScmPost.Sync) } }
                        Text(localized(Str.scm_commit_menu_note), fontSize = 11.sp, color = cs.onSurfaceVariant, modifier = Modifier.widthIn(max = 240.dp).padding(horizontal = 12.dp, vertical = 6.dp))
                    }
                }
            }
        }
        state.note?.let { Text(it, fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp).testTag("scm_note")) }
        when (state.ask) {
            ScmAsk.Smart -> AskBox(
                question = localized(Str.scm_smart_q), body = localized(Str.scm_smart_body), confirm = localized(Str.scm_yes),
                onConfirm = { scope.launch { state.answerSmart("yes") } }, onCancel = { state.ask = null }, tag = "scm_ask_smart",
                danger = false, start = 0.dp,
                extra = listOf(localized(Str.scm_always) to { scope.launch { state.answerSmart("always") } }, localized(Str.scm_never) to { scope.launch { state.answerSmart("never") } }),
            )
            ScmAsk.NoMessage -> AskBox(
                question = localized(Str.scm_no_message_q), body = localized(Str.scm_no_message_body), confirm = null,
                onConfirm = {}, onCancel = { state.ask = null }, tag = "scm_ask_no_message", danger = false, warn = true, start = 0.dp,
            )
            else -> Unit
        }
    }
}

/** "on <branch> · from <base> · <sync>": where you are, always (U3). The branch opens the picker; the sync state syncs. */
@Composable
private fun WhereLine(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope, onBranches: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val st = state.status ?: return
    val branch = st.branch ?: st.detachedSha?.let { localized(Str.scm_detached, it) } ?: return
    // The branch and its OWN upstream: what pull / push / sync mean (VS Code's status bar). Never the base.
    Row(Modifier.fillMaxWidth().padding(start = Space.md, end = Space.md, bottom = 4.dp).testTag("scm_where"), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(localized(Str.scm_where_on), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        ScmHint(localized(Str.scm_branch_hint_title), localized(Str.scm_branch_hint)) {
            Text(branch, fontFamily = MonoFontFamily, fontSize = 11.5.sp, color = cs.primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 4.dp).clickable(onClick = onBranches).testTag("scm_where_branch"))
        }
        Text(" · ", fontSize = 12.sp, color = cs.onSurfaceVariant)
        val up = st.upstream.orEmpty()
        val (text, op) = when {
            !st.hasRemote -> localized(Str.scm_sync_local_publish) to "publish"
            st.upstream == null -> localized(Str.scm_sync_unpublished) to "publish"
            st.ahead > 0 || st.behind > 0 -> localized(Str.scm_sync_counts, listOfNotNull(st.behind.takeIf { it > 0 }?.let { "$it↓" }, st.ahead.takeIf { it > 0 }?.let { "$it↑" }).joinToString(" "), up) to "sync"
            else -> localized(Str.scm_sync_in, up) to null
        }
        Text(
            mono(text, up), fontSize = 12.sp, color = if (op != null) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false).then(
                if (op == null) Modifier else Modifier.clickable { scope.launch { if (op == "publish") { state.sync("publish"); state.refresh() } else state.syncChanges() } },
            ).testTag("scm_where_sync"),
        )
    }
    // The base, on its own labelled line: what the chat has that it doesn't, what it has that the chat doesn't, and the
    // explicit action for that. Never ↓↑ (those are the upstream's).
    val base = state.base ?: return
    val incoming = state.history?.incoming ?: 0
    Row(Modifier.fillMaxWidth().padding(start = Space.md + 20.dp, end = Space.md, bottom = 8.dp).testTag("scm_base_line"), verticalAlignment = Alignment.CenterVertically) {
        Text(localized(Str.scm_where_from) + " ", fontSize = 12.sp, color = cs.onSurfaceVariant)
        Text(base, fontFamily = MonoFontFamily, fontSize = 11.5.sp, color = cs.onSurface, maxLines = 1)
        Text(" · " + localizedPlural(Plurals.scm_base_ahead, state.ownCommits, state.ownCommits), fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
        if (incoming > 0) {
            Text(" · ", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Text(mono(localizedPlural(Plurals.scm_base_has_new, incoming, base, incoming), base), fontSize = 12.sp, color = cs.onSurfaceVariant, maxLines = 1)
            Text(" · ", fontSize = 12.sp, color = cs.onSurfaceVariant)
            Text(localized(Str.scm_get_latest_short), fontSize = 12.sp, color = cs.primary, maxLines = 1,
                modifier = Modifier.clickable { scope.launch { state.getLatest() } }.testTag("scm_base_get_latest"))
        }
    }
}

/** Under the commit box: a merge in progress (resolve, stage, Continue · Abort Merge), Undo Get latest, a merge into the base. */
@Composable
private fun Banners(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (state.merging) {
            Row(
                Modifier.padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh)
                    .padding(horizontal = 10.dp, vertical = 8.dp).testTag("scm_merging"),
            ) {
                Icon(Icons.Filled.Warning, null, tint = LocalSemantics.current.warning, modifier = Modifier.size(15.dp))
                Column(Modifier.padding(start = 8.dp)) {
                    // Abort Merge runs on in the sentence, as the prototype's link does.
                    // No names: a pull merges the branch's own upstream, Get latest its base; git knows which, the banner needn't.
                    val said = androidx.compose.ui.text.AnnotatedString(localized(Str.scm_merge_in_progress))
                    val abort = localized(Str.scm_abort_merge)
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            append(said); append(" ")
                            withLink(androidx.compose.ui.text.LinkAnnotation.Clickable("abort", androidx.compose.ui.text.TextLinkStyles(androidx.compose.ui.text.SpanStyle(color = cs.primary))) { scope.launch { state.abortMerge() } }) { append(abort) }
                        },
                        fontSize = 12.sp, color = cs.onSurface, modifier = Modifier.testTag("scm_abort"),
                    )
                }
            }
        }
        if (state.latest != null) {
            Text(localized(Str.scm_undo_latest), fontSize = 12.sp, color = cs.primary, modifier = Modifier.padding(bottom = 8.dp).clickable { scope.launch { state.undoLatest() } }.testTag("scm_undo_latest"))
        }
        val jobs = state.api.finishJobs?.collectAsState()?.value
        // A finished job's outcome shows until the next action (as a note does); a running one always.
        val job = state.api.sessionId?.let { jobs?.get(it) }?.takeIf { it.action == "merge" && (it.status == "running" || (it.endedAt ?: it.startedAt) > state.actedAt) }
        if (job != null) {
            val o = job.outcome
            val text = when {
                job.status == "running" -> job.stage ?: localized(Str.scm_merging_into, state.base ?: "")
                o?.status == "integrated" -> localized(Str.scm_merged_into, o.base ?: state.base ?: "")
                o?.status == "non_ff" -> localized(Str.scm_merge_base_moved, state.base ?: "")
                o?.status == "dirty_overlap" -> localized(Str.scm_merge_dirty_overlap, o.files.joinToString(", "))
                o?.status == "uncommitted" -> localized(Str.scm_merge_uncommitted)
                o?.status == "nothing_to_do" -> localized(Str.scm_merge_nothing)
                o?.status == "sync_conflict" -> localized(Str.scm_conflict_first, o.files.joinToString(", "))
                else -> scmFailed(o?.message ?: o?.status ?: "")
            }
            Text(text, fontSize = 12.sp, color = if (o?.status == "integrated" || job.status == "running") cs.onSurfaceVariant else cs.error, modifier = Modifier.padding(bottom = 8.dp).testTag("scm_merge_job"))
            if (o?.status == "non_ff") Text(localized(Str.scm_get_latest, state.base ?: ""), fontSize = 12.sp, color = cs.primary, modifier = Modifier.padding(bottom = 8.dp).clickable { scope.launch { state.getLatest() } })
        }
        if (state.ask == ScmAsk.MergeInto) {
            AskBox(
                question = localizedPlural(Plurals.scm_merge_into_q, state.ownCommits, state.ownCommits, state.base ?: ""),
                body = localized(Str.scm_merge_into_body, state.base ?: ""),
                confirm = localized(Str.scm_merge_into_go, state.base ?: ""),
                onConfirm = { scope.launch { state.mergeIntoBase() } }, onCancel = { state.ask = null },
                tag = "scm_ask_merge_into", danger = false, start = 0.dp,
            )
        }
    }
}

/** VS Code's "Select a branch or tag to checkout", inside the pane: + Create new branch…, then local and remote branches. */
@Composable
private fun BranchPicker(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope, onOpenChat: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val q = state.pickFilter.lowercase()
    val b = state.branches
    Column(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHighest).border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp)).padding(8.dp).testTag("scm_picker"),
    ) {
        // The filter is also the new branch's name: typed text that matches no branch offers to create it (VS Code).
        val typed = state.pickFilter.trim().replace(' ', '-')
        val exists = b != null && (b.local.any { it.name == typed } || b.remote.any { it.name == typed })
        val focus = remember { androidx.compose.ui.focus.FocusRequester() }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        androidx.compose.foundation.text.BasicTextField(
            value = state.pickFilter, onValueChange = { state.pickFilter = it },
            textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 12.5.sp, fontFamily = MonoFontFamily),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary), singleLine = true,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerLow)
                .border(1.dp, cs.primary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        androidx.compose.ui.input.key.Key.Enter -> {
                            if (typed.isNotEmpty()) { if (exists) state.ask = ScmAsk.Switch(typed) else scope.launch { state.switchTo(typed, create = true) } }
                            true
                        }
                        androidx.compose.ui.input.key.Key.Escape -> { state.picking = false; true }
                        else -> false
                    }
                }
                .testTag("scm_picker_filter"),
            decorationBox = { inner -> if (state.pickFilter.isEmpty()) Text(localized(Str.scm_picker_placeholder), fontSize = 12.5.sp, fontFamily = MonoFontFamily, color = cs.onSurfaceVariant); inner() },
        )
        if (typed.isNotEmpty() && !exists) {
            PickRow(localized(Str.scm_create_branch_named, typed), null, null, "scm_pick_create") { scope.launch { state.switchTo(typed, create = true) } }
        } else if (typed.isEmpty()) {
            PickRow(localized(Str.scm_create_branch), null, null, "scm_pick_create") { runCatching { focus.requestFocus() } }
        }
        androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 3.dp))
        if (b == null) { Text(localized(Str.scm_loading), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(8.dp)); return@Column }
        b.local.filter { q.isEmpty() || it.name.lowercase().contains(q) }.forEach { br ->
            val where = br.openIn
            val off = where != null && where.kind != "this"
            val label = when (where?.kind) {
                "this" -> "✓ " + localized(Str.scm_open_this_chat)
                "folder" -> localized(Str.scm_open_in_folder)
                "session" -> localized(Str.scm_open_in_chat, where.name ?: "")
                "external" -> localized(Str.scm_open_elsewhere)
                else -> localized(if (br.isDefault) Str.scm_default_branch else Str.scm_local_branch)
            }
            PickRow(br.name, label, if (where?.kind == "session") ({ onOpenChat(where.id ?: "") }) else null, "scm_pick_${br.name}", dim = off, current = where?.kind == "this") {
                if (!off && where?.kind != "this") state.ask = ScmAsk.Switch(br.name)
            }
        }
        b.remote.filter { q.isEmpty() || it.name.lowercase().contains(q) }.forEach { r ->
            PickRow(r.name, localized(Str.scm_remote_branch), null, "scm_pick_${r.name}") { state.ask = ScmAsk.Switch(r.name) }
        }
    }
    when (val a = state.ask) {
        is ScmAsk.Switch -> AskBox(
            question = localized(Str.scm_switch_q, a.branch), body = localized(Str.scm_switch_body, a.branch, state.base ?: "") +
                (if (state.changedCount > 0) " " + localized(Str.scm_switch_carry) else ""),
            confirm = localized(Str.scm_switch_go, a.branch), onConfirm = { scope.launch { state.switchTo(a.branch, create = false) } },
            onCancel = { state.ask = null }, tag = "scm_ask_switch", danger = false, start = 12.dp,
        )
        else -> Unit
    }
    state.heldBy?.let { (id, name) ->
        Text(localized(Str.scm_open_that_chat) + " · " + name, fontSize = 12.sp, color = cs.primary,
            modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 8.dp).clickable { onOpenChat(id) }.testTag("scm_open_that_chat"))
    }
}

@Composable
private fun PickRow(name: String, detail: String?, openChat: (() -> Unit)?, tag: String, dim: Boolean = false, current: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth().heightIn(min = 26.dp).clip(RoundedCornerShape(6.dp)).clickable(enabled = !dim && !current, onClick = onClick)
            .padding(horizontal = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (detail != null) Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
        Text(name, fontFamily = if (detail != null) MonoFontFamily else null, fontSize = 12.5.sp, color = if (dim) cs.onSurfaceVariant else cs.onSurface,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = if (detail != null) 7.dp else 0.dp))
        // The name keeps its width; where it is open shrinks first (the prototype's).
        if (detail != null) Text(detail, fontSize = 11.sp, color = if (current) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 10.dp).widthIn(max = 150.dp))
        if (openChat != null) Text(localized(Str.scm_open_that_chat), fontSize = 11.sp, color = cs.primary, maxLines = 1,
            modifier = Modifier.padding(start = 8.dp).clickable(onClick = openChat))
    }
}

/** The GitHub Pull Requests extension's flow (GitLab: merge request), Basics: create, then status, checks, comments. */
@Composable
private fun PrSection(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    val read = state.pr ?: return
    if (read.status == "unavailable" && read.reason in setOf("no_remote", "no_branch")) return
    val gitlab = read.forge == "gitlab" || read.pr?.forge == "gitlab"
    val host = if (gitlab) "GitLab" else "GitHub" // i18n-ok: product names
    val sign = if (gitlab) "!" else "#" // i18n-ok: GitHub's and GitLab's own number signs
    val pr = read.pr
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("scm_pr")) {
        androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable { state.prOpen = !state.prOpen }.padding(start = 4.dp, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (state.prOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(localized(if (gitlab) Str.scm_mr_title else Str.scm_pr_title), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.44.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 2.dp))
            if (pr != null) ScmIcon(Icons.AutoMirrored.Filled.OpenInNew, localized(Str.scm_open_on, host), "scm_pr_open", hint = localized(Str.scm_hint_open_on, host)) { platform.openUrl(pr.url) }
        }
        if (!state.prOpen) return@Column
        val pad = Modifier.padding(horizontal = 14.dp)
        if (state.prForm == null) state.prProblem?.let { p -> FailText(p, state.details?.takeIf { it.first == p }?.second, pad.padding(top = 4.dp), "scm_pr_problem") }
        val form = state.prForm
        when {
            read.status == "unavailable" && read.reason in setOf("no_cli", "not_authed") -> Text(
                localized(if (read.reason == "no_cli") Str.scm_pr_no_cli else Str.scm_pr_not_authed),
                fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = pad.padding(vertical = 6.dp),
            )
            read.status == "unavailable" -> FailText(localized(Str.scm_pr_read_failed, host), read.message, pad.padding(vertical = 6.dp), "scm_pr_read_failed", muted = true)
            form != null -> PrFormView(state, scope, form, gitlab, host)
            pr == null && state.last == null -> Text(mono(localized(Str.scm_pr_commit_first, state.status?.branch ?: ""), state.status?.branch ?: ""), fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = pad.padding(vertical = 6.dp))
            pr == null -> Column(pad.padding(vertical = 6.dp)) {
                Text(mono(localized(if (gitlab) Str.scm_mr_none else Str.scm_pr_none, state.status?.branch ?: ""), state.status?.branch ?: ""), fontSize = 12.5.sp, color = cs.onSurfaceVariant)
                Row(
                    Modifier.padding(top = 8.dp).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp))
                        .clickable { scope.launch { state.openPrForm() } }.testTag("scm_pr_create"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(PullRequestGlyph, null, tint = cs.onSurface, modifier = Modifier.size(15.dp))
                    Text(localized(if (gitlab) Str.scm_mr_create else Str.scm_pr_create), fontSize = 12.5.sp, color = cs.onSurface, modifier = Modifier.padding(start = 6.dp))
                }
            }
            else -> Column(pad.padding(vertical = 4.dp)) {
                Text(buildString { append(pr.title); append("  "); append(sign); append(pr.number) }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
                Row(Modifier.padding(top = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    val (word, color) = when {
                        pr.state == "merged" -> localized(Str.scm_pr_merged) to Color(0xFFB48EF0)
                        pr.state == "closed" -> localized(Str.scm_pr_closed) to cs.error
                        pr.draft -> localized(Str.scm_pr_draft) to cs.onSurfaceVariant
                        else -> localized(Str.scm_pr_open) to LocalSemantics.current.success
                    }
                    Text(word, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 7.dp, vertical = 1.dp))
                    Text("${pr.head} → ${pr.target}", fontFamily = MonoFontFamily, fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
                    Text("$host ↗", fontSize = 12.sp, color = cs.primary, modifier = Modifier.clickable { platform.openUrl(pr.url) })
                }
                ReviewLine(pr.review, gitlab)
                if (pr.state == "open") {
                    val bad = pr.checks.count { it.state == "bad" }
                    val run = pr.checks.count { it.state == "run" }
                    val verdict = when { bad > 0 -> localizedPlural(Plurals.scm_checks_failing, bad, bad); run > 0 -> localized(Str.scm_checks_running); pr.checks.isEmpty() -> localized(Str.scm_checks_none); else -> localized(Str.scm_checks_passing) }
                    Row(Modifier.padding(top = 12.dp, bottom = 2.dp)) {
                        SubHead(localized(Str.scm_checks) + " · ")
                        SubHead(verdict, if (bad > 0) cs.error else cs.onSurfaceVariant)
                    }
                    pr.checks.forEach { k -> CheckRow(k, onFix = { scope.launch { state.askFix(k) } }, onLog = { k.url?.let(platform::openUrl) }) }
                    if (pr.comments.isNotEmpty()) {
                        Box(Modifier.padding(top = 12.dp, bottom = 6.dp)) { SubHead(localizedPlural(Plurals.scm_review_comments, pr.comments.size, pr.comments.size)) }
                        pr.comments.forEach { c -> CommentCard(c, sent = state.sentComments[c.body] == true, onSend = { scope.launch { state.sendComment(c) } }, onOpen = { (c.url ?: pr.url).let(platform::openUrl) }) }
                    }
                    Row(Modifier.padding(top = 12.dp, bottom = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        if (pr.draft) Text(localized(if (gitlab) Str.scm_mr_mark_ready else Str.scm_pr_ready), fontSize = 12.5.sp, color = cs.onSurface,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp)).clickable { scope.launch { state.prReady(pr.number) } }
                                .padding(horizontal = 12.dp, vertical = 5.dp).testTag("scm_pr_ready"))
                        Spacer(Modifier.weight(1f))
                        Text(localized(Str.scm_merge_on, host) + " ↗", fontSize = 12.5.sp, color = cs.primary, modifier = Modifier.clickable { platform.openUrl(pr.url) }.testTag("scm_pr_merge_on"))
                    }
                }
            }
        }
    }
}

/** The form's "into" branch: a chip whose menu lists the local branches (the base first). */
@Composable
private fun TargetPicker(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope, f: PrForm) {
    val cs = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 6.dp)) {
        Row(
            Modifier.clip(RoundedCornerShape(6.dp)).border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp))
                .clickable { open = true; if (state.branches == null) scope.launch { state.branches = state.api.branches() } }
                .padding(start = 6.dp, end = 4.dp, top = 2.dp, bottom = 2.dp).testTag("scm_pr_target"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Filled.CallSplit, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(12.dp))
            Text(f.target, fontFamily = MonoFontFamily, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.padding(start = 4.dp))
            Icon(Icons.Filled.KeyboardArrowDown, localized(Str.scm_pr_target_pick), tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
        ScmMenu(expanded = open, onDismiss = { open = false }, tag = "scm_pr_target_menu") {
            // The extension's base picker lists the base repository's branches: the remote's (upstream before origin),
            // never this branch.
            val head = state.status?.branch
            val remotes = state.status?.remotes.orEmpty()
            val from = when { "upstream" in remotes -> "upstream"; "origin" in remotes -> "origin"; else -> remotes.firstOrNull() }
            val names = state.branches?.remote?.map { it.name }?.filter { from != null && it.startsWith("$from/") }
                ?.map { it.removePrefix("$from/") }?.filter { it != head && it != "HEAD" }.orEmpty()
            names.distinct().forEach { n ->
                ScmMenuItem(n, onClick = { open = false; state.prForm = state.prForm?.copy(target = n) }, strong = n == f.target)
            }
        }
    }
}

@Composable
private fun PrFormView(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope, f: PrForm, gitlab: Boolean, host: String) {
    val cs = MaterialTheme.colorScheme
    val field = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerLow).border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp))
    Column(
        Modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHighest)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp)).padding(10.dp).testTag("scm_pr_form"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(localized(Str.scm_pr_merge_word), fontSize = 12.sp, color = cs.onSurfaceVariant)
            Text(state.status?.branch ?: "", fontFamily = MonoFontFamily, fontSize = 12.sp, color = cs.onSurface, modifier = Modifier.padding(horizontal = 6.dp))
            Text(localized(Str.scm_pr_into_word), fontSize = 12.sp, color = cs.onSurfaceVariant)
            TargetPicker(state, scope, f)
        }
        Text(localized(Str.scm_pr_title_label), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Box(field) {
            androidx.compose.foundation.text.BasicTextField(
                value = f.title, onValueChange = { state.prForm = f.copy(title = it, edited = true) }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 12.5.sp), cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 32.dp, top = 6.dp, bottom = 6.dp).testTag("scm_pr_title_input"),
            )
            Box(Modifier.align(Alignment.CenterEnd)) {
                ScmIcon(Icons.Filled.AutoAwesome, localized(Str.scm_pr_rewrite), "scm_pr_rewrite", hint = localized(Str.scm_hint_pr_rewrite), main = true) { scope.launch { state.rewritePr() } }
            }
        }
        Text(localized(Str.scm_pr_body_label), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        androidx.compose.foundation.text.BasicTextField(
            value = f.body, onValueChange = { state.prForm = f.copy(body = it, edited = true) },
            textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 12.sp, fontFamily = MonoFontFamily, lineHeight = 17.sp),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary),
            modifier = field.heightIn(min = 110.dp).padding(8.dp).testTag("scm_pr_body_input"),
        )
        Text(
            when { f.loading -> localized(Str.scm_pr_writing); f.by == "agent" -> localized(Str.scm_pr_by_agent); f.by == "commits" -> localized(Str.scm_pr_by_commits); else -> localized(Str.scm_pr_text_failed) },
            fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
        )
        Row(Modifier.padding(top = 8.dp).clickable { state.prForm = f.copy(draft = !f.draft) }.testTag("scm_pr_draft"), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(15.dp).clip(RoundedCornerShape(3.dp)).background(if (f.draft) cs.primary else Color.Transparent)
                    .border(1.5.dp, if (f.draft) cs.primary else cs.onSurfaceVariant, RoundedCornerShape(3.dp)),
                contentAlignment = Alignment.Center,
            ) { if (f.draft) Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(12.dp)) }
            Text(localized(Str.scm_pr_draft_check), fontSize = 12.5.sp, color = cs.onSurface, modifier = Modifier.padding(start = 8.dp))
        }
        LaunchedEffect(f.target) { state.checkPrTarget(f.target) }
        // The extension's two checks ("The upstream branch … does not exist on GitHub", "There are no commits
        // between …"); Create waits for them.
        val head = state.status?.branch.orEmpty()
        val t = state.prFormCheck?.takeIf { it.target == f.target }
        val why = when {
            f.target.isEmpty() -> localized(Str.scm_pr_choose_base)
            t == null -> null
            !t.onRemote -> localized(Str.scm_pr_base_not_on_remote, t.target, host)
            t.ahead == 0 -> localized(Str.scm_pr_no_commits_between, t.target, head)
            else -> null
        }
        // A failed Create says why right here, by the button it came from.
        state.prProblem?.let { p -> FailText(p, state.details?.takeIf { it.first == p }?.second, Modifier.padding(top = 8.dp), "scm_pr_problem") }
        if (why != null) Text(mono(why, f.target, head), fontSize = 12.sp, color = LocalSemantics.current.warning, modifier = Modifier.padding(top = 8.dp).testTag("scm_pr_form_why"))
        // The extension's questions before Create, in the pane.
        state.prAsk?.let { a -> PrAskBox(state, scope, a, host) }
        val can = f.title.isNotBlank() && !state.busy && why == null && t != null && !f.loading && state.prAsk == null
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // Plain Create: publishing / pushing first is the extension's question, asked when it applies.
            val go = localized(Str.scm_pr_create_go) + if (f.draft) " " + localized(Str.scm_pr_draft) else ""
            Text(go, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = cs.onPrimary,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (can) cs.primary else cs.primary.copy(alpha = 0.5f))
                    .clickable(enabled = can) { scope.launch { state.createPr() } }.padding(horizontal = 12.dp, vertical = 5.dp).testTag("scm_pr_create_go"))
            Text(localized(Str.scm_cancel), fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { state.prForm = null }.padding(horizontal = 12.dp, vertical = 5.dp))
        }
        Text(localized(Str.scm_pr_as_you, host),
            fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** The PR section's CHECKS / REVIEW COMMENTS heads: small caps, as the section titles. */
@Composable
private fun SubHead(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(text.uppercase(), fontSize = 10.5.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp, color = color)
}

@Composable
private fun ReviewLine(r: dev.supermux.net.PrReview, gitlab: Boolean) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    val who = r.by.joinToString(", ")
    val (text, color) = when (r.state) {
        "approved" -> localized(Str.scm_review_approved, who) to sem.success
        "changes" -> localized(Str.scm_review_changes, who) to cs.error
        "required" -> { val need = r.need; (if (gitlab && need != null) localized(Str.scm_review_approvals, r.got ?: 0, need) else localized(Str.scm_review_required)) to sem.warning }
        else -> return
    }
    val icon = when (r.state) { "approved" -> Icons.Filled.CheckCircle; "changes" -> Icons.Filled.Cancel; else -> Icons.Filled.Schedule }
    Row(Modifier.padding(top = 7.dp).testTag("scm_pr_review"), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(14.dp))
        Text(text, fontSize = 12.sp, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun CheckRow(k: dev.supermux.net.PrCheck, onFix: () -> Unit, onLog: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val sem = LocalSemantics.current
    Row(Modifier.fillMaxWidth().heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically) {
        val (icon, color) = when (k.state) {
            "ok" -> Icons.Filled.CheckCircle to sem.success
            "bad" -> Icons.Filled.Cancel to cs.error
            "skip" -> Icons.Filled.RemoveCircleOutline to cs.onSurfaceVariant
            else -> Icons.Filled.Sync to sem.warning
        }
        Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
        Text(k.name, fontSize = 12.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp).weight(1f))
        if (k.state == "bad") ScmHint(localized(Str.scm_ask_fix), localized(Str.scm_hint_ask_fix)) {
            Text(localized(Str.scm_ask_fix), fontSize = 12.sp, color = cs.primary, modifier = Modifier.clickable(onClick = onFix).padding(horizontal = 4.dp).testTag("scm_ask_fix_${k.name}"))
        }
        if (k.url != null) Text(localized(Str.scm_log), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.clickable(onClick = onLog).padding(start = 8.dp))
    }
}

@Composable
private fun CommentCard(c: dev.supermux.net.PrComment, sent: Boolean, onSend: () -> Unit, onOpen: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(cs.surfaceContainerHigh).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(c.who, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, maxLines = 1)
            c.file?.let { f ->
                Text(" " + localized(Str.scm_comment_on) + " ", fontSize = 11.5.sp, color = cs.onSurfaceVariant)
                Text(f + (c.line?.let { ":$it" } ?: ""), fontSize = 11.sp, color = cs.onSurfaceVariant, fontFamily = MonoFontFamily, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(c.body, fontSize = 12.5.sp, color = cs.onSurface, maxLines = 6, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (sent) Text(localized(Str.scm_sent_to_agent), fontSize = 12.sp, color = cs.onSurfaceVariant)
            else ScmHint(localized(Str.scm_send_to_agent), localized(Str.scm_hint_send_to_agent)) {
                Text("➤ " + localized(Str.scm_send_to_agent), fontSize = 12.sp, color = cs.primary, modifier = Modifier.clickable(onClick = onSend).testTag("scm_send_comment"))
            }
            Spacer(Modifier.width(14.dp))
            Text(localized(Str.scm_view_comment), fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.clickable(onClick = onOpen))
        }
    }
}

/** VS Code's Source Control Graph (Basics): incoming changes on top (from the base → Merge, from origin → Pull),
 *  then this chat's commits and the base's, with their branch badges; a commit's menu copies its hash or message. */
@Composable
private fun Graph(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    val h = state.history ?: return
    val st = state.status
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).testTag("scm_graph")) {
        androidx.compose.material3.HorizontalDivider(color = cs.outlineVariant)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 28.dp).clickable { state.graphOpen = !state.graphOpen }.padding(start = 4.dp, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (state.graphOpen) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowRight, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(localized(Str.scm_graph), fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.44.sp, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 2.dp))
            ScmIcon(Icons.Filled.Refresh, localized(Str.scm_fetch), "scm_graph_fetch", hint = localized(Str.scm_hint_fetch)) { scope.launch { state.sync("fetch"); state.refresh() } }
        }
        if (!state.graphOpen) return@Column
        val base = h.base
        if (base != null && h.incoming > 0) GraphNode(localized(Str.scm_incoming_from, base), localizedPlural(Plurals.scm_commits_n, h.incoming, h.incoming), localized(Str.scm_merge_link), "scm_graph_incoming_base") { scope.launch { state.getLatest() } }
        if (st?.upstream != null && st.behind > 0) GraphNode(localized(Str.scm_incoming), localized(Str.scm_from_origin, st.behind, st.upstream!!), localized(Str.scm_pull), "scm_graph_incoming_upstream") { scope.launch { state.sync("pull"); state.refresh() } }
        h.chat.forEachIndexed { i, c -> GraphCommit(c, own = true, refs = if (i == 0) listOfNotNull(h.branch) else emptyList()) }
        h.baseCommits.forEachIndexed { i, c -> GraphCommit(c, own = false, refs = if (i == 0 && base != null) listOf(base) else emptyList()) }
    }
}

@Composable
private fun GraphNode(title: String, sub: String, action: String, tag: String, onAction: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Lane(color = LocalSemantics.current.info, filled = true)
        Column(Modifier.weight(1f).padding(vertical = 5.dp, horizontal = 8.dp)) {
            Text("↓ $title", fontSize = 12.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 11.sp, color = cs.onSurfaceVariant)
        }
        Text(action, fontSize = 12.sp, color = cs.primary, modifier = Modifier.clickable(onClick = onAction).padding(4.dp).testTag(tag))
    }
}

@Composable
private fun GraphCommit(c: dev.supermux.net.LogCommit, own: Boolean, refs: List<String>) {
    val cs = MaterialTheme.colorScheme
    val platform = LocalPlatform.current
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).clickable { menu = true }.padding(start = 18.dp, end = 8.dp).testTag("scm_commit_${c.sha.take(7)}"), verticalAlignment = Alignment.CenterVertically) {
            Lane(color = if (own) cs.primary else cs.onSurfaceVariant, filled = false)
            Column(Modifier.weight(1f).padding(vertical = 5.dp, horizontal = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.subject, fontSize = 12.5.sp, color = cs.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    // The badge shrinks before the subject does (the prototype's).
                    refs.forEach { r ->
                        Text(r, fontFamily = MonoFontFamily, fontSize = 10.5.sp, color = if (own) cs.primary else cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(start = 6.dp).widthIn(max = 88.dp).clip(RoundedCornerShape(4.dp)).background(if (own) cs.primary.copy(alpha = 0.2f) else cs.surfaceContainerHighest).padding(horizontal = 5.dp))
                    }
                }
                Row {
                    Text(c.author.ifBlank { "—" } + " · ", fontSize = 11.sp, color = cs.onSurfaceVariant)
                    Text(c.sha.take(7), fontSize = 11.sp, color = cs.onSurfaceVariant, fontFamily = MonoFontFamily)
                    if (c.pushed == true) Text(" · " + localized(Str.scm_pushed), fontSize = 11.sp, color = cs.onSurfaceVariant)
                }
            }
        }
        ScmMenu(expanded = menu, onDismiss = { menu = false }, tag = "scm_commit_menu_${c.sha.take(7)}") {
            MenuItem(localized(Str.scm_copy_hash), Str.scm_hint_copy) { menu = false; platform.copyToClipboard(c.sha) }
            MenuItem(localized(Str.scm_copy_message), Str.scm_hint_copy) { menu = false; platform.copyToClipboard(c.subject) }
        }
    }
}

/** The graph's lane: a line with a dot. */
@Composable
private fun Lane(color: Color, filled: Boolean) {
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.18f) // the prototype's #272a25 on the pane
    Box(Modifier.width(12.dp).fillMaxHeight().heightIn(min = 38.dp)) {
        Box(Modifier.align(Alignment.Center).width(2.dp).fillMaxHeight().background(line))
        Box(Modifier.align(Alignment.Center).size(9.dp).clip(androidx.compose.foundation.shape.CircleShape).background(if (filled) color else MaterialTheme.colorScheme.surfaceContainer)
            .border(2.dp, color, androidx.compose.foundation.shape.CircleShape))
    }
}

/**
 * VS Code's Publish Branch for a project with no remote, as its quick pick at the top of the pane: the new repository's
 * name (the project's folder), then where it goes: GitHub or GitLab, private or public, or Add Remote… by a URL.
 * ↑ ↓ choose, Enter runs, Esc closes (or, on the URL, goes back). A failure stays here, where it was clicked.
 */
@Composable
private fun PublishPicker(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(state.publishUrl) { runCatching { focus.requestFocus() } }
    val busy = state.publishRunning != null
    val branch = state.status?.branch.orEmpty()
    val name = state.publishInput.trim()
    val empty = !state.hasCommits
    class Choice(val key: String, val label: String, val detail: String?, val icon: ImageVector, val enabled: Boolean, val run: () -> Unit)
    val forge = { f: String, v: String, label: String -> Choice("$f:$v", label, name.ifEmpty { null }, Icons.Filled.CloudUpload, !empty) { scope.launch { state.publishTo(f, v) } } }
    val choices = if (state.publishUrl) listOf(
        Choice("remote", localized(Str.scm_add_remote_go), null, Icons.Filled.Add, name.isNotEmpty()) { scope.launch { state.addRemote() } },
        Choice("back", localized(Str.scm_publish_back), null, Icons.AutoMirrored.Filled.ArrowBack, true) { state.publishByUrl(false) },
    ) else listOf(
        forge("github", "private", localized(Str.scm_publish_github_private)),
        forge("github", "public", localized(Str.scm_publish_github_public)),
        forge("gitlab", "private", localized(Str.scm_publish_gitlab_private)),
        forge("gitlab", "public", localized(Str.scm_publish_gitlab_public)),
        Choice("url", localized(Str.scm_add_remote_ellipsis), null, Icons.Filled.Add, true) { state.publishByUrl(true) },
    )
    var sel by remember(state.publishUrl) { mutableStateOf(0) }
    val run = { i: Int -> choices.getOrNull(i)?.takeIf { it.enabled && !busy }?.run?.invoke() }
    Column(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHighest).border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp)).padding(8.dp).testTag("scm_publish_pick"),
    ) {
        Text(
            mono(localized(if (state.publishUrl) Str.scm_publish_pick_url_title else Str.scm_publish_pick_title, branch), branch),
            fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp),
        )
        androidx.compose.foundation.text.BasicTextField(
            value = state.publishInput, onValueChange = { state.publishInput = it; state.publishError = null },
            enabled = !busy,
            textStyle = androidx.compose.ui.text.TextStyle(color = cs.onSurface, fontSize = 12.5.sp, fontFamily = MonoFontFamily),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(cs.primary), singleLine = true,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerLow)
                .border(1.dp, cs.primary, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 6.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { e ->
                    if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        androidx.compose.ui.input.key.Key.DirectionDown -> { sel = (sel + 1) % choices.size; true }
                        androidx.compose.ui.input.key.Key.DirectionUp -> { sel = (sel - 1 + choices.size) % choices.size; true }
                        androidx.compose.ui.input.key.Key.Enter -> { run(sel); true }
                        androidx.compose.ui.input.key.Key.Escape -> { if (state.publishUrl) state.publishByUrl(false) else state.closePublish(); true }
                        else -> false
                    }
                }
                .testTag("scm_publish_input"),
            decorationBox = { inner ->
                if (state.publishInput.isEmpty()) Text(localized(if (state.publishUrl) Str.scm_remote_url else Str.scm_repo_name), fontSize = 12.5.sp, fontFamily = MonoFontFamily, color = cs.onSurfaceVariant, maxLines = 1)
                inner()
            },
        )
        if (empty && !state.publishUrl) Text(
            mono(localized(Str.scm_publish_commit_first, branch), branch),
            fontSize = 12.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, top = 6.dp).testTag("scm_publish_commit_first"),
        )
        Column(Modifier.padding(top = 6.dp)) {
            choices.forEachIndexed { i, c ->
                if (c.key == "url") androidx.compose.material3.HorizontalDivider(Modifier.padding(vertical = 3.dp), color = cs.onSurfaceVariant.copy(alpha = 0.18f))
                QuickRow(
                    icon = c.icon, label = c.label,
                    detail = if (state.publishRunning == c.key) localized(Str.scm_publishing) else c.detail,
                    selected = i == sel && c.enabled, enabled = c.enabled && !busy, tag = "scm_publish_${c.key.replace(':', '_')}",
                    onHover = { sel = i }, onClick = { sel = i; run(i) },
                )
            }
        }
        state.publishError?.let { FailText(it, state.publishDetails, Modifier.padding(start = 2.dp, top = 6.dp), "scm_publish_error") }
    }
}

/**
 * The GitHub Pull Requests extension's questions before Create, inside the form: "There is no remote branch … publish
 * it and then create?" (Publish Branch · Always Publish Branch) and "You have N unpushed commit(s) … push them before
 * creating?" (Push Commits · Always Push · Continue Without Pushing). Always is remembered.
 */
@Composable
private fun PrAskBox(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope, a: dev.supermux.net.PrCreateResult, host: String) {
    val branch = state.status?.branch.orEmpty()
    val publish = a.ask == "publish"
    val extra = if (publish) listOf(localized(Str.scm_pr_always_publish) to { scope.launch { state.alwaysPushForPr() }; Unit })
    else listOf(localized(Str.scm_pr_always_push) to { scope.launch { state.alwaysPushForPr() }; Unit },
        localized(Str.scm_pr_continue_without) to { scope.launch { state.createPr(skipPush = true) }; Unit })
    AskBox(
        question = if (publish) localized(Str.scm_pr_ask_publish_q, branch, host) else localizedPlural(Plurals.scm_pr_ask_push_q, a.count, a.count, branch),
        body = localized(if (publish) Str.scm_pr_ask_publish_body else Str.scm_pr_ask_push_body),
        confirm = localized(if (publish) Str.scm_publish_branch else Str.scm_pr_push_commits),
        onConfirm = { scope.launch { if (publish) state.createPr(publish = true) else state.createPr(push = true) } },
        onCancel = { state.prAsk = null }, tag = "scm_pr_ask", danger = false, start = 0.dp, extra = extra,
    )
}

/** VS Code's "Pick a remote to publish the branch … to:", for a repo with several remotes. */
@Composable
private fun RemotePicker(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope) {
    val cs = MaterialTheme.colorScheme
    val remotes = state.status?.remotes.orEmpty()
    val branch = state.status?.branch.orEmpty()
    var sel by remember { mutableStateOf(maxOf(0, remotes.indexOf("origin"))) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.padding(horizontal = 12.dp).padding(bottom = 10.dp).fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(cs.surfaceContainerHighest).border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp)).padding(8.dp)
            .focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != androidx.compose.ui.input.key.KeyEventType.KeyDown || remotes.isEmpty()) return@onPreviewKeyEvent false
                when (e.key) {
                    androidx.compose.ui.input.key.Key.DirectionDown -> { sel = (sel + 1) % remotes.size; true }
                    androidx.compose.ui.input.key.Key.DirectionUp -> { sel = (sel - 1 + remotes.size) % remotes.size; true }
                    androidx.compose.ui.input.key.Key.Enter -> { remotes.getOrNull(sel)?.let { scope.launch { state.publishToRemote(it) } }; true }
                    androidx.compose.ui.input.key.Key.Escape -> { state.pickingRemote = false; true }
                    else -> false
                }
            }
            .testTag("scm_remote_pick"),
    ) {
        Text(mono(localized(Str.scm_pick_remote_title, branch), branch), fontSize = 11.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
        remotes.forEachIndexed { i, r ->
            QuickRow(Icons.Filled.CloudUpload, r, null, selected = i == sel, enabled = !state.busy, tag = "scm_remote_$r",
                onHover = { sel = i }, onClick = { scope.launch { state.publishToRemote(r) } })
        }
    }
}

/** A failure in plain words; the tool's own text (gh's, git's) one click away under "Details", selectable to copy. */
@Composable
private fun FailText(text: String, details: String?, modifier: Modifier, tag: String, muted: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    var open by remember(details) { mutableStateOf(false) }
    Column(modifier.testTag(tag)) {
        Text(text, fontSize = 12.sp, color = if (muted) cs.onSurfaceVariant else cs.error)
        if (details != null) {
            Text(localized(if (open) Str.scm_details_hide else Str.scm_details), fontSize = 11.5.sp, color = cs.primary,
                modifier = Modifier.padding(top = 2.dp).clickable { open = !open }.testTag("${tag}_details"))
            if (open) androidx.compose.foundation.text.selection.SelectionContainer {
                Text(details, fontSize = 11.sp, fontFamily = MonoFontFamily, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerLow).padding(8.dp))
            }
        }
    }
}

/** A row of a quick pick: VS Code's highlight (the accent) on the one the keyboard or the pointer is on. */
@Composable
private fun QuickRow(icon: ImageVector, label: String, detail: String?, selected: Boolean, enabled: Boolean, tag: String, onHover: () -> Unit, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    LaunchedEffect(hovered) { if (hovered && enabled) onHover() }
    val fg = when { !enabled -> cs.onSurfaceVariant.copy(alpha = 0.6f); selected -> cs.onPrimary; else -> cs.onSurface }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 26.dp).clip(RoundedCornerShape(6.dp))
            .background(if (selected) cs.primary else Color.Transparent)
            .hoverable(hover).clickable(enabled = enabled, interactionSource = hover, indication = null, onClick = onClick)
            .padding(horizontal = 8.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = fg.copy(alpha = if (selected) 1f else 0.7f), modifier = Modifier.size(13.dp))
        Text(label, fontSize = 12.5.sp, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 7.dp))
        if (detail != null) Text(detail, fontSize = 11.sp, fontFamily = MonoFontFamily, color = when { selected -> cs.onPrimary.copy(alpha = 0.75f); enabled -> cs.onSurfaceVariant; else -> cs.onSurfaceVariant.copy(alpha = 0.6f) },
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 10.dp).widthIn(max = 150.dp))
    }
}

@Composable
private fun ConflictLink(label: String, hint: String, tag: String, onClick: () -> Unit) {
    ScmHint(label, hint) {
        Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.primary, maxLines = 1,
            modifier = Modifier.padding(horizontal = 4.dp).clickable(onClick = onClick).testTag(tag))
    }
}

/** VS Code's ⋯ (Basics): Pull, Push, Checkout to…, Fetch, then Get latest / Merge into the base, Commit ▸, Changes ▸, Pull, Push ▸. */
@Composable
private fun MoreMenu(state: SourceControlState, scope: kotlinx.coroutines.CoroutineScope, onBranches: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    var sub by remember { mutableStateOf<String?>(null) }
    Box {
        ScmIcon(Icons.Filled.MoreHoriz, localized(Str.scm_more), "scm_more", hint = localized(Str.scm_hint_more)) { open = true; sub = null }
        ScmMenu(expanded = open, onDismiss = { open = false }, tag = "scm_more_menu") {
            val go = { f: suspend () -> Unit -> open = false; scope.launch { f() }; Unit }
            MenuItem(localized(Str.scm_pull), Str.scm_hint_pull) { go { state.sync("pull"); state.refresh() } }
            MenuItem(localized(Str.scm_push), Str.scm_hint_push) { go { state.sync(if (state.status?.upstream == null) "publish" else "push"); state.refresh() } }
            MenuItem(localized(Str.scm_checkout_to), Str.scm_branch_hint) { open = false; onBranches() }
            MenuItem(localized(Str.scm_fetch), Str.scm_hint_fetch) { go { state.sync("fetch"); state.refresh() } }
            state.base?.let { b ->
                ScmMenuSep()
                MenuItem(localized(Str.scm_get_latest, b), Str.scm_hint_get_latest) { go { state.getLatest() } }
                MenuItem(localized(Str.scm_merge_into, b), Str.scm_hint_merge_into, enabled = state.ownCommits > 0) { open = false; state.ask = ScmAsk.MergeInto }
            }
            ScmMenuSep()
            SubHead(localized(Str.scm_sub_commit), sub == "commit") { sub = if (sub == "commit") null else "commit" }
            if (sub == "commit") {
                MenuItem(localized(Str.scm_commit), Str.scm_hint_commit, indent = true) { go { state.commit() } }
                MenuItem(localized(Str.scm_commit_staged), Str.scm_hint_commit_staged, indent = true, enabled = state.stagedCount > 0) { go { state.commit() } }
                MenuItem(localized(Str.scm_commit_all), Str.scm_hint_commit_all, indent = true, enabled = state.changedCount > 0) { go { state.commit(all = true) } }
                MenuItem(localized(Str.scm_undo_last), Str.scm_hint_undo_last, indent = true, enabled = state.last != null && !state.lastPushed) { go { state.undoLastCommit() } }
                MenuItem(localized(Str.scm_commit_amend), Str.scm_hint_amend, indent = true, enabled = state.last != null && !state.lastPushed) { open = false; if (!state.amend) state.toggleAmend() }
            }
            SubHead(localized(Str.scm_sub_changes), sub == "changes") { sub = if (sub == "changes") null else "changes" }
            if (sub == "changes") {
                val repo = state.repo
                val files = state.repos?.firstOrNull { it.repo == repo }?.files.orEmpty()
                MenuItem(localized(Str.scm_stage_all_changes), Str.scm_hint_stage_all, indent = true, enabled = files.any { it.staged != "all" }) { go { state.stage(repo, files.map { it.path }, true) } }
                MenuItem(localized(Str.scm_unstage_all_changes), Str.scm_hint_unstage_all, indent = true, enabled = state.stagedCount > 0) { go { state.stage(repo, files.filter { it.staged == "all" || it.staged == "partial" }.map { it.path }, false) } }
                MenuItem(localized(Str.scm_discard_all), Str.scm_hint_discard_all, indent = true, enabled = files.isNotEmpty()) { open = false; state.ask = ScmAsk.DiscardAll(repo, files.size) }
            }
            SubHead(localized(Str.scm_sub_pullpush), sub == "pullpush") { sub = if (sub == "pullpush") null else "pullpush" }
            if (sub == "pullpush") {
                MenuItem(localized(Str.scm_sync), Str.scm_hint_sync, indent = true) { go { state.syncChanges() } }
                MenuItem(localized(Str.scm_pull), Str.scm_hint_pull, indent = true) { go { state.sync("pull"); state.refresh() } }
                MenuItem(localized(Str.scm_push), Str.scm_hint_push, indent = true) { go { state.sync(if (state.status?.upstream == null) "publish" else "push"); state.refresh() } }
                MenuItem(localized(Str.scm_fetch), Str.scm_hint_fetch, indent = true) { go { state.sync("fetch"); state.refresh() } }
            }
        }
    }
}

@Composable
private fun MenuItem(label: String, hint: dev.supermux.ui.i18n.StringKey, indent: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    ScmHint(label, localized(hint)) { ScmMenuItem(label, onClick = onClick, enabled = enabled, indent = indent) }
}

/** A submenu that opens in place (the prototype's), not a flyout over the chat. */
@Composable
private fun SubHead(label: String, open: Boolean, onClick: () -> Unit) = ScmMenuItem(label, onClick = onClick, trailing = if (open) "⌄" else "›", strong = open)

@Composable
private fun PostItem(label: String, picked: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    ScmMenuItem(label, onClick = onClick, enabled = enabled, trailing = if (picked) "✓" else null)

/**
 * The pane's menu, as the v4 prototype (and a desktop app) draws it: a compact rounded panel under its button, 26 dp
 * rows, the accent under the pointer, Esc or a click outside closes it. Not Material's dropdown (a phone's 48 dp rows).
 */
@Composable
internal fun ScmMenu(expanded: Boolean, onDismiss: () -> Unit, tag: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    if (!expanded) return
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(10.dp)
    val y = with(androidx.compose.ui.platform.LocalDensity.current) { 28.dp.roundToPx() }
    androidx.compose.ui.window.Popup(
        alignment = Alignment.TopEnd, offset = androidx.compose.ui.unit.IntOffset(0, y), onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.PopupProperties(focusable = true),
    ) {
        Column(
            Modifier.widthIn(min = 230.dp, max = 320.dp).heightIn(max = 560.dp).shadow(16.dp, shape).clip(shape).background(cs.surfaceContainerHighest)
                .border(1.dp, cs.outline, shape).padding(5.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())
                .onPreviewKeyEvent { e -> if (e.key == androidx.compose.ui.input.key.Key.Escape) { onDismiss(); true } else false }
                .testTag(tag),
            content = content,
        )
    }
}

@Composable
internal fun ScmMenuItem(label: String, onClick: () -> Unit, enabled: Boolean = true, trailing: String? = null, indent: Boolean = false, strong: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val on = hovered && enabled
    Row(
        Modifier.fillMaxWidth().heightIn(min = 26.dp).clip(RoundedCornerShape(6.dp)).background(if (on) cs.primary else Color.Transparent)
            .hoverable(hover).clickable(enabled = enabled, onClick = onClick).padding(start = if (indent) 22.dp else 10.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 12.5.sp, fontWeight = if (strong) FontWeight.Medium else null, maxLines = 1,
            color = when { on -> cs.onPrimary; enabled -> cs.onSurface; else -> cs.onSurfaceVariant.copy(alpha = 0.6f) }, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, fontSize = 12.sp, color = if (on) cs.onPrimary else cs.onSurfaceVariant, modifier = Modifier.padding(start = 18.dp))
    }
}

@Composable
internal fun ScmMenuSep() = Box(Modifier.padding(horizontal = 6.dp, vertical = 4.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outline))

@Composable
private fun Note(text: String) = Note(androidx.compose.ui.text.AnnotatedString(text))

@Composable
private fun Note(text: androidx.compose.ui.text.AnnotatedString) {
    Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm))
}

/** [text] with [name] (a branch, a remote branch) in mono, as the prototype sets names. */
@Composable
private fun mono(text: String, vararg names: String, bold: Boolean = false): androidx.compose.ui.text.AnnotatedString {
    val family = MonoFontFamily
    return androidx.compose.ui.text.buildAnnotatedString {
    append(text)
    names.filter { it.isNotEmpty() }.forEach { n ->
        val i = text.indexOf(n)
        if (i >= 0) addStyle(androidx.compose.ui.text.SpanStyle(fontFamily = if (bold) null else family, fontWeight = if (bold) FontWeight.SemiBold else null), i, i + n.length)
    }
    }
}

@Composable
private fun RepoTitle(repo: String) {
    Text(
        repo.ifEmpty { "." }, fontFamily = MonoFontFamily, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = Space.md, top = Space.sm, bottom = 2.dp),
    )
}

@Composable
private fun GroupHeader(
    state: SourceControlState, repo: String, group: ScmGroup, files: List<ChangedFile>, touch: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val cs = MaterialTheme.colorScheme
    val key = "$repo:$group"
    val shut = state.collapsed[key] == true
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Column {
        Row(
            Modifier.fillMaxWidth().hoverable(hover).clickable { state.collapsed[key] = !shut }
                .heightIn(min = if (touch) 36.dp else 26.dp).padding(start = 4.dp, end = Space.xs)
                .testTag("scm_group_${group.name.lowercase()}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (shut) Icons.Filled.KeyboardArrowRight else Icons.Filled.KeyboardArrowDown, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(
                localized(when (group) { ScmGroup.Merge -> Str.scm_group_merge; ScmGroup.Staged -> Str.scm_group_staged; ScmGroup.Changes -> Str.scm_group_changes }),
                fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface, modifier = Modifier.weight(1f).padding(start = 2.dp),
            )
            if (group == ScmGroup.Changes && (hovered || touch)) {
                ScmIcon(Icons.AutoMirrored.Filled.Undo, localized(Str.scm_discard_all), "scm_discard_all", hint = localized(Str.scm_hint_discard_all)) { state.ask = ScmAsk.DiscardAll(repo, files.size) }
            }
            // Labelled and always there, like the + on each row.
            val all = files.map { it.path }
            when (group) {
                ScmGroup.Staged -> GroupAction(Icons.Filled.Remove, localized(Str.scm_unstage_all), localized(Str.scm_hint_unstage_all), "scm_unstage_all") { scope.launch { state.stage(repo, all, false) } }
                else -> GroupAction(Icons.Filled.Add, localized(Str.scm_stage_all), localized(Str.scm_hint_stage_all), "scm_stage_all_${group.name.lowercase()}") { scope.launch { state.stage(repo, all, true) } }
            }
            Text(
                "${files.size}", fontSize = 11.sp, color = cs.onSurface,
                modifier = Modifier.padding(start = 4.dp).background(cs.surfaceContainerHighest, RoundedCornerShape(9.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        val ask = state.ask
        if (group == ScmGroup.Changes && ask is ScmAsk.DiscardAll && ask.repo == repo) {
            AskBox(
                question = localizedPlural(Plurals.scm_discard_all_q, ask.count, ask.count),
                body = localized(Str.scm_discard_all_body),
                confirm = localizedPlural(Plurals.scm_discard_all_n, ask.count, ask.count),
                onConfirm = { scope.launch { state.discardAll(repo) } },
                onCancel = { state.ask = null },
                tag = "scm_ask_discard_all",
            )
        }
    }
}

@Composable
private fun FileRow(
    state: SourceControlState, repo: String, group: ScmGroup, f: ChangedFile, touch: Boolean,
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenDiff: (String, ChangedFile, ScmGroup, Boolean) -> Unit, onOpenFile: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val name = f.path.substringAfterLast('/') // platform-ok: a git path, repo-relative, always / on every OS
    val dir = f.path.substringBeforeLast('/', "") // platform-ok: a git path, repo-relative, always / on every OS
    val letter = scmLetter(f, group)
    Column {
        Row(
            Modifier.fillMaxWidth().hoverable(hover)
                .combinedClickable(onClick = { onOpenDiff(repo, f, group, false) }, onDoubleClick = { onOpenDiff(repo, f, group, true) })
                .heightIn(min = if (touch) 36.dp else 24.dp).padding(start = 24.dp, end = Space.xs)
                .testTag("scm_row_${group.name.lowercase()}_${f.path}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Outlined.Description, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(
                name, fontSize = if (touch) 14.sp else 13.sp, maxLines = 1, color = if (letter == "D") cs.onSurfaceVariant else cs.onSurface,
                textDecoration = if (letter == "D") TextDecoration.LineThrough else null, modifier = Modifier.padding(start = 6.dp),
            )
            Text(dir, fontSize = 11.5.sp, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 6.dp))
            if (group == ScmGroup.Merge && (hovered || touch)) {
                // VS Code's CodeLens words, for the whole file: keep mine, theirs, or both.
                ConflictLink(localized(Str.scm_accept_current), localized(Str.scm_hint_accept_current), "scm_accept_current_${f.path}") { scope.launch { state.resolve(f.path, "mine") } }
                ConflictLink(localized(Str.scm_accept_incoming), localized(Str.scm_hint_accept_incoming), "scm_accept_incoming_${f.path}") { scope.launch { state.resolve(f.path, "theirs") } }
                ConflictLink(localized(Str.scm_accept_both), localized(Str.scm_hint_accept_both), "scm_accept_both_${f.path}") { scope.launch { state.resolve(f.path, "both") } }
            }
            if (hovered || touch) {
                ScmIcon(Icons.Outlined.FileOpen, localized(Str.scm_open_file), "scm_open_${f.path}", hint = localized(Str.scm_hint_open_file)) { onOpenFile(repo, f.path) }
                if (group == ScmGroup.Changes) {
                    ScmIcon(Icons.AutoMirrored.Filled.Undo, localized(Str.scm_discard), "scm_discard_${f.path}", hint = localized(Str.scm_hint_discard)) {
                        state.ask = if (state.ask == ScmAsk.Discard(repo, f.path)) null else ScmAsk.Discard(repo, f.path)
                    }
                }
            }
            // The row's + or −: never hidden.
            when (group) {
                ScmGroup.Staged -> ScmIcon(Icons.Filled.Remove, localized(Str.scm_unstage), "scm_unstage_${f.path}", main = true, hint = localized(Str.scm_hint_unstage)) { scope.launch { state.stage(repo, listOf(f.path), false) } }
                else -> ScmIcon(Icons.Filled.Add, localized(Str.scm_stage), "scm_stage_${f.path}", main = true, hint = localized(Str.scm_hint_stage)) { scope.launch { state.stage(repo, listOf(f.path), true) } }
            }
            Text(letter, fontFamily = MonoFontFamily, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = letterColor(letter), modifier = Modifier.width(16.dp).padding(start = 4.dp))
        }
        if (group == ScmGroup.Changes && state.ask == ScmAsk.Discard(repo, f.path)) {
            AskBox(
                question = localized(Str.scm_discard_q, name),
                body = localized(Str.scm_irreversible),
                confirm = localized(Str.scm_discard_file),
                onConfirm = { scope.launch { state.discard(repo, f.path) } },
                onCancel = { state.ask = null },
                tag = "scm_ask_discard",
            )
        }
    }
}

@Composable
private fun letterColor(letter: String): Color {
    val cs = MaterialTheme.colorScheme
    return when (letter) {
        "M" -> LocalSemantics.current.warning
        "U", "A" -> LocalSemantics.current.success
        else -> cs.error // D, R, ! (a conflict)
    }
}

/** VS Code's git-pull-request glyph: two branch ends and the arrow between them. */
private val PullRequestGlyph: androidx.compose.ui.graphics.vector.ImageVector by lazy {
    androidx.compose.ui.graphics.vector.ImageVector.Builder("scm.pr", 24.dp, 24.dp, 24f, 24f).path(
        fill = null, stroke = androidx.compose.ui.graphics.SolidColor(Color.Black), strokeLineWidth = 2f,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round, strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
    ) {
        // The two ends: (6,6) and (18,18), radius 3.
        moveTo(9f, 6f); arcTo(3f, 3f, 0f, true, true, 3f, 6f); arcTo(3f, 3f, 0f, true, true, 9f, 6f); close()
        moveTo(21f, 18f); arcTo(3f, 3f, 0f, true, true, 15f, 18f); arcTo(3f, 3f, 0f, true, true, 21f, 18f); close()
        moveTo(6f, 9f); lineTo(6f, 21f)
        moveTo(18f, 15f); lineTo(18f, 8f); quadTo(18f, 6f, 16f, 6f); lineTo(13f, 6f)
        moveTo(15f, 3.5f); lineTo(12.5f, 6f); lineTo(15f, 8.5f)
    }.build()
}

/** A box's 2 dp left edge, drawn over its border as the prototype's border-left is. */
private fun Modifier.leftEdge(color: Color): Modifier = drawWithContent {
    drawContent()
    drawRect(color, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
}

/** VS Code's confirm, in the pane, right under what it is about. */
@Composable
private fun AskBox(
    question: String, body: String, confirm: String?, onConfirm: () -> Unit, onCancel: () -> Unit, tag: String,
    danger: Boolean = true, warn: Boolean = false, start: androidx.compose.ui.unit.Dp = 24.dp,
    extra: List<Pair<String, () -> Unit>> = emptyList(),
) {
    val cs = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(8.dp)
    val accent = when { danger -> cs.error; warn -> LocalSemantics.current.warning; else -> cs.primary }
    Row(
        Modifier.padding(start = start, end = if (start == 0.dp) 0.dp else Space.sm, top = if (start == 0.dp) 8.dp else 2.dp, bottom = 6.dp).fillMaxWidth()
            .clip(shape).background(cs.surfaceContainerHighest).leftEdge(accent).border(1.dp, cs.outlineVariant, shape).testTag(tag),
    ) {
        // The left edge (leftEdge): red for throwing work away, amber for a missing message, the accent for a question.
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(question, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(body, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            // Wraps when the buttons don't fit the pane (a narrow pane, longer labels such as "Continue Without Pushing").
            androidx.compose.foundation.layout.FlowRow(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (confirm != null) Text(
                    confirm, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = if (danger) cs.error else cs.onPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(if (danger) cs.error.copy(alpha = 0.18f) else cs.primary)
                        .border(1.dp, if (danger) cs.error.copy(alpha = 0.5f) else cs.primary, RoundedCornerShape(6.dp)).clickable(onClick = onConfirm)
                        .padding(horizontal = 12.dp, vertical = 4.dp).testTag("${tag}_confirm"),
                )
                extra.forEachIndexed { i, (label, act) ->
                    Text(
                        label, fontSize = 12.5.sp, color = cs.onSurface,
                        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(cs.surfaceContainerHigh)
                            .border(1.dp, cs.outlineVariant, RoundedCornerShape(6.dp)).clickable(onClick = act)
                            .padding(horizontal = 12.dp, vertical = 4.dp).testTag("${tag}_extra_$i"),
                    )
                }
                Text(
                    localized(Str.scm_cancel), fontSize = 12.5.sp, color = cs.onSurfaceVariant,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 4.dp).testTag("${tag}_cancel"),
                )
            }
        }
    }
}

/** An icon button with its name for the hover tooltip and for screen readers; [main] = the row's + / −, in the accent. */
@Composable
private fun ScmIcon(icon: ImageVector, label: String, tag: String, main: Boolean = false, hint: String? = null, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val touch = LocalWindowWidthClass.current == WindowWidthClass.Compact
    ScmHint(label, hint) {
        Box(
            Modifier.size(if (touch) 34.dp else 24.dp).clickable(onClick = onClick).testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (main) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(if (touch) 18.dp else 16.dp))
        }
    }
}

/** The hover hint: the button's name, then what it does in one line (the button guide's words). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScmHint(label: String, hint: String?, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(modifier = Modifier.widthIn(max = 300.dp)) {
                Column {
                    Text(label, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                    if (hint != null) Text(hint, fontSize = 12.sp)
                }
            }
        },
        state = rememberTooltipState(),
        content = content,
    )
}

/** "+ Stage All" / "− Unstage All": labelled, always shown, in the accent. */
@Composable
private fun GroupAction(icon: ImageVector, label: String, hint: String, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    ScmHint(label, hint) {
    Row(
        Modifier.padding(start = 2.dp).background(cs.primary.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).clickable(onClick = onClick)
            .padding(start = 4.dp, end = 7.dp, top = 2.dp, bottom = 2.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = cs.primary, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(3.dp))
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = cs.primary)
    }
    }
}
