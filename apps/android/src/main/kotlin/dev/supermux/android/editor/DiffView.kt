package dev.supermux.android.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.supermux.android.R
import dev.supermux.android.theme.HapticKind
import dev.supermux.android.theme.MonoFontFamily
import dev.supermux.android.theme.Space
import dev.supermux.android.theme.rememberHaptics
import dev.supermux.net.DiffFile
import dev.supermux.net.RepoDiff
import dev.supermux.net.ReviewComment
import kotlinx.coroutines.launch

// ─── Diff colours — same semantic palette as iOS DiffView.swift:38-41 (emerald/red/
//     blue/amber), applied as opacity tints so they read in light + dark. State is never
//     conveyed by colour alone: every row keeps its +/-/@@ sigil and status text. ─────
private val Emerald = Color(red = 0.20f, green = 0.78f, blue = 0.55f)
private val DiffRed = Color(red = 0.90f, green = 0.30f, blue = 0.30f)
private val DiffBlue = Color(red = 0.36f, green = 0.56f, blue = 0.94f)
private val Amber = Color(red = 0.98f, green = 0.75f, blue = 0.14f)

/**
 * Native M3 git-diff viewer + lightweight inline code-review — 1:1 parity with iOS
 * `DiffView.swift` (and the PWA `DiffView.vue`). Files are grouped per repo (repo header
 * only when >1 repo), each file expands to a unified diff of monospaced add/del/ctx/hunk
 * rows, and add/ctx rows take inline review comments + resolve; a submit-review bar
 * delivers open comments to the agent.
 *
 * Pure Compose state; all mutations go through the injected suspend lambdas, and the
 * parent re-supplies [repos]/[comments] after [onReload].
 */
