package dev.supermux.editor.core

/**
 * The document text: an immutable rope of UTF-16 chunks.
 *
 * Every edit returns a NEW rope that shares all untouched chunks with the old one, so keeping an
 * old version (undo, a background parse, the diff base) costs nothing and needs no lock.
 *
 * Offsets are UTF-16 code units, the same unit Kotlin strings, Compose text layout and
 * tree-sitter's UTF-16 mode use. Lines are 0-based internally ([lineIndexAt], [lineStart]);
 * [line] returns a 1-based [Line.number] for display.
 *
 * Positions passed in must never fall inside a surrogate pair: splitting and concatenating rely on
 * it, and only building a rope from a string guards it.
 *
 * Only `\n` is a line break. Hosts normalize `\r\n` to `\n` when loading a file and remember the
 * file's line ending to write it back on save.
 */
class Rope private constructor(private val root: RopeNode) {
    /** Length in UTF-16 code units. */
    val length: Int get() = root.length

    /** Number of lines; an empty document has one (empty) line. */
    val lineCount: Int get() = root.breaks + 1

    fun replace(from: Int, to: Int, insert: String): Rope {
        require(from in 0..to && to <= length) { "replace($from, $to) out of bounds for length $length" }
        if (from == to && insert.isEmpty()) return this
        val (left, rest) = root.split(from)
        val (_, right) = rest.split(to - from)
        return Rope(RopeNode.concat(RopeNode.concat(left, RopeNode.build(insert)), right).rebalanced())
    }

    fun slice(from: Int = 0, to: Int = length): String {
        require(from in 0..to && to <= length) { "slice($from, $to) out of bounds for length $length" }
        val sb = StringBuilder(to - from)
        root.appendRange(from, to, sb)
        return sb.toString()
    }

    fun charAt(pos: Int): Char {
        require(pos in 0 until length) { "charAt($pos) out of bounds for length $length" }
        return root.charAt(pos)
    }

    /** 0-based index of the line containing [pos] (a position at a line break belongs to that line). */
    fun lineIndexAt(pos: Int): Int {
        require(pos in 0..length) { "lineIndexAt($pos) out of bounds for length $length" }
        return root.breaksBefore(pos)
    }

    /** Offset of the first character of 0-based line [index]. */
    fun lineStart(index: Int): Int {
        require(index in 0 until lineCount) { "lineStart($index) out of bounds for $lineCount lines" }
        return if (index == 0) 0 else root.offsetAfterBreak(index) // after the index-th '\n'
    }

    /** The line containing [pos]. */
    fun lineAt(pos: Int): Line = line(lineIndexAt(pos) + 1)

    /** 1-based line [number]. */
    fun line(number: Int): Line {
        val i = number - 1
        val from = lineStart(i)
        val to = if (i + 1 < lineCount) lineStart(i + 1) - 1 else length
        return Line(number, from, to, slice(from, to))
    }

    /** The chunk containing [pos], from [pos] to the end of that chunk ("" at the end). For parsers. */
    fun chunkAt(pos: Int): CharSequence {
        require(pos in 0..length) { "chunkAt($pos) out of bounds for length $length" }
        return if (pos == length) "" else root.chunkAt(pos)
    }

    override fun toString(): String = slice()

    /** Content equality, chunk by chunk: never builds either text as one string. */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Rope) return false
        if (other.root === root) return true
        if (other.length != length) return false
        if (hash != 0 && other.hash != 0 && hash != other.hash) return false
        val a = LeafCursor(root); val b = LeafCursor(other.root)
        var ta = ""; var ia = 0
        var tb = ""; var ib = 0
        var left = length
        while (left > 0) {
            if (ia == ta.length) { ta = a.next()!!; ia = 0 }
            if (ib == tb.length) { tb = b.next()!!; ib = 0 }
            val n = minOf(ta.length - ia, tb.length - ib, left)
            if (!ta.regionMatches(ia, tb, ib, n)) return false
            ia += n; ib += n; left -= n
        }
        return true
    }

    // Content hash (String.hashCode's formula), computed on first use. A racing second computation
    // just writes the same value again.
    private var hash = 0

    override fun hashCode(): Int {
        var h = hash
        if (h == 0 && length > 0) {
            val c = LeafCursor(root)
            while (true) { val t = c.next() ?: break; for (ch in t) h = 31 * h + ch.code }
            hash = h
        }
        return h
    }

    internal val depth: Int get() = root.depth

    companion object {
        val EMPTY = Rope(RopeLeaf(""))
        fun of(text: String): Rope = if (text.isEmpty()) EMPTY else Rope(RopeNode.build(text))
    }
}

/** One line of a [Rope]: [from]..[to] excludes the line break. */
data class Line(val number: Int, val from: Int, val to: Int, val text: String) {
    val length: Int get() = to - from
}

internal sealed class RopeNode {
    abstract val length: Int
    abstract val breaks: Int
    abstract val depth: Int
    abstract val leaves: Int

    abstract fun split(pos: Int): Pair<RopeNode, RopeNode>
    abstract fun appendRange(from: Int, to: Int, out: StringBuilder)
    abstract fun charAt(pos: Int): Char
    abstract fun breaksBefore(pos: Int): Int
    /** Offset right after the [n]-th line break (1-based n). */
    abstract fun offsetAfterBreak(n: Int): Int
    abstract fun chunkAt(pos: Int): CharSequence
    abstract fun collectLeaves(out: MutableList<RopeLeaf>)

