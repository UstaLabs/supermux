package dev.supermux.editor.plugins.diff

import dev.supermux.editor.compose.EditorEffects
import dev.supermux.editor.compose.GutterClickHandler
import dev.supermux.editor.compose.gutterClickFacet
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.Decoration
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.FacetDep
import dev.supermux.editor.core.GutterMarker
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.RangeSet
import dev.supermux.editor.core.Ranged
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.StateEffectType
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.WidgetKey
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.decorationsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.gutterMarkersFacet

/** One comment of a review thread (today's `DiffRegionComment`). [author] "agent", "user" or a name. */
data class ReviewComment(val id: String, val author: String, val body: String, val createdAt: String = "")

/**
 * A review thread from the host (today's `DiffRegionThread`), on the working copy's 0-based [line]
 * (the host's 1-based line − 1), its [comments] root first.
 */
data class ReviewThread(val id: String, val line: Int, val resolved: Boolean = false, val comments: List<ReviewComment> = emptyList())

/**
 * An open (or restored) composer on 0-based [line] with its [draft] (today's `DiffRegionComposer`).
 * [focus]: it takes the keyboard focus when it appears (on a phone: the keyboard rises), as a
 * composer the user opens does; false for one a host shows on its own (a walkthrough step opening
 * with a kept draft).
 */
data class ReviewComposer(val line: Int, val draft: String = "", val focus: Boolean = true)

/** [canComment]: the gutter's comment column and the comment command open a composer (a host with no posting hides them). */
data class ReviewConfig(val canComment: Boolean = true)

/**
 * Review threads in a diff view (inline or the working side of a side-by-side pair): the host's
 * threads as block widgets under their lines (`review:thread`) with a 💬 marker (`comment` column),
 * resolved ones collapsed to a line until tapped, reply and resolve; the comment composer
 * (`review:composer`) opened from a line's `comment` gutter cell or [Review.comment]. Everything
 * the user does goes to the [DiffHost]; the threads follow edits of the working copy. Widgets:
 * [Review.registerWidgets].
 */
fun review(host: DiffHost? = null, config: ReviewConfig = ReviewConfig()): Extension = Review.extension(host, config)

object Review {
    const val THREAD = "review:thread"
    const val COMPOSER = "review:composer"
    const val COLUMN = "comment"

    /** A thread where it is now: [pos] is its line's start in the current document. */
    class Anchored internal constructor(val thread: ReviewThread, internal val pos: Int, internal val hostLine: Int)

    /** The open composer: its line's start [pos], its [draft] as last typed, and [gen] (a new composer, a new widget). */
    class Composer internal constructor(
        internal val pos: Int,
        val draft: String,
        internal val gen: Int,
        internal val focus: Boolean = true,
        /** The draft the host last heard (from it, or from [DiffHost.onComposerDraft]). */
        internal val reported: String = draft,
    )

    /** The review's state: the threads, the composer, the resolved threads the user expanded (per view). */
    class State internal constructor(val threads: List<Anchored>, val composer: Composer?, val expanded: Set<String>, internal val config: ReviewConfig)

    internal val setThreads = StateEffectType<List<ReviewThread>>("review.setThreads")
    internal val setComposer = StateEffectType<ReviewComposer?>("review.setComposer")
    internal val open = StateEffectType<Int>("review.open")
    internal val close = StateEffectType<Unit>("review.close")
    internal val draft = StateEffectType<String>("review.draft")
    internal val reported = StateEffectType<String>("review.reported")
    internal val toggle = StateEffectType<String>("review.toggle")
    private var gens = 0

    internal fun extension(host: DiffHost?, config: ReviewConfig): Extension {
        val f = StateField<State?>("review", { State(emptyList(), null, emptySet(), config) }, { s, tr -> s?.update(tr) })
        return extensionOf(
            stateField.of(f),
            f,
            if (host != null) diffHostFacet.of(host) else extensionOf(),
            decorationsFacet.compute(FacetDep.field(f)) { st -> st.field(f)?.let { widgets(st, it) } ?: RangeSet.empty() },
            gutterMarkersFacet.compute(FacetDep.field(f)) { st -> st.field(f)?.let { markers(st, it) } ?: RangeSet.empty() },
            // The diff keeps these lines open (a thread or the composer never hides in a folded run).
            Diff.pinnedLinesFacet.compute(FacetDep.field(f)) { st ->
                st.field(f)?.let { r -> Pins(r.threads.map { st.doc.lineIndexAt(it.pos) }, listOfNotNull(r.composer?.let { st.doc.lineIndexAt(it.pos) })) } ?: Pins()
            },
            gutterClickFacet.of(GutterClickHandler { t, column, line, _ ->
                if (column != COLUMN) return@GutterClickHandler false
                openComposer(t, line)
            }),
            commandsFacet.of(listOf(NamedCommand("review.comment", "Comment on this line", comment))),
        )
    }