@Composable
fun DiffView(
    repos: List<RepoDiff>,
    comments: List<ReviewComment>,
    /** repo, path, anchorLine (new-side), anchorContext (line text), hunkHeader (@@ line), body. */
    onAddComment: suspend (repo: String, path: String, anchorLine: Int, anchorContext: String, hunkHeader: String, body: String) -> Unit,
    onResolve: suspend (commentId: String) -> Unit,
    onSubmit: suspend () -> Unit,
    onReload: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val haptic = rememberHaptics()
    val scope = rememberCoroutineScope()

    // Keys are stable strings so the sets survive re-composition (Set<String> like iOS/Vue).
    var expandedFiles by remember { mutableStateOf(setOf<String>()) }
    var expandedRepos by remember { mutableStateOf(setOf<String>()) }
    // `repo||path||newLine` of the line whose composer is open (null = none).
    var composerFor by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var wrap by remember { mutableStateOf(true) }

    // Seed expansion to every repo, re-seeding when the repo set itself changes (parity
    // with the iOS seedRepos + onChange(of: repos.map(\.repo)) — DiffView.swift:61-69).
    val repoKey = repos.joinToString(" ") { it.repo }
    LaunchedEffect(repoKey) {
        expandedRepos = repos.map { it.repo }.toSet()
    }

    val totalFiles = repos.sumOf { it.files.size }
    val multiRepo = repos.size > 1
    val openCount = comments.count { it.status == "open" }
    val hasComments = comments.isNotEmpty() || openCount > 0

    fun toggle(set: Set<String>, key: String): Set<String> =
        if (key in set) set - key else set + key

    fun toggleComposer(key: String) {
        composerFor = if (composerFor == key) null else key
        draft = ""
    }

    Column(modifier.fillMaxSize().background(cs.surface)) {
        // ── Header: file count · Wrap toggle · close ──────────────────────────
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .background(cs.surfaceContainer)
                .padding(horizontal = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$totalFiles changed file${if (totalFiles == 1) "" else "s"}",
                style = MaterialTheme.typography.titleSmall,
                color = cs.onSurface,
            )
            Box(Modifier.weight(1f))
            TextButton(onClick = { haptic(HapticKind.Tick); wrap = !wrap }) {
                Text(
                    "Wrap",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (wrap) cs.primary else cs.onSurfaceVariant,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    painter = painterResource(R.drawable.ic_x),
                    contentDescription = "Close diff",
                    tint = cs.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)

        // ── Body ──────────────────────────────────────────────────────────────
        if (totalFiles == 0) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("No changes found", color = cs.onSurfaceVariant, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                repos.forEach { repo ->
                    if (multiRepo) {
                        item(key = "repo:${repo.repo}") {
                            RepoHeader(
                                repo = repo,
                                expanded = repo.repo in expandedRepos,
                                onToggle = {
                                    haptic(HapticKind.Tick)
                                    expandedRepos = toggle(expandedRepos, repo.repo)
                                },
                            )
                            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)
                        }
                    }
                    if (!multiRepo || repo.repo in expandedRepos) {
                        repo.files.forEach { file ->
                            val key = fileKey(repo.repo, file.path)
                            item(key = "file:$key") {
                                FileSection(
                                    repo = repo.repo,
                                    file = file,
                                    expanded = key in expandedFiles,
                                    multiRepo = multiRepo,
                                    wrap = wrap,
                                    comments = comments,
                                    composerFor = composerFor,
                                    draft = draft,
                                    submitting = submitting,
                                    onToggleFile = {
                                        haptic(HapticKind.Tick)
                                        expandedFiles = toggle(expandedFiles, key)
                                    },
                                    onToggleComposer = { ck -> haptic(HapticKind.Tick); toggleComposer(ck) },
                                    onDraftChange = { draft = it },
                                    onCancelComposer = { composerFor = null; draft = "" },
                                    onAdd = { repoId, path, line, hunkHeader ->
                                        val body = draft.trim()
                                        val newLine = line.newLine
                                        if (body.isNotEmpty() && newLine != null) {
                                            scope.launch {
                                                submitting = true
                                                onAddComment(repoId, path, newLine, line.content, hunkHeader, body)
                                                draft = ""
                                                composerFor = null
                                                submitting = false
                                                onReload()
                                            }
                                        }
                                    },
                                    onResolve = { commentId ->
                                        scope.launch { onResolve(commentId); onReload() }
                                    },
                                )
                                HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)
                            }
                        }
                    }
                }
            }
        }

        // ── Sticky submit bar ──────────────────────────────────────────────────
        if (hasComments) {
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(cs.surfaceContainer)
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$openCount open comment${if (openCount == 1) "" else "s"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = cs.onSurfaceVariant,
                )
                Box(Modifier.weight(1f))
                Button(
                    onClick = {
                        scope.launch {
                            submitting = true
                            onSubmit()
                            submitting = false
                            onReload()
                        }
                    },
                    enabled = openCount > 0 && !submitting,
                ) {
                    Text("Submit review", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── Repo group header (only when >1 repo) ──────────────────────────────────────

@Composable
private fun RepoHeader(repo: RepoDiff, expanded: Boolean, onToggle: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val label = repo.repo.ifEmpty { "workdir" }
    Row(
        Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHigh)
            .clickable(onClick = onToggle)
            .heightIn(min = 48.dp)
            .padding(horizontal = Space.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(
                if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
            ),
            contentDescription = null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
        Text(
            label,
            fontFamily = MonoFontFamily,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = cs.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = Space.sm),
        )
        Text(
            "${repo.files.size} file${if (repo.files.size == 1) "" else "s"}",
            fontSize = 11.sp,
            color = cs.onSurfaceVariant,
        )
    }
}

// ── File section (header row + expanded diff) ──────────────────────────────────

@Composable
private fun FileSection(
    repo: String,
    file: DiffFile,
    expanded: Boolean,
    multiRepo: Boolean,
    wrap: Boolean,
    comments: List<ReviewComment>,
    composerFor: String?,
    draft: String,
    submitting: Boolean,
    onToggleFile: () -> Unit,
    onToggleComposer: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onCancelComposer: () -> Unit,
    onAdd: (repo: String, path: String, line: DiffLine, hunkHeader: String) -> Unit,
    onResolve: (commentId: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val stats = remember(file.diff) { diffStats(file.diff) }
    Column(Modifier.fillMaxWidth().padding(start = if (multiRepo) Space.md else 0.dp)) {
        // File header
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleFile)
                .heightIn(min = 48.dp)
                .padding(horizontal = Space.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(
                    if (expanded) R.drawable.ic_chevron_down else R.drawable.ic_chevron_right,
                ),
                contentDescription = null,
                tint = cs.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(
                file.path,
                fontFamily = MonoFontFamily,
                fontSize = 13.sp,
                color = cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = Space.sm),
            )
            if (file.binary) {
                Tag("Binary")
            } else if (file.modeChange) {
                Tag("Mode")
            }
            Text(
                statusLabel(file.status),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                color = statusColor(file.status),
                modifier = Modifier.padding(start = Space.xs),
            )
            if (!file.binary) {
                if (stats.first > 0) {
                    Text(
                        "+${stats.first}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Emerald,
                        modifier = Modifier.padding(start = Space.xs),
                    )
                }
                if (stats.second > 0) {
                    Text(
                        "-${stats.second}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = DiffRed,
                        modifier = Modifier.padding(start = Space.xs),
                    )
                }
            }
        }

        if (expanded) {
            HorizontalDivider(color = cs.outlineVariant, thickness = 0.5.dp)
            val lines = remember(file.diff) { parseDiffLines(file.diff) }
            when {
                file.binary -> Placeholder("Binary file — no text diff")
                file.modeChange && lines.isEmpty() -> Placeholder("File mode changed")
                else -> {
                    val body: @Composable () -> Unit = {
                        DiffRows(
                            repo = repo,
                            path = file.path,
                            lines = lines,
                            wrap = wrap,
                            comments = comments,
                            composerFor = composerFor,
                            draft = draft,
                            submitting = submitting,
                            onToggleComposer = onToggleComposer,
                            onDraftChange = onDraftChange,
                            onCancelComposer = onCancelComposer,
                            onAdd = onAdd,
                            onResolve = onResolve,
                        )
                    }
                    if (wrap) {
                        body()
                    } else {
                        // No wrap → diff + its comment rows share one horizontal scroll so
                        // they stay column-aligned (parity DiffView.swift:269-272).
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            body()
                        }
                    }
                }
            }
        }
    }
}

