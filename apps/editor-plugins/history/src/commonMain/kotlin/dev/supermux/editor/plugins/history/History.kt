package dev.supermux.editor.plugins.history

import dev.supermux.editor.compose.EditorAnnotations
import dev.supermux.editor.core.AnnotationType
import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.Command
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Extension
import dev.supermux.editor.core.Facet
import dev.supermux.editor.core.KeyBinding
import dev.supermux.editor.core.NamedCommand
import dev.supermux.editor.core.StateEffect
import dev.supermux.editor.core.StateField
import dev.supermux.editor.core.Transaction
import dev.supermux.editor.core.TransactionSpec
import dev.supermux.editor.core.commandsFacet
import dev.supermux.editor.core.extensionOf
import dev.supermux.editor.core.keymapFacet
import kotlin.time.TimeSource

/**
 * How [History] groups and how deep it goes.
 *
 * @param depth events kept per branch (undo and redo), the oldest dropped first.
 * @param newGroupDelay ms: consecutive typing (or deleting) at adjacent positions within this joins one step.
 * @param clock ms, monotonic; tests pass a fake one.
 */
data class HistoryConfig(
    val depth: Int = 100,
    val newGroupDelay: Long = 500,
    val clock: () -> Long = ::monotonicMillis,
) {
    init { require(depth >= 1) { "history depth must be at least 1" } }
}

private val origin = TimeSource.Monotonic.markNow()

/** Milliseconds since the process started, never going back. */
fun monotonicMillis(): Long = origin.elapsedNow().inWholeMilliseconds

/**
 * Undo / redo: CM6's `@codemirror/commands` history, in Kotlin.
 *
 * - A state field holds two branches (done, undone) of events: each an inverted [ChangeSet], the
 *   effects that ask to be undone ([invertedEffects]), the selection before it, and the selections
 *   the user moved through after it (for [undoSelection]).
 * - **Grouping.** Typing (`input`, `input.type*`, `input.ime*`) and deleting (`delete.backward*`,
 *   `delete.forward*`) join the previous step when it was typing or deleting too, came within
 *   [HistoryConfig.newGroupDelay], touches it, and no cursor move happened in between. A newline
 *   starts a step (the line typed after it joins it, as in CM6). An IME composition is always one
 *   step: a step with [EditorAnnotations.imeJoinPrevious], and every `input.ime` step after another,
 *   joins whatever the delay. Paste, drop and every other command are steps of their own, and so is
 *   anything without a userEvent.
 * - **Not ours to undo:** userEvents `disk`, `remote`, `agent`, `lsp` (and their sub-events),
 *   [EditorAnnotations.remote], and [addToHistory] false. Every event is MAPPED through such a
 *   change ([ChangeSet.map], operational transform), so a later undo still undoes the local edit
 *   where it now is; an event whose changes were all deleted by it is dropped.
 * - A selection-only transaction is no step, but is remembered for [undoSelection] (`Mod-u`).
 * - Undo / redo transactions carry userEvent `undo` / `redo` (the surface's atomic-range rules let
 *   them through) and scroll into view.
 */
object History {
    /** On a transaction: false keeps it out of the history (it is mapped over, like a remote edit). */
    val addToHistory: AnnotationType<Boolean> = AnnotationType("history.add")

    /** editor-core's `invertedEffectsFacet` (CM6's `invertedEffects`), where plugins register. */
    val invertedEffects: Facet<(Transaction) -> List<StateEffect<*>>, List<(Transaction) -> List<StateEffect<*>>>> get() = dev.supermux.editor.core.invertedEffectsFacet

    private val config: Facet<HistoryConfig, HistoryConfig> = Facet.first("history.config", HistoryConfig())

    private enum class Side { DONE, UNDONE }

    /** On an undo / redo: which branch it came from, that branch without the event, and the selection to redo to. */
    private class FromHistory(val side: Side, val rest: List<HistEvent>, val selection: EditorSelection)

    private val fromHistory = AnnotationType<FromHistory>("history.from")

    /** One step. [changes] null: only selections (a branch's first entry, before any edit). */
    internal class HistEvent(
        /** Applies to the document after the step and undoes it. */
        val changes: ChangeSet?,
        /** In the document the undo produces. */
        val effects: List<StateEffect<*>>,
        /** Where the selection was before the step (in the document the undo produces). */
        val startSelection: EditorSelection?,
        /** The selections moved through after it, oldest first (in the document after it). */
        val selectionsAfter: List<EditorSelection>,
    ) {
        fun withSelectionsAfter(s: List<EditorSelection>) = HistEvent(changes, effects, startSelection, s)
    }

    internal class HistoryState(
        val done: List<HistEvent>,
        val undone: List<HistEvent>,
        val prevTime: Long = 0,
        val prevUserEvent: String? = null,
    )

    internal val field: StateField<HistoryState> = StateField("history", { HistoryState(emptyList(), emptyList()) }, ::update)

    /** The plugin: the history field, its keymap and its named commands, with [config]. */
    fun extension(config: HistoryConfig = HistoryConfig()): Extension = extensionOf(
        field,
        this.config.of(config),
        keymapFacet.of(keymap),
        commandsFacet.of(commands),
    )

