/**
 * The workspace layout tree: splits and groups, VS-Code style.
 *
 * Pure logic only — no bun:sqlite, no node:*, no I/O. Phase 2 ports this file to
 * Kotlin (apps/shared) with parity tests that lock the two implementations
 * together, exactly as PredictiveEcho.kt and TerminalKeys.kt are locked to their
 * TypeScript twins. Keep it portable.
 *
 * Spec: docs/superpowers/specs/2026-08-06-workspaces-and-views-design.md §5.3
 */

export type LayoutGroup = {
  type: "group"
  id: string
  viewIds: string[]
  activeViewId?: string
}

export type LayoutSplit = {
  type: "split"
  direction: "row" | "column"
  /** Fractions, one per child, all > 0, adding up to 1. */
  sizes: number[]
  children: LayoutNode[]
}

export type LayoutNode = LayoutGroup | LayoutSplit

/** Float comparison tolerance for the sizes-add-up-to-1 rule. */
const SIZE_EPSILON = 1e-6

export function singleViewLayout(groupId: string, viewId: string): LayoutNode {
  return { type: "group", id: groupId, viewIds: [viewId], activeViewId: viewId }
}

/** Every view id in the tree, in document order. Duplicates are kept — validateLayout reports them. */
export function collectViewIds(node: LayoutNode): string[] {
  if (node.type === "group") return [...node.viewIds]
  return node.children.flatMap(collectViewIds)
}

/**
 * Returns null when the tree is valid, or a human-readable reason when it is not.
 * The HTTP layer turns a non-null return into a 400.
 */
export function validateLayout(node: LayoutNode): string | null {
  const seen = new Set<string>()
  const walk = (n: LayoutNode): string | null => {
    if (n.type === "group") {
      if (n.viewIds.length === 0) return `empty group: ${n.id}`
      for (const v of n.viewIds) {
        if (seen.has(v)) return `duplicate view id: ${v}`
        seen.add(v)
      }
      if (n.activeViewId !== undefined && !n.viewIds.includes(n.activeViewId)) {
        return `activeViewId not in group ${n.id}: ${n.activeViewId}`
      }
      return null
    }
    if (n.sizes.length !== n.children.length) {
      return `split sizes length ${n.sizes.length} does not match children length ${n.children.length}`
    }
    if (n.children.length < 2) {
      return `split needs at least 2 children, got ${n.children.length}`
    }
    if (n.sizes.some((s) => s <= 0)) return "split sizes must all be greater than 0"
    const total = n.sizes.reduce((a, b) => a + b, 0)
    if (Math.abs(total - 1) > SIZE_EPSILON) {
      // Trim the float noise so the message is readable (0.7, not 0.7000000000000001).
      return `split sizes must add up to 1, got ${Number(total.toFixed(6))}`
    }
    for (const c of n.children) {
      const err = walk(c)
      if (err) return err
    }
    return null
  }
  return walk(node)
}

/**
 * Repair a tree into a valid one, or return null when nothing is left.
 *
 * - an empty group is dropped
 * - a split with one surviving child becomes that child
 * - a split with no surviving child is dropped
 * - sizes are re-spread evenly whenever the child count changed
 * - an activeViewId that is not in its group falls back to the first view
 *
 * Run this after EVERY structural edit. A drag that leaves an empty group is the
 * normal case, not an error.
 */
export function normalizeLayout(node: LayoutNode): LayoutNode | null {
  if (node.type === "group") {
    if (node.viewIds.length === 0) return null
    const active = node.activeViewId !== undefined && node.viewIds.includes(node.activeViewId)
      ? node.activeViewId
      : node.viewIds[0]
    return { type: "group", id: node.id, viewIds: [...node.viewIds], activeViewId: active }
  }

  const kept: Array<{ child: LayoutNode; size: number }> = []
  node.children.forEach((child, i) => {
    const n = normalizeLayout(child)
    if (n !== null) kept.push({ child: n, size: node.sizes[i] ?? 0 })
  })

  if (kept.length === 0) return null
  if (kept.length === 1) return kept[0]!.child

  // Re-spread only when a child was dropped; an untouched split keeps the user's
  // drag positions. An even spread on every normalize would reset the splitter
  // every time an unrelated tab closed somewhere else in the tree.
  const sizes = kept.length === node.children.length
    ? kept.map((k) => k.size)
    : kept.map(() => 1 / kept.length)

  return { type: "split", direction: node.direction, sizes, children: kept.map((k) => k.child) }
}

