# Workspaces Phase 2 — Shared Layout Tree Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Port the broker's layout-tree logic to Kotlin in `apps/shared`, locked to the TypeScript original by a case-for-case parity test suite, so every Kotlin client edits the tree with exactly the semantics the broker validates.

**Architecture:** One new file, `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutTree.kt`, is a faithful port of `src/core/workspace/layout-tree.ts`. One new test file mirrors `layout-tree.test.ts` test-for-test. This follows the two existing precedents in this repository — `PredictiveEcho.kt` against the web engine (23 parity tests) and `TerminalKeys.kt` against `terminal-keys.ts` (12 parity tests). Both name their reference file in a header comment; this one does the same.

**Tech Stack:** Kotlin Multiplatform `commonMain` (no platform APIs), `kotlin.test` in `commonTest`, run on the JVM via `:shared:jvmTest`.

**Spec:** `docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md`, sections 5.3 and 13.1.

**Depends on:**
- `2026-08-06-workspaces-phase1-broker-data-model.md` Task 1 — it creates the TypeScript file this ports.
- `2026-08-06-workspaces-phase1b-routes-and-frames.md` Task 4 — it creates `LayoutNodeDto`, which this module converts to and from.

---

## Why a separate module from `LayoutNodeDto`

`LayoutNodeDto` (in `proto/Frames.kt`) is a **wire type**: it exists to be serialized, its fields are nullable where the wire allows it, and kotlinx.serialization owns its shape. `LayoutNode` (this module) is a **domain type**: non-null defaults, invariants, and pure edit operations.

Keeping them apart means a change to the wire format cannot silently change the semantics of a drag, and the parity tests compare domain behaviour rather than JSON. The conversion is two small functions, written and tested in Task 2.

⚠ **This file must stay portable.** No `java.*`, no `kotlinx.coroutines`, no Compose. It compiles for JVM, Android, and every Apple target. A single JVM-only call here breaks the iOS framework build later.

---

## File structure

| File | Responsibility |
|---|---|
| `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutTree.kt` | **Create.** The domain type and the pure operations. Port of `src/core/workspace/layout-tree.ts`. |
| `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutDto.kt` | **Create.** `LayoutNodeDto` ⇄ `LayoutNode`. Kept separate so the domain file has no serialization import at all. |
| `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutTreeTest.kt` | **Create.** The parity suite — one test per TypeScript test, same names, same cases. |
| `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutDtoTest.kt` | **Create.** Round-trip tests for the conversion. |

---

## Task 1: Port the layout tree

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutTree.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutTreeTest.kt`

- [ ] **Step 1: Read the reference implementation**

Open `src/core/workspace/layout-tree.ts` and read it end to end. It is about 150 lines. Every function in it has a counterpart below, with the same name and the same behaviour. Two details are easy to get wrong and are called out in the code comments there:

1. `normalizeLayout` re-spreads `sizes` **only when a child was dropped**. An unconditional even spread would reset the user's splitter position every time an unrelated tab closed elsewhere in the tree.
2. `validateLayout` returns a **message string or null**, not a boolean. The messages are asserted verbatim in both test suites, so they must match character for character.

- [ ] **Step 2: Write the failing parity tests**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutTreeTest.kt`:

