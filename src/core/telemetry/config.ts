// Is telemetry on? Precedence: DO_NOT_TRACK / MUX_TELEMETRY (env) → dev/test-build guard →
// the saved choice (settings key "telemetry") → on. TELEMETRY.md "Turning it off" documents this.
export const SETTINGS_KEY_TELEMETRY = "telemetry"

export type TelemetrySetting = { enabled?: boolean; noticeShown?: boolean }
export type TelemetryEnv = Record<string, string | undefined>
export type TelemetryBuild = { buildVersion: string; testBroker: boolean }
export type TelemetryState = { enabled: boolean; source: "env" | "build" | "setting" | "default"; noticeShown: boolean }

const OFF = new Set(["0", "false", "off", "no"])

function sanitize(stored: unknown): TelemetrySetting {
  if (!stored || typeof stored !== "object") return {}
  const o = stored as Record<string, unknown>
  const out: TelemetrySetting = {}
  if (typeof o.enabled === "boolean") out.enabled = o.enabled
  if (typeof o.noticeShown === "boolean") out.noticeShown = o.noticeShown
  return out
}

export function resolveTelemetry(stored: unknown, env: TelemetryEnv, build: TelemetryBuild): TelemetryState {
  const s = sanitize(stored)
  const noticeShown = s.noticeShown ?? false
  const dnt = env.DO_NOT_TRACK?.trim()
  if (dnt && dnt !== "0") return { enabled: false, source: "env", noticeShown }
  if (OFF.has(env.MUX_TELEMETRY?.trim().toLowerCase() ?? "")) return { enabled: false, source: "env", noticeShown }
  const forced = env.MUX_TELEMETRY_FORCE === "1"
  if (!forced && (build.buildVersion === "dev" || build.testBroker)) return { enabled: false, source: "build", noticeShown }
  if (s.enabled !== undefined) return { enabled: s.enabled, source: "setting", noticeShown }
  return { enabled: true, source: "default", noticeShown }
}

export function mergeTelemetrySetting(stored: unknown, patch: TelemetrySetting): TelemetrySetting {
  return { ...sanitize(stored), ...sanitize(patch) }
}

export function parseTelemetryBody(input: unknown): { enabled: boolean } | { error: string } {
  if (!input || typeof input !== "object" || Array.isArray(input)) return { error: "body must be an object" }
  const o = input as Record<string, unknown>
  if (typeof o.enabled !== "boolean") return { error: "enabled must be a boolean" }
  return { enabled: o.enabled }
}
