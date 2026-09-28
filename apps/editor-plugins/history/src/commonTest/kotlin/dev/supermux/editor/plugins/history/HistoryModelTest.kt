package dev.supermux.editor.plugins.history

import dev.supermux.editor.core.ChangeSet
import dev.supermux.editor.core.ChangeSpec
import dev.supermux.editor.core.CommandTarget
import dev.supermux.editor.core.EditorSelection
import dev.supermux.editor.core.EditorState
import dev.supermux.editor.core.Rope
import dev.supermux.editor.core.TransactionSpec
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The lazy history (only the top event mapped, the rest carried) against an EAGER model that maps
 * every event through every remote change at once, with the same drop rules: random local typing,
 * deletions, undo and redo interleaved with agent inserts, deletions and rewrites. Every step must
 * give the same document, and nothing may throw.
 */
class HistoryModelTest {
    /** The eager model: each branch a stack of inverse change sets, mapped at every remote change. */
    private class Model(var doc: Rope) {
        var done = ArrayList<ChangeSet>()
        var undone = ArrayList<ChangeSet>()

        fun local(cs: ChangeSet) {
            done.add(cs.invert(doc)); undone.clear(); doc = cs.apply(doc)
        }

        fun remote(cs: ChangeSet) {
            done = map(done, cs); undone = map(undone, cs); doc = cs.apply(doc)
        }

        fun pop(fromDone: Boolean): Boolean {
            val from = if (fromDone) done else undone
            val to = if (fromDone) undone else done
            val inv = from.removeLastOrNull() ?: return false
            to.add(inv.invert(doc))
            doc = inv.apply(doc)
            return true
        }

        private fun map(branch: List<ChangeSet>, remote: ChangeSet): ArrayList<ChangeSet> {
            // Every event at once, top down, at the moment the remote change arrives, with the same
            // per-mapping drop rules and the same pending sequence (a dropped step's forward change).
            var ms = listOf(History.Pending(remote, remote = true))
            val out = ArrayList<ChangeSet>()
            for (e in branch.asReversed()) {
                val step = History.mapThrough(e, ms)
                ms = if (step.droppedAt < 0) step.befores
                else step.befores.subList(0, if (step.inside) step.droppedAt else step.droppedAt + 1) +
                    (if (step.inside) listOf(History.Pending(History.forwardDesc(step.at), remote = false)) else emptyList()) +
                    ms.subList(if (step.inside) step.droppedAt else step.droppedAt + 1, ms.size)
                if (step.droppedAt < 0 && !step.mapped.isEmpty) out.add(step.mapped)
            }
            out.reverse()
            return out
        }
    }

    @Test fun lazyHistoryAgreesWithTheEagerModel() {
        assertEquals(3000, run(Random(20260928), cases = 3000, ops = 5..24, agentShare = 0.4))
    }