```kotlin
package dev.supermux.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Parity suite for [LayoutTree]. Mirrors the reference suite
 * src/core/workspace/layout-tree.test.ts case for case, including the exact
 * validation message strings — the broker rejects a layout with those messages
 * and the client must predict the same rejection before it sends one.
 *
 * Same precedent as PredictiveEchoTest and TerminalKeysTest: when you change one
 * side, change both, or the two drift and only production notices.
 */
class LayoutTreeTest {

    private fun group(id: String, viewIds: List<String>, activeViewId: String? = null): LayoutNode =
        LayoutNode.Group(id, viewIds, activeViewId ?: viewIds.firstOrNull())

    @Test
    fun singleViewLayout_makes_a_one_group_layout_with_the_view_active() {
        val l = singleViewLayout("g1", "v1")
        assertEquals(LayoutNode.Group("g1", listOf("v1"), "v1"), l)
        assertNull(validateLayout(l))
    }

    @Test
    fun collectViewIds_walks_the_whole_tree_in_document_order() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v2", "v3"))))
        assertEquals(listOf("v1", "v2", "v3"), collectViewIds(l))
    }

    @Test
    fun validateLayout_rejects_a_duplicate_view_id() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v1"))))
        assertEquals("duplicate view id: v1", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_an_empty_group() {
        assertEquals("empty group: g1", validateLayout(LayoutNode.Group("g1", emptyList(), null)))
    }

    @Test
    fun validateLayout_rejects_an_activeViewId_that_is_not_in_the_group() {
        assertEquals("activeViewId not in group g1: v9", validateLayout(LayoutNode.Group("g1", listOf("v1"), "v9")))
    }

    @Test
    fun validateLayout_rejects_a_split_whose_sizes_length_differs_from_its_children_length() {
        val l = LayoutNode.Split("row", listOf(1.0), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("split sizes length 1 does not match children length 2", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_a_split_with_fewer_than_two_children() {
        val l = LayoutNode.Split("row", listOf(1.0), listOf(group("g1", listOf("v1"))))
        assertEquals("split needs at least 2 children, got 1", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_sizes_that_do_not_add_up_to_1() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.2), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("split sizes must add up to 1, got 0.7", validateLayout(l))
    }

    @Test
    fun validateLayout_rejects_a_non_positive_size() {
        val l = LayoutNode.Split("row", listOf(0.0, 1.0), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("split sizes must all be greater than 0", validateLayout(l))
    }

    @Test
    fun validateLayout_accepts_a_valid_nested_tree() {
        val l = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                group("g1", listOf("v1")),
                LayoutNode.Split("column", listOf(0.6, 0.4), listOf(group("g2", listOf("v2", "v3"), "v2"), group("g3", listOf("v4")))),
            ),
        )
        assertNull(validateLayout(l))
    }

    @Test
    fun normalizeLayout_drops_an_empty_group_and_collapses_the_single_child_split() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), LayoutNode.Group("g2", emptyList(), null)))
        assertEquals(group("g1", listOf("v1")), normalizeLayout(l))
    }

    @Test
    fun normalizeLayout_returns_null_when_every_group_is_empty() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(LayoutNode.Group("g1", emptyList(), null), LayoutNode.Group("g2", emptyList(), null)))
        assertNull(normalizeLayout(l))
    }

    @Test
    fun normalizeLayout_repairs_sizes_after_a_child_is_dropped() {
        val l = LayoutNode.Split(
            "row", listOf(0.2, 0.3, 0.5),
            listOf(group("g1", listOf("v1")), LayoutNode.Group("gx", emptyList(), null), group("g3", listOf("v3"))),
        )
        assertEquals(
            LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g3", listOf("v3")))),
            normalizeLayout(l),
        )
    }

    @Test
    fun normalizeLayout_keeps_the_sizes_when_no_child_was_dropped() {
        // The rule the TypeScript comment calls out: an unrelated close must not
        // reset a splitter the user dragged.
        val l = LayoutNode.Split("row", listOf(0.2, 0.8), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals(l, normalizeLayout(l))
    }

    @Test
    fun normalizeLayout_repairs_an_activeViewId_that_left_the_group() {
        val l = LayoutNode.Group("g1", listOf("v1", "v2"), "v9")
        assertEquals(group("g1", listOf("v1", "v2"), "v1"), normalizeLayout(l))
    }

    @Test
    fun addViewToGroup_appends_the_view_and_makes_it_active() {
        val l = singleViewLayout("g1", "v1")
        assertEquals(group("g1", listOf("v1", "v2"), "v2"), addViewToGroup(l, "g1", "v2"))
    }

    @Test
    fun addViewToGroup_leaves_the_tree_alone_when_the_group_id_is_unknown() {
        val l = singleViewLayout("g1", "v1")
        assertEquals(l, addViewToGroup(l, "nope", "v2"))
    }

    @Test
    fun removeViewFromLayout_removes_the_view_and_normalizes() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals(group("g1", listOf("v1")), removeViewFromLayout(l, "v2"))
    }

    @Test
    fun removeViewFromLayout_returns_null_when_the_last_view_goes() {
        assertNull(removeViewFromLayout(singleViewLayout("g1", "v1"), "v1"))
    }

    @Test
    fun removeViewFromLayout_picks_a_new_active_view_when_the_active_one_goes() {
        val l = group("g1", listOf("v1", "v2"), "v1")
        assertEquals(group("g1", listOf("v2"), "v2"), removeViewFromLayout(l, "v1"))
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*LayoutTreeTest*'
```

