// #72 v4: the Changes pane as VS Code's Source Control, literally (Ahmet: "don't invent anything new").
// What is not committed yet, in VS Code's three groups: Merge Changes (conflicts), Staged Changes (what the next
// commit holds) and Changes (the rest; a partly staged file is in both). Each file row ALWAYS shows its + (stage)
// or − (unstage), as Ahmet asked; Open File and Discard come on hover (always on a phone). Each group header has a
// labelled "Stage All" / "Unstage All". Discard asks VS Code's question INSIDE the pane, under the row it is for:
// nothing floats over the chat. Clicking a row opens its diff. The commit box, sync and the rest are later cars.
package dev.supermux.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.TextButton
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
}

/** What the pane holds: the uncommitted files per repo, the open question, and the last thing that went wrong. */
class SourceControlState(val api: SourceControlApi) {
    var repos by mutableStateOf<List<RepoChanges>?>(null)
    var offline by mutableStateOf(false)
    var problem by mutableStateOf<String?>(null)
    var ask by mutableStateOf<ScmAsk?>(null)
    val collapsed = mutableStateMapOf<String, Boolean>()

    suspend fun refresh() {
        val r = api.changes()
        offline = r == null
        if (r != null) repos = r.repos
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
    /** A file's diff (repo, path, the group it was clicked in). */
    onOpenDiff: (repo: String, path: String, group: ScmGroup) -> Unit,
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
    Column(modifier.fillMaxSize().background(cs.surfaceContainer).testTag("scm_pane")) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 34.dp).padding(start = Space.md, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(localized(Str.scm_title), fontSize = 11.sp, fontWeight = FontWeight.Medium, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (onReview != null) ScmIcon(Icons.Outlined.RateReview, localized(Str.scm_review_hint), "scm_review", onClick = onReview)
            ScmIcon(Icons.Filled.Refresh, localized(Str.scm_refresh), "scm_refresh") { scope.launch { state.refresh() } }
        }
        state.problem?.let { p ->
            Text(p, fontSize = 12.sp, color = cs.error, modifier = Modifier.padding(horizontal = Space.md, vertical = Space.xs).testTag("scm_problem"))
        }
        val repos = state.repos
        when {
            repos == null && state.offline -> Note(localized(Str.scm_offline))
            repos == null -> Unit
            repos.all { it.files.isEmpty() } -> Note(localized(Str.scm_no_changes))
            else -> LazyColumn(Modifier.fillMaxSize()) {
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
                ScmIcon(Icons.AutoMirrored.Filled.Undo, localized(Str.scm_discard_all), "scm_discard_all") { state.ask = ScmAsk.DiscardAll(repo, files.size) }
            }
            // Labelled and always there, like the + on each row.
            val all = files.map { it.path }
            when (group) {
                ScmGroup.Staged -> GroupAction(Icons.Filled.Remove, localized(Str.scm_unstage_all), "scm_unstage_all") { scope.launch { state.stage(repo, all, false) } }
                else -> GroupAction(Icons.Filled.Add, localized(Str.scm_stage_all), "scm_stage_all_${group.name.lowercase()}") { scope.launch { state.stage(repo, all, true) } }
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
    onOpenDiff: (String, String, ScmGroup) -> Unit, onOpenFile: (String, String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val name = f.path.substringAfterLast('/') // platform-ok: a git path, repo-relative, always / on every OS
    val dir = f.path.substringBeforeLast('/', "") // platform-ok: a git path, repo-relative, always / on every OS
    val letter = scmLetter(f, group)
    Column {
        Row(
            Modifier.fillMaxWidth().hoverable(hover).clickable { onOpenDiff(repo, f.path, group) }
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
                ScmIcon(Icons.Outlined.FileOpen, localized(Str.scm_open_file), "scm_open_${f.path}") { onOpenFile(repo, f.path) }
                if (group == ScmGroup.Changes) {
                    ScmIcon(Icons.AutoMirrored.Filled.Undo, localized(Str.scm_discard), "scm_discard_${f.path}") {
                        state.ask = if (state.ask == ScmAsk.Discard(repo, f.path)) null else ScmAsk.Discard(repo, f.path)
                    }
                }
            }
            // The row's + or −: never hidden.
            when (group) {
                ScmGroup.Staged -> ScmIcon(Icons.Filled.Remove, localized(Str.scm_unstage), "scm_unstage_${f.path}", main = true) { scope.launch { state.stage(repo, listOf(f.path), false) } }
                else -> ScmIcon(Icons.Filled.Add, localized(Str.scm_stage), "scm_stage_${f.path}", main = true) { scope.launch { state.stage(repo, listOf(f.path), true) } }
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
private fun AskBox(question: String, body: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, tag: String) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier.padding(start = 24.dp, end = Space.sm, top = 2.dp, bottom = 6.dp).fillMaxWidth()
            .background(cs.surfaceContainerHighest, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 9.dp).testTag(tag),
    ) {
        Text(question, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
        Text(body, fontSize = 12.5.sp, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag("${tag}_confirm")) { Text(confirm, color = cs.error, fontSize = 12.5.sp) }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("${tag}_cancel")) { Text(localized(Str.scm_cancel), fontSize = 12.5.sp) }
        }
    }
}

/** An icon button with its name for the hover tooltip and for screen readers; [main] = the row's + / −, in the accent. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScmIcon(icon: ImageVector, label: String, tag: String, main: Boolean = false, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val touch = LocalWindowWidthClass.current == WindowWidthClass.Compact
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            Modifier.size(if (touch) 34.dp else 24.dp).clickable(onClick = onClick).testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = label, tint = if (main) cs.primary else cs.onSurfaceVariant, modifier = Modifier.size(if (touch) 18.dp else 16.dp))
        }
    }
}

/** "+ Stage All" / "− Unstage All": labelled, always shown, in the accent. */
@Composable
private fun GroupAction(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
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
