package dev.supermux.editor.core

/** One requested replacement, in coordinates of the document BEFORE the change. */
data class ChangeSpec(val from: Int, val to: Int = from, val insert: String = "") {
    init { require(from in 0..to) { "invalid change range $from..$to" } }
}

/** One concrete change as [ChangeSet.iterChanges] reports it: [fromA, toA) in the old doc became [fromB, toB) in the new. */
data class Change(val fromA: Int, val toA: Int, val fromB: Int, val toB: Int, val inserted: String)

/**
 * A set of changes to a document, as a normalized sequence of retain / delete / insert ops.
 *
 * Normal form: no empty ops, no two adjacent ops of the same kind, and at any one position a
 * delete comes BEFORE an insert. Two change sets with the same effect are therefore equal.
 *
 * This one type carries undo ([invert]), history merging ([compose]) and position tracking
 * ([mapPos]): diagnostics, review comments and cursors all move through edits with it.
 */
class ChangeSet internal constructor(internal val ops: List<Op>) {
    internal sealed class Op {
        data class Retain(val n: Int) : Op()
        data class Delete(val n: Int) : Op()
        data class Insert(val text: String) : Op()
    }

    /** Length of the document this applies to. */
    val lengthBefore: Int = ops.sumOf { when (it) { is Op.Retain -> it.n; is Op.Delete -> it.n; is Op.Insert -> 0 } }
    /** Length of the document it produces. */
    val lengthAfter: Int = ops.sumOf { when (it) { is Op.Retain -> it.n; is Op.Delete -> 0; is Op.Insert -> it.text.length } }

    /** True when applying this changes nothing. */
    val isEmpty: Boolean get() = ops.all { it is Op.Retain }

    /** Every contiguous change, in document order. */
    fun iterChanges(): List<Change> {
        val out = ArrayList<Change>()
        var a = 0; var b = 0
        var i = 0
        while (i < ops.size) {
            when (val op = ops[i]) {
                is Op.Retain -> { a += op.n; b += op.n; i++ }
                else -> {
                    val fromA = a; val fromB = b
                    val ins = StringBuilder()
                    while (i < ops.size && ops[i] !is Op.Retain) {
                        when (val o = ops[i]) {
                            is Op.Delete -> a += o.n
                            is Op.Insert -> { ins.append(o.text); b += o.text.length }
                            is Op.Retain -> Unit
                        }
                        i++
                    }
                    out += Change(fromA, a, fromB, b, ins.toString())
                }
            }
        }
        return out
    }

    fun apply(doc: Rope): Rope {
        require(doc.length == lengthBefore) { "change set for length $lengthBefore applied to length ${doc.length}" }
        var result = doc
        // Back to front: earlier positions stay valid while later ones are replaced.
        for (c in iterChanges().asReversed()) result = result.replace(c.fromA, c.toA, c.inserted)
        return result
    }

    fun apply(doc: String): String = apply(Rope.of(doc)).toString()

    /** The change set that undoes this one; [doc] is the document this was applied TO. */
    fun invert(doc: Rope): ChangeSet {
        require(doc.length == lengthBefore) { "invert needs the original document" }
        // Walks the NEW document: the builder's lengthBefore is the position reached in it so far.
        val b = Builder()
        for (c in iterChanges()) {
            b.retain(c.fromB - b.lengthBefore)
            b.delete(c.toB - c.fromB)
            b.insert(doc.slice(c.fromA, c.toA))
        }
        b.retain(lengthAfter - b.lengthBefore)
        return b.build()
    }

    /** This change set followed by [other] (which must apply to this one's result), as one. */
    fun compose(other: ChangeSet): ChangeSet {
        require(lengthAfter == other.lengthBefore) { "compose: $lengthAfter != ${other.lengthBefore}" }
        val out = Builder()
        val a = OpCursor(ops); val b = OpCursor(other.ops)
        while (true) {
            val opA = a.peek(); val opB = b.peek()
            if (opA == null && opB == null) break
            if (opA is Op.Delete) { out.delete(opA.n); a.take(opA.n); continue }
            if (opB is Op.Insert) { out.insert(opB.text); b.take(opB.text.length); continue }
            checkNotNull(opA) { "compose: first change set ran out" }
            checkNotNull(opB) { "compose: second change set ran out" }
            val k = minOf(a.size(opA), b.size(opB))
            when {
                opA is Op.Retain && opB is Op.Retain -> out.retain(k)
                opA is Op.Insert && opB is Op.Delete -> Unit // inserted, then deleted again
                opA is Op.Insert && opB is Op.Retain -> out.insert(opA.text.substring(0, k))
                opA is Op.Retain && opB is Op.Delete -> out.delete(k)
            }
            a.take(k); b.take(k)
        }
        return out.build()
    }