    val undo: Command = Command { t -> pop(t, Side.DONE, onlySelection = false) }
    val redo: Command = Command { t -> pop(t, Side.UNDONE, onlySelection = false) }
    /** Undo the last selection move (or, with none since the last change, the change). */
    val undoSelection: Command = Command { t -> pop(t, Side.DONE, onlySelection = true) }
    val redoSelection: Command = Command { t -> pop(t, Side.UNDONE, onlySelection = true) }

    /** CM6's historyKeymap: `Mod-z`; redo `Mod-y` (Apple: `Mod-Shift-z`) and `Mod-Shift-z`; `Mod-u`, `Alt-u` (Apple: `Mod-Shift-u`). */
    val keymap: List<KeyBinding> = listOf(
        KeyBinding("Mod-z", undo),
        KeyBinding("Mod-y", redo, mac = "Mod-Shift-z"),
        KeyBinding("Mod-Shift-z", redo),
        KeyBinding("Mod-u", undoSelection),
        KeyBinding("Alt-u", redoSelection, mac = "Mod-Shift-u"),
    )

    val commands: List<NamedCommand> = listOf(
        NamedCommand("history.undo", "Undo", undo),
        NamedCommand("history.redo", "Redo", redo),
        NamedCommand("history.undoSelection", "Undo Selection", undoSelection),
        NamedCommand("history.redoSelection", "Redo Selection", redoSelection),
    )

    /** How many steps [undo] can take back. */
    fun undoDepth(state: EditorState): Int = state.fieldOrNull(field)?.done?.count { it.changes != null } ?: 0

    /** How many steps [redo] can take. */
    fun redoDepth(state: EditorState): Int = state.fieldOrNull(field)?.undone?.count { it.changes != null } ?: 0

    // ------------------------------------------------------------------------------ commands --

    private fun pop(t: CommandTarget, side: Side, onlySelection: Boolean): Boolean {
        val st = t.state
        val h = st.fieldOrNull(field) ?: return false
        val branch = if (side == Side.DONE) h.done else h.undone
        val event = branch.lastOrNull() ?: return false
        val selection = event.selectionsAfter.firstOrNull() ?: st.selection
        if (onlySelection && event.selectionsAfter.isNotEmpty()) {
            t.dispatch(TransactionSpec(
                selection = event.selectionsAfter.last(),
                annotations = listOf(fromHistory.of(FromHistory(side, popSelection(branch), selection))),
                userEvent = if (side == Side.DONE) "select.undo" else "select.redo",
                scrollIntoView = true,
            ))
            return true
        }
        val changes = event.changes ?: return false
        t.dispatch(TransactionSpec(
            changeSet = changes,
            selection = event.startSelection,
            effects = event.effects,
            annotations = listOf(fromHistory.of(FromHistory(side, branch.dropLast(1), selection))),
            userEvent = if (side == Side.DONE) "undo" else "redo",
            scrollIntoView = true,
        ))
        return true
    }

    private fun popSelection(branch: List<HistEvent>): List<HistEvent> {
        val last = branch.last()
        return branch.dropLast(1) + last.withSelectionsAfter(last.selectionsAfter.dropLast(1))
    }

    // ---------------------------------------------------------------------------- the field --

    private fun update(h: HistoryState, tr: Transaction): HistoryState {
        val cfg = tr.state.facet(config)
        val from = tr.annotation(fromHistory)
        if (from != null) {
            val item = eventOf(tr, from.selection)
            var other = if (from.side == Side.DONE) h.undone else h.done
            other = if (item != null) push(other, item, cfg.depth) else addSelection(other, tr.startState.selection)
            return if (from.side == Side.DONE) HistoryState(from.rest, other) else HistoryState(other, from.rest)
        }
        if (!recorded(tr)) {
            return if (tr.docChanged) HistoryState(mapBranch(h.done, tr.changes), mapBranch(h.undone, tr.changes), h.prevTime, h.prevUserEvent) else h
        }
        val event = eventOf(tr, null)
        val userEvent = tr.annotation(Transaction.userEvent)
        if (event != null) return addChanges(h, event, cfg.clock(), userEvent, cfg, tr)
        if (tr.selectionSet && tr.selection != tr.startState.selection) {
            val time = cfg.clock()
            val last = h.done.lastOrNull()?.selectionsAfter.orEmpty()
            // A run of selection moves of the same kind (a drag's) in quick succession is one entry.
            if (last.isNotEmpty() && time - h.prevTime < cfg.newGroupDelay && userEvent != null && userEvent == h.prevUserEvent &&
                (userEvent == "select" || userEvent.startsWith("select.")) && sameShape(last.last(), tr.startState.selection)
            ) return h
            return HistoryState(addSelection(h.done, tr.startState.selection), h.undone, time, userEvent)
        }
        return h
    }

    /** False for changes that are not the local user's (see [History]). */
    private fun recorded(tr: Transaction): Boolean {
        if (tr.annotation(addToHistory) == false) return false
        if (tr.annotation(EditorAnnotations.remote) == true) return false
        return NOT_RECORDED.none { tr.isUserEvent(it) }
    }

