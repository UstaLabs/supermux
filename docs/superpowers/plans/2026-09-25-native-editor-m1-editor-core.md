# Native editor M1: editor-core. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build `:editor-core`, the pure-Kotlin heart of the native editor (spec §4): the rope, change sets,
multi-range selection, the immutable state and its transactions, and the extension system (facets, state
fields, precedence, compartments). It also defines the plugin-facing data for commands, key bindings and
decorations.

**Architecture:** A Kotlin Multiplatform library with no UI toolkit and no dependency on any other module
here, like `:terminal-core`. Everything is immutable. `EditorState.update(spec)` returns a `Transaction`
carrying the new state. Plugins are plain `Extension` values. The design follows CodeMirror 6's
state/extension model, because the current editor is CM6 and its model is proven.

**Tech stack:** Kotlin 2.4.10 Multiplatform (jvm, android, iosArm64, iosSimulatorArm64, wasmJs), `kotlin.test`.
No other dependencies.

**Verified:** every file in this plan was compiled and its tests run on the Mac on 2026-09-25 before the
plan was written: 35 JVM tests pass, the iOS simulator tests pass, and the wasmJs and Android compiles are
clean. The code blocks are that exact code.

**Relationship to M0:** independent of the M0 risk checks. The rope counts **UTF-16 units and lines only**.
If M0 §2 finds tree-sitter's UTF-16 mode unusable, a follow-up task adds a UTF-8 byte count to `RopeNode`,
and nothing else here changes.

## Ground rules

- **No Gradle on the Linux host** (RAM). Edit in the worktree, sync to the Mac's private checkout, and run
  there. `apps/editor-spike/mac-sync.sh` comes from Task 0 of the M0 plan. If M0 hasn't run yet, do that
  task first: it only creates the script.
- In your local shell:
  `MACENV='export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH=$JAVA_HOME/bin:/opt/homebrew/bin:$PATH'`
- Offsets are UTF-16 code units everywhere. Lines are 0-based in `lineIndexAt`/`lineStart`, and
  `Line.number` is 1-based.
- Follow the file layout exactly: one responsibility per file. Later milestones (syntax, compose,
  plugins) import these names.

## File map

| File | Responsibility |
|---|---|
| `Rope.kt` | immutable text; lines; chunks for parsers |
| `ChangeSet.kt` | `ChangeSpec`, `Change`, `ChangeSet` (+ `Builder`): apply / invert / compose / mapPos |
| `Selection.kt` | `SelectionRange`, `EditorSelection` (multi-range, normalized) |
| `Extension.kt` | `Extension`, `Prec`, `Facet`, `StateField`, `Compartment`, internal `Configuration` |
| `State.kt` | `EditorState`, `Transaction`, `TransactionSpec`, `StateEffect(Type)`, `Annotation(Type)` |
| `Commands.kt` | `Command`, `CommandTarget`, `NamedCommand`, `KeyChord`, `KeyBinding`, `keymapFacet`, `runKey` |
| `Decorations.kt` | `Decoration` (mark, line, inline/block widget, replace), `WidgetKey`, `Ranged`, `RangeSet`, `decorationsFacet` |

---

### Task 1: Module skeleton

**Files:**
- Modify: `apps/settings.gradle.kts` (after the `:terminal-sample` include)
- Create: `apps/editor-core/build.gradle.kts`

- [ ] **Step 1: Include the module**

Append to `apps/settings.gradle.kts`:
```kotlin
// The native editor's pure-Kotlin core (rope, transactions, extensions). Depends on nothing else here,
// like :terminal-core (docs/superpowers/specs/2026-09-25-native-editor-design.md).
include(":editor-core")
```

- [ ] **Step 2: Write the build file**

`apps/editor-core/build.gradle.kts`:
```kotlin
plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// editor-core: the native editor's pure-Kotlin heart: rope, change sets, selection, state,
// transactions and the extension system (docs/superpowers/specs/2026-09-25-native-editor-design.md §4).
//
// Depends on NOTHING in this repository and on no UI toolkit: no Compose, no :shared, no :ui.
// Every client runs the same code, and the whole contract is testable in milliseconds on the JVM.
group = "dev.supermux.editor"
version = "0.1.0-dev.1"

kotlin {
    jvmToolchain(17)
    jvm()
    androidTarget()
    // Apple targets: compiled on the Mac (kotlin.native.ignoreDisabledTargets elsewhere).
    iosArm64()
    iosSimulatorArm64()
    // Browser; its test task is off: commonTest runs on the JVM, and nothing here is browser-specific.
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)
    wasmJs { browser { testTask { enabled = false } } }
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

android {
    namespace = "dev.supermux.editor.core"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.androidMinSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
```

- [ ] **Step 3: Check that it configures**

Run: `apps/editor-spike/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:tasks -q | head -3"`
Expected: exit 0. An AGP deprecation warning about `android {}` is expected and harmless (`:terminal-compose`
prints the same one).

- [ ] **Step 4: Commit**

```bash
git add apps/settings.gradle.kts apps/editor-core/build.gradle.kts
git commit -m "build(editor-core): new pure-Kotlin multiplatform module"
```

---

### Task 2: Rope

**Files:**
- Create: `apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Rope.kt`
- Test: `apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/RopeTest.kt`

The random-edit test is the important one: 4 000 edits against a `String` model, including emoji, `ğ`, newlines and occasional 3 000-char pastes that cross leaf boundaries.

- [ ] **Step 1: Write the failing test**

`apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/RopeTest.kt`:
```kotlin
package dev.supermux.editor.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RopeTest {
    @Test fun emptyRopeHasOneEmptyLine() {
        val r = Rope.of("")
        assertEquals(0, r.length)
        assertEquals(1, r.lineCount)
        assertEquals(Line(1, 0, 0, ""), r.line(1))
    }

    @Test fun linesAreFoundByNumberAndPosition() {
        val r = Rope.of("ab\ncde\n\nf")
        assertEquals(4, r.lineCount)
        assertEquals(Line(2, 3, 6, "cde"), r.line(2))
        assertEquals(Line(3, 7, 7, ""), r.line(3))
        assertEquals(2, r.lineAt(6).number) // the position of the '\n' belongs to its line
        assertEquals(4, r.lineAt(9).number)
    }

    @Test fun replaceSharesAndDoesNotMutate() {
        val a = Rope.of("hello world")
        val b = a.replace(6, 11, "rope")
        assertEquals("hello world", a.toString())
        assertEquals("hello rope", b.toString())
    }

    @Test fun surrogatePairsCountAsTwoUnits() {
        val r = Rope.of("a😀b")
        assertEquals(4, r.length)
        assertEquals("b", r.slice(3, 4))
    }

    @Test fun chunkAtFeedsAParserTheWholeText() {
        val text = buildString { repeat(5000) { append("line $it\n") } }
        val r = Rope.of(text)
        val rebuilt = StringBuilder()
        var pos = 0
        while (pos < r.length) { val c = r.chunkAt(pos); rebuilt.append(c); pos += c.length }
        assertEquals(text, rebuilt.toString())
        assertEquals("", r.chunkAt(r.length).toString())
    }

    /** The rope must agree with a plain String through thousands of random edits. */
    @Test fun randomEditsMatchAStringModel() {
        val rnd = Random(20260925)
        var model = ""
        var rope = Rope.EMPTY
        val alphabet = "abc \n😀ğ"
        repeat(4000) { step ->
            val from = rnd.nextInt(model.length + 1)
            val to = (from + rnd.nextInt(0, 12)).coerceAtMost(model.length)
            val len = if (rnd.nextInt(20) == 0) rnd.nextInt(3000) else rnd.nextInt(8)
            val insert = buildString { repeat(len) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
            model = model.replaceRange(from, to, insert)
            rope = rope.replace(from, to, insert)
            if (step % 97 == 0) {
                assertEquals(model, rope.toString(), "text after step $step")
                assertEquals(model.count { it == '\n' } + 1, rope.lineCount, "lineCount after step $step")
                if (model.isNotEmpty()) {
                    val p = rnd.nextInt(model.length + 1)
                    assertEquals(model.substring(0, p).count { it == '\n' }, rope.lineIndexAt(p), "lineIndexAt($p)")
                    val n = rnd.nextInt(rope.lineCount) + 1
                    assertEquals(model.split('\n')[n - 1], rope.line(n).text, "line($n)")
                }
            }
        }
        assertEquals(model, rope.toString())
        // Lazy rebalancing keeps the tree shallow.
        assertTrue(rope.depth <= 8 + 2 * 32, "depth ${rope.depth}")
    }

    @Test fun bigDocumentStaysShallow() {
        val r = Rope.of("x".repeat(4_000_000))
        assertTrue(r.depth <= 13, "depth ${r.depth}")
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `apps/editor-spike/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:jvmTest --tests 'dev.supermux.editor.core.RopeTest' --console=plain"`
Expected: compilation FAILS: `Rope` is unresolved.

- [ ] **Step 3: Implement**

`apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Rope.kt`:
```kotlin
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
    override fun equals(other: Any?): Boolean = other is Rope && other.length == length && other.toString() == toString()
    override fun hashCode(): Int = toString().hashCode()

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
                val end = minOf(text.length, i + LEAF_MAX)
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
```

- [ ] **Step 4: Run it and check that it passes**

