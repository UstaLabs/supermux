// The diff plugin's DiffHost on the review REST endpoints and frames (M5 A5), shared by the
// walkthrough slide and the Changes pane. The plugin speaks 0-based working-copy lines; the broker
// speaks 1-based new-side lines: every conversion is here.
package dev.supermux.ui.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.plugins.diff.DiffConfig
import dev.supermux.editor.plugins.diff.DiffHost
import dev.supermux.editor.plugins.diff.DiffHunk
import dev.supermux.editor.plugins.diff.DiffPage
import dev.supermux.editor.plugins.diff.Review
import dev.supermux.editor.plugins.diff.ReviewComposer
import dev.supermux.editor.plugins.diff.ReviewThread
import dev.supermux.editor.plugins.diff.UnifiedPatch
import dev.supermux.net.ReviewComment
import dev.supermux.net.WalkthroughStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import dev.supermux.editor.plugins.diff.ReviewComment as ThreadComment

/** Where a file's composer drafts are kept, by 1-based line (the walkthrough's per-anchor state, the Changes pane's map). */
interface ReviewDrafts {
    fun get(line: Int): String
    fun set(line: Int, text: String)
    fun clear(line: Int)
}

/** Drafts that live as long as their holder (the Changes pane's file section). */
class MapReviewDrafts : ReviewDrafts {
    private val map = HashMap<Int, String>()
    override fun get(line: Int): String = map[line].orEmpty()
    override fun set(line: Int, text: String) { map[line] = text }
    override fun clear(line: Int) { map.remove(line) }
}

/** A walkthrough's drafts: [WalkthroughState]'s, keyed by anchor, so they survive a revision swap. */
class WalkthroughDrafts(private val state: WalkthroughState, private val repo: String, private val path: String, private val side: String) : ReviewDrafts {
    private fun anchor(line: Int) = CommentAnchor(repo, path, side, line)
    override fun get(line: Int): String = state.draft(anchor(line))
    override fun set(line: Int, text: String) = state.setDraft(anchor(line), text)
    override fun clear(line: Int) = state.clearDraft(anchor(line))
}

/**
 * [DiffHost] for one file's review: the composer's comment ([submit], with the working line's text
 * as the anchor context), replies ([reply], by the thread's root), resolve ([resolve]), the
 * composer's draft ([drafts], restored when the same line's composer opens again), paging ([page],
 * the walkthrough's steps) and a reverted hunk ([revert], the Changes pane's save). The network
 * calls run on [scope]; each returns whether the broker took it.
 */
class FileReviewHost(
    val repo: String,
    val path: String,
    private val scope: CoroutineScope,
    private val lineText: (line: Int) -> String,
    private val drafts: ReviewDrafts,
    private val comments: () -> List<ReviewComment>,
    private val submit: suspend (line: Int, anchorContext: String, body: String) -> Boolean,
    private val reply: suspend (root: ReviewComment, body: String) -> Boolean,
    private val resolve: suspend (commentId: String) -> Boolean,
    private val page: (DiffPage) -> Unit = {},
    private val revert: (DiffHunk) -> Unit = {},
) : DiffHost {
    /** The view whose composer the host restores a draft into (set once the view exists). */
    var target: CommandTarget? = null

    /** A post is in flight (a host can dim its controls). */
    var submitting: Boolean by mutableStateOf(false)
        private set

    private var composerLine: Int? = null

    override fun onCommentSubmit(line: Int, text: String) {
        val wire = line + 1
        composerLine = null
        drafts.set(wire, text)
        val body = text.trim()
        if (body.isEmpty()) return
        val context = lineText(line)
        scope.launch {
            submitting = true
            val ok = try { submit(wire, context, body) } finally { submitting = false }
            if (ok) drafts.clear(wire)
        }
    }

    override fun onReply(threadId: String, text: String) {
        val root = comments().firstOrNull { it.id == threadId } ?: return
        val body = text.trim()
        if (body.isEmpty()) return
        scope.launch {
            submitting = true
            try { reply(root, body) } finally { submitting = false }
        }
    }

    override fun onResolve(threadId: String) {
        scope.launch { resolve(threadId) }
    }

    override fun onComposerOpen(line: Int) {
        composerLine = line
        val kept = drafts.get(line + 1)
        if (kept.isNotEmpty()) target?.let { Review.setComposer(it, ReviewComposer(line, kept)) }
    }

    override fun onComposerDraft(line: Int, text: String) {
        composerLine = line
        drafts.set(line + 1, text)
    }

    override fun onComposerClosed() {
        composerLine?.let { drafts.clear(it + 1) }
        composerLine = null
    }

    override fun onDiffPage(direction: DiffPage) = page(direction)

    override fun onRevert(hunk: DiffHunk) = revert(hunk)
}