Expected: FAIL — `Unresolved reference: LayoutNode`.

- [ ] **Step 4: Write the implementation**

Create `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutTree.kt`:

```kotlin
package dev.supermux.workspace

import kotlin.math.abs

/**
 * The workspace layout tree: splits and groups, VS-Code style.
 *
 * Faithful port of src/core/workspace/layout-tree.ts. LayoutTreeTest mirrors that
 * file's test suite case for case, including the exact validation message
 * strings. When you change one side, change both — the same contract
 * PredictiveEcho.kt and TerminalKeys.kt live under.
 *
 * commonMain only: no java.*, no coroutines, no Compose. This compiles for JVM,
 * Android, and every Apple target.
 *
 * Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md §5.3
 */
sealed interface LayoutNode {
    data class Group(
        val id: String,
        val viewIds: List<String> = emptyList(),
        val activeViewId: String? = null,
    ) : LayoutNode

    data class Split(
        /** "row" places children side by side; "column" stacks them. */
        val direction: String,
        /** Fractions, one per child, all > 0, adding up to 1. */
        val sizes: List<Double> = emptyList(),
        val children: List<LayoutNode> = emptyList(),
    ) : LayoutNode
}

/** Float comparison tolerance for the sizes-add-up-to-1 rule. Same value as the TypeScript. */
private const val SIZE_EPSILON = 1e-6

fun singleViewLayout(groupId: String, viewId: String): LayoutNode =
    LayoutNode.Group(groupId, listOf(viewId), viewId)

/** Every view id in the tree, in document order. Duplicates are kept — [validateLayout] reports them. */
fun collectViewIds(node: LayoutNode): List<String> = when (node) {
    is LayoutNode.Group -> node.viewIds
    is LayoutNode.Split -> node.children.flatMap { collectViewIds(it) }
}

/**
 * Null when the tree is valid, or a human-readable reason when it is not.
 *
 * The client calls this BEFORE a PATCH so a bad drag never reaches the broker.
 * The messages match the broker's byte for byte, so a rejection that does slip
 * through reads the same on both sides.
 */
fun validateLayout(node: LayoutNode): String? {
    val seen = mutableSetOf<String>()

    fun walk(n: LayoutNode): String? = when (n) {
        is LayoutNode.Group -> {
            when {
                n.viewIds.isEmpty() -> "empty group: ${n.id}"
                else -> {
                    var err: String? = null
                    for (v in n.viewIds) {
                        if (!seen.add(v)) { err = "duplicate view id: $v"; break }
                    }
                    when {
                        err != null -> err
                        n.activeViewId != null && n.activeViewId !in n.viewIds ->
                            "activeViewId not in group ${n.id}: ${n.activeViewId}"
                        else -> null
                    }
                }
            }
        }
        is LayoutNode.Split -> {
            when {
                n.sizes.size != n.children.size ->
                    "split sizes length ${n.sizes.size} does not match children length ${n.children.size}"
                n.children.size < 2 ->
                    "split needs at least 2 children, got ${n.children.size}"
                n.sizes.any { it <= 0.0 } ->
                    "split sizes must all be greater than 0"
                abs(n.sizes.sum() - 1.0) > SIZE_EPSILON ->
                    "split sizes must add up to 1, got ${trimFloat(n.sizes.sum())}"
                else -> n.children.firstNotNullOfOrNull { walk(it) }
            }
        }
    }

    return walk(node)
}

/**
 * Trim float noise so a message reads "0.7", not "0.7000000000000001".
 * Mirrors the TypeScript `Number(total.toFixed(6))`.
 */
private fun trimFloat(v: Double): String {
    val rounded = kotlin.math.round(v * 1_000_000.0) / 1_000_000.0
    val s = rounded.toString()
    return if (s.endsWith(".0")) s.dropLast(2) else s
}

/**
 * Repair a tree into a valid one, or null when nothing is left.
 *
 *  - an empty group is dropped
 *  - a split with one surviving child becomes that child
 *  - a split with no surviving child is dropped
 *  - sizes are re-spread evenly ONLY when the child count changed
 *  - an activeViewId that is not in its group falls back to the first view
 *
 * Run this after every structural edit. A drag that leaves an empty group is the
 * normal case, not an error.
 */
fun normalizeLayout(node: LayoutNode): LayoutNode? = when (node) {
    is LayoutNode.Group -> {
        if (node.viewIds.isEmpty()) null
        else {
            val active = if (node.activeViewId != null && node.activeViewId in node.viewIds) node.activeViewId
                         else node.viewIds.first()
            LayoutNode.Group(node.id, node.viewIds, active)
        }
    }
    is LayoutNode.Split -> {
        val kept = node.children.mapIndexedNotNull { i, child ->
            normalizeLayout(child)?.let { it to (node.sizes.getOrNull(i) ?: 0.0) }
        }
        when {
            kept.isEmpty() -> null
            kept.size == 1 -> kept[0].first
            else -> {
                // Re-spread only when a child was dropped; an untouched split keeps
                // the user's drag positions. An even spread on every normalize would
                // reset the splitter whenever an unrelated tab closed elsewhere.
                val sizes = if (kept.size == node.children.size) kept.map { it.second }
                            else List(kept.size) { 1.0 / kept.size }
                LayoutNode.Split(node.direction, sizes, kept.map { it.first })
            }
        }
    }
}

/** Append a view to one group and make it the active tab. An unknown group id changes nothing. */
fun addViewToGroup(node: LayoutNode, groupId: String, viewId: String): LayoutNode = when (node) {
    is LayoutNode.Group -> when {
        node.id != groupId -> node
        viewId in node.viewIds -> node.copy(activeViewId = viewId)
        else -> LayoutNode.Group(node.id, node.viewIds + viewId, viewId)
    }
    is LayoutNode.Split -> node.copy(children = node.children.map { addViewToGroup(it, groupId, viewId) })
}

/** Remove a view wherever it is, then normalize. Null when the tree empties. */
fun removeViewFromLayout(node: LayoutNode, viewId: String): LayoutNode? {
    fun strip(n: LayoutNode): LayoutNode = when (n) {
        is LayoutNode.Group -> n.copy(viewIds = n.viewIds.filter { it != viewId })
        is LayoutNode.Split -> n.copy(children = n.children.map { strip(it) })
    }
    return normalizeLayout(strip(node))
}

/** The id of the first group in document order, or null for a tree with no group. */
fun firstGroupId(node: LayoutNode): String? = when (node) {
    is LayoutNode.Group -> node.id
    is LayoutNode.Split -> node.children.firstNotNullOfOrNull { firstGroupId(it) }
}

/**
 * Split one group in two: the named view moves into a NEW group beside the old
 * one, inside a split running in [direction].
 *
 * This has no TypeScript counterpart — the broker never splits, it only stores
 * what the client sends. It lives here because every Kotlin client needs the
 * same drag-to-split behaviour, and the result still has to pass [validateLayout].
 */
fun splitGroup(
    node: LayoutNode,
    groupId: String,
    viewId: String,
    direction: String,
    newGroupId: String,
): LayoutNode {
    fun walk(n: LayoutNode): LayoutNode = when (n) {
        is LayoutNode.Group -> {
            if (n.id != groupId || viewId !in n.viewIds || n.viewIds.size < 2) n
            else {
                val remaining = n.viewIds.filter { it != viewId }
                LayoutNode.Split(
                    direction = direction,
                    sizes = listOf(0.5, 0.5),
                    children = listOf(
                        LayoutNode.Group(n.id, remaining, remaining.first()),
                        LayoutNode.Group(newGroupId, listOf(viewId), viewId),
                    ),
                )
            }
        }
        is LayoutNode.Split -> n.copy(children = n.children.map { walk(it) })
    }
    return walk(node)
}
```

