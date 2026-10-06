import { CoreError } from "./errors.js"
import type { SessionConfiguration } from "./types.js"

const CONFIGURATION_KEYS = new Set(["model", "reasoningEffort"])

/** Full requested state or patch. `undefined` values omit/clear that key. */
export function mergeConfiguration(current: SessionConfiguration, patch: SessionConfiguration): SessionConfiguration {
  if (!patch || typeof patch !== "object" || Array.isArray(patch)) {
    throw new CoreError("invalid_input", "Invalid session configuration")
  }
  const next: SessionConfiguration = { ...current }
  for (const key of Object.keys(patch) as (keyof SessionConfiguration)[]) {
    if (!CONFIGURATION_KEYS.has(key)) throw new CoreError("invalid_input", "Unknown configuration key")
    const value = patch[key]
    if (value === undefined) delete next[key]
    else if (typeof value !== "string" || !value) throw new CoreError("invalid_input", "Configuration values must be nonempty strings")
    else next[key] = value
  }
  return next
}

export function assertConfiguration(value?: SessionConfiguration): void {
  if (value === undefined) return
  mergeConfiguration({}, value)
}

/** Drop undefined values. Empty object means driver defaults (omit on disk / open). */
export function normalizeRequestedConfiguration(value?: SessionConfiguration): SessionConfiguration | undefined {
  if (value === undefined) return undefined
  const next = mergeConfiguration({}, value)
  return Object.keys(next).length > 0 ? next : undefined
}

export function nonemptyConfiguration(value?: SessionConfiguration): value is SessionConfiguration {
  return !!value && Object.keys(value).some(key => (value as Record<string, unknown>)[key] !== undefined)
}
