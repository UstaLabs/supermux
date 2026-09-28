package dev.supermux.editor.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.supermux.editor.compose.EditorView
import dev.supermux.editor.plugins.diff.Diff
import dev.supermux.editor.plugins.diff.DiffHost
import dev.supermux.editor.plugins.diff.DiffHunk
import dev.supermux.editor.plugins.diff.DiffPage
import dev.supermux.editor.plugins.diff.Review
import dev.supermux.editor.plugins.diff.ReviewComment
import dev.supermux.editor.plugins.diff.ReviewComposer
import dev.supermux.editor.plugins.diff.ReviewThread

/**
 * The diff demos' host (M4d): the review threads and the composer drafts kept in memory, as M5's
 * `:ui` keeps them (its walkthrough state), and the thread list pushed back after every change.
 * A comment or a reply gets an "agent" answer, so a thread grows like a real one.
 */
class SampleReviewHost(initial: List<ReviewThread> = emptyList()) : DiffHost {
    /** The view the threads are on (the working copy). */
    var view: EditorView? = null
        set(v) { field = v; push() }

    var threads: List<ReviewThread> = initial
        private set

    /** What the host last heard (the status line). */
    var note: String by mutableStateOf("")

    private val drafts = HashMap<Int, String>()
    private var next = 1

    private fun push() { view?.let { Review.setThreads(it, threads) } }

    override fun onCommentSubmit(line: Int, text: String) {
        val id = "s${next++}"
        threads = threads + ReviewThread(id, line, comments = listOf(ReviewComment("$id-1", "user", text, "now"), ReviewComment("$id-2", "agent", "Noted — I'll look at line ${line + 1}.", "now")))
        drafts.remove(line)
        note = "commented on line ${line + 1}"
        push()
    }

    override fun onReply(threadId: String, text: String) {
        threads = threads.map { t -> if (t.id == threadId) t.copy(comments = t.comments + ReviewComment("${t.id}-${t.comments.size + 1}", "user", text, "now")) else t }
        note = "replied"
        push()
    }

    override fun onResolve(threadId: String) {
        threads = threads.map { t -> if (t.id == threadId) t.copy(resolved = true) else t }
        note = "resolved"
        push()
    }

    override fun onComposerOpen(line: Int) {
        note = "composer on line ${line + 1}"
        val kept = drafts[line] ?: return
        view?.let { Review.setComposer(it, ReviewComposer(line, kept)) }
    }

    override fun onComposerDraft(line: Int, text: String) { drafts[line] = text }

    override fun onComposerClosed() { note = "composer closed" }

    override fun onRevert(hunk: DiffHunk) { note = "reverted the change at line ${hunk.bFrom + 1}" }

    /** The walkthrough's step paging (the sample's two demo steps; M5's host pages its real ones). */
    var onPage: (DiffPage) -> Unit = {}

    override fun onDiffPage(direction: DiffPage) { note = "page ${direction.name.lowercase()}"; onPage(direction) }

    companion object {
        /** The walkthrough's two steps (0-based working-copy lines): the inserted run, the changed run. */
        val DEMO_STEPS: List<IntRange> = listOf(96..98, 148..151)

        /** The walkthrough's two threads, on the demo's inserted and changed runs (fakeWorkingCopy). */
        fun demoThreads(): List<ReviewThread> = listOf(
            ReviewThread("w1", 96, comments = listOf(
                ReviewComment("w1-1", "user", "Why three lines here? One comment would do.", "10:02"),
                ReviewComment("w1-2", "agent", "They mark where the working copy grew; the demo needs an inserted run.", "10:04"),
            )),
            ReviewThread("w2", 148, resolved = true, comments = listOf(
                ReviewComment("w2-1", "user", "This run changed length: does the alignment hold?", "10:10"),
                ReviewComment("w2-2", "agent", "Yes, the linked views pad from measured heights.", "10:11"),
            )),
        )
    }
}

/** One status-line part for a diff view: its changes and whether a background diff runs. */
fun diffStatus(view: EditorView): String {
    val m = Diff.model(view.state) ?: return ""
    val n = m.hunks.size
    return " · diff $n ${if (n == 1) "change" else "changes"}" + if (!Diff.idle(view.state)) " (diffing…)" else ""
}