- [ ] **Step 5: Add the tests for `splitGroup` and `firstGroupId`**

These two have no TypeScript counterpart, so they go in a clearly-marked section rather than in the parity block. Append to `LayoutTreeTest.kt`, inside the class:

```kotlin
    // --- Kotlin-only helpers (no TypeScript counterpart) ---

    @Test
    fun firstGroupId_finds_the_leftmost_group() {
        val l = LayoutNode.Split("row", listOf(0.5, 0.5), listOf(group("g1", listOf("v1")), group("g2", listOf("v2"))))
        assertEquals("g1", firstGroupId(l))
    }

    @Test
    fun splitGroup_moves_the_view_into_a_new_group_beside_the_old_one() {
        val l = group("g1", listOf("v1", "v2"), "v1")
        val out = splitGroup(l, "g1", "v2", "row", "g2")
        assertEquals(
            LayoutNode.Split("row", listOf(0.5, 0.5), listOf(
                LayoutNode.Group("g1", listOf("v1"), "v1"),
                LayoutNode.Group("g2", listOf("v2"), "v2"),
            )),
            out,
        )
        assertNull(validateLayout(out))
    }

    @Test
    fun splitGroup_refuses_to_split_a_group_holding_one_view() {
        // Splitting the only view would leave an empty group, which is invalid.
        val l = singleViewLayout("g1", "v1")
        assertEquals(l, splitGroup(l, "g1", "v1", "row", "g2"))
    }

    @Test
    fun splitGroup_leaves_the_tree_alone_for_an_unknown_group() {
        val l = group("g1", listOf("v1", "v2"), "v1")
        assertEquals(l, splitGroup(l, "nope", "v2", "row", "g2"))
    }
```