// ── Diff rows + inline comments ────────────────────────────────────────────────

@Composable
private fun DiffRows(
    repo: String,
    path: String,
    lines: List<DiffLine>,
    wrap: Boolean,
    comments: List<ReviewComment>,
    composerFor: String?,
    draft: String,
    submitting: Boolean,
    onToggleComposer: (String) -> Unit,
    onDraftChange: (String) -> Unit,
    onCancelComposer: () -> Unit,
    onAdd: (repo: String, path: String, line: DiffLine, hunkHeader: String) -> Unit,
    onResolve: (commentId: String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val rowsModifier = if (wrap) Modifier.fillMaxWidth() else Modifier
    Column(rowsModifier.background(cs.surfaceContainerLow.copy(alpha = 0.4f))) {
        lines.forEachIndexed { idx, line ->
            DiffRowItem(
                repo = repo,
                path = path,
                line = line,
                wrap = wrap,
                composerFor = composerFor,
                onToggleComposer = onToggleComposer,
            )
            if ((line.type == DiffLineType.Add || line.type == DiffLineType.Ctx) && line.newLine != null) {
                val newLine = line.newLine
                val key = composerKey(repo, path, newLine)
                if (composerFor == key) {
                    Composer(
                        draft = draft,
                        submitting = submitting,
                        onDraftChange = onDraftChange,
                        onCancel = onCancelComposer,
                        onAdd = { onAdd(repo, path, line, hunkHeader(lines, idx)) },
                    )
                }
                commentsFor(comments, repo, path, newLine).forEach { c ->
                    CommentThreadRow(c, onResolve = { onResolve(c.id) })
                }
            }
        }
    }
}

/** One diff line: a gutter sigil (− / @@ / a tappable + on add+ctx) + the line text. */
@Composable
private fun DiffRowItem(
    repo: String,
    path: String,
    line: DiffLine,
    wrap: Boolean,
    composerFor: String?,
    onToggleComposer: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    val rowModifier = if (wrap) Modifier.fillMaxWidth() else Modifier
    Row(
        rowModifier.background(rowBackground(line.type)),
        verticalAlignment = Alignment.Top,
    ) {
        Gutter(repo = repo, path = path, line = line, composerFor = composerFor, onToggleComposer = onToggleComposer)
        Text(
            text = line.content.ifEmpty { " " },
            fontFamily = MonoFontFamily,
            fontSize = 11.sp,
            color = if (line.type == DiffLineType.Ctx) cs.onSurfaceVariant else textColor(line.type),
            maxLines = if (wrap) Int.MAX_VALUE else 1,
            softWrap = wrap,
            modifier = (if (wrap) Modifier.weight(1f) else Modifier)
                .padding(end = Space.sm, top = 1.dp, bottom = 1.dp),
        )
    }
}

@Composable
private fun Gutter(
    repo: String,
    path: String,
    line: DiffLine,
    composerFor: String?,
    onToggleComposer: (String) -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    when (line.type) {
        DiffLineType.Del -> GutterText("-", DiffRed)
        DiffLineType.Hunk -> GutterText("@@", DiffBlue)
        DiffLineType.Add, DiffLineType.Ctx -> {
            val newLine = line.newLine
            if (newLine != null) {
                val key = composerKey(repo, path, newLine)
                val open = composerFor == key
                Box(
                    Modifier
                        .size(28.dp)
                        .clickable { onToggleComposer(key) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = if (open) "Close comment composer on line $newLine" else "Add comment on line $newLine",
                        tint = if (open) cs.primary else cs.primary.copy(alpha = 0.7f),
                        modifier = Modifier.size(13.dp),
                    )
                }
            } else {
                GutterText(if (line.type == DiffLineType.Add) "+" else "", Emerald)
            }
        }
    }
}

@Composable
private fun GutterText(s: String, color: Color) {
    Text(
        s,
        fontFamily = MonoFontFamily,
        fontSize = 10.sp,
        color = color,
        modifier = Modifier.widthIn(min = 28.dp).padding(vertical = 1.dp),
    )
}

@Composable
private fun Composer(
    draft: String,
    submitting: Boolean,
    onDraftChange: (String) -> Unit,
    onCancel: () -> Unit,
    onAdd: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHighest)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        OutlinedTextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = { Text("Leave a comment…") },
            modifier = Modifier.fillMaxWidth().widthIn(min = 220.dp),
            minLines = 2,
            maxLines = 6,
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            Button(
                onClick = onAdd,
                enabled = draft.trim().isNotEmpty() && !submitting,
                modifier = Modifier.padding(start = Space.sm),
            ) { Text("Add") }
        }
    }
}

