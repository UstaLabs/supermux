// Pure geometry for the editor's resizable/collapsible file-tree sidebar.
// Kept free of Vue/DOM so the clamp + collapse rules can be unit-tested.

/** Absolute bounds for the persisted tree width (px). */
export const TREE_WIDTH = { default: 192, min: 140, max: 600 }

/** Dragging the handle below this width (px) collapses the tree instead of shrinking it. */
export const TREE_COLLAPSE_AT = 110

export function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value))
}

/** Sanitize a persisted/raw width into a usable, rounded px value within absolute bounds. */
export function clampTreeWidth(value: unknown): number {
  if (typeof value !== "number" || !Number.isFinite(value)) return TREE_WIDTH.default
  return clamp(Math.round(value), TREE_WIDTH.min, TREE_WIDTH.max)
}

export type ResizeAction = { type: "collapse" } | { type: "resize"; width: number }

/**
 * Decide what a drag to `desiredPx` should do, given live bounds.
 * Below `collapseAt` → collapse; otherwise resize, clamped to [min, max].
 */
export function resolveTreeResize(
  desiredPx: number,
  opts: { min: number; max: number; collapseAt: number },
): ResizeAction {
  if (!Number.isFinite(desiredPx)) return { type: "resize", width: opts.min }
  if (desiredPx < opts.collapseAt) return { type: "collapse" }
  return { type: "resize", width: clamp(desiredPx, opts.min, opts.max) }
}