    /**
     * Long and agent-heavy: 400 operations, about 85 % agent edits, so the steps nobody undoes carry
     * well past MAX_PENDING (64) mappings and those get COMPOSED into one. The merged change is then
     * judged by the drop rule as one remote change (its deletions merged: two adjacent deletions
     * become one wide "rewrite"; a deletion and a later insertion at the same place cancel out), so
     * the lazy history may keep a step the eager model drops, or drop one it keeps: the model's
     * verdicts are no longer the reference past the cap. What must hold anyway: nothing throws,
     * every undo applies to the current document, and undo then redo (redo then undo) gives the
     * document back exactly. The divergences from the eager model are counted and printed.
     */
    @Test fun aLongAgentHeavyRunPastTheMappingCapNeverBreaks() {
        val rnd = Random(4400)
        var diverged = 0
        var undos = 0
        repeat(40) { case ->
            var now = 0L
            val start = buildString { repeat(rnd.nextInt(0, 30)) { append("abcdefgh\n"[rnd.nextInt(9)]) } }
            var st = EditorState.create(start, EditorSelection.cursor(0), History.extension(HistoryConfig(clock = { now })))
            val target = object : CommandTarget {
                override val state get() = st
                override fun dispatch(spec: TransactionSpec) { st = st.update(spec).state }
            }
            val model = Model(Rope.of(start))
            var agrees = true
            val log = ArrayList<String>()
            fun randomChange(len: Int, maxDelete: Int): ChangeSpec {
                val from = rnd.nextInt(0, len + 1)
                val to = minOf(len, from + rnd.nextInt(0, maxDelete + 1))
                val ins = if (rnd.nextBoolean() || to == from) "XYZ".take(rnd.nextInt(1, 4)) else ""
                return ChangeSpec(from, to, ins)
            }
            // Runs [first] on a copy of the state, then [second]: the document must come back.
            fun roundTrips(first: dev.supermux.editor.core.Command, second: dev.supermux.editor.core.Command): Boolean {
                val before = st
                val doc = st.doc.toString()
                if (!first.run(target)) { st = before; return true }
                val ok = !second.run(target) || st.doc.toString() == doc
                st = before
                return ok
            }
            repeat(400) {
                now += 1000
                val len = st.doc.length
                val op = rnd.nextInt(20)
                try {
                    when {
                        op < 17 -> {
                            val cs = ChangeSet.of(len, listOf(randomChange(len, 8)))
                            log += "agent"
                            model.remote(cs)
                            target.dispatch(TransactionSpec(changeSet = cs, userEvent = "agent"))
                        }
                        op == 17 -> {
                            val c = randomChange(len, 3)
                            val cs = ChangeSet.of(len, listOf(c))
                            if (!cs.isEmpty) {
                                log += "local"
                                model.local(cs)
                                target.dispatch(TransactionSpec(changeSet = cs, userEvent = if (c.insert.isEmpty()) "delete.backward" else "input"))
                            }
                        }
                        else -> {
                            val undo = op == 18
                            log += if (undo) "undo" else "redo"
                            val a = model.pop(undo)
                            assertTrue(roundTrips(if (undo) History.undo else History.redo, if (undo) History.redo else History.undo), "case $case: undo/redo did not round-trip")
                            val b = (if (undo) History.undo else History.redo).run(target)
                            if (b) undos++
                            if (a != b) agrees = false
                        }
                    }
                } catch (e: Throwable) {
                    fail("case $case threw $e after ${log.takeLast(40).joinToString("; ")} on '$start'")
                }
                if (model.doc.toString() != st.doc.toString()) agrees = false
            }
            if (!agrees) diverged++
        }
        println("HistoryModelTest: 40 long agent-heavy runs, $undos undos/redos applied, $diverged diverged from the eager model past the mapping cap")
        assertTrue(undos > 0)
    }

    /** [cases] random runs of [ops] operations, [agentShare] of them agent edits; returns the runs done. */
    private fun run(rnd: Random, cases: Int, ops: IntRange, agentShare: Double): Int {
        var done = 0
        repeat(cases) { case ->
            var now = 0L
            val start = buildString { repeat(rnd.nextInt(0, 30)) { append("abcdefgh\n"[rnd.nextInt(9)]) } }
            var st = EditorState.create(start, EditorSelection.cursor(0), History.extension(HistoryConfig(clock = { now })))
            val target = object : CommandTarget {
                override val state get() = st
                override fun dispatch(spec: TransactionSpec) { st = st.update(spec).state }
            }
            val model = Model(Rope.of(start))
            val log = ArrayList<String>()
            fun randomChange(len: Int, maxDelete: Int): ChangeSpec {
                val from = rnd.nextInt(0, len + 1)
                val to = minOf(len, from + rnd.nextInt(0, maxDelete + 1))
                val ins = if (rnd.nextBoolean() || to == from) "XYZ".take(rnd.nextInt(1, 4)) else ""
                return ChangeSpec(from, to, ins)
            }
            repeat(rnd.nextInt(ops.first, ops.last + 1)) {
                now += 1000 // no two local edits join: every one is its own step in both
                val len = st.doc.length
                val agent = rnd.nextDouble() < agentShare
                val op = rnd.nextInt(6)
                try {
                    when {
                        agent -> {
                            val c = randomChange(len, 8)
                            val cs = ChangeSet.of(len, listOf(c))
                            log += "agent $c"
                            model.remote(cs)
                            target.dispatch(TransactionSpec(changeSet = cs, userEvent = "agent"))
                        }
                        op <= 2 -> {
                            val c = randomChange(len, 3)
                            val cs = ChangeSet.of(len, listOf(c))
                            if (!cs.isEmpty) {
                                log += "local $c"
                                model.local(cs)
                                target.dispatch(TransactionSpec(changeSet = cs, userEvent = if (c.insert.isEmpty()) "delete.backward" else "input"))
                            }
                        }
                        op <= 4 -> { log += "undo"; val a = model.pop(true); val b = History.undo.run(target); assertEquals(a, b, "undo availability") }
                        else -> { log += "redo"; val a = model.pop(false); val b = History.redo.run(target); assertEquals(a, b, "redo availability") }
                    }
                } catch (e: Throwable) {
                    fail("case $case threw $e after ${log.takeLast(40).joinToString("; ")} on '$start'")
                }
                assertEquals(model.doc.toString(), st.doc.toString(), "case $case diverged after ${log.takeLast(40).joinToString("; ")} on '$start'")
            }
            done++
        }
        return done
    }
}
