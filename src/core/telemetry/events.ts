// The telemetry contract. TELEMETRY.md (repo root) documents every event and field below for
// users; the broker builds events against this file and the ingest server rejects anything it
// doesn't allow. Change TELEMETRY.md in the same commit as any change here.
import { AGENT_KINDS } from "../../shared/agents"

export const EVENT_NAMES = [
  "broker.started", "broker.heartbeat", "client.connected", "session.created",
  "turn.requested", "turn.completed", "model.switched", "feature.used",
] as const
export type EventName = (typeof EVENT_NAMES)[number]

export const SURFACES = ["android", "ios", "desktop", "web", "telegram", "whatsapp"] as const
export type Surface = (typeof SURFACES)[number]
export const CLIENT_OSES = ["android", "ios", "macos", "windows", "linux"] as const
export const INSTALL_MODES = ["binary", "docker", "source", "managed"] as const

// feature → allowed variants (empty = the feature takes no variant).
export const FEATURE_VARIANTS: Record<string, readonly string[]> = {
  "worktree.create": [],
  "forge.clone": ["github", "gitlab"],
  "forge.create": ["github", "gitlab", "local"],
  "workspace.create": [],
  "session.archive": [],
  "session.mute": [],
  "terminal.open": [],
  "editor.open": [],
  "file.write": [],
  "upload": [],
  "display.open": [],
  "emulator.open": [],
  "voice.transcribe": [],
  "voice.speak": [],
  "search.messages": [],
  "proxy.expose": [],
  // Mode ids are validated loosely here (slug, ≤32) because each agent has its own list; the
  // broker only ever sends an id that `isPermissionMode(agent, id)` accepted.
  "permission_mode.set": ["*"],
  "pair.device": ["android", "ios", "desktop", "web"],
  "update.run": [],
}
export const FEATURES = Object.keys(FEATURE_VARIANTS)

type Check = { optional?: true; test: (v: unknown) => boolean }

const str = (max: number): Check => ({ test: (v) => typeof v === "string" && v.length > 0 && v.length <= max })
const oneOf = (values: readonly string[]): Check => ({ test: (v) => typeof v === "string" && values.includes(v) })
const int = (min: number, max: number): Check => ({ test: (v) => Number.isInteger(v) && (v as number) >= min && (v as number) <= max })
const bool: Check = { test: (v) => typeof v === "boolean" }
const opt = (c: Check): Check => ({ ...c, optional: true })
const SLUG = /^[a-z0-9][a-z0-9._/-]*$/i

const agent = oneOf(AGENT_KINDS)
const model: Check = { test: (v) => typeof v === "string" && v.length > 0 && v.length <= 80 && SLUG.test(v) }
const effort: Check = { test: (v) => typeof v === "string" && v.length > 0 && v.length <= 24 && SLUG.test(v) }
const surface = oneOf(SURFACES)
const agentList: Check = { test: (v) => Array.isArray(v) && v.length <= AGENT_KINDS.length && v.every((a) => agent.test(a)) }
const channels: Check = {
  test: (v) => {
    if (!v || typeof v !== "object" || Array.isArray(v)) return false
    const o = v as Record<string, unknown>
    return Object.keys(o).every((k) => ["web", "telegram", "whatsapp"].includes(k) && typeof o[k] === "boolean")
  },
}

const BROKER_PROPS = { sessionCount: int(0, 1_000_000), workspaceCount: int(0, 1_000_000), agentsInstalled: agentList, channels }

const PROPS: Record<EventName, Record<string, Check>> = {
  "broker.started": BROKER_PROPS,
  "broker.heartbeat": { ...BROKER_PROPS, activeSessions24h: int(0, 1_000_000) },
  "client.connected": { surface, clientVersion: opt(str(32)), clientOs: opt(oneOf(CLIENT_OSES)), clientOsMajor: opt(int(1, 999)) },
  "session.created": { agent, model, effort, fast: bool },
  "turn.requested": { agent, surface: opt(surface) },
  "turn.completed": { agent, model, effort, fast: bool, status: oneOf(["completed", "error", "interrupted"]), durationMs: opt(int(0, 7 * 24 * 3600 * 1000)) },
  "model.switched": { agent, fromModel: model, toModel: model, fromEffort: effort, toEffort: effort },
  "feature.used": { feature: oneOf(FEATURES), variant: opt(str(32)), agent: opt(agent), surface: opt(surface) },
}

const COMMON: Record<string, Check> = {
  uuid: { test: (v) => typeof v === "string" && /^[0-9a-f-]{36}$/.test(v) },
  ts: { test: (v) => typeof v === "string" && v.length <= 32 && !Number.isNaN(Date.parse(v)) },
  installId: { test: (v) => typeof v === "string" && /^[0-9a-f-]{36}$/.test(v) },
  version: str(32),
  commit: str(40),
  installMode: oneOf(INSTALL_MODES),
  os: oneOf(["linux", "darwin", "win32"]),
  arch: oneOf(["x64", "arm64"]),
  event: oneOf(EVENT_NAMES),
}

export type TelemetryEvent = {
  uuid: string; ts: string; installId: string; version: string; commit: string
  installMode: string; os: string; arch: string; event: EventName; props: Record<string, unknown>
}

export type ValidateResult = { ok: true; event: TelemetryEvent } | { ok: false; error: string }

function checkFields(where: string, obj: Record<string, unknown>, schema: Record<string, Check>): string | null {
  for (const k of Object.keys(obj)) if (!(k in schema)) return `${where}: unknown property ${k}`
  for (const [k, c] of Object.entries(schema)) {
    if (obj[k] === undefined) { if (!c.optional) return `${where}: missing ${k}`; continue }
    if (!c.test(obj[k])) return `${where}: bad ${k}`
  }
  return null
}

export function validateEvent(input: unknown): ValidateResult {
  if (!input || typeof input !== "object" || Array.isArray(input)) return { ok: false, error: "event must be an object" }
  const { props, ...common } = input as Record<string, unknown>
  const commonErr = checkFields("event", common, COMMON)
  if (commonErr) return { ok: false, error: commonErr }
  const name = common.event as EventName
  if (!props || typeof props !== "object" || Array.isArray(props)) return { ok: false, error: `${name}: props must be an object` }
  const p = props as Record<string, unknown>
  const propErr = checkFields(name, p, PROPS[name])
  if (propErr) return { ok: false, error: propErr }
  if (name === "feature.used" && p.variant !== undefined) {
    const allowed = FEATURE_VARIANTS[p.feature as string]!
    const fits = allowed.includes("*") ? SLUG.test(p.variant as string) : allowed.includes(p.variant as string)
    if (!fits) return { ok: false, error: `feature.used: bad variant for ${p.feature}` }
  }
  return { ok: true, event: input as TelemetryEvent }
}