    /**
     * Where [pos] (in the old document) ends up. [assoc] decides the ambiguous cases: for an
     * insertion exactly at [pos], `assoc < 0` stays before the inserted text and `assoc > 0` moves
     * after it; for a position strictly inside a replaced range, `assoc < 0` maps to the start of
     * the replacement and `assoc > 0` to its end.
     */
    fun mapPos(pos: Int, assoc: Int = -1): Int {
        require(pos in 0..lengthBefore) { "mapPos($pos) out of bounds for length $lengthBefore" }
        var delta = 0
        for (c in iterChanges()) {
            if (pos < c.fromA) break
            val insLen = c.toB - c.fromB
            if (c.fromA == c.toA) {
                if (pos == c.fromA) return if (assoc < 0) pos + delta else pos + delta + insLen
            } else {
                if (pos == c.fromA) return pos + delta
                if (pos < c.toA) return if (assoc < 0) c.fromB else c.toB
            }
            delta += insLen - (c.toA - c.fromA)
        }
        return pos + delta
    }

    override fun equals(other: Any?) = other is ChangeSet && other.ops == ops
    override fun hashCode() = ops.hashCode()
    override fun toString() = ops.joinToString(" ") {
        when (it) { is Op.Retain -> "=${it.n}"; is Op.Delete -> "-${it.n}"; is Op.Insert -> "+\"${it.text}\"" }
    }

    /** Builds a normalized change set left to right. */
    class Builder {
        private val ops = ArrayList<Op>()
        var lengthBefore = 0; private set

        fun retain(n: Int): Builder {
            require(n >= 0)
            if (n == 0) return this
            lengthBefore += n
            val last = ops.lastOrNull()
            if (last is Op.Retain) ops[ops.size - 1] = Op.Retain(last.n + n) else ops += Op.Retain(n)
            return this
        }

        fun delete(n: Int): Builder {
            require(n >= 0)
            if (n == 0) return this
            lengthBefore += n
            // Canonical order: delete before insert at the same position.
            val last = ops.lastOrNull()
            when {
                last is Op.Delete -> ops[ops.size - 1] = Op.Delete(last.n + n)
                last is Op.Insert -> {
                    val before = ops.getOrNull(ops.size - 2)
                    if (before is Op.Delete) ops[ops.size - 2] = Op.Delete(before.n + n)
                    else ops.add(ops.size - 1, Op.Delete(n))
                }
                else -> ops += Op.Delete(n)
            }
            return this
        }

        fun insert(text: String): Builder {
            if (text.isEmpty()) return this
            val last = ops.lastOrNull()
            if (last is Op.Insert) ops[ops.size - 1] = Op.Insert(last.text + text) else ops += Op.Insert(text)
            return this
        }

        fun build(): ChangeSet = ChangeSet(ops.toList())
    }

    private class OpCursor(private val ops: List<Op>) {
        private var i = 0
        private var used = 0 // how much of ops[i] is consumed
        fun peek(): Op? = ops.getOrNull(i)?.let { op ->
            if (used == 0) op else when (op) {
                is Op.Retain -> Op.Retain(op.n - used)
                is Op.Delete -> Op.Delete(op.n - used)
                is Op.Insert -> Op.Insert(op.text.substring(used))
            }
        }
        fun size(op: Op) = when (op) { is Op.Retain -> op.n; is Op.Delete -> op.n; is Op.Insert -> op.text.length }
        fun take(k: Int) {
            val op = ops[i]
            used += k
            if (used >= size(op)) { i++; used = 0 }
        }
    }

    companion object {
        fun empty(length: Int): ChangeSet = Builder().retain(length).build()

        /**
         * Build from [specs], all in coordinates of a document of [docLength]. Specs may come in any
         * order but must not overlap (two pure insertions at the same position are kept in order).
         */
        fun of(docLength: Int, specs: List<ChangeSpec>): ChangeSet {
            val sorted = specs.withIndex().sortedWith(compareBy({ it.value.from }, { it.index })).map { it.value }
            val b = Builder()
            var pos = 0
            for (s in sorted) {
                require(s.from >= pos) { "overlapping changes at ${s.from}" }
                require(s.to <= docLength) { "change ${s.from}..${s.to} beyond document length $docLength" }
                b.retain(s.from - pos)
                b.delete(s.to - s.from)
                b.insert(s.insert)
                pos = s.to
            }
            b.retain(docLength - pos)
            return b.build()
        }

        fun of(docLength: Int, vararg specs: ChangeSpec) = of(docLength, specs.toList())
    }
}