    internal val stateField: dev.supermux.editor.core.Facet<StateField<State?>, StateField<State?>?> = dev.supermux.editor.core.Facet.define("review.field") { it.firstOrNull() }

    /** The review state of [state], or null when it has no review. */
    fun state(state: EditorState): State? = state.facet(stateField)?.let { state.fieldOrNull(it) }

    /** The threads, each on its current line. */
    fun threads(state: EditorState): List<Pair<ReviewThread, Int>> = state(state)?.threads.orEmpty().map { it.thread to state.doc.lineIndexAt(it.pos) }

    /** The open composer's current line and draft, or null. */
    fun composer(state: EditorState): ReviewComposer? = state(state)?.composer?.let { ReviewComposer(state.doc.lineIndexAt(it.pos), it.draft, it.focus) }

    // ---------------------------------------------------------------- the host's data --

    /**
     * The host's threads (a live update: a reply, a resolution, a new thread). A thread the view
     * already shows at the line the host gave last time stays where the edits moved it; a new one,
     * or one the host moved, goes to its [ReviewThread.line]. Never touches the diff (expanded
     * context stays).
     */
    fun setThreads(target: CommandTarget, threads: List<ReviewThread>) =
        target.dispatch(TransactionSpec(effects = listOf(setThreads.of(threads))))

    /**
     * The host's composer (today's rule): null closes it; a composer on the line of the open one keeps
     * what the user is typing (its draft is taken only while the open one is still empty); on another
     * line it opens there with its draft.
     */
    fun setComposer(target: CommandTarget, composer: ReviewComposer?) {
        // Replaced by one on another line: the old one's last typing reaches the host first.
        val open = state(target.state)?.composer
        if (open != null && composer != null && target.state.doc.lineIndexAt(open.pos) != composer.line) flushDraft(target)
        target.dispatch(TransactionSpec(effects = listOf(setComposer.of(composer))))
    }

    /** Open the composer on [line] (a gutter tap, [comment]); the host hears [DiffHost.onComposerOpen]. */
    fun openComposer(target: CommandTarget, line: Int): Boolean {
        val st = state(target.state) ?: return false
        if (!st.config.canComment) return false
        val doc = target.state.doc
        val l = line.coerceIn(0, doc.lineCount - 1)
        if (st.composer != null && doc.lineIndexAt(st.composer.pos) == l) return true
        // Another line: what was typed in the open composer reaches the host before it is replaced.
        flushDraft(target)
        // The line after the composer scrolled into view: the composer (between) is on screen too.
        val after = if (l + 1 < doc.lineCount) doc.lineStart(l + 1) else doc.length
        target.dispatch(TransactionSpec(effects = listOf(open.of(l), EditorEffects.scrollTo.of(after))))
        target.state.facet(diffHostFacet)?.onComposerOpen(l)
        return true
    }

    /** "Comment on this line": the composer at the main cursor's line. */
    val comment: Command = Command { t -> openComposer(t, t.state.doc.lineIndexAt(t.state.selection.main.head)) }

    // ---------------------------------------------------------------- what the widgets do --

    /** The composer's text changed: kept in the state at once (the host hears it debounced: [flushDraft]). */
    internal fun typed(target: CommandTarget, text: String) {
        val c = state(target.state)?.composer ?: return
        if (c.draft == text) return
        target.dispatch(TransactionSpec(effects = listOf(draft.of(text))))
    }

    /**
     * Tell the host the open composer's draft if it has not heard it yet: the widget calls this
     * ~300 ms after the typing stops and when it is disposed; paging ([Diff.page]), opening another
     * composer, a close and a submit call it first. What the host heard is kept in the state, so a
     * recreated widget never swallows (or repeats) it.
     */
    fun flushDraft(target: CommandTarget) {
        val c = state(target.state)?.composer ?: return
        if (c.draft == c.reported) return
        target.dispatch(TransactionSpec(effects = listOf(reported.of(c.draft))))
        target.state.facet(diffHostFacet)?.onComposerDraft(target.state.doc.lineIndexAt(c.pos), c.draft)
    }

    internal fun submit(target: CommandTarget, text: String): Boolean {
        flushDraft(target)
        val c = state(target.state)?.composer ?: return false
        val body = text.trim()
        if (body.isEmpty()) return false
        val line = target.state.doc.lineIndexAt(c.pos)
        target.dispatch(TransactionSpec(effects = listOf(close.of(Unit))))
        target.state.facet(diffHostFacet)?.onCommentSubmit(line, body)
        return true
    }

    internal fun cancel(target: CommandTarget) {
        if (state(target.state)?.composer == null) return
        flushDraft(target)
        target.dispatch(TransactionSpec(effects = listOf(close.of(Unit))))
        target.state.facet(diffHostFacet)?.onComposerClosed()
    }