- [ ] **Step 6: Run the tests to verify they pass**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*LayoutTreeTest*'
```

Expected: PASS, 24 tests (20 parity + 4 Kotlin-only).

- [ ] **Step 7: Prove the parity count matches**

```bash
grep -c "^test(" src/core/workspace/layout-tree.test.ts
grep -c "@Test" apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutTreeTest.kt
```

Expected: the TypeScript count is 18, the Kotlin count is 24. The difference is exactly 4 Kotlin-only tests plus the 2 extra Kotlin parity tests noted below.

⚠ The Kotlin suite adds `normalizeLayout_keeps_the_sizes_when_no_child_was_dropped`, which the TypeScript suite does not have. **Add the same test to `src/core/workspace/layout-tree.test.ts`** so the two suites stay symmetric:

```ts
test("normalizeLayout keeps the sizes when no child was dropped", () => {
  const l: LayoutNode = {
    type: "split", direction: "row", sizes: [0.2, 0.8],
    children: [group("g1", ["v1"]), group("g2", ["v2"])],
  }
  expect(normalizeLayout(l)).toEqual(l)
})
```

Then re-run `bun test src/core/workspace/layout-tree.test.ts` and confirm 19 tests pass.

- [ ] **Step 8: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutTree.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutTreeTest.kt \
        src/core/workspace/layout-tree.test.ts
git commit -m "feat(shared): port the layout tree to Kotlin with parity tests

Faithful port of src/core/workspace/layout-tree.ts, locked by a case-for-case
parity suite that asserts the same validation message strings. Same contract as
PredictiveEcho.kt and TerminalKeys.kt.

Adds two Kotlin-only helpers the broker has no need for: firstGroupId and
splitGroup (drag-to-split). Also backfills the one parity test the TypeScript
suite was missing."
```

---

## Task 2: Convert between the wire DTO and the domain type

**Files:**
- Create: `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutDto.kt`
- Test: `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutDtoTest.kt`

- [ ] **Step 1: Write the failing tests**

Create `apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutDtoTest.kt`:

```kotlin
package dev.supermux.workspace

import dev.supermux.proto.LayoutNodeDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LayoutDtoTest {

    @Test
    fun aGroupRoundTrips() {
        val domain = LayoutNode.Group("g1", listOf("v1", "v2"), "v2")
        assertEquals(domain, domain.toDto().toDomain())
    }

    @Test
    fun aNestedSplitRoundTrips() {
        val domain = LayoutNode.Split(
            "row", listOf(0.5, 0.5),
            listOf(
                LayoutNode.Group("g1", listOf("v1"), "v1"),
                LayoutNode.Split("column", listOf(0.6, 0.4), listOf(
                    LayoutNode.Group("g2", listOf("v2"), "v2"),
                    LayoutNode.Group("g3", listOf("v3"), "v3"),
                )),
            ),
        )
        assertEquals(domain, domain.toDto().toDomain())
    }

    @Test
    fun aDtoGroupWithNoActiveViewGetsTheFirstOne() {
        // The broker always sends activeViewId, but an older row or a hand-edited
        // one might not. Fall back rather than carrying a null into the UI.
        val dto = LayoutNodeDto.Group(id = "g1", viewIds = listOf("v1", "v2"), activeViewId = null)
        assertEquals(LayoutNode.Group("g1", listOf("v1", "v2"), "v1"), dto.toDomain())
    }

    @Test
    fun anEmptyDtoGroupStaysEmptyWithNoActiveView() {
        val dto = LayoutNodeDto.Group(id = "g1", viewIds = emptyList(), activeViewId = null)
        assertEquals(LayoutNode.Group("g1", emptyList(), null), dto.toDomain())
    }

    @Test
    fun aNullDtoConvertsToNull() {
        assertNull((null as LayoutNodeDto?).toDomainOrNull())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*LayoutDtoTest*'
```