Run the Step 2 command again. Expected: BUILD SUCCESSFUL, every test in `RopeTest` passes.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-core
git commit -m "feat(editor-core): immutable rope with lines and parser chunks"
```

---

### Task 3: ChangeSet

**Files:**
- Create: `apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/ChangeSet.kt`
- Test: `apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/ChangeSetTest.kt`

`invert`, `compose` and `mapPos` are each checked against 500 random documents. Those three operations carry undo, history grouping and anchoring for comments, diagnostics and cursors, so they get property tests, not just examples.

- [ ] **Step 1: Write the failing test**

`apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/ChangeSetTest.kt`:
```kotlin
package dev.supermux.editor.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChangeSetTest {
    @Test fun appliesUnsortedNonOverlappingSpecs() {
        val cs = ChangeSet.of(11, ChangeSpec(6, 11, "rope"), ChangeSpec(0, 0, ">"))
        assertEquals(">hello rope", cs.apply("hello world"))
        assertEquals(11, cs.lengthBefore)
        assertEquals(11, cs.lengthAfter)
    }

    @Test fun overlappingSpecsAreRejected() {
        assertFailsWith<IllegalArgumentException> { ChangeSet.of(10, ChangeSpec(0, 5), ChangeSpec(3, 6)) }
    }

    @Test fun normalFormMakesEqualEffectsEqual() {
        val a = ChangeSet.Builder().retain(2).insert("x").delete(3).retain(1).build()
        val b = ChangeSet.Builder().retain(2).delete(3).insert("x").retain(1).build()
        assertEquals(a, b)
        assertEquals("=2 -3 +\"x\" =1", a.toString())
    }

    @Test fun iterChangesReportsBothCoordinates() {
        val cs = ChangeSet.of(10, ChangeSpec(2, 4, "abc"), ChangeSpec(8, 8, "Z"))
        assertEquals(listOf(Change(2, 4, 2, 5, "abc"), Change(8, 8, 9, 10, "Z")), cs.iterChanges())
    }

    @Test fun mapPosFollowsTheDocumentedRules() {
        val ins = ChangeSet.of(10, ChangeSpec(5, 5, "abc"))
        assertEquals(4, ins.mapPos(4))
        assertEquals(5, ins.mapPos(5, -1))  // stays before an insertion at its position
        assertEquals(8, ins.mapPos(5, 1))   // or moves after it
        assertEquals(9, ins.mapPos(6))
        val rep = ChangeSet.of(10, ChangeSpec(2, 6, "xy"))
        assertEquals(2, rep.mapPos(2, 1))   // start of a replaced range stays at its start
        assertEquals(2, rep.mapPos(4, -1))  // inside: start of the replacement…
        assertEquals(4, rep.mapPos(4, 1))   // …or its end
        assertEquals(4, rep.mapPos(6))      // end of the range = end of the replacement
        assertEquals(8, rep.mapPos(10))
    }

    @Test fun invertUndoesExactly() = repeatRandom { rnd, doc ->
        val cs = randomChangeSet(rnd, doc.length)
        val after = cs.apply(Rope.of(doc))
        assertEquals(doc, cs.invert(Rope.of(doc)).apply(after).toString())
    }

    @Test fun composeEqualsApplyingBoth() = repeatRandom { rnd, doc ->
        val a = randomChangeSet(rnd, doc.length)
        val mid = a.apply(doc)
        val b = randomChangeSet(rnd, mid.length)
        assertEquals(b.apply(mid), a.compose(b).apply(doc))
    }

    @Test fun composeWithInverseIsIdentity() = repeatRandom { rnd, doc ->
        val a = randomChangeSet(rnd, doc.length)
        val undone = a.compose(a.invert(Rope.of(doc)))
        assertEquals(doc, undone.apply(doc))
    }

    @Test fun mapPosStaysInBoundsAndMonotonic() = repeatRandom { rnd, doc ->
        val cs = randomChangeSet(rnd, doc.length)
        var last = -1
        for (p in 0..doc.length) {
            val m = cs.mapPos(p, 1)
            assertTrue(m in 0..cs.lengthAfter, "mapPos($p)=$m")
            assertTrue(m >= last, "not monotonic at $p")
            last = m
        }
    }

    private fun repeatRandom(block: (Random, String) -> Unit) {
        val rnd = Random(42)
        repeat(500) {
            val doc = buildString { repeat(rnd.nextInt(0, 40)) { append("ab\nc"[rnd.nextInt(4)]) } }
            block(rnd, doc)
        }
    }

    private fun randomChangeSet(rnd: Random, length: Int): ChangeSet {
        val specs = ArrayList<ChangeSpec>()
        var pos = 0
        while (pos <= length && rnd.nextInt(4) != 0) {
            val from = pos + rnd.nextInt(0, (length - pos) + 1).coerceAtMost(5)
            if (from > length) break
            val to = (from + rnd.nextInt(0, 4)).coerceAtMost(length)
            val ins = "XYZ".take(rnd.nextInt(0, 4))
            specs += ChangeSpec(from, to, ins)
            pos = to + 1
        }
        return ChangeSet.of(length, specs)
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `apps/editor-spike/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:jvmTest --tests 'dev.supermux.editor.core.ChangeSetTest' --console=plain"`
Expected: compilation FAILS: `ChangeSet`/`ChangeSpec`/`Change` are unresolved.

- [ ] **Step 3: Implement**

`apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/ChangeSet.kt`:
```kotlin
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
```

- [ ] **Step 4: Run it and check that it passes**

Run the Step 2 command again. Expected: BUILD SUCCESSFUL, every test in `ChangeSetTest` passes.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-core
git commit -m "feat(editor-core): change sets with invert, compose and position mapping"
```

---

### Task 4: Selection

**Files:**
- Create: `apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Selection.kt`
- Test: `apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/SelectionTest.kt`

- [ ] **Step 1: Write the failing test**

`apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/SelectionTest.kt`:
```kotlin
package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionTest {
    @Test fun rangesAreSortedAndOverlapsMerged() {
        val s = EditorSelection.create(listOf(SelectionRange(10, 12), SelectionRange(0, 3), SelectionRange(2, 5)), mainIndex = 0)
        assertEquals("0-5,*10-12", s.toString())
    }

    @Test fun touchingNonEmptyRangesStayApartButACursorJoins() {
        assertEquals(2, EditorSelection.create(listOf(SelectionRange(0, 3), SelectionRange(3, 6))).ranges.size)
        assertEquals(1, EditorSelection.create(listOf(SelectionRange(0, 3), SelectionRange(3))).ranges.size)
        assertEquals(1, EditorSelection.create(listOf(SelectionRange(4), SelectionRange(4))).ranges.size)
    }

    @Test fun selectionFollowsEdits() {
        val s = EditorSelection.create(listOf(SelectionRange(2), SelectionRange(8, 10)))
        val cs = ChangeSet.of(12, ChangeSpec(0, 0, "abc"))
        assertEquals("*5-5,11-13", s.map(cs).toString())
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `apps/editor-spike/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:jvmTest --tests 'dev.supermux.editor.core.SelectionTest' --console=plain"`
Expected: compilation FAILS: `EditorSelection`/`SelectionRange` are unresolved.

- [ ] **Step 3: Implement**

`apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Selection.kt`:
```kotlin
package dev.supermux.editor.core

/** One selection range. [anchor] stays put while extending; [head] is where the cursor is drawn. */
data class SelectionRange(val anchor: Int, val head: Int = anchor) {
    val from: Int get() = minOf(anchor, head)
    val to: Int get() = maxOf(anchor, head)
    val empty: Boolean get() = anchor == head

    fun map(changes: ChangeSet, assoc: Int = -1): SelectionRange {
        val a = changes.mapPos(anchor, assoc)
        return if (empty) SelectionRange(a) else SelectionRange(a, changes.mapPos(head, assoc))
    }
}

/**
 * The selection: one or more ranges, sorted and non-overlapping, with one [main] range.
 *
 * Multiple ranges exist from day one (column selection needs them); a single cursor is just the
 * one-range case, so nothing downstream special-cases it.
 */
class EditorSelection private constructor(val ranges: List<SelectionRange>, val mainIndex: Int) {
    val main: SelectionRange get() = ranges[mainIndex]

    fun map(changes: ChangeSet, assoc: Int = -1): EditorSelection =
        create(ranges.map { it.map(changes, assoc) }, mainIndex)

    fun addRange(range: SelectionRange, makeMain: Boolean = true): EditorSelection =
        create(ranges + range, if (makeMain) ranges.size else mainIndex)

    override fun equals(other: Any?) = other is EditorSelection && other.ranges == ranges && other.mainIndex == mainIndex
    override fun hashCode() = ranges.hashCode() * 31 + mainIndex
    override fun toString() = ranges.mapIndexed { i, r -> (if (i == mainIndex) "*" else "") + "${r.anchor}-${r.head}" }.joinToString(",")

    companion object {
        fun cursor(pos: Int) = EditorSelection(listOf(SelectionRange(pos)), 0)
        fun single(anchor: Int, head: Int = anchor) = EditorSelection(listOf(SelectionRange(anchor, head)), 0)

        /** Sorts [ranges] by position and merges overlapping (or touching, when non-empty) ones. */
        fun create(ranges: List<SelectionRange>, mainIndex: Int = 0): EditorSelection {
            require(ranges.isNotEmpty()) { "a selection needs at least one range" }
            require(mainIndex in ranges.indices) { "main index $mainIndex out of range" }
            val main = ranges[mainIndex]
            val sorted = ranges.sortedWith(compareBy({ it.from }, { it.to }))
            val merged = ArrayList<SelectionRange>(sorted.size)
            var newMain = 0
            for (r in sorted) {
                val last = merged.lastOrNull()
                // A cursor touching a range joins it; two non-empty ranges that only touch stay apart.
                if (last != null && (if (r.empty) r.from <= last.to else r.from < last.to)) {
                    val from = minOf(last.from, r.from); val to = maxOf(last.to, r.to)
                    val forward = last.head >= last.anchor
                    merged[merged.size - 1] = if (forward) SelectionRange(from, to) else SelectionRange(to, from)
                    if (r == main) newMain = merged.size - 1
                } else {
                    merged += r
                    if (r == main) newMain = merged.size - 1
                }
            }
            return EditorSelection(merged, newMain)
        }
    }
}
```

- [ ] **Step 4: Run it and check that it passes**

Run the Step 2 command again. Expected: BUILD SUCCESSFUL, every test in `SelectionTest` passes.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-core
git commit -m "feat(editor-core): multi-range normalized selection"
```

---

### Task 5: Extensions and state

**Files:**
- Create: `apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Extension.kt`
- Create: `apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/State.kt`
- Test: `apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/StateTest.kt`

These two files depend on each other (`Transaction` feeds `StateField.update`, and `Configuration` is read by `EditorState`), so they land in one task. Rule enforced by `EditorState`: a field's `create`/`update` may read static facets and EARLIER fields. Facet values read while a state is still being built are computed but not cached.

- [ ] **Step 1: Write the failing test**

`apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/StateTest.kt`:
```kotlin
package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StateTest {
    private val tabSize = Facet.first("tabSize", 4)
    private val words = Facet.list<String>("words")

    /** Counts transactions that changed the document. */
    private val editCount = StateField<Int>("editCount", create = { 0 }, update = { v, tr -> if (tr.docChanged) v + 1 else v })

    @Test fun updateProducesANewStateAndLeavesTheOldOne() {
        val s0 = EditorState.create("hello", extensions = editCount)
        val tr = s0.update(ChangeSpec(5, 5, "!"), userEvent = "input")
        assertEquals("hello", s0.doc.toString())
        assertEquals("hello!", tr.state.doc.toString())
        assertEquals(0, s0.field(editCount))
        assertEquals(1, tr.state.field(editCount))
        assertTrue(tr.isUserEvent("input"))
    }

    @Test fun selectionIsMappedUnlessSetExplicitly() {
        val s0 = EditorState.create("abc", EditorSelection.cursor(3))
        assertEquals(EditorSelection.cursor(4), s0.update(ChangeSpec(0, 0, "x")).state.selection)
        val explicit = s0.update(ChangeSpec(0, 0, "x"), selection = EditorSelection.cursor(1))
        assertEquals(EditorSelection.cursor(1), explicit.state.selection)
        assertTrue(explicit.selectionSet)
    }

    @Test fun userEventMatchesByDottedPrefix() {
        val tr = EditorState.create("a").update(ChangeSpec(1, 1, "b"), userEvent = "input.ime")
        assertTrue(tr.isUserEvent("input"))
        assertTrue(tr.isUserEvent("input.ime"))
        assertFalse(tr.isUserEvent("in"))
        assertFalse(tr.isUserEvent("paste"))
    }

    @Test fun facetsCombineInPrecedenceOrder() {
        val s = EditorState.create(extensions = extensionOf(
            words.of("default-1"),
            Prec.lowest(words.of("lowest")),
            Prec.highest(words.of("highest")),
            words.of("default-2"),
            tabSize.of(2),
            Prec.high(tabSize.of(8)),
        ))
        assertEquals(listOf("highest", "default-1", "default-2", "lowest"), s.facet(words))
        assertEquals(8, s.facet(tabSize))
        assertEquals(4, EditorState.create().facet(tabSize))
    }

    @Test fun aFieldCanProvideAComputedFacet() {
        val shout = StateField<String>(
            "shout", create = { it.doc.toString().uppercase() }, update = { _, tr -> tr.state.doc.toString().uppercase() },
            provide = { f -> words.compute { st -> st.field(f) } },
        )
        val s = EditorState.create("hi", extensions = shout)
        assertEquals(listOf("HI"), s.facet(words))
        assertEquals(listOf("HIX"), s.update(ChangeSpec(2, 2, "x")).state.facet(words))
    }

    @Test fun effectsReachFields() {
        val setFlag = StateEffectType<Boolean>("setFlag")
        val flag = StateField<Boolean>("flag", { false }, { v, tr -> tr.effects.firstNotNullOfOrNull { it.valueIf(setFlag) } ?: v })
        val s0 = EditorState.create(extensions = flag)
        val s1 = s0.update(TransactionSpec(effects = listOf(setFlag.of(true)))).state
        assertTrue(s1.field(flag))
        assertTrue(s1.update(TransactionSpec()).state.field(flag))
    }

    @Test fun compartmentReconfigureSwapsContentAndKeepsOtherFields() {
        val wrap = Compartment("wrap")
        val lineWrap = Facet.first("lineWrap", false)
        val s0 = EditorState.create("a", extensions = extensionOf(editCount, wrap.of(lineWrap.of(false))))
        val s1 = s0.update(ChangeSpec(1, 1, "b")).state
        val tr = s1.update(TransactionSpec(effects = listOf(wrap.reconfigure(lineWrap.of(true)))))
        assertTrue(tr.reconfigured)
        assertTrue(tr.state.facet(lineWrap))
        assertEquals(1, tr.state.field(editCount)) // survived the reconfiguration
        // and the new content persists through later, ordinary transactions
        assertTrue(tr.state.update(ChangeSpec(2, 2, "c")).state.facet(lineWrap))
    }

    @Test fun reconfigureAddsAndDropsFields() {
        val s0 = EditorState.create("a")
        assertNull(s0.fieldOrNull(editCount))
        val s1 = s0.update(TransactionSpec(effects = listOf(StateEffect.reconfigure.of(editCount)))).state
        assertEquals(0, s1.field(editCount))
        val s2 = s1.update(TransactionSpec(effects = listOf(StateEffect.reconfigure.of(extensionOf())))).state
        assertNull(s2.fieldOrNull(editCount))
    }

    @Test fun theSameExtensionIncludedTwiceCountsOnce() {
        val w = words.of("once")
        assertEquals(listOf("once"), EditorState.create(extensions = extensionOf(w, w)).facet(words))
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `apps/editor-spike/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:jvmTest --tests 'dev.supermux.editor.core.StateTest' --console=plain"`
Expected: compilation FAILS: `EditorState`, `Facet`, `StateField`, `Prec`, `Compartment` are unresolved.

- [ ] **Step 3: Implement**

`apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Extension.kt`:
```kotlin
package dev.supermux.editor.core

/**
 * Anything a plugin contributes to an editor: facet values, state fields, compartments, or a
 * nested group of those. Plugins are plain values of this type; there is no plugin base class.
 */
sealed interface Extension

internal class ExtensionGroup(val items: List<Extension>) : Extension

/** Group several extensions into one. */
fun extensionOf(vararg items: Extension): Extension = ExtensionGroup(items.toList())
fun List<Extension>.toExtension(): Extension = ExtensionGroup(this)

/** When two extensions both provide something, the higher precedence one comes first. */
enum class Precedence { HIGHEST, HIGH, DEFAULT, LOW, LOWEST }

internal class PrecExtension(val inner: Extension, val prec: Precedence) : Extension

object Prec {
    fun highest(e: Extension): Extension = PrecExtension(e, Precedence.HIGHEST)
    fun high(e: Extension): Extension = PrecExtension(e, Precedence.HIGH)
    fun default(e: Extension): Extension = PrecExtension(e, Precedence.DEFAULT)
    fun low(e: Extension): Extension = PrecExtension(e, Precedence.LOW)
    fun lowest(e: Extension): Extension = PrecExtension(e, Precedence.LOWEST)
}

/**
 * A configuration slot that several extensions can fill, combined into one output value.
 *
 * `Facet.list<KeyBinding>()` collects every binding in precedence order; `Facet.first(4)` takes
 * the highest-precedence tab size or falls back to 4. Inputs are either static ([of]) or derived
 * from the state ([compute]), which is how a plugin turns its own state field into, say,
 * decorations.
 */
class Facet<I, O> private constructor(val name: String, private val combine: (List<I>) -> O) {
    fun of(value: I): Extension = FacetProvider(this, value, null)
    fun compute(get: (EditorState) -> I): Extension = FacetProvider(this, null, get)

    internal fun combineValues(values: List<I>): O = combine(values)
    override fun toString() = "Facet($name)"

    companion object {
        fun <I, O> define(name: String, combine: (List<I>) -> O): Facet<I, O> = Facet(name, combine)
        fun <T> list(name: String): Facet<T, List<T>> = Facet(name) { it }
        fun <T> first(name: String, default: T): Facet<T, T> = Facet(name) { it.firstOrNull() ?: default }
    }
}

internal class FacetProvider<I>(
    val facet: Facet<I, *>,
    val static: I?,
    val dynamic: ((EditorState) -> I)?,
) : Extension {
    @Suppress("UNCHECKED_CAST")
    fun valueIn(state: EditorState): I = if (dynamic != null) dynamic.invoke(state) else static as I
}

/**
 * A plugin's own memory, stored in the state and updated by every transaction.
 *
 * [provide] lets the field feed facets from its value (for example decorations), so the field
 * and what it shows travel as one extension.
 */
class StateField<V>(
    val name: String,
    private val create: (EditorState) -> V,
    private val update: (V, Transaction) -> V,
    private val provide: ((StateField<V>) -> Extension)? = null,
) : Extension {
    internal fun createIn(state: EditorState): V = create(state)
    internal fun updateWith(value: Any?, tr: Transaction): V {
        @Suppress("UNCHECKED_CAST")
        return update(value as V, tr)
    }
    internal fun provided(): Extension? = provide?.invoke(this)
    override fun toString() = "StateField($name)"
}

/**
 * A replaceable slot in the configuration, for things that change while the editor is open:
 * the language when a file is renamed, line wrap when the setting flips.
 */
class Compartment(val name: String = "compartment") {
    fun of(ext: Extension): Extension = CompartmentExtension(this, ext)
    fun reconfigure(ext: Extension): StateEffect<*> = StateEffect.compartmentReconfigure.of(this to ext)
    fun get(state: EditorState): Extension? = state.config.compartments[this]
    override fun toString() = "Compartment($name)"
}

internal class CompartmentExtension(val compartment: Compartment, val inner: Extension) : Extension

/** The flattened, precedence-ordered form of an extension tree. */
internal class Configuration(
    val root: Extension,
    val fields: List<StateField<*>>,
    val providers: Map<Facet<*, *>, List<FacetProvider<*>>>,
    val compartments: Map<Compartment, Extension>,
) {
    companion object {
        fun resolve(root: Extension, compartmentContent: Map<Compartment, Extension>): Configuration {
            val buckets = Precedence.entries.associateWith { ArrayList<Extension>() }
            val seen = HashSet<Extension>()
            val compartments = LinkedHashMap<Compartment, Extension>()

            fun visit(e: Extension, prec: Precedence) {
                when (e) {
                    is ExtensionGroup -> e.items.forEach { visit(it, prec) }
                    is PrecExtension -> visit(e.inner, e.prec)
                    is CompartmentExtension -> {
                        val content = compartmentContent[e.compartment] ?: e.inner
                        compartments[e.compartment] = content
                        visit(content, prec)
                    }
                    is StateField<*> -> {
                        if (seen.add(e)) {
                            buckets.getValue(prec) += e
                            e.provided()?.let { visit(it, prec) }
                        }
                    }
                    is FacetProvider<*> -> if (seen.add(e)) buckets.getValue(prec) += e
                }
            }
            visit(root, Precedence.DEFAULT)

            val ordered = Precedence.entries.flatMap { buckets.getValue(it) }
            val fields = ordered.filterIsInstance<StateField<*>>()
            val providers = LinkedHashMap<Facet<*, *>, MutableList<FacetProvider<*>>>()
            for (p in ordered.filterIsInstance<FacetProvider<*>>()) providers.getOrPut(p.facet) { ArrayList() } += p
            return Configuration(root, fields, providers, compartments)
        }
    }
}
```

`apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/State.kt`:
```kotlin
package dev.supermux.editor.core

/** A typed message a transaction carries to plugins ("fold lines 10–20", "set diagnostics"). */
class StateEffectType<T>(val name: String, private val mapper: ((T, ChangeSet) -> T?)? = null) {
    fun of(value: T): StateEffect<T> = StateEffect(this, value)
    internal fun mapValue(value: T, changes: ChangeSet): T? = if (mapper == null) value else mapper.invoke(value, changes)
    override fun toString() = "StateEffectType($name)"
}

class StateEffect<T> internal constructor(val type: StateEffectType<T>, val value: T) {
    fun isOf(t: StateEffectType<*>): Boolean = type === t

    /** This effect moved through [changes], or null when its target was deleted. */
    fun map(changes: ChangeSet): StateEffect<T>? = type.mapValue(value, changes)?.let { StateEffect(type, it) }

    /** [value] as [t]'s type when this effect is of [t], else null. */
    @Suppress("UNCHECKED_CAST")
    fun <U> valueIf(t: StateEffectType<U>): U? = if (type === t) value as U else null

    override fun toString() = "${type.name}($value)"

    companion object {
        /** Replace the whole configuration. */
        val reconfigure = StateEffectType<Extension>("reconfigure")
        internal val compartmentReconfigure = StateEffectType<Pair<Compartment, Extension>>("compartment.reconfigure")
    }
}

/** A typed label on a transaction that plugins read but never store (its origin, a timestamp). */
class AnnotationType<T>(val name: String) {
    fun of(value: T): Annotation<T> = Annotation(this, value)
    override fun toString() = "AnnotationType($name)"
}

class Annotation<T> internal constructor(val type: AnnotationType<T>, val value: T)

/**
 * What a caller asks for. [changes] (or a prebuilt [changeSet]) are in the coordinates of the
 * CURRENT document; [selection], when given, is in the coordinates of the NEW one.
 *
 * [userEvent] names where the change came from, dot-separated from general to specific:
 * `input`, `input.ime`, `paste`, `undo`, `redo`, `disk`, `lsp`, `command`, and later `agent`.
 */
data class TransactionSpec(
    val changes: List<ChangeSpec> = emptyList(),
    val changeSet: ChangeSet? = null,
    val selection: EditorSelection? = null,
    val effects: List<StateEffect<*>> = emptyList(),
    val annotations: List<Annotation<*>> = emptyList(),
    val userEvent: String? = null,
    val scrollIntoView: Boolean = false,
)

/** One applied update: the old state, what changed, and the resulting [state]. Plain data. */
class Transaction internal constructor(
    val startState: EditorState,
    val changes: ChangeSet,
    val selection: EditorSelection,
    val effects: List<StateEffect<*>>,
    private val annotations: List<Annotation<*>>,
    val scrollIntoView: Boolean,
    /** True when the spec set the selection explicitly (instead of mapping the old one). */
    val selectionSet: Boolean,
) {
    val docChanged: Boolean get() = !changes.isEmpty
    val newDoc: Rope = if (changes.isEmpty) startState.doc else changes.apply(startState.doc)

    lateinit var state: EditorState
        internal set

    /** True when this transaction replaced (part of) the configuration. */
    var reconfigured: Boolean = false
        internal set

    @Suppress("UNCHECKED_CAST")
    fun <T> annotation(type: AnnotationType<T>): T? = annotations.lastOrNull { it.type === type }?.value as T?

    /** `isUserEvent("input")` is true for `input` and `input.ime`, not for `inputs`. */
    fun isUserEvent(event: String): Boolean {
        val e = annotation(userEvent) ?: return false
        return e == event || (e.length > event.length && e.startsWith(event) && e[event.length] == '.')
    }

    companion object {
        val userEvent = AnnotationType<String>("userEvent")
    }
}

/**
 * The whole editor state as ONE immutable value: the document, the selection and every plugin's
 * field. The only way to get a new one is [update].
 */
class EditorState private constructor(
    val doc: Rope,
    val selection: EditorSelection,
    internal val config: Configuration,
) {
    private val values = HashMap<StateField<*>, Any?>()
    private val facetCache = HashMap<Facet<*, *>, Any?>()
    // False while fields are still being created/updated: a facet read then may see a partial
    // state, so it is computed but not cached. A field may read static facets and EARLIER fields.
    private var complete = false

    @Suppress("UNCHECKED_CAST")
    fun <V> field(f: StateField<V>): V {
        require(values.containsKey(f)) { "$f is not part of this state's configuration" }
        return values[f] as V
    }

    @Suppress("UNCHECKED_CAST")
    fun <V> fieldOrNull(f: StateField<V>): V? = values[f] as V?

    @Suppress("UNCHECKED_CAST")
    fun <I, O> facet(f: Facet<I, O>): O {
        if (facetCache.containsKey(f)) return facetCache[f] as O
        val providers = config.providers[f].orEmpty()
        val value = f.combineValues(providers.map { (it as FacetProvider<I>).valueIn(this) })
        if (complete) facetCache[f] = value
        return value
    }

    fun sliceDoc(from: Int = 0, to: Int = doc.length): String = doc.slice(from, to)

    fun update(spec: TransactionSpec): Transaction {
        val changes = spec.changeSet ?: ChangeSet.of(doc.length, spec.changes)
        require(changes.lengthBefore == doc.length) { "change set for length ${changes.lengthBefore}, doc is ${doc.length}" }
        val selection = spec.selection ?: selection.map(changes)
        selection.ranges.forEach { require(it.to <= changes.lengthAfter) { "selection $it beyond new doc length ${changes.lengthAfter}" } }
        val annotations = if (spec.userEvent != null) spec.annotations + Transaction.userEvent.of(spec.userEvent) else spec.annotations
        val tr = Transaction(this, changes, selection, spec.effects, annotations, spec.scrollIntoView, spec.selection != null)

        // Reconfiguration: a whole new root, and/or new compartment contents.
        var root = config.root
        var compartments = config.compartments
        var reconfigured = false
        for (e in spec.effects) {
            e.valueIf(StateEffect.reconfigure)?.let { root = it; reconfigured = true }
            e.valueIf(StateEffect.compartmentReconfigure)?.let { (c, ext) -> compartments = compartments + (c to ext); reconfigured = true }
        }
        val newConfig = if (reconfigured) Configuration.resolve(root, compartments) else config
        tr.reconfigured = reconfigured

        val next = EditorState(tr.newDoc, selection, newConfig)
        tr.state = next
        for (f in newConfig.fields) {
            next.values[f] = if (values.containsKey(f)) f.updateWith(values[f], tr) else f.createIn(next)
        }
        next.complete = true
        return tr
    }

    fun update(
        vararg changes: ChangeSpec,
        selection: EditorSelection? = null,
        userEvent: String? = null,
    ): Transaction = update(TransactionSpec(changes.toList(), selection = selection, userEvent = userEvent))

    companion object {
        fun create(
            doc: String = "",
            selection: EditorSelection? = null,
            extensions: Extension = extensionOf(),
        ): EditorState {
            val rope = Rope.of(doc)
            val sel = selection ?: EditorSelection.cursor(0)
            sel.ranges.forEach { require(it.to <= rope.length) { "selection $it beyond doc length ${rope.length}" } }
            val state = EditorState(rope, sel, Configuration.resolve(extensions, emptyMap()))
            for (f in state.config.fields) state.values[f] = f.createIn(state)
            state.complete = true
            return state
        }
    }
}
```

- [ ] **Step 4: Run it and check that it passes**

Run the Step 2 command again. Expected: BUILD SUCCESSFUL, every test in `StateTest` passes.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-core
git commit -m "feat(editor-core): editor state, transactions and the extension system"
```

---

### Task 6: Commands and key bindings

**Files:**
- Create: `apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Commands.kt`
- Test: `apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/CommandsTest.kt`

- [ ] **Step 1: Write the failing test**

`apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/CommandsTest.kt`:
```kotlin
package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CommandsTest {
    @Test fun modResolvesPerPlatform() {
        assertEquals(KeyChord("s", meta = true), KeyChord.parse("Mod-s", apple = true))
        assertEquals(KeyChord("s", ctrl = true), KeyChord.parse("Mod-s", apple = false))
        assertEquals(KeyChord("z", ctrl = true, shift = true), KeyChord.parse("Mod-Shift-z", apple = false))
        assertEquals(KeyChord("-", meta = true), KeyChord.parse("Mod--", apple = true))
        assertEquals(KeyChord("ArrowUp", alt = true), KeyChord.parse("Alt-ArrowUp", apple = true))
        assertEquals(KeyChord("S"[0].lowercase()), KeyChord.parse("S", apple = true))
    }

    private class Target(override var state: EditorState) : CommandTarget {
        override fun dispatch(tr: Transaction) { state = tr.state }
    }

    @Test fun firstCommandThatHandlesTheKeyWins() {
        val log = ArrayList<String>()
        val declines = Command { log += "declines"; false }
        val handles = Command { t -> log += "handles"; t.dispatch(t.state.update(ChangeSpec(0, 0, "!"))); true }
        val never = Command { log += "never"; true }
        val target = Target(EditorState.create("x", extensions = extensionOf(
            keymapOf(KeyBinding("Mod-k", handles), KeyBinding("Mod-k", never)),
            Prec.high(keymapOf(KeyBinding("Mod-k", declines))),
        )))
        assertTrue(runKey(target, KeyChord("k", ctrl = true), apple = false))
        assertEquals(listOf("declines", "handles"), log)
        assertEquals("!x", target.state.doc.toString())
        assertFalse(runKey(target, KeyChord("j", ctrl = true), apple = false))
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `apps/editor-spike/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:jvmTest --tests 'dev.supermux.editor.core.CommandsTest' --console=plain"`
Expected: compilation FAILS: `KeyChord`, `Command`, `keymapOf`, `runKey` are unresolved.

- [ ] **Step 3: Implement**

`apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Commands.kt`:
```kotlin
package dev.supermux.editor.core

/** What a command runs against: the current state, and a way to apply a transaction. */
interface CommandTarget {
    val state: EditorState
    fun dispatch(tr: Transaction)
}

/** An action. Returns true when it did something (so the key that triggered it is consumed). */
fun interface Command {
    fun run(target: CommandTarget): Boolean
}

/** A command with a stable id and a human title, so menus and a command palette can list it. */
data class NamedCommand(val id: String, val title: String, val command: Command)

/**
 * A key chord. Written like `Mod-s`, `Shift-Tab`, `Ctrl-Space`, `Alt-ArrowUp`, `Mod-Shift-z`.
 * `Mod` is Cmd on Apple platforms and Ctrl elsewhere; it is resolved by [parse].
 */
data class KeyChord(
    val key: String,
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
) {
    companion object {
        fun parse(spec: String, apple: Boolean): KeyChord {
            // The key itself may be "-" ("Mod--"), so split off modifiers from the left.
            val parts = ArrayList<String>()
            var rest = spec
            while (true) {
                val i = rest.indexOf('-')
                if (i <= 0 || i == rest.length - 1) break
                parts += rest.substring(0, i)
                rest = rest.substring(i + 1)
            }
            var ctrl = false; var alt = false; var shift = false; var meta = false
            for (m in parts) when (m) {
                "Mod" -> if (apple) meta = true else ctrl = true
                "Ctrl", "Control" -> ctrl = true
                "Alt", "Option" -> alt = true
                "Shift" -> shift = true
                "Meta", "Cmd" -> meta = true
                else -> throw IllegalArgumentException("unknown modifier '$m' in '$spec'")
            }
            val key = if (rest.length == 1) rest.lowercase() else rest
            return KeyChord(key, ctrl, alt, shift, meta)
        }
    }
}

/** One keymap entry, as a plugin declares it. */
data class KeyBinding(val key: String, val command: Command)

/** Every plugin's key bindings, highest precedence first. The surface tries them in order. */
val keymapFacet: Facet<List<KeyBinding>, List<KeyBinding>> = Facet.define("keymap") { it.flatten() }

/** Every plugin's named commands, highest precedence first. */
val commandsFacet: Facet<List<NamedCommand>, List<NamedCommand>> = Facet.define("commands") { it.flatten() }

fun keymapOf(vararg bindings: KeyBinding): Extension = keymapFacet.of(bindings.toList())

/**
 * Run the first binding for [chord] whose command returns true. This is the whole key-dispatch
 * rule; the surface only turns a platform key event into a [KeyChord].
 */
fun runKey(target: CommandTarget, chord: KeyChord, apple: Boolean): Boolean {
    for (b in target.state.facet(keymapFacet)) {
        if (KeyChord.parse(b.key, apple) == chord && b.command.run(target)) return true
    }
    return false
}
```

- [ ] **Step 4: Run it and check that it passes**

Run the Step 2 command again. Expected: BUILD SUCCESSFUL, every test in `CommandsTest` passes.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-core
git commit -m "feat(editor-core): commands, key chords and the keymap facet"
```

---

### Task 7: Decorations and RangeSet

**Files:**
- Create: `apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Decorations.kt`
- Test: `apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/RangeSetTest.kt`

Decorations are DATA: semantic class names and widget keys, no Compose types. `BlockWidget` (real height between lines) is here from day one, because the side-by-side diff needs it (spec §6.4, §7).

- [ ] **Step 1: Write the failing test**

`apps/editor-core/src/commonTest/kotlin/dev/supermux/editor/core/RangeSetTest.kt`:
```kotlin
package dev.supermux.editor.core

import kotlin.test.Test
import kotlin.test.assertEquals

class RangeSetTest {
    private val mark = Decoration.Mark(setOf("m"))
    private val inclusive = Decoration.Mark(setOf("m"), inclusiveStart = true, inclusiveEnd = true)

    @Test fun exclusiveMarksDoNotGrowAtTheirEdges() {
        val set = RangeSet.of(listOf(Ranged(2, 5, mark)))
        assertEquals(listOf(Ranged(3, 6, mark)), set.map(ChangeSet.of(8, ChangeSpec(2, 2, "x"))).ranges)
        assertEquals(listOf(Ranged(2, 5, mark)), set.map(ChangeSet.of(8, ChangeSpec(5, 5, "x"))).ranges)
    }

    @Test fun inclusiveMarksGrow() {
        val set = RangeSet.of(listOf(Ranged(2, 5, inclusive)))
        assertEquals(listOf(Ranged(2, 6, inclusive)), set.map(ChangeSet.of(8, ChangeSpec(2, 2, "x"))).ranges)
        assertEquals(listOf(Ranged(2, 6, inclusive)), set.map(ChangeSet.of(8, ChangeSpec(5, 5, "x"))).ranges)
    }

    @Test fun deletedRangesDisappearAndPartialOnesShrink() {
        val set = RangeSet.of(listOf(Ranged(2, 5, mark), Ranged(6, 9, mark)))
        val mapped = set.map(ChangeSet.of(10, ChangeSpec(1, 7)))
        assertEquals(listOf(Ranged(1, 3, mark)), mapped.ranges)
    }

    @Test fun pointDecorationsSurviveAndRespectSide() {
        val before = Decoration.InlineWidget(WidgetKey("w", "a"), side = -1)
        val after = Decoration.InlineWidget(WidgetKey("w", "b"), side = 1)
        val set = RangeSet.of(listOf(Ranged(3, 3, before), Ranged(3, 3, after)))
        val mapped = set.map(ChangeSet.of(5, ChangeSpec(3, 3, "xy"))).ranges
        assertEquals(listOf(Ranged(3, 3, before), Ranged(5, 5, after)), mapped)
    }

    @Test fun betweenFindsOverlapsIncludingEdges() {
        val set = RangeSet.of(listOf(Ranged(0, 2, mark), Ranged(4, 6, mark), Ranged(9, 9, mark)))
        assertEquals(2, set.between(2, 4).size)
        assertEquals(1, set.between(9, 20).size)
    }
}
```

- [ ] **Step 2: Run it and check that it fails**

Run: `apps/editor-spike/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:jvmTest --tests 'dev.supermux.editor.core.RangeSetTest' --console=plain"`
Expected: compilation FAILS: `Decoration`, `RangeSet`, `Ranged` are unresolved.

- [ ] **Step 3: Implement**

`apps/editor-core/src/commonMain/kotlin/dev/supermux/editor/core/Decorations.kt`:
```kotlin
package dev.supermux.editor.core

/**
 * What a plugin wants drawn, as DATA. The core never draws; editor-compose maps these to pixels.
 *
 * Styling is by semantic class names (`tok-keyword`, `diff-add`, `search-match`) that the
 * surface's theme resolves, so no Compose type ever enters a plugin. Widgets are referenced by
 * [WidgetKey]; the surface looks up what to render for a key.
 */
sealed class Decoration {
    /** Style a span of text. [inclusiveStart]/[inclusiveEnd]: does text typed at the edge join the mark? */
    data class Mark(
        val classes: Set<String>,
        val inclusiveStart: Boolean = false,
        val inclusiveEnd: Boolean = false,
    ) : Decoration()

    /** Style a whole line (put it at the line's start position, zero length). */
    data class LineStyle(val classes: Set<String>) : Decoration()

    /** A widget inside a line, at a point. [side] < 0 draws it before a cursor at that point. */
    data class InlineWidget(val key: WidgetKey, val side: Int = 1) : Decoration()

    /**
     * A widget with its own height BETWEEN lines: diff alignment gaps, "⋯ 120 unchanged lines",
     * review threads. [above] places it above the line containing the position, else below.
     */
    data class BlockWidget(val key: WidgetKey, val above: Boolean = false, val estimatedHeightLines: Float = 1f) : Decoration()

    /** Hide a range, optionally showing a widget instead (a folded region's "…"). */
    data class Replace(val widget: WidgetKey? = null) : Decoration()
}

/** Identifies widget content: [type] picks the renderer, [id] the instance (a thread id, a fold). */
data class WidgetKey(val type: String, val id: String)

/** One decorated range. Point decorations have from == to. */
data class Ranged<T>(val from: Int, val to: Int, val value: T) {
    init { require(from in 0..to) { "invalid range $from..$to" } }
}

/**
 * An immutable, sorted set of ranged values that moves through edits.
 *
 * Sorted by (from, to). A range entirely inside deleted text disappears when mapped; marks grow
 * or not at their edges according to their inclusive flags.
 */
class RangeSet<T> private constructor(val ranges: List<Ranged<T>>) {
    val size: Int get() = ranges.size

    /** Every range overlapping [from, to]; point ranges at the edges count. */
    fun between(from: Int, to: Int): List<Ranged<T>> = ranges.filter { it.to >= from && it.from <= to }

    fun map(changes: ChangeSet): RangeSet<T> {
        if (changes.isEmpty) return this
        val out = ArrayList<Ranged<T>>(ranges.size)
        for (r in ranges) {
            val v = r.value
            val (startAssoc, endAssoc) = if (v is Decoration.Mark) {
                (if (v.inclusiveStart) -1 else 1) to (if (v.inclusiveEnd) 1 else -1)
            } else if (r.from == r.to) {
                val side = (v as? Decoration.InlineWidget)?.side ?: -1
                side to side
            } else 1 to -1
            val from = changes.mapPos(r.from, startAssoc)
            val to = changes.mapPos(r.to, endAssoc)
            // A range whose text was all deleted carries nothing any more.
            if (r.from < r.to && from >= to) continue
            out += Ranged(from, to, v)
        }
        return of(out)
    }

    fun update(add: List<Ranged<T>> = emptyList(), filter: ((Ranged<T>) -> Boolean)? = null): RangeSet<T> =
        of((if (filter == null) ranges else ranges.filter(filter)) + add)

    override fun equals(other: Any?) = other is RangeSet<*> && other.ranges == ranges
    override fun hashCode() = ranges.hashCode()
    override fun toString() = ranges.joinToString { "${it.from}-${it.to}:${it.value}" }

    companion object {
        fun <T> empty(): RangeSet<T> = RangeSet(emptyList())
        fun <T> of(ranges: List<Ranged<T>>): RangeSet<T> =
            RangeSet(ranges.sortedWith(compareBy({ it.from }, { it.to })))
    }
}

/** Decorations from every plugin; the surface draws all of them, in precedence order. */
val decorationsFacet: Facet<RangeSet<Decoration>, List<RangeSet<Decoration>>> = Facet.list("decorations")
```

- [ ] **Step 4: Run it and check that it passes**

Run the Step 2 command again. Expected: BUILD SUCCESSFUL, every test in `RangeSetTest` passes.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-core
git commit -m "feat(editor-core): decoration data model and mappable range sets"
```

---

### Task 8: Every target compiles, and the iOS tests pass

- [ ] **Step 1: Run the full check on the Mac**

Run:
```bash
apps/editor-spike/mac-sync.sh
ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-core:jvmTest :editor-core:iosSimulatorArm64Test :editor-core:compileKotlinWasmJs :editor-core:compileDebugKotlinAndroid --console=plain"
```
Expected: BUILD SUCCESSFUL. The JVM results show 35 tests across 6 suites
(`ChangeSetTest` 9, `CommandsTest` 2, `RangeSetTest` 5, `RopeTest` 7, `SelectionTest` 3, `StateTest` 9).

- [ ] **Step 2: Record it in shared memory**

Append under `## editor-core landed (<date>)` in `~/.mux/domains/_inbox.md`: the module's path, the
"field reads earlier fields only" rule, and the UTF-16-only decision (pending M0 §2).

- [ ] **Step 3: Commit** (only if Step 2 changed tracked files; the memory file is outside the repo)

## Deliberately NOT in M1 (and where each goes)

- `ChangeSet.map(other)`, operational transform for concurrent changes: **M4**, with the history plugin and
  the "file changed on disk while editing" merge.
- View plugins, panels, gutters and widget renderers need a view: **M3** (`editor-compose`).
- Undo history, search, folding, LSP and diff are plugins: **M4**.
- A faster `RangeSet` (chunked, for tens of thousands of highlight spans): **M2/M3**, once the highlighter
  shows whether the simple sorted list is too slow.