/** Append a view to one group and make it the active tab. Unknown group id = no change. */
export function addViewToGroup(node: LayoutNode, groupId: string, viewId: string): LayoutNode {
  if (node.type === "group") {
    if (node.id !== groupId) return node
    if (node.viewIds.includes(viewId)) return { ...node, activeViewId: viewId }
    return { type: "group", id: node.id, viewIds: [...node.viewIds, viewId], activeViewId: viewId }
  }
  return {
    ...node,
    children: node.children.map((c) => addViewToGroup(c, groupId, viewId)),
  }
}

/** Remove a view wherever it is, then normalize. Returns null when the tree empties. */
export function removeViewFromLayout(node: LayoutNode, viewId: string): LayoutNode | null {
  const strip = (n: LayoutNode): LayoutNode => {
    if (n.type === "group") {
      return { ...n, viewIds: n.viewIds.filter((v) => v !== viewId) }
    }
    return { ...n, children: n.children.map(strip) }
  }
  return normalizeLayout(strip(node))
}

/**
 * Move [viewId] to [index] within its own group. Out-of-range indices clamp.
 * The active view is unchanged — reordering tabs must not switch which one you
 * are looking at.
 */
export function reorderWithinGroup(
  node: LayoutNode,
  groupId: string,
  viewId: string,
  index: number,
): LayoutNode {
  if (node.type === "group") {
    if (node.id !== groupId || !node.viewIds.includes(viewId)) return node
    const rest = node.viewIds.filter((v) => v !== viewId)
    const at = Math.max(0, Math.min(index, rest.length))
    return {
      ...node,
      viewIds: [...rest.slice(0, at), viewId, ...rest.slice(at)],
    }
  }
  return {
    ...node,
    children: node.children.map((c) => reorderWithinGroup(c, groupId, viewId, index)),
  }
}

/**
 * Move [viewId] out of wherever it is and into [toGroupId] at [index], and make
 * it active there — you dragged it, you want to see it.
 *
 * Emptying the source group collapses it, and a split left with one child
 * collapses too; that is normalizeLayout's job and it runs here. Returns null
 * only if the whole tree emptied, which cannot happen while the moved view still
 * exists — but the signature stays nullable to match removeViewFromLayout.
 */
export function moveViewToGroup(
  node: LayoutNode,
  viewId: string,
  toGroupId: string,
  index: number,
): LayoutNode | null {
  // Same-group move is a reorder; going through remove+add would briefly empty
  // a one-view group and collapse the split out from under the user.
  const owner = groupIdOf(node, viewId)
  if (owner === toGroupId) return reorderWithinGroup(node, toGroupId, viewId, index)
  if (!hasGroup(node, toGroupId)) return node

  const without = removeViewFromLayout(node, viewId)
  if (without === null) return node
  if (!hasGroup(without, toGroupId)) return node
  return normalizeLayout(insertIntoGroup(without, toGroupId, viewId, index))
}

/** The id of the group holding [viewId], or null. */
export function groupIdOf(node: LayoutNode, viewId: string): string | null {
  if (node.type === "group") {
    return node.viewIds.includes(viewId) ? node.id : null
  }
  for (const c of node.children) {
    const id = groupIdOf(c, viewId)
    if (id !== null) return id
  }
  return null
}

function hasGroup(node: LayoutNode, groupId: string): boolean {
  if (node.type === "group") return node.id === groupId
  return node.children.some((c) => hasGroup(c, groupId))
}

function insertIntoGroup(
  node: LayoutNode,
  groupId: string,
  viewId: string,
  index: number,
): LayoutNode {
  if (node.type === "group") {
    if (node.id !== groupId) return node
    const at = Math.max(0, Math.min(index, node.viewIds.length))
    return {
      type: "group",
      id: node.id,
      viewIds: [...node.viewIds.slice(0, at), viewId, ...node.viewIds.slice(at)],
      activeViewId: viewId,
    }
  }
  return {
    ...node,
    children: node.children.map((c) => insertIntoGroup(c, groupId, viewId, index)),
  }
}
