package dev.supermux.desktop.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import dev.supermux.net.ReviewComment
import dev.supermux.net.Walkthrough
import dev.supermux.net.WalkthroughStep

/** Per-session walkthrough UI state. Mutable maps are deliberately keyed by stable code anchors so
 * drafts and scroll survive a replacement walkthrough whose step indices or ids changed. */
class WalkthroughState(val sessionId: String) {
    var walkthrough by mutableStateOf<Walkthrough?>(null)
        private set
    var stepIndex by mutableStateOf(0)
        private set
    var loading by mutableStateOf(false)
        private set
    var isOpen by mutableStateOf(false)
        private set
    var comments by mutableStateOf<List<ReviewComment>>(emptyList())
        private set
    var unreadReplies by mutableStateOf(0)
        private set
    var unreadStepId by mutableStateOf<String?>(null)
        private set
    var updatedStepIndex by mutableStateOf<Int?>(null)
        private set

    private val drafts = mutableStateMapOf<CommentAnchor, String>()
    private val scrollOffsets = mutableStateMapOf<CommentAnchor, Int>()
    private var requestedStepId: String? = null

    val steps: List<WalkthroughStep> get() = walkthrough?.steps.orEmpty().sortedBy { it.ord }
    val currentStep: WalkthroughStep? get() = steps.getOrNull(stepIndex)
    val currentAnchor: CommentAnchor? get() = currentStep?.commentAnchor()

    suspend fun load(fetch: suspend () -> Walkthrough?) {
        val before = walkthrough
        loading = true
        val result = fetch()
        loading = false
        if (result != null) {
            val current = walkthrough
            // A websocket update may land while the snapshot request is in flight. Only apply the
            // response when no live update raced it, or when it is provably newer than that update.
            if (current === before || result.revision > (current?.revision ?: Int.MIN_VALUE)) {
                applyWalkthrough(result)
            }
        }
    }

    fun applyWalkthrough(next: Walkthrough) {
        val old = walkthrough
        if (old != null && next.revision < old.revision) return
        val selectedId = currentStep?.id
        updatedStepIndex = if (old != null && old != next) {
            val oldById = old.steps.associateBy { it.id }
            val changed = next.steps.sortedBy { it.ord }.indexOfFirst { step ->
                val prior = oldById[step.id]
                prior == null || prior != step
            }
            changed.takeIf { it >= 0 } ?: stepIndex.coerceIn(0, next.steps.lastIndex.coerceAtLeast(0))
        } else null
        if (old != null) {
            val oldById = old.steps.associateBy { it.id }
            next.steps.forEach { nextStep ->
                val oldAnchor = oldById[nextStep.id]?.commentAnchor()
                val nextAnchor = nextStep.commentAnchor()
                if (oldAnchor != null && nextAnchor != null && oldAnchor != nextAnchor) {
                    drafts[oldAnchor]?.let { if (nextAnchor !in drafts) drafts[nextAnchor] = it }
                    scrollOffsets[oldAnchor]?.let { if (nextAnchor !in scrollOffsets) scrollOffsets[nextAnchor] = it }
                }
            }
        }
        walkthrough = next.copy(steps = next.steps.sortedBy { it.ord })
        val selected = steps.indexOfFirst { it.id == (requestedStepId ?: selectedId) }
        stepIndex = when {
            steps.isEmpty() -> 0
            selected >= 0 -> selected
            else -> stepIndex.coerceIn(0, steps.lastIndex)
        }
        if (selected >= 0) requestedStepId = null
    }

    fun applyComment(comment: ReviewComment) {
        val wasNew = comments.none { it.id == comment.id }
        comments = if (!wasNew) {
            comments.map { if (it.id == comment.id) comment else it }
        } else comments + comment
        if (wasNew && !isOpen && comment.author == "agent" && comment.parentId != null) {
            unreadReplies += 1
            val root = comments.firstOrNull { it.id == comment.parentId }
            val line = root?.let { it.currentLine ?: it.anchorLine } ?: (comment.currentLine ?: comment.anchorLine)
            unreadStepId = steps.firstOrNull { step ->
                step.repo == comment.repo && step.path == comment.path &&
                    line in (step.rangeStart ?: step.anchorLine ?: line)..(step.rangeEnd ?: step.anchorLine ?: line)
            }?.id
        }
    }

    fun seedComments(value: List<ReviewComment>) {
        // A GET can race a newer review_comment frame. Seed the snapshot first, then let already
        // reduced live values win by id so the fetch can never erase a reply or resolution.
        comments = (value + comments).associateBy { it.id }.values.toList()
    }
    fun next() = goTo(stepIndex + 1)
    fun previous() = goTo(stepIndex - 1)
    fun goTo(index: Int) { if (steps.isNotEmpty()) stepIndex = index.coerceIn(0, steps.lastIndex) }

    fun open(stepId: String? = null) {
        isOpen = true
        unreadReplies = 0
        unreadStepId = null
        requestedStepId = stepId
        stepId?.let { id ->
            steps.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { index ->
                goTo(index)
                requestedStepId = null
            }
        }
    }

    fun close() { isOpen = false }
    fun clearUpdatedBadge() { updatedStepIndex = null }
    fun draft(anchor: CommentAnchor): String = drafts[anchor].orEmpty()
    fun setDraft(anchor: CommentAnchor, value: String) { drafts[anchor] = value }
    fun clearDraft(anchor: CommentAnchor) { drafts.remove(anchor) }
    fun scroll(anchor: CommentAnchor): Int = scrollOffsets[anchor] ?: 0
    fun setScroll(anchor: CommentAnchor, value: Int) { scrollOffsets[anchor] = value }
}

data class CommentAnchor(
    val repo: String,
    val path: String,
    val side: String,
    val line: Int,
)

fun WalkthroughStep.commentAnchor(): CommentAnchor? {
    val file = path ?: return null
    val line = anchorLine ?: rangeStart ?: return null
    return CommentAnchor(repo, file, side, line)
}