    internal fun reply(target: CommandTarget, threadId: String, text: String): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        target.state.facet(diffHostFacet)?.onReply(threadId, body)
        return true
    }

    internal fun resolve(target: CommandTarget, threadId: String) {
        target.state.facet(diffHostFacet)?.onResolve(threadId)
    }

    /** Show or hide a resolved thread's comments (this view only). */
    fun toggleExpanded(target: CommandTarget, threadId: String) =
        target.dispatch(TransactionSpec(effects = listOf(toggle.of(threadId))))

    // ---------------------------------------------------------------- state --

    private fun lineStartAt(doc: Rope, line: Int) = doc.lineStart(line.coerceIn(0, doc.lineCount - 1))

    private fun State.update(tr: Transaction): State {
        var threads = threads
        var composer = composer
        var expanded = expanded
        val doc = tr.state.doc
        if (tr.docChanged) {
            // Each thread keeps its line: its start mapped (text typed at the start stays below the
            // line above), snapped back to a line start.
            fun map(pos: Int) = doc.lineStart(doc.lineIndexAt(tr.changes.mapPos(pos, 1).coerceIn(0, doc.length)))
            threads = threads.map { a -> Anchored(a.thread, map(a.pos), a.hostLine) }
            composer = composer?.let { Composer(map(it.pos), it.draft, it.gen, it.focus, it.reported) }
        }
        for (e in tr.effects) {
            e.valueIf(setThreads)?.let { list ->
                val old = threads.associateBy { it.thread.id }
                threads = list.map { t ->
                    val was = old[t.id]
                    if (was != null && was.hostLine == t.line) Anchored(t, was.pos, t.line) else Anchored(t, lineStartAt(doc, t.line), t.line)
                }
                expanded = expanded.filterTo(HashSet()) { id -> list.any { it.id == id } }
            }
            e.valueIf(setComposer)?.let { c ->
                composer = when {
                    composer != null && doc.lineIndexAt(composer!!.pos) == c.line ->
                        if (composer!!.draft.isEmpty() && c.draft.isNotEmpty()) Composer(composer!!.pos, c.draft, ++gens, composer!!.focus || c.focus, c.draft) else composer
                    else -> Composer(lineStartAt(doc, c.line), c.draft, ++gens, c.focus)
                }
            }
            // (valueIf is null for a null value too: the host closing the composer.)
            if (e.isOf(setComposer) && e.value == null) composer = null
            e.valueIf(open)?.let { l -> composer = Composer(lineStartAt(doc, l), "", ++gens) }
            e.valueIf(reported)?.let { d -> composer = composer?.let { Composer(it.pos, it.draft, it.gen, it.focus, d) } }
            if (e.isOf(close)) composer = null
            e.valueIf(draft)?.let { d -> composer = composer?.let { Composer(it.pos, d, it.gen, it.focus, it.reported) } }
            e.valueIf(toggle)?.let { id -> expanded = if (id in expanded) expanded - id else expanded + id }
        }
        return if (threads === this.threads && composer === this.composer && expanded === this.expanded) this else State(threads, composer, expanded, config)
    }

    // ---------------------------------------------------------------- decorations --

    private fun widgets(st: EditorState, s: State): RangeSet<Decoration> {
        val out = ArrayList<Ranged<Decoration>>()
        for (a in s.threads) {
            val t = a.thread
            val collapsed = t.resolved && t.id !in s.expanded
            val est = if (collapsed) 1.2f else 1.5f + t.comments.size * 2f + if (t.resolved) 0f else 2f
            out += Ranged(a.pos, a.pos, Decoration.BlockWidget(WidgetKey(THREAD, t.id), above = false, estimatedHeightLines = est))
        }
        s.composer?.let { c -> out += Ranged(c.pos, c.pos, Decoration.BlockWidget(WidgetKey(COMPOSER, "c${c.gen}"), above = false, estimatedHeightLines = 5f)) }
        return RangeSet.of(out)
    }

    private val PLACEHOLDER = GutterMarker(COLUMN, "comment-add")

    private fun markers(st: EditorState, s: State): RangeSet<GutterMarker> {
        val out = ArrayList<Ranged<GutterMarker>>()
        // The column exists while comments can be made (a tap on any line's cell opens the composer):
        // an invisible marker keeps it (a kind the theme does not draw).
        if (s.config.canComment) out += Ranged(0, 0, PLACEHOLDER)
        for (a in s.threads) {
            val t = a.thread
            val n = t.comments.size
            val tip = "${if (t.resolved) "resolved " else ""}review thread: $n ${if (n == 1) "comment" else "comments"}"
            out += Ranged(a.pos, a.pos, GutterMarker(COLUMN, "comment", tip))
        }
        return RangeSet.of(out)
    }

    /** The thread [id] in [state]. */
    internal fun thread(state: EditorState, id: String): ReviewThread? = state(state)?.threads?.firstOrNull { it.thread.id == id }?.thread
}