/**
 * The broker's review comments on [repo]/[path] as the plugin's threads: roots with their replies
 * (root first), on the CURRENT new-side line, 0-based.
 */
fun reviewThreads(comments: List<ReviewComment>, repo: String, path: String): List<ReviewThread> {
    val forFile = comments.filter { it.repo == repo && it.path == path }
    return forFile.filter { it.parentId == null }.map { root ->
        ReviewThread(
            id = root.id,
            line = ((root.currentLine ?: root.anchorLine) - 1).coerceAtLeast(0),
            resolved = root.status == "resolved",
            comments = (listOf(root) + forFile.filter { it.parentId == root.id }).map { c ->
                ThreadComment(id = c.id, author = c.author.ifEmpty { "user" }, body = c.body)
            },
        )
    }
}

/** A walkthrough step as the plugin's slice: its 1-based range as 0-based lines, 20 lines of context, read-only; `not_in_diff` plain. */
fun walkthroughDiffConfig(step: WalkthroughStep, lineCount: Int): DiffConfig {
    val start = (step.rangeStart ?: step.anchorLine ?: 1).coerceAtLeast(1)
    val end = (step.rangeEnd ?: start).coerceAtLeast(start)
    val last = (lineCount - 1).coerceAtLeast(0)
    return DiffConfig(
        context = 20,
        editable = false,
        range = (start - 1).coerceAtMost(last)..(end - 1).coerceAtMost(last),
        plain = step.anchorStatus == "not_in_diff",
    )
}

/**
 * The base a file's diff is drawn against: its unified [patch] applied in reverse to the [working]
 * copy (`UnifiedPatch.base`), or the working copy itself when there is no patch or it does not fit
 * (a file that changed since the diff was fetched: no changes shown rather than wrong ones).
 */
fun diffBase(working: String, patch: String?): String {
    if (patch.isNullOrBlank()) return working
    return runCatching { UnifiedPatch.base(working, patch.replace("\r\n", "\n")) }.getOrElse { working }
}

/**
 * The `@@` header of the hunk holding new-side [line] (1-based) in [patch], else the nearest one
 * above it (a line the user expanded into), else "". The Changes pane's comments carry it
 * (`AddCommentBody.diffHunkHeader`), as the rows' `+` composer did.
 */
fun hunkHeaderFor(patch: String, line: Int): String {
    var header = ""
    var best = ""
    for (l in parseDiffLines(patch)) {
        if (l.type == DiffLineType.Hunk) {
            header = l.content
            val start = Regex("\\+(\\d+)").find(l.content)?.groupValues?.get(1)?.toIntOrNull() ?: continue
            if (start <= line) best = l.content
            continue
        }
        if (l.newLine == line) return header
    }
    return best
}

/** A diff file's path in the workdir: [path] inside [repo] (a blank repo is the workdir itself). */
fun repoPath(repo: String, path: String): String = if (repo.isBlank()) path else "$repo/$path"

/** A reply to [root]'s thread, on the root's current line (as the walkthrough's replies are). */
fun replyBody(root: ReviewComment, body: String, deliver: String? = null): dev.supermux.net.AddCommentBody =
    dev.supermux.net.AddCommentBody(
        repo = root.repo, path = root.path, side = root.side,
        anchorLine = root.currentLine ?: root.anchorLine,
        anchorContext = root.anchorContext, body = body,
        deliver = deliver, parentId = root.id,
    )
