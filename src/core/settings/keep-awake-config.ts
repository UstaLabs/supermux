// "Keep this computer awake" (spec 2026-09-30-desktop-hosting-lifecycle-design, "Keep the computer
// awake while hosting"). Persisted in the `settings` table under key "keepAwake" as a SPARSE
// `{ enabled?, onBattery? }`: only what the user chose is stored, so the defaults below keep showing
// through for the rest. Precedence at read time: saved user choice → `MUX_KEEP_AWAKE` → default.

export interface KeepAwakeSettings {
  /** Hold the keep-awake inhibitor while this broker runs. */
  enabled: boolean
  /** "Also on battery": when false the inhibitor is released while the computer runs on battery. */
  onBattery: boolean
}

export const SETTINGS_KEY_KEEP_AWAKE = "keepAwake"

export type KeepAwakeEnv = Record<string, string | undefined>

/**
 * Defaults: ON for a desktop-managed broker (`MUX_MANAGED_BY=desktop`), OFF for a CLI/server broker.
 * `MUX_KEEP_AWAKE` (1/0, true/false, on/off, any case) overrides that default (it never overrides a
 * saved choice). Anything else is ignored.
 */
export function defaultKeepAwakeSettings(env: KeepAwakeEnv): KeepAwakeSettings {
  let enabled = env.MUX_MANAGED_BY === "desktop"
  const override = env.MUX_KEEP_AWAKE?.trim().toLowerCase()
  if (override === "1" || override === "true" || override === "on") enabled = true
  else if (override === "0" || override === "false" || override === "off") enabled = false
  return { enabled, onBattery: true }
}

/** The booleans out of arbitrary input (stored row, request body); anything else is dropped. */
export function sanitizeKeepAwakePatch(input: unknown): Partial<KeepAwakeSettings> {
  if (!input || typeof input !== "object") return {}
  const o = input as Record<string, unknown>
  const out: Partial<KeepAwakeSettings> = {}
  if (typeof o.enabled === "boolean") out.enabled = o.enabled
  if (typeof o.onBattery === "boolean") out.onBattery = o.onBattery
  return out
}

/** Strict request-body check: an object whose only known keys are booleans, at least one present. */
export function parseKeepAwakeBody(input: unknown): Partial<KeepAwakeSettings> | { error: string } {
  if (!input || typeof input !== "object" || Array.isArray(input)) return { error: "body must be an object" }
  const o = input as Record<string, unknown>
  for (const k of ["enabled", "onBattery"] as const) {
    if (o[k] !== undefined && typeof o[k] !== "boolean") return { error: `${k} must be a boolean` }
  }
  const patch = sanitizeKeepAwakePatch(o)
  if (Object.keys(patch).length === 0) return { error: "nothing to change (enabled / onBattery)" }
  return patch
}

/** Saved choice over env over built-in default. */
export function resolveKeepAwakeSettings(stored: unknown, env: KeepAwakeEnv): KeepAwakeSettings {
  return { ...defaultKeepAwakeSettings(env), ...sanitizeKeepAwakePatch(stored) }
}
