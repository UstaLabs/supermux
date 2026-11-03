// Card #54: turn perf.jsonl back into something a person reads — one app launch or one chat open
// as a timeline in the app's own clock, with the broker's half of each request nested under the app
// step that asked for it (paired by request id, so clock skew between the two machines can never
// reorder them), and the WebSocket traffic per frame type. Used by scripts/perf-timeline.ts.
import { perfPath, type PerfEvent } from "./perf-log"

export type TraceKind = "launch" | "chat"

const kindOf = (trace: string): TraceKind | undefined => (trace.startsWith("L") ? "launch" : trace.startsWith("C") ? "chat" : undefined)

export function parsePerfLines(text: string): PerfEvent[] {
  const out: PerfEvent[] = []
  for (const line of text.split("\n")) {
    if (!line.trim()) continue
    try {
      const e = JSON.parse(line) as PerfEvent
      if (e && typeof e.event === "string" && typeof e.ts === "number") out.push(e)
    } catch { /* a torn last line, or not ours */ }
  }
  return out
}

export interface TraceSummary { trace: string; kind: TraceKind; first: number; device?: string; surface?: string; events: number }

/** Every launch and chat-open trace, oldest first. */
export function listTraces(events: PerfEvent[]): TraceSummary[] {
  const byTrace = new Map<string, TraceSummary>()
  for (const e of events) {
    const kind = e.trace ? kindOf(e.trace) : undefined
    if (!e.trace || !kind) continue
    const s = byTrace.get(e.trace) ?? { trace: e.trace, kind, first: e.ts, events: 0 }
    s.first = Math.min(s.first, e.ts)
    s.events++
    if (e.src === "client") {
      s.device ??= str(e.device)
      s.surface ??= str(e.surface)
    }
    byTrace.set(e.trace, s)
  }
  return [...byTrace.values()].sort((a, b) => a.first - b.first)
}

export function latestTrace(events: PerfEvent[], kind: TraceKind, device?: string): string | undefined {
  return listTraces(events).filter((t) => t.kind === kind && (device === undefined || t.device === device)).at(-1)?.trace
}

// ── formatting ──────────────────────────────────────────────────────────────────────────────────

const str = (v: unknown): string | undefined => (typeof v === "string" ? v : undefined)
const num = (v: unknown): number | undefined => (typeof v === "number" && Number.isFinite(v) ? v : undefined)
const ms = (v: number) => `${Math.round(v)} ms`
const secs = (v: number) => `${(v / 1000).toFixed(1)} s`

