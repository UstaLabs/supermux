// Curator configuration: persisted in the `settings` table under key "curator",
// edited from the PWA settings page, and compiled to a cron expression for the
// scheduler. Friendly daily picker (HH:MM) over a real cron engine.
// Agent/model/reasoningLevel choose which session the nightly run spawns —
// same fields as session launch / PA create.

import { type AgentKind, AgentKind as Agents, parseAgentKind } from "../../shared/agents"

export interface CuratorConfig {
  enabled: boolean
  hour: number // 0..23
  minute: number // 0..59
  /** Agent kind used for the nightly curator session. */
  agent: AgentKind
  /** Optional model id; empty/undefined → agent default. */
  model?: string
  /** Optional thinking/effort level; empty/undefined → agent default. */
  reasoningLevel?: string
}

export const SETTINGS_KEY_CURATOR = "curator"

export const defaultCuratorConfig: CuratorConfig = {
  enabled: false,
  hour: 1,
  minute: 0,
  agent: Agents.Claude,
}

function clampInt(v: unknown, lo: number, hi: number, fallback: number): number {
  const n = typeof v === "number" ? Math.floor(v) : Number(v)
  if (!Number.isFinite(n)) return fallback
  return Math.min(hi, Math.max(lo, n))
}

/** Empty / whitespace / non-string → undefined (agent default). */
function optionalString(v: unknown): string | undefined {
  if (typeof v !== "string") return undefined
  const t = v.trim()
  return t ? t : undefined
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
  let agent: AgentKind = base.agent
  try {
    if (o.agent !== undefined) agent = parseAgentKind(o.agent, base.agent)
  } catch {
    agent = base.agent
  }
  const cfg: CuratorConfig = {
    enabled: o.enabled === undefined ? base.enabled : Boolean(o.enabled),
    hour: clampInt(o.hour, 0, 23, base.hour),
    minute: clampInt(o.minute, 0, 59, base.minute),
    agent,
  }
  // model / reasoningLevel: missing key keeps base; present empty clears to default.
  if (o.model !== undefined) {
    const m = optionalString(o.model)
    if (m) cfg.model = m
  } else if (base.model) {
    cfg.model = base.model
  }
  if (o.reasoningLevel !== undefined) {
    const r = optionalString(o.reasoningLevel)
    if (r) cfg.reasoningLevel = r
  } else if (base.reasoningLevel) {
    cfg.reasoningLevel = base.reasoningLevel
  }
  return cfg
}

/** Daily-at-HH:MM compiled to a standard 5-field cron expression. */
export function toCron(cfg: CuratorConfig): string {
  return `${cfg.minute} ${cfg.hour} * * *`
}
