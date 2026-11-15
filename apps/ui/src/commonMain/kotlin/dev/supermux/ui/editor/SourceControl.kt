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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import dev.supermux.net.RepoChanges
import dev.supermux.state.SourceControlApi
import dev.supermux.ui.adaptive.LocalWindowWidthClass
import dev.supermux.ui.adaptive.WindowWidthClass
import dev.supermux.ui.i18n.Plurals
import dev.supermux.ui.i18n.Str
import dev.supermux.ui.i18n.localized
import dev.supermux.ui.i18n.localizedPlural
import dev.supermux.ui.theme.LocalSemantics
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
    group == ScmGroup.Merge -> "C"
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
}

/** VS Code's Commit ▾: what the button does after committing (postCommitCommands). */
enum class ScmPost(val wire: String) { Commit("commit"), Push("push"), Sync("sync");
    companion object { fun of(w: String?) = entries.firstOrNull { it.wire == w } ?: Commit }
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
    var status by mutableStateOf<dev.supermux.net.GitRemoteStatus?>(null)
    /** The last commit on this chat's branch (for Amend), and whether origin already has it. */
    var last by mutableStateOf<dev.supermux.net.LogCommit?>(null)
    private var prefsRead = false

    /** The repo the commit box acts on: the chat's own (""), else the first with changes. */
    val repo: String get() = repos?.firstOrNull { it.repo == "" }?.repo ?: repos?.firstOrNull { it.files.isNotEmpty() }?.repo ?: ""
    private val files: List<ChangedFile> get() = repos?.firstOrNull { it.repo == repo }?.files.orEmpty()
    val stagedCount: Int get() = files.count { it.staged == "all" || it.staged == "partial" }
    val changedCount: Int get() = files.size
    val merging: Boolean get() = repos?.firstOrNull { it.repo == repo }?.merging == true
    val conflicts: Int get() = files.count { it.staged == "conflict" }
    val lastPushed: Boolean get() = last?.pushed == true

    suspend fun refresh() {
        val r = api.changes()
        offline = r == null
        if (r != null) repos = r.repos
        status = api.status() ?: status
        last = api.history(repo)?.chat?.firstOrNull()
        if (!prefsRead) api.prefs()?.let { (p, s) -> prefsRead = true; post = ScmPost.of(p); smart = s ?: "ask" }
        if (amend && (last == null || lastPushed)) amend = false
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
        note = null
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
            !r.ok -> problem = localized(Str.scm_failed, r.message ?: r.reason ?: "")
            else -> {
                problem = null; ask = null
                note = localized(if (amend) Str.scm_amended else Str.scm_committed, r.subject ?: "")
                message = ""; amend = false
                when (post) {
                    ScmPost.Push -> sync(if (status?.upstream == null) "publish" else "push")
                    ScmPost.Sync -> { sync("pull"); sync(if (status?.upstream == null) "publish" else "push") }
                    ScmPost.Commit -> Unit
                }
            }
        }
        refresh()
    }

    /** Pull / push / publish / fetch, with VS Code's outcome in the note line. */
    suspend fun sync(op: String) {
        busy = true
        val r = api.sync(op)
        busy = false
        problem = when {
            r == null -> localized(Str.scm_offline)
            r.status in setOf("pushed", "up_to_date", "clean", "ok", "fetched", "") -> null
            r.status == "conflict" -> localized(Str.scm_conflict_first, r.files.joinToString(", "))
            else -> localized(Str.scm_failed, r.message ?: r.status)
        }
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

    suspend fun undoLastCommit() {
        val r = api.uncommit(repo)
        when {
            r == null -> problem = localized(Str.scm_offline)
            r.ok -> { message = r.subject.orEmpty(); note = localized(Str.scm_undone, r.subject ?: "") }
            r.reason == "pushed" -> problem = localized(Str.scm_undo_pushed)
            else -> problem = localized(Str.scm_failed, r.message ?: r.reason ?: "")
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
            else -> localized(Str.scm_failed, r.message ?: r.error ?: r.reason ?: "")
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
) {
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme
    // A phone has no hover: every row action stays shown.
    val touch = LocalWindowWidthClass.current == WindowWidthClass.Compact
    // Real time, off the UI's frame clock: a poll on it would keep a UI test from ever going idle.
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
            if (onReview != null) ScmIcon(Icons.Outlined.RateReview, localized(Str.scm_review), "scm_review", hint = localized(Str.scm_review_hint), onClick = onReview)
            ScmIcon(Icons.Filled.Refresh, localized(Str.scm_refresh), "scm_refresh", hint = localized(Str.scm_hint_refresh)) { scope.launch { state.refresh() } }
        }
        state.problem?.let { p ->
            Text(p, fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xs).testTag("scm_problem"))
        }
        val repos = state.repos
        when {
            repos == null && state.offline -> Note(localized(Str.scm_offline))
            repos == null -> Unit
            else -> LazyColumn(Modifier.fillMaxSize()) {
                item(key = "commit") { CommitBox(state, scope) }
                if (repos.all { it.files.isEmpty() }) item(key = "empty") { Note(localized(Str.scm_no_changes)) }
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
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp)),
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
        val label = when {
            state.merging -> localized(Str.scm_continue)
            state.amend -> localized(Str.scm_amend_commit)
            else -> localized(when (state.post) { ScmPost.Commit -> Str.scm_commit; ScmPost.Push -> Str.scm_commit_push; ScmPost.Sync -> Str.scm_commit_sync })
        }
        val enabled = !state.busy && !(state.merging && state.conflicts > 0)
        var menu by remember { mutableStateOf(false) }
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().height(30.dp).clip(RoundedCornerShape(6.dp))
                .background(if (enabled) cs.primary else cs.primary.copy(alpha = 0.5f)),
        ) {
            Row(
                Modifier.weight(1f).fillMaxHeight().then(if (enabled) Modifier.clickable { scope.launch { state.commit() } } else Modifier).testTag("scm_commit"),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Check, null, tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = cs.onPrimary, modifier = Modifier.padding(start = 6.dp))
            }
            if (!state.merging) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(cs.onPrimary.copy(alpha = 0.25f)))
                Box(Modifier.width(30.dp).fillMaxHeight().clickable { menu = true }.testTag("scm_commit_menu"), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.KeyboardArrowDown, localized(Str.scm_commit_options), tint = cs.onPrimary, modifier = Modifier.size(16.dp))
                    androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
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

@Composable
private fun PostItem(label: String, picked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    androidx.compose.material3.DropdownMenuItem(
        text = { Text(label, fontSize = 12.5.sp) },
        trailingIcon = if (picked) { { Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp)) } } else null,
        enabled = enabled,
        onClick = onClick,
    )
}

@Composable
private fun Note(text: String) {
    Text(text, fontSize = 12.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm))
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
        else -> cs.error
    }
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
            .clip(shape).background(cs.surfaceContainerHighest).border(1.dp, cs.outlineVariant, shape).height(IntrinsicSize.Min).testTag(tag),
    ) {
        // The left edge: red for throwing work away, amber for a missing message, the accent for a question.
        Box(Modifier.width(2.dp).fillMaxHeight().background(accent))
        Column(Modifier.padding(horizontal = 10.dp, vertical = 9.dp)) {
            Text(question, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
            Text(body, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            Row(Modifier.padding(top = 9.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