@Composable
private fun CommentThreadRow(c: ReviewComment, onResolve: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerHighest)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                c.author.ifEmpty { "You" },
                fontSize = 11.sp,
                color = cs.onSurfaceVariant,
            )
            CommentStatusBadge(c)
            Box(Modifier.weight(1f))
            if (c.status == "open") {
                TextButton(onClick = onResolve) {
                    Text("Resolve", fontSize = 12.sp, fontWeight = FontWeight.Medium, color = cs.primary)
                }
            }
        }
        Text(
            c.body,
            style = MaterialTheme.typography.bodyMedium,
            color = cs.onSurface,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun CommentStatusBadge(c: ReviewComment) {
    val cs = MaterialTheme.colorScheme
    when {
        c.outdated -> Badge("outdated", Amber)
        c.status == "submitted" -> Badge("submitted", DiffBlue)
        c.status == "resolved" -> Badge("resolved", Emerald)
        else -> Badge("open", cs.onSurfaceVariant)
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Text(
        text,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        modifier = Modifier
            .padding(start = Space.xs)
            .background(color.copy(alpha = 0.18f), shape = CircleShape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Tag(text: String) {
    Text(text, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Space.xs))
}

@Composable
private fun Placeholder(text: String) {
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        fontSize = 11.sp,
        color = cs.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .background(cs.surfaceContainerLow.copy(alpha = 0.4f))
            .padding(horizontal = Space.md, vertical = Space.sm),
    )
}

// ─── Diff parsing (ported 1:1 from DiffView.swift:548-627 / DiffView.vue) ──────

enum class DiffLineType { Add, Del, Ctx, Hunk }

data class DiffLine(val type: DiffLineType, val content: String, val newLine: Int?)

/**
 * Parse a unified diff into typed rows. Only counts new-side line numbers (`newLine`),
 * assigned to `add` and `ctx` rows — exactly like the web `parseDiffLines`.
 */
fun parseDiffLines(diff: String): List<DiffLine> {
    val out = mutableListOf<DiffLine>()
    var inHunk = false
    var newLn = 0
    // omittingEmptySubsequences:false ↔ keep trailing/empty segments (split with -1 limit).
    for (line in diff.split("\n")) {
        if (line.startsWith("@@")) {
            inHunk = true
            newLn = newSideStart(line) ?: 0
            out.add(DiffLine(DiffLineType.Hunk, line, null))
            continue
        }
        if (!inHunk) continue
        when {
            line.startsWith("+") -> {
                out.add(DiffLine(DiffLineType.Add, line.drop(1), newLn))
                newLn += 1
            }
            line.startsWith("-") -> {
                out.add(DiffLine(DiffLineType.Del, line.drop(1), null))
            }
            line.startsWith(" ") -> {
                out.add(DiffLine(DiffLineType.Ctx, line.drop(1), newLn))
                newLn += 1
            }
        }
    }
    return out
}

/**
 * Extract the new-side start line from a hunk header — the first `+<digits>` group.
 * Mirrors the JS regex `/\+(\d+)/`: a `+` not immediately followed by a digit is skipped.
 */
private fun newSideStart(hunk: String): Int? {
    var i = 0
    while (i < hunk.length) {
        if (hunk[i] == '+' && i + 1 < hunk.length && hunk[i + 1].isDigit()) {
            var j = i + 1
            val sb = StringBuilder()
            while (j < hunk.length && hunk[j].isDigit()) {
                sb.append(hunk[j]); j += 1
            }
            return sb.toString().toIntOrNull()
        }
        i += 1
    }
    return null
}

/** +/- counts, ignoring the `+++`/`---` file headers (parity with web `diffStats`). */
fun diffStats(diff: String): Pair<Int, Int> {
    var added = 0
    var deleted = 0
    for (line in diff.split("\n")) {
        if (line.startsWith("+") && !line.startsWith("+++")) added += 1
        else if (line.startsWith("-") && !line.startsWith("---")) deleted += 1
    }
    return added to deleted
}

private fun statusColor(status: String): Color = when (status) {
    "added" -> Emerald
    "deleted" -> DiffRed
    "renamed" -> DiffBlue
    else -> Amber
}

private fun statusLabel(status: String): String = when (status) {
    "added" -> "Added"
    "deleted" -> "Deleted"
    "renamed" -> "Renamed"
    else -> "Modified"
}

private fun rowBackground(type: DiffLineType): Color = when (type) {
    DiffLineType.Add -> Emerald.copy(alpha = 0.12f)
    DiffLineType.Del -> DiffRed.copy(alpha = 0.12f)
    DiffLineType.Hunk -> DiffBlue.copy(alpha = 0.08f)
    DiffLineType.Ctx -> Color.Transparent
}

private fun textColor(type: DiffLineType): Color = when (type) {
    DiffLineType.Add -> Emerald
    DiffLineType.Del -> DiffRed
    DiffLineType.Hunk -> DiffBlue
    DiffLineType.Ctx -> Color.Unspecified
}

// ─── Keys + comment filtering + hunk lookup ────────────────────────────────────

private fun fileKey(repo: String, path: String): String = "$repo $path"
private fun composerKey(repo: String, path: String, newLine: Int): String = "$repo||$path||$newLine"

/**
 * Existing top-level comments anchored at this new-side line. Mirrors the Vue filter:
 * `repo == && path == && (currentLine ?? anchorLine) == newLine` (DiffView.swift:518-524).
 */
private fun commentsFor(
    comments: List<ReviewComment>,
    repo: String,
    path: String,
    newLine: Int,
): List<ReviewComment> = comments.filter { c ->
    c.repo == repo && c.path == path && (c.currentLine ?: c.anchorLine) == newLine
}

/** The nearest preceding `@@` header content for the line at [index] (its hunk header). */
private fun hunkHeader(lines: List<DiffLine>, index: Int): String {
    for (i in index downTo 0) {
        if (lines[i].type == DiffLineType.Hunk) return lines[i].content
    }
    return ""
}