export function size(bytes: number): string {
  if (bytes >= 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(2)} MB`
  const kb = bytes / 1024
  return kb >= 100 ? `${Math.round(kb)} KB` : `${kb.toFixed(1)} KB`
}

/** Fields worth showing that the line format does not already say. */
const QUIET = new Set(["ts", "src", "event", "trace", "req", "t", "device", "surface", "version", "launch", "host"])
function rest(e: PerfEvent, also: string[] = []): string {
  return Object.entries(e)
    .filter(([k, v]) => !QUIET.has(k) && !also.includes(k) && v !== undefined && v !== null && typeof v !== "object")
    .map(([k, v]) => `${k}=${v}`)
    .join(" ")
}

function median(xs: number[]): number {
  if (xs.length === 0) return 0
  const s = [...xs].sort((a, b) => a - b)
  return s[Math.floor(s.length / 2)]!
}

function lapsText(laps: unknown): string {
  if (!laps || typeof laps !== "object") return ""
  return Object.entries(laps as Record<string, number>).map(([k, v]) => `${k} ${Math.round(v)}`).join(" · ")
}

function keyBytesText(keyBytes: unknown, top = 5): string {
  if (!keyBytes || typeof keyBytes !== "object") return ""
  return Object.entries(keyBytes as Record<string, number>)
    .filter(([k]) => k !== "type")
    .sort((a, b) => b[1] - a[1])
    .slice(0, top)
    .map(([k, v]) => `${k} ${size(v)}`)
    .join(", ")
}

/** The broker's half of one request, as the line nested under the app's step. */
function brokerLine(b: PerfEvent): string {
  const took = num(b.ms)
  switch (b.event) {
    case "snapshot.built":
      return `broker built the snapshot in ${ms(took ?? 0)}: ${lapsText(b.laps)} — ${size(num(b.bytes) ?? 0)} (${keyBytesText(b.keyBytes)})`
    case "ws.upgrade":
      return `broker authenticated the socket in ${num(b.authMs) ?? 0} ms`
    case "http": {
      const parts = [`broker ${ms(took ?? 0)}`]
      if (num(b.count) !== undefined) parts.push(`${b.count} rows, sqlite ${Math.round(num(b.logMs) ?? 0)} · attachments ${Math.round(num(b.enrichMs) ?? 0)}`)
      if (num(b.activity) !== undefined) parts.push(`${b.activity} activity rows, ${b.commands} commands`)
      return parts.join(": ")
    }
    default:
      return `broker ${b.event} ${rest(b)}`
  }
}

/** The app's request line: what it asked, what came back, and where the time went. */
function httpLine(e: PerfEvent, body: PerfEvent | undefined): string {
  const route = str(e.route) ?? perfPath(str(e.path) ?? "?")
  const parts = [`${e.method ?? "GET"} ${route}`, `${e.status ?? "?"}`]
  if (num(e.bytes) !== undefined) parts.push(size(num(e.bytes)!))
  else if (body && num(body.chars) !== undefined) parts.push(size(num(body.chars)!))
  let timing = `${ms(num(e.ms) ?? 0)} to headers`
  if (num(e.serverMs) !== undefined) timing += ` (broker ${ms(num(e.serverMs)!)})`
  parts.push(timing)
  if (body) parts.push(`body ${ms(num(body.bodyMs) ?? 0)} + decode ${ms(num(body.decodeMs) ?? 0)}`)
  return parts.join(" · ")
}

interface Row { t: number; text: string; nested: string[] }

/**
 * The trace's rows in app time. App events give their own `t`; broker events that pair with an app
 * request (same req) nest under it; the rest are placed by the median app↔broker clock offset.
 */
function rows(events: PerfEvent[], trace: string, origin: number, describe: (e: PerfEvent, mine: PerfEvent[]) => string | undefined): Row[] {
  const mine = events.filter((e) => e.trace === trace)
  const client = mine.filter((e) => e.src === "client" && num(e.t) !== undefined).sort((a, b) => num(a.t)! - num(b.t)!)
  const broker = mine.filter((e) => e.src === "broker")
  const offset = median(client.map((e) => e.ts - num(e.t)!))
  const anchorOf = (b: PerfEvent): PerfEvent | undefined => {
    if (!b.req) return undefined
    const same = client.filter((c) => c.req === b.req)
    const prefer = b.event === "snapshot.built" ? "ws.subscribe.sent" : b.event === "ws.upgrade" ? "ws.open" : b.event === "http" ? "http" : undefined
    return same.find((c) => c.event === prefer) ?? same[0]
  }
  const nested = new Map<PerfEvent, string[]>()
  const out: Row[] = []
  for (const b of broker) {
    const anchor = anchorOf(b)
    if (anchor) nested.set(anchor, [...(nested.get(anchor) ?? []), brokerLine(b)])
    else out.push({ t: b.ts - offset - origin, text: brokerLine(b), nested: [] })
  }
  for (const c of client) {
    const text = describe(c, mine)
    if (text === undefined) continue
    out.push({ t: num(c.t)! - origin, text, nested: nested.get(c) ?? [] })
  }
  return out.sort((a, b) => a.t - b.t)
}

function table(rs: Row[]): string[] {
  const lines: string[] = []
  let prev = 0
  for (const r of rs) {
    lines.push(`${String(Math.round(r.t)).padStart(7)} ${String(Math.round(r.t - prev)).padStart(6)}  ${r.text}`)
    for (const n of r.nested) lines.push(`${" ".repeat(16)}└ ${n}`)
    prev = r.t
  }
  return lines
}

function describeClient(e: PerfEvent, mine: PerfEvent[]): string | undefined {
  const name = e.event.padEnd(24)
  switch (e.event) {
    case "http.body":
      return undefined // folded into its request's line
    case "http":
      return `${name}${httpLine(e, mine.find((x) => x.event === "http.body" && x.req === e.req))}`
    case "process.start":
      return `${name}${num(e.uptimeMs) !== undefined ? `the process ran ${e.uptimeMs} ms before this` : ""}`
    case "ws.open":
      return `${name}handshake ${ms(num(e.handshakeMs) ?? 0)}`
    case "ws.snapshot.arrived": {
      const built = mine.find((x) => x.event === "snapshot.built" && x.req === e.req)
      const waited = num(e.sinceSubscribeMs) ?? 0
      const brokerMs = num(built?.ms)
      return `${name}${size(num(e.chars) ?? 0)}, ${ms(waited)} after subscribe${brokerMs !== undefined ? ` (broker ${ms(brokerMs)} → network ~${ms(waited - brokerMs)})` : ""}`
    }
    case "ws.snapshot.decoded":
      return `${name}decode ${ms(num(e.decodeMs) ?? 0)}`
    default:
      return `${name}${rest(e)}`
  }
}

function header(mine: PerfEvent[], title: string): string {
  const first = mine.find((e) => e.src === "client") ?? mine[0]
  const who = [str(first?.surface), str(first?.version), first?.device ? `on ${first.device}` : undefined].filter(Boolean).join(" ")
  const when = first ? new Date(first.ts).toISOString().replace("T", " ").slice(0, 19) : ""
  return `${title} — ${who}${who ? ", " : ""}${when} UTC\n${"t(ms)".padStart(7)} ${"+Δ".padStart(6)}  event`
}

export function renderLaunch(events: PerfEvent[], trace: string): string {
  const mine = events.filter((e) => e.trace === trace)
  if (mine.length === 0) return `No events for ${trace}.`
  const lines = [header(mine, `Launch ${trace}`), ...table(rows(events, trace, 0, describeClient))]
  const at = (name: string) => num(mine.find((e) => e.src === "client" && e.event === name)?.t)
  const parts: string[] = []
  const open = at("ws.open")
  if (open !== undefined) parts.push(`connected at ${secs(open)}`)
  const arrived = mine.find((e) => e.event === "ws.snapshot.arrived")
  const built = mine.find((e) => e.event === "snapshot.built")
  if (arrived) {
    const bits = [built ? `broker ${ms(num(built.ms) ?? 0)}` : undefined, built && num(arrived.sinceSubscribeMs) !== undefined ? `network ~${ms(num(arrived.sinceSubscribeMs)! - (num(built.ms) ?? 0))}` : undefined]
    const decoded = mine.find((e) => e.event === "ws.snapshot.decoded")
    const reduced = mine.find((e) => e.event === "state.snapshot.reduced")
    if (decoded) bits.push(`decode ${ms(num(decoded.decodeMs) ?? 0)}`)
    if (reduced) bits.push(`reduce ${ms(num(reduced.ms) ?? 0)}`)
    parts.push(`snapshot in at ${secs(num(arrived.t) ?? 0)} (${bits.filter(Boolean).join(", ")})`)
  }
  const sessionsAt = at("ui.sessions.drawn")
  if (sessionsAt !== undefined) parts.push(`sessions on screen at ${secs(sessionsAt)}`)
  const wsAt = at("ui.workspaces.drawn")
  if (wsAt !== undefined) parts.push(`workspaces on screen at ${secs(wsAt)}`)
  const agentsAt = at("ui.agents.drawn")
  const picturesAt = at("avatars.loaded")
  if (agentsAt !== undefined) parts.push(`Agents at ${secs(agentsAt)}${picturesAt !== undefined ? `, their pictures at ${secs(picturesAt)}` : ""}`)
  const http = mine.filter((e) => e.src === "client" && e.event === "http")
  if (http.length) parts.push(`${http.length} HTTP request${http.length === 1 ? "" : "s"}, ${size(http.reduce((n, e) => n + (num(e.bytes) ?? 0), 0))}`)
  lines.push("", `Summary: ${parts.join(" · ")}`)
  return lines.join("\n")
}

export function renderChat(events: PerfEvent[], trace: string): string {
  const mine = events.filter((e) => e.trace === trace)
  if (mine.length === 0) return `No events for ${trace}.`
  const open = mine.find((e) => e.event === "chat.open")
  const origin = num(open?.t) ?? 0
  const title = `Chat open ${trace}${open ? ` — "${open.name ?? "?"}" (${open.session ?? "?"})` : ""}`
  const lines = [header(mine, title), ...table(rows(events, trace, origin, describeClient))]
  const at = (name: string) => num(mine.find((e) => e.src === "client" && e.event === name)?.t)
  const parts: string[] = []
  if (open) parts.push(open.loaded ? "already in memory, nothing fetched" : `fetched ${size(mine.filter((e) => e.src === "client" && e.event === "http").reduce((n, e) => n + (num(e.bytes) ?? 0), 0))}`)
  const first = at("chat.firstFrame")
  const complete = at("chat.complete")
  if (first !== undefined) parts.push(`history on screen at ${ms(first - origin)}${complete !== undefined ? `, all of it at ${ms(complete - origin)}` : ""}`)
  lines.push("", `Summary: ${parts.join(" · ")}`)
  return lines.join("\n")
}

/** WebSocket traffic per frame type (broker `ws.frames`) plus what the apps spent on it (`client.stats`). */
export function renderWs(events: PerfEvent[], since = 0): string {
  const frames = events.filter((e) => e.event === "ws.frames" && e.ts >= since)
  const totals = new Map<string, { frames: number; bytes: number; sends: number }>()
  let periodMs = 0
  let worstLag = 0
  let over50 = 0
  for (const f of frames) {
    periodMs += num(f.periodMs) ?? 60_000
    for (const [type, s] of Object.entries((f.frames ?? {}) as Record<string, { frames: number; bytes: number; sends: number }>)) {
      const t = totals.get(type) ?? { frames: 0, bytes: 0, sends: 0 }
      t.frames += s.frames
      t.bytes += s.bytes
      t.sends += s.sends
      totals.set(type, t)
    }
    const lag = f.loopLag as { maxMs?: number; over50ms?: number } | undefined
    worstLag = Math.max(worstLag, lag?.maxMs ?? 0)
    over50 += lag?.over50ms ?? 0
  }
  const minutes = Math.max(1, periodMs / 60_000)
  const lines = [`Broadcast frames over ${minutes.toFixed(0)} min (bytes are per frame; sends = frames × sockets):`]
  for (const [type, t] of [...totals].sort((a, b) => b[1].bytes - a[1].bytes)) {
    lines.push(`  ${type.padEnd(22)} ${t.frames} frames  ${size(t.bytes).padStart(9)}  ${(t.frames / minutes).toFixed(1)}/min  ${size(t.bytes / minutes)}/min  ${t.sends} sends`)
  }
  if (frames.length) lines.push(`  event loop: worst ${Math.round(worstLag)} ms late, ${over50} ticks more than 50 ms late`)
  const stats = events.filter((e) => e.event === "client.stats" && e.ts >= since)
  const byDevice = new Map<string, PerfEvent[]>()
  for (const s of stats) byDevice.set(str(s.device) ?? "?", [...(byDevice.get(str(s.device) ?? "?") ?? []), s])
  for (const [device, list] of byDevice) {
    lines.push(``, `App ${device} (${list.length} reports):`)
    const decode = new Map<string, { n: number; chars: number; decodeMs: number; maxDecodeMs: number; reduceMs: number }>()
    const recompose = new Map<string, number>()
    for (const s of list) {
      for (const [type, f] of Object.entries((s.frames ?? {}) as Record<string, { n: number; chars: number; decodeMs: number; maxDecodeMs: number }>)) {
        const d = decode.get(type) ?? { n: 0, chars: 0, decodeMs: 0, maxDecodeMs: 0, reduceMs: 0 }
        d.n += f.n; d.chars += f.chars; d.decodeMs += f.decodeMs; d.maxDecodeMs = Math.max(d.maxDecodeMs, f.maxDecodeMs)
        decode.set(type, d)
      }
      for (const [type, r] of Object.entries((s.reduce ?? {}) as Record<string, { ms: number }>)) {
        const d = decode.get(type) ?? { n: 0, chars: 0, decodeMs: 0, maxDecodeMs: 0, reduceMs: 0 }
        d.reduceMs += r.ms
        decode.set(type, d)
      }
      for (const [scope, n] of Object.entries((s.recompose ?? {}) as Record<string, number>)) recompose.set(scope, (recompose.get(scope) ?? 0) + n)
    }
    for (const [type, d] of [...decode].sort((a, b) => b[1].decodeMs + b[1].reduceMs - a[1].decodeMs - a[1].reduceMs)) {
      lines.push(`  ${type.padEnd(22)} ${d.n} frames  ${size(d.chars).padStart(9)}  decode ${d.decodeMs.toFixed(1)} ms (worst ${d.maxDecodeMs.toFixed(1)})  reduce ${d.reduceMs.toFixed(1)} ms`)
    }
    if (recompose.size) lines.push(`  recompositions: ${[...recompose].map(([k, v]) => `${k} ${v}`).join(" · ")}`)
  }
  return lines.join("\n")
}
