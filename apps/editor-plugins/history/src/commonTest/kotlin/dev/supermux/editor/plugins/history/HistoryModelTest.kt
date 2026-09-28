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
        val rnd = Random(20260928)
        var cases = 0
        repeat(3000) { case ->
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
            repeat(rnd.nextInt(5, 25)) {
                now += 1000 // no two local edits join: every one is its own step in both
                val len = st.doc.length
                val op = rnd.nextInt(10)
                try {
                    when (op) {
                        in 0..2 -> {
                            val c = randomChange(len, 3)
                            val cs = ChangeSet.of(len, listOf(c))
                            if (!cs.isEmpty) {
                                log += "local $c"
                                model.local(cs)
                                target.dispatch(TransactionSpec(changeSet = cs, userEvent = if (c.insert.isEmpty()) "delete.backward" else "input"))
                            }
                        }
                        3, 4 -> { log += "undo"; val a = model.pop(true); val b = History.undo.run(target); assertEquals(a, b, "undo availability") }
                        5 -> { log += "redo"; val a = model.pop(false); val b = History.redo.run(target); assertEquals(a, b, "redo availability") }
                        else -> {
                            val c = randomChange(len, 8)
                            val cs = ChangeSet.of(len, listOf(c))
                            log += "agent $c"
                            model.remote(cs)
                            target.dispatch(TransactionSpec(changeSet = cs, userEvent = "agent"))
                        }
                    }
                } catch (e: Throwable) {
                    fail("case $case threw $e after ${log.joinToString("; ")} on '$start'")
                }
                assertEquals(model.doc.toString(), st.doc.toString(), "case $case diverged after ${log.joinToString("; ")} on '$start'")
            }
            cases++
        }
        assertEquals(3000, cases)
    }
}
