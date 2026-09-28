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

    /**
     * One step. [changes] null: only selections (a branch's first entry, before any edit).
     *
     * Mapping over changes that are not ours is LAZY (CM6's scheme): only a branch's top event is
     * mapped; [mapped] carries what the events below it still need, and they are mapped when they
     * become the top. The remembered selections are mapped lazily too ([selMapping]).
     */
    internal class HistEvent(
        /** Applies to the document after the step and undoes it. */
        val changes: ChangeSet?,
        /** In the document the undo produces. */
        val effects: List<StateEffect<*>>,
        /** The mapping the events below this one still need (from the document before this step). */
        val mapped: ChangeSet?,
        /** Where the selection was before the step (in the document the undo produces). */
        val startSelection: EditorSelection?,
        private val rawSelections: List<EditorSelection>,
        /** Still to apply to [rawSelections]. */
        private val selMapping: ChangeSet? = null,
    ) {
        /** The selections moved through after it, oldest first (in the document after it). */
        val selectionsAfter: List<EditorSelection> by lazy { if (selMapping == null) rawSelections else rawSelections.map { it.map(selMapping) } }
        val selectionCount: Int get() = rawSelections.size

        fun withSelectionsAfter(s: List<EditorSelection>) = HistEvent(changes, effects, mapped, startSelection, s)

        /** Mapped: new changes, effects, carried mapping and start selection; the selections lazily through [m]. */
        fun remapped(changes: ChangeSet, effects: List<StateEffect<*>>, mapped: ChangeSet, start: EditorSelection?, m: ChangeSet) =
            HistEvent(changes, effects, mapped, start, rawSelections, selMapping?.compose(m) ?: m)

        /** The same, its selections still to be mapped through [m] too. */
        fun selectionsMappedBy(m: ChangeSet) = HistEvent(changes, effects, mapped, startSelection, rawSelections, selMapping?.compose(m) ?: m)
    }

    internal class HistoryState(
        val done: List<HistEvent>,
        val undone: List<HistEvent>,
        val prevTime: Long = 0,
        val prevUserEvent: String? = null,
        /** When the top step's group started (the IME join cap). */
        val groupStart: Long = 0,
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
        // The events below get what this one carried for them (CM6's lazy mapping).
        val rest = branch.dropLast(1).let { r -> event.mapped?.let { addMapping(r, it) } ?: r }
        t.dispatch(TransactionSpec(
            changeSet = changes,
            selection = event.startSelection,
            effects = event.effects,
            annotations = listOf(fromHistory.of(FromHistory(side, rest, selection))),
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
            return if (tr.docChanged) HistoryState(addMapping(h.done, tr.changes), addMapping(h.undone, tr.changes), h.prevTime, h.prevUserEvent, h.groupStart) else h
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
            return HistoryState(addSelection(h.done, tr.startState.selection), h.undone, time, userEvent, h.groupStart)
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
        return HistEvent(tr.changes.invert(tr.startState.doc), effects, null, selection ?: tr.startState.selection, emptyList())
    }

    private fun addChanges(h: HistoryState, event: HistEvent, time: Long, userEvent: String?, cfg: HistoryConfig, tr: Transaction): HistoryState {
        val last = h.done.lastOrNull()
        val lastChanges = last?.changes
        val changes = event.changes!!
        val join = lastChanges != null && !lastChanges.isEmpty && !changes.isEmpty && isAdjacent(lastChanges, changes) && (
            // An IME composition is one step past the typing delay, up to IME_GROUP_MS and a newline.
            tr.annotation(EditorAnnotations.imeJoinPrevious) == true ||
                (userEvent.isIme() && h.prevUserEvent.isIme() && time - h.groupStart < IME_GROUP_MS && !startsLine(tr)) ||
                // Typing and deleting in one burst, the previous step being typing or deleting too.
                (joinable(userEvent) && joinable(h.prevUserEvent) && last.selectionCount == 0 &&
                    time - h.prevTime < cfg.newGroupDelay && !startsLine(tr))
            )
        val done = if (join) {
            val merged = HistEvent(
                changes.compose(lastChanges!!),
                event.effects.mapNotNull { it.map(lastChanges) } + last.effects,
                last.mapped,
                last.startSelection,
                emptyList(),
            )
            h.done.dropLast(1) + merged
        } else {
            push(h.done, event, cfg.depth)
        }
        return HistoryState(done, emptyList(), time, userEvent, if (join) h.groupStart else time)
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
        val last = branch.lastOrNull() ?: return listOf(HistEvent(null, emptyList(), null, null, listOf(selection)))
        val sels = last.selectionsAfter.takeLast(MAX_SELECTIONS - 1)
        if (sels.isNotEmpty() && sels.last() == selection) return branch
        return branch.dropLast(1) + last.withSelectionsAfter(sels + selection)
    }

    private const val MAX_SELECTIONS = 200

    /** An IME composition joins one step for at most this long (then, or at a newline, a new step). */
    private const val IME_GROUP_MS = 2_000L

    /**
     * [branch] after [mapping] (a change that is not ours, made to the current document), CM6's
     * addMappingToBranch: only the TOP event is mapped (its changes, effects and start selection;
     * its remembered selections lazily); what the events below it need is carried in its `mapped`.
     * A top event with nothing left (its text deleted, or everything it would restore lying inside
     * text the change deleted: [inDeletedText]) is dropped and the next one mapped instead.
     */
    private fun addMapping(branch: List<HistEvent>, mapping: ChangeSet): List<HistEvent> {
        var m = mapping
        var length = branch.size
        while (length > 0) {
            val e = branch[length - 1]
            val changes = e.changes ?: return branch.subList(0, length - 1) + e.selectionsMappedBy(m)
            val mapped = changes.map(m)
            val before = m.map(changes, before = true)
            val full = e.mapped?.compose(before) ?: before
            val effects = e.effects.mapNotNull { it.map(before) }
            val dropped = (mapped.isEmpty && effects.isEmpty()) || (effects.isEmpty() && inDeletedText(changes, m))
            if (!dropped) {
                return branch.subList(0, length - 1) + e.remapped(mapped, effects, full, e.startSelection?.map(before), m)
            }
            m = full
            length--
        }
        return emptyList()
    }

    /**
     * Does every change of [event] (an undo step, in [remote]'s input document) lie inside text
     * [remote] deleted or rewrote? Then undoing it would put text back into the middle of someone
     * else's output (an agent's rewrite of the region): the step is dropped instead.
     */
    private fun inDeletedText(event: ChangeSet, remote: ChangeSet): Boolean {
        val deleted = remote.iterChanges().filter { it.toA > it.fromA }
        if (deleted.isEmpty()) return false
        val own = event.iterChanges()
        return own.isNotEmpty() && own.all { c ->
            deleted.any { d -> if (c.fromA == c.toA) d.fromA < c.fromA && c.fromA < d.toA else d.fromA <= c.fromA && c.toA <= d.toA }
        }
    }
}

/** [History.extension]. */
fun history(config: HistoryConfig = HistoryConfig()): Extension = History.extension(config)
