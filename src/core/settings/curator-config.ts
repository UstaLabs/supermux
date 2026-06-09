// Curator configuration: persisted in the `settings` table under key "curator",
// edited from the PWA settings page, and compiled to a cron expression for the
// scheduler. Friendly daily picker (HH:MM) over a real cron engine.

export interface CuratorConfig {
  enabled: boolean
  hour: number // 0..23
  minute: number // 0..59
}

export const SETTINGS_KEY_CURATOR = "curator"

export const defaultCuratorConfig: CuratorConfig = {
  enabled: false,
  hour: 1,
  minute: 0,
}

function clampInt(v: unknown, lo: number, hi: number, fallback: number): number {
  const n = typeof v === "number" ? Math.floor(v) : Number(v)
  if (!Number.isFinite(n)) return fallback
  return Math.min(hi, Math.max(lo, n))
}

/**
 * Coerce arbitrary input (env, JSON, request body) into a valid CuratorConfig.
 * Clamps hour/minute into range; requires a non-empty chatId (falls back to the
 * default). Never throws — bad input yields a safe config.
 */
export function parseCuratorConfig(input: unknown, base: CuratorConfig = defaultCuratorConfig): CuratorConfig {
  const o = (input ?? {}) as Record<string, unknown>
  // A stray `chatId` from an older stored config is ignored — web is one channel,
  // the digest fans out to all devices.
  return {
    enabled: o.enabled === undefined ? base.enabled : Boolean(o.enabled),
    hour: clampInt(o.hour, 0, 23, base.hour),
    minute: clampInt(o.minute, 0, 59, base.minute),
  }
}

/** Daily-at-HH:MM compiled to a standard 5-field cron expression. */
export function toCron(cfg: CuratorConfig): string {
  return `${cfg.minute} ${cfg.hour} * * *`
}