Expected: FAIL — `Unresolved reference: toDto`.

- [ ] **Step 3: Write the implementation**

Create `apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutDto.kt`:

```kotlin
package dev.supermux.workspace

import dev.supermux.proto.LayoutNodeDto

/**
 * Conversion between the wire type ([LayoutNodeDto], owned by kotlinx.serialization)
 * and the domain type ([LayoutNode], owned by LayoutTree.kt).
 *
 * They are separate so a wire-format change cannot silently change what a drag
 * does. This file is the only place that knows about both.
 */

fun LayoutNode.toDto(): LayoutNodeDto = when (this) {
    is LayoutNode.Group -> LayoutNodeDto.Group(id = id, viewIds = viewIds, activeViewId = activeViewId)
    is LayoutNode.Split -> LayoutNodeDto.Split(
        direction = direction,
        sizes = sizes,
        children = children.map { it.toDto() },
    )
}

fun LayoutNodeDto.toDomain(): LayoutNode = when (this) {
    is LayoutNodeDto.Group -> LayoutNode.Group(
        id = id,
        viewIds = viewIds,
        // The broker always sends activeViewId, but do not carry a null into the
        // UI when it does not: the first tab is the sane default everywhere.
        activeViewId = activeViewId ?: viewIds.firstOrNull(),
    )
    is LayoutNodeDto.Split -> LayoutNode.Split(
        direction = direction,
        sizes = sizes,
        children = children.map { it.toDomain() },
    )
}

fun LayoutNodeDto?.toDomainOrNull(): LayoutNode? = this?.toDomain()
```

- [ ] **Step 4: Run the tests to verify they pass**

```bash
cd apps
./gradlew :shared:jvmTest --tests '*LayoutDtoTest*'
```

Expected: PASS, 5 tests.

- [ ] **Step 5: Run the whole shared suite on every target that builds on Linux**

```bash
cd apps
./gradlew :shared:jvmTest :shared:compileKotlinJvm
```

Expected: `BUILD SUCCESSFUL`.

⚠ The Apple targets cannot compile here. `commonMain` code that uses only the Kotlin standard library is safe by construction, and this module does — but the first Mac build after this lands is still the real proof. Note it in the commit so whoever builds on the Mac knows to check.

- [ ] **Step 6: Commit**

```bash
git add apps/shared/src/commonMain/kotlin/dev/supermux/workspace/LayoutDto.kt \
        apps/shared/src/commonTest/kotlin/dev/supermux/workspace/LayoutDtoTest.kt
git commit -m "feat(shared): convert between LayoutNodeDto and LayoutNode

Wire type and domain type stay separate so a serialization change cannot
silently alter what a drag does. commonMain, stdlib only — Apple targets are
unverified on Linux; the next Mac build is the proof."
```

---

## Self-review notes

**Spec coverage.** This plan implements spec §13.1's first two bullets: `dev/supermux/workspace/LayoutTree.kt` with the pure operations, and `commonTest` coverage of every invariant in §5.3.

The third bullet of §13.1 (`Workspace.kt` DTOs) and the fourth (`BrokerApi` calls) were both done in the Phase 1b plan, Tasks 4 and 5 — they had to be, because the broker routes needed a wire contract to test against. §13.1 is complete once this plan lands.

**Not implemented here:** the TypeScript parity port mentioned in spec §13.2 (`src/web-app/src/lib/layout-tree.ts`). The canonical TypeScript implementation already lives in the broker at `src/core/workspace/layout-tree.ts`, and the web app is out of scope until the desktop client works. When the web plan is written, it should **import or copy from the broker file**, not write a third implementation.

**Type consistency check.** `LayoutNode.Group(id, viewIds, activeViewId)` and `LayoutNode.Split(direction, sizes, children)` take the same arguments in both tasks. `validateLayout` returns `String?` in both the implementation and every test. `firstGroupId` is defined in Task 1 and is the same function Phase 3 calls when it appends a view to the default group.