    private val NOT_RECORDED = listOf("disk", "remote", "agent", "lsp")

    private fun eventOf(tr: Transaction, selection: EditorSelection?): HistEvent? {
        var effects: List<StateEffect<*>> = emptyList()
        for (invert in tr.startState.facet(invertedEffects)) {
            val r = invert(tr)
            if (r.isNotEmpty()) effects = effects + r
        }
        if (effects.isEmpty() && !tr.docChanged) return null
        return HistEvent(tr.changes.invert(tr.startState.doc), effects, selection ?: tr.startState.selection, emptyList())
    }

    private fun addChanges(h: HistoryState, event: HistEvent, time: Long, userEvent: String?, cfg: HistoryConfig, tr: Transaction): HistoryState {
        val last = h.done.lastOrNull()
        val lastChanges = last?.changes
        val changes = event.changes!!
        val join = lastChanges != null && !lastChanges.isEmpty && !changes.isEmpty && isAdjacent(lastChanges, changes) && (
            // An IME composition is one step, however slow.
            tr.annotation(EditorAnnotations.imeJoinPrevious) == true ||
                (userEvent.isIme() && h.prevUserEvent.isIme()) ||
                // Typing and deleting in one burst, the previous step being typing or deleting too.
                (joinable(userEvent) && joinable(h.prevUserEvent) && last.selectionsAfter.isEmpty() &&
                    time - h.prevTime < cfg.newGroupDelay && !startsLine(tr))
            )
        val done = if (join) {
            val merged = HistEvent(
                changes.compose(lastChanges!!),
                event.effects.mapNotNull { it.map(lastChanges) } + last.effects,
                last.startSelection,
                emptyList(),
            )
            h.done.dropLast(1) + merged
        } else {
            push(h.done, event, cfg.depth)
        }
        return HistoryState(done, emptyList(), time, userEvent)
    }

    private fun String?.isIme() = this != null && (this == "input.ime" || startsWith("input.ime."))

    private fun joinable(e: String?): Boolean = e != null && (
        e == "input" || e == "input.type" || e.startsWith("input.type.") || e.isIme() ||
            e == "delete.backward" || e.startsWith("delete.backward.") || e == "delete.forward" || e.startsWith("delete.forward.")
        )

    /** A newline typed (Enter, a soft Return) starts a step, as CM6's non-joinable Enter does. */
    private fun startsLine(tr: Transaction) = tr.changes.iterChanges().any { it.inserted.indexOf('\n') >= 0 }

    /**
     * Do the two inverted steps touch? [a] (the last step's inverse) applies to the document before
     * the new transaction, [b] (the new one's inverse) produces it: both ranges are in that document.
     */
    private fun isAdjacent(a: ChangeSet, b: ChangeSet): Boolean {
        val ranges = a.iterChanges()
        for (c in b.iterChanges()) for (r in ranges) if (c.toB >= r.fromA && c.fromB <= r.toA) return true
        return false
    }

    private fun sameShape(a: EditorSelection, b: EditorSelection) =
        a.ranges.size == b.ranges.size && a.ranges.indices.all { a.ranges[it].empty == b.ranges[it].empty }

    private fun push(branch: List<HistEvent>, event: HistEvent, depth: Int): List<HistEvent> {
        val out = branch + event
        // The oldest steps go; a leading selection-only entry is not a step.
        val steps = out.count { it.changes != null }
        return if (steps <= depth) out else out.drop(out.size - depth)
    }

    private fun addSelection(branch: List<HistEvent>, selection: EditorSelection): List<HistEvent> {
        val last = branch.lastOrNull() ?: return listOf(HistEvent(null, emptyList(), null, listOf(selection)))
        val sels = last.selectionsAfter.takeLast(MAX_SELECTIONS - 1)
        if (sels.isNotEmpty() && sels.last() == selection) return branch
        return branch.dropLast(1) + last.withSelectionsAfter(sels + selection)
    }

    private const val MAX_SELECTIONS = 200

    /**
     * Every event of [branch] moved through [mapping] (a change that is not ours, made to the current
     * document), newest first: each event's own changes turn the mapping into the one for the
     * document before it (`mapping.map(changes, before = true)`). An event with nothing left is dropped.
     */
    private fun mapBranch(branch: List<HistEvent>, mapping: ChangeSet): List<HistEvent> {
        if (branch.isEmpty()) return branch
        var m = mapping
        val out = ArrayList<HistEvent>(branch.size)
        for (e in branch.asReversed()) {
            val sels = e.selectionsAfter.map { it.map(m) }
            val changes = e.changes
            if (changes == null) { out += HistEvent(null, emptyList(), null, sels); continue }
            val mapped = changes.map(m)
            val before = m.map(changes, before = true)
            val effects = e.effects.mapNotNull { it.map(before) }
            val start = e.startSelection?.map(before)
            m = before
            if (mapped.isEmpty && effects.isEmpty()) continue
            out += HistEvent(mapped, effects, start, sels)
        }
        out.reverse()
        return out
    }
}

/** [History.extension]. */
fun history(config: HistoryConfig = HistoryConfig()): Extension = History.extension(config)