    /** Lazy rebalancing: concat is O(1), so rebuild once the tree gets clearly lopsided. */
    fun rebalanced(): RopeNode {
        if (depth <= MAX_SLACK + 2 * log2Ceil(leaves)) return this
        val all = ArrayList<RopeLeaf>(leaves)
        collectLeaves(all)
        return balance(all, 0, all.size)
    }

    companion object {
        const val LEAF_MAX = 1024
        private const val MAX_SLACK = 8

        fun build(text: String): RopeNode {
            if (text.length <= LEAF_MAX) return RopeLeaf(text)
            val leaves = ArrayList<RopeLeaf>(text.length / LEAF_MAX + 1)
            var i = 0
            while (i < text.length) {
                var end = minOf(text.length, i + LEAF_MAX)
                // Keep a surrogate pair in one leaf, so every chunk a parser sees is whole code points.
                if (end < text.length && text[end - 1].isHighSurrogate()) end--
                leaves += RopeLeaf(text.substring(i, end))
                i = end
            }
            return balance(leaves, 0, leaves.size)
        }

        fun concat(a: RopeNode, b: RopeNode): RopeNode = when {
            a.length == 0 -> b
            b.length == 0 -> a
            // Typing appends to small leaves: merge instead of growing the tree one char at a time.
            a is RopeLeaf && b is RopeLeaf && a.length + b.length <= LEAF_MAX -> RopeLeaf(a.text + b.text)
            a is RopeBranch && a.right is RopeLeaf && b is RopeLeaf && a.right.length + b.length <= LEAF_MAX ->
                RopeBranch(a.left, RopeLeaf(a.right.text + b.text))
            b is RopeBranch && b.left is RopeLeaf && a is RopeLeaf && a.length + b.left.length <= LEAF_MAX ->
                RopeBranch(RopeLeaf(a.text + b.left.text), b.right)
            else -> RopeBranch(a, b)
        }

        private fun balance(leaves: List<RopeLeaf>, from: Int, to: Int): RopeNode =
            if (to - from == 1) leaves[from] else {
                val mid = (from + to) ushr 1
                RopeBranch(balance(leaves, from, mid), balance(leaves, mid, to))
            }

        private fun log2Ceil(n: Int): Int = if (n <= 1) 0 else 32 - (n - 1).countLeadingZeroBits()
    }
}

/** Walks a tree's non-empty leaves left to right. */
private class LeafCursor(root: RopeNode) {
    private val stack = ArrayList<RopeNode>().apply { add(root) }

    fun next(): String? {
        while (stack.isNotEmpty()) {
            when (val n = stack.removeAt(stack.size - 1)) {
                is RopeLeaf -> if (n.text.isNotEmpty()) return n.text
                is RopeBranch -> { stack += n.right; stack += n.left }
            }
        }
        return null
    }
}

internal class RopeLeaf(val text: String) : RopeNode() {
    override val length get() = text.length
    override val breaks: Int = text.count { it == '\n' }
    override val depth get() = 0
    override val leaves get() = 1

    override fun split(pos: Int) = RopeLeaf(text.substring(0, pos)) to RopeLeaf(text.substring(pos))
    override fun appendRange(from: Int, to: Int, out: StringBuilder) { out.append(text, from, to) }
    override fun charAt(pos: Int) = text[pos]
    override fun breaksBefore(pos: Int): Int {
        var n = 0
        for (i in 0 until pos) if (text[i] == '\n') n++
        return n
    }
    override fun offsetAfterBreak(n: Int): Int {
        var seen = 0
        for (i in text.indices) if (text[i] == '\n' && ++seen == n) return i + 1
        error("line break $n not in leaf")
    }
    override fun chunkAt(pos: Int): CharSequence = text.subSequence(pos, text.length)
    override fun collectLeaves(out: MutableList<RopeLeaf>) { if (text.isNotEmpty()) out += this }
}

internal class RopeBranch(val left: RopeNode, val right: RopeNode) : RopeNode() {
    override val length = left.length + right.length
    override val breaks = left.breaks + right.breaks
    override val depth = 1 + maxOf(left.depth, right.depth)
    override val leaves = left.leaves + right.leaves

    override fun split(pos: Int): Pair<RopeNode, RopeNode> = when {
        pos <= left.length -> {
            val (a, b) = left.split(pos)
            a to concat(b, right)
        }
        else -> {
            val (a, b) = right.split(pos - left.length)
            concat(left, a) to b
        }
    }

    override fun appendRange(from: Int, to: Int, out: StringBuilder) {
        val l = left.length
        if (from < l) left.appendRange(from, minOf(to, l), out)
        if (to > l) right.appendRange(maxOf(0, from - l), to - l, out)
    }

    override fun charAt(pos: Int) = if (pos < left.length) left.charAt(pos) else right.charAt(pos - left.length)
    override fun breaksBefore(pos: Int) =
        if (pos <= left.length) left.breaksBefore(pos) else left.breaks + right.breaksBefore(pos - left.length)
    override fun offsetAfterBreak(n: Int) =
        if (n <= left.breaks) left.offsetAfterBreak(n) else left.length + right.offsetAfterBreak(n - left.breaks)
    override fun chunkAt(pos: Int) = if (pos < left.length) left.chunkAt(pos) else right.chunkAt(pos - left.length)
    override fun collectLeaves(out: MutableList<RopeLeaf>) { left.collectLeaves(out); right.collectLeaves(out) }
}
