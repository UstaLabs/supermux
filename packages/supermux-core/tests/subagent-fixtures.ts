import { readFileSync } from "node:fs"
import { dirname, join } from "node:path"
import { fileURLToPath } from "node:url"
import type { AgentUpdate } from "../src/types.js"
import type { NormalizedBody } from "../src/events/normalized.js"

const dir = dirname(fileURLToPath(import.meta.url))

export type Row = { d: "c" | "a"; m: Record<string, any> }

export const fixturePath = (name: string) => join(dir, "fixtures/real/subagents", name)
export const replayAgent = join(dir, "fixtures/replay-agent.mjs")

export function rows(name: string): Row[] {
  return readFileSync(fixturePath(name), "utf8").split("\n").filter(Boolean).map(line => JSON.parse(line) as Row)
}

const claudePlumbing = new Set(["control_request", "control_response", "control_cancel_request", "keep_alive"])

/** What the Claude driver forwards to onUpdate: every agent frame except control plumbing. */
export function claudeUpdates(name: string): AgentUpdate[] {
  return rows(name)
    .filter(r => r.d === "a" && !claudePlumbing.has(r.m.type))
    .map(r => ({ protocol: "native" as const, value: r.m }))
}

/** Agent notifications (no response frames), shaped like the Codex driver's onUpdate values. */
export function codexUpdates(name: string, keep: (m: Record<string, any>) => boolean = () => true): AgentUpdate[] {
  return rows(name)
    .filter(r => r.d === "a" && typeof r.m.method === "string" && keep(r.m))
    .map(r => ({ protocol: "native" as const, value: { method: r.m.method, params: r.m.params, ...(r.m.id !== undefined ? { id: r.m.id } : {}) } }))
}

/** ACP updates as the ACP driver emits them (session/update keeps its sessionId). */
export function acpUpdates(name: string): AgentUpdate[] {
  const out: AgentUpdate[] = []
  for (const r of rows(name)) {
    if (r.d !== "a" || typeof r.m.method !== "string") continue
    if (r.m.method === "session/update") out.push({ protocol: "acp", value: r.m.params.update, sessionId: r.m.params.sessionId })
    else out.push({ protocol: "native", value: { method: r.m.method, params: r.m.params } })
  }
  return out
}

export function acpMainSession(name: string): string {
  for (const r of rows(name)) {
    if (r.d === "a" && r.m.result && typeof r.m.result.sessionId === "string") return r.m.result.sessionId
  }
  throw new Error(`no session/new result in ${name}`)
}

export function run(normalize: ((u: AgentUpdate) => NormalizedBody[]) & { flush?: () => NormalizedBody[] }, updates: AgentUpdate[]): NormalizedBody[] {
  const out: NormalizedBody[] = []
  for (const update of updates) out.push(...normalize(update))
  if (normalize.flush) out.push(...normalize.flush())
  return out
}

export type SubagentBody = Extract<NormalizedBody, { kind: "subagent" }>
export const subagentEvents = (events: NormalizedBody[], id?: string): SubagentBody[] =>
  events.filter((e): e is SubagentBody => e.kind === "subagent" && (id === undefined || e.subagentId === id))

export const TERMINAL = new Set(["completed", "failed", "cancelled"])

/**
 * Lifecycle invariants: one `started` first, terminal phases only after a start/resume, never two
 * terminals in a row without a `resumed` between them.
 */
export function assertLifecycle(events: SubagentBody[]): void {
  if (!events.length) throw new Error("no subagent events")
  if (events[0]!.phase !== "started") throw new Error(`first phase is ${events[0]!.phase}`)
  if (events.filter(e => e.phase === "started").length !== 1) throw new Error("more than one started")
  let open = false
  for (const e of events) {
    if (e.phase === "started" || e.phase === "resumed") {
      if (open && e.phase === "resumed") throw new Error("resumed while still running")
      open = true
    } else if (TERMINAL.has(e.phase)) {
      if (!open) throw new Error(`terminal ${e.phase} without an open run`)
      open = false
    }
  }
}

/** Main-thread assistant text (bodies without subagentId). */
export const mainText = (events: NormalizedBody[]): string =>
  events.filter(e => e.kind === "assistant-message" && !e.subagentId).map(e => (e as { text: string }).text).join("\n")
