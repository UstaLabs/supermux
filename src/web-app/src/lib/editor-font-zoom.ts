// Pure, dependency-free font-size math shared by the code editor's zoom inputs
// (keyboard Cmd/Ctrl +/−/0 and two-finger pinch).
//
// This is the single source of truth for the web app. The mobile CodeMirror
// bundle (apps/android/codemirror/cm6-entry.mjs) mirrors this logic inline —
// it is bundled from a temp dir and can't import app source — so keep the two
// in sync (same range, same rounding).

export const FONT_SIZE = { default: 13, min: 10, max: 24 } as const

/** Round to a whole px and clamp into the allowed range. Non-numbers → default. */
export function clampFont(v: unknown): number {
  if (typeof v !== "number" || Number.isNaN(v)) return FONT_SIZE.default
  return Math.min(FONT_SIZE.max, Math.max(FONT_SIZE.min, Math.round(v)))
}

/** Keyboard step: bump the current size by `delta` px, clamped. */
export function stepFont(current: number, delta: number): number {
  return clampFont(current + delta)
}

/**
 * Pinch: scale the size captured at gesture start (`baseFont`, `baseDist`) by the
 * live two-finger distance ratio, clamped + rounded. A non-positive base distance
 * (no valid gesture start) leaves the base size unchanged.
 */
export function pinchFont(baseFont: number, baseDist: number, curDist: number): number {
  if (!(baseDist > 0)) return clampFont(baseFont)
  return clampFont(baseFont * (curDist / baseDist))
}
