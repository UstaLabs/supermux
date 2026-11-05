// Card #54: turn perf.jsonl back into something a person reads — one app launch or one chat open
// as a timeline in the app's own clock, with the broker's half of each request nested under the app
// step that asked for it (paired by request id, so clock skew between the two machines can never
// reorder them), and the WebSocket traffic per frame type. Used by scripts/perf-timeline.ts.
import { perfPath, type PerfEvent } from "./perf-log"

export type TraceKind = "launch" | "chat" | "start"

const kindOf = (trace: string): TraceKind | undefined => (trace.startsWith("L") ? "launch" : trace.startsWith("C") ? "chat" : trace.startsWith("S") ? "start" : undefined)

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
function rows(events: PerfEvent[], trace: string, origin: number, describe: (e: PerfEvent, mine: PerfEvent[]) => string | undefined, describeBroker: (b: PerfEvent) => string = brokerLine): Row[] {
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
    else out.push({ t: b.ts - offset - origin, text: describeBroker(b), nested: [] })
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

// ── Session start (card #75) ────────────────────────────────────────────────────────────────────

/** A start step's own line (app and broker alike): what it did, and how long it took when it says. */
function describeStartEvent(e: PerfEvent): string {
  const name = e.event.replace(/^start\./, "")
  const took = num(e.ms) !== undefined ? ` ${ms(num(e.ms)!)}` : ""
  const side = e.src === "client" ? "app" : "broker"
  const label = `${side} ${name}`.padEnd(30)
  switch (name) {
    case "request": return `${label}${e.via ?? "?"} · ${e.agent ?? "?"}${e.model ? ` (${e.model})` : ""} · worktree ${e.worktree ? "yes" : "no"}${e.firstMessage ? " · with a first message" : ""}`
    case "account": return `${label}${took} · ${e.auto ? "Auto picked" : "asked for"} ${e.account ?? "?"}`
    case "repo": return `${label}${took} · repo probe (rev-parse, nested-repo scan, branch lists)${e.eligible === false ? " · NOT eligible: no worktree" : ""}`
    case "worktree.branches": return `${label}${took} · git for-each-ref (${e.count ?? "?"} branches)`
    case "worktree.add": return `${label}${took} · git worktree add -b ${e.branch ?? ""}`
    case "worktree.includes": return `${label}${took} · .worktreeinclude: ${e.copied ?? 0} entr${e.copied === 1 ? "y" : "ies"} copied`
    case "worktree.hook": return `${label}${took} · .mux/worktree-setup.sh ${e.present ? `ran (exit ${e.code})` : "absent"}`
    case "worktree": return `${label}${took} · worktree ready${e.failed ? ` · FAILED: ${e.failed}` : ""}`
    case "host.prepared": return `${label}${took} · plugins, instructions, agent environment`
    case "host.prepare.laps": return `${label}prepare split: ${lapsText(e.laps)} (${e.plugins ?? "?"} plugins, ${e.instructionsChars ?? "?"} chars of instructions)`
    case "claude.transport": return `${label}${took} · keeper started and connected`
    case "core.context": return `${label}${took} · session context folder (instructions file, plugin folder, MCP config)`
    case "keeper.spawned": return `${label}${took} · keeper process up (socket + status)`
    case "keeper.connected": return `${label}${took} · connected to the keeper`
    case "claude.initialized": return `${label}${took} · Claude answered initialize (${e.commands ?? "?"} slash commands)`
    case "codex.catalog": return `${label}${took} · \`codex debug models\` before the launch`
    case "codex.initialized": return `${label}${took} · Codex answered initialize`
    case "codex.thread": return `${label}${took} · thread/start`
    case "host.opened": return `${label}${took} · agent session open`
    case "spawned": return `${label}${took} · agent spawned and ready`
    case "telegram": return `${label}${took} · Telegram menu refresh (${e.where ?? "?"})${e.on === false ? " · Telegram off" : ""}`
    case "announced": return `${label}${took} · workspace + session_added frames`
    case "created": return `${label}${took} · create path done`
    case "send": return `${label}Send pressed · ${e.agent ?? "?"} · worktree ${e.worktree ? "yes" : "no"}${num(e.files) ? ` · ${e.files} files` : ""}`
    case "validated": return `${label}${took} · POST /paths/validate`
    case "uploaded": return `${label}${took} · ${e.files ?? 0} files uploaded`
    case "response": return `${label}the app has the new session${e.session ? ` (${String(e.session).slice(0, 8)})` : ""}`
    case "session_added": return `${label}session_added frame in`
    case "workspace_added": return `${label}workspace_added frame in`
    case "chat.shown": return `${label}the new chat is on screen`
    case "message.echoed": return `${label}the first message is back in the chat`
    case "agent.working": return `${label}agent shows as working${e.detail ? ` (${e.detail})` : ""}`
    case "activity": return `${label}first activity card in`
    case "reply.arrived": return `${label}reply frame in`
    case "reply.drawn": return `${label}reply on screen`
    case "message.delivering": return `${label}first message on its way to the agent (${e.chars ?? "?"} chars)`
    case "claude.prompt_written": case "codex.prompt_written": return `${label}prompt written to the agent`
    case "claude.system_init": return `${label}Claude's system/init${e.mcpServers ? ` · MCP: ${e.mcpServers}` : ""}`
    case "claude.first_stream": return `${label}Claude's first stream event (the model is answering)`
    case "codex.first_notify": return `${label}Codex's first notification (${e.method ?? "?"})`
    case "codex.first_text": return `${label}Codex's first text (the model is answering)`
    case "activity.first": return `${label}first activity card (${e.kind ?? "?"}${e.tool ? ` ${e.tool}` : ""})`
    case "reply.first": return `${label}first reply (${e.chars ?? "?"} chars)`
    case "turn.completed": return `${label}first turn over${e.error ? ` · ERROR ${e.error}` : ""}`
    default: return `${label}${took}${rest(e, ["ms", "session"]) ? ` · ${rest(e, ["ms", "session"])}` : ""}`
  }
}

/** The broker phases a start's wait is made of, in order: [label, event]. */
const START_PHASES: Array<[string, string]> = [
  ["Auto account choice", "start.account"],
  ["repo probe", "start.repo"],
  ["branch list", "start.worktree.branches"],
  ["git worktree add", "start.worktree.add"],
  [".worktreeinclude copies", "start.worktree.includes"],
  ["setup hook", "start.worktree.hook"],
  ["agent environment (prepare)", "start.host.prepared"],
  ["session context", "start.core.context"],
  ["codex debug models", "start.codex.catalog"],
  ["keeper up", "start.keeper.spawned"],
  ["Claude boot → initialize", "start.claude.initialized"],
  ["Codex initialize", "start.codex.initialized"],
  ["Codex thread/start", "start.codex.thread"],
  ["workspace + frames", "start.announced"],
]

/** One start's measured steps (ms) and milestones (ms from Send, or from the request when the app sent no marks). */
interface StartFacts {
  mine: PerfEvent[]
  origin: number
  request?: PerfEvent
  phases: Array<[string, number]>
  created?: number
  response?: number
  shown?: number
  reply?: number
  drawn?: number
  failed?: string
}

function startFacts(events: PerfEvent[], trace: string): StartFacts {
  const mine = events.filter((e) => e.trace === trace)
  const client = mine.filter((e) => e.src === "client" && num(e.t) !== undefined)
  const send = client.find((e) => e.event === "start.send")
  const request = mine.find((e) => e.src === "broker" && e.event === "start.request")
  // App time when the app traced it (origin: Send), else the broker's clock (origin: the request).
  const origin = send ? num(send.t)! : client.length ? num(client[0]!.t)! : (request ?? mine[0])?.ts ?? 0
  // When each milestone happened, on the timeline's own clock.
  const offset = median(client.map((e) => e.ts - num(e.t)!))
  const at = (event: string, src?: "client" | "broker"): number | undefined => {
    const e = mine.find((x) => x.event === event && (src === undefined || x.src === src))
    if (!e) return undefined
    return e.src === "client" && num(e.t) !== undefined ? num(e.t)! - origin : e.ts - (client.length ? offset : 0) - origin
  }
  const phases: Array<[string, number]> = []
  for (const [label, event] of START_PHASES) {
    const e = mine.find((x) => x.event === event)
    if (e && num(e.ms) !== undefined) phases.push([label, num(e.ms)!])
  }
  const telegram = mine.filter((e) => e.event === "start.telegram")
  if (telegram.length) phases.push([`Telegram menu refresh ×${telegram.length}`, telegram.reduce((n, e) => n + (num(e.ms) ?? 0), 0)])
  // The first turn, split where the marks allow: hand-over → prompt written → (Claude: system/init,
  // its MCP servers and repo snapshot) → the model's first output → the whole reply.
  const delivering = at("start.message.delivering")
  const written = at("start.claude.prompt_written") ?? at("start.codex.prompt_written")
  const init = at("start.claude.system_init")
  const firstOutput = at("start.claude.first_stream") ?? at("start.codex.first_text") ?? at("start.codex.first_notify")
  const reply = at("start.reply.first")
  if (delivering !== undefined && written !== undefined) phases.push(["first message → written to the agent", written - delivering])
  if (written !== undefined && init !== undefined) phases.push(["prompt → Claude's system/init", init - written])
  const modelFrom = init ?? written
  if (modelFrom !== undefined && firstOutput !== undefined) phases.push([init !== undefined ? "system/init → model's first output" : "prompt → model's first output", firstOutput - modelFrom])
  if (firstOutput !== undefined && reply !== undefined) phases.push(["first output → whole reply", reply - firstOutput])
  const failed = mine.find((e) => e.event === "start.failed" || e.event === "start.spawn_failed")
  return {
    mine, origin, request, phases,
    created: num(mine.find((e) => e.event === "start.created")?.ms),
    response: at("start.response", "client"),
    shown: at("start.chat.shown", "client"),
    reply,
    drawn: at("start.reply.drawn", "client"),
    failed: failed ? String(failed.err ?? failed.error ?? "?") : undefined,
  }
}

const dur = (v: number) => (v >= 1000 ? secs(v) : ms(v))

export function renderStart(events: PerfEvent[], trace: string): string {
  const f = startFacts(events, trace)
  const { mine, origin, request } = f
  if (mine.length === 0) return `No events for ${trace}.`
  const describe = (e: PerfEvent): string | undefined => {
    if (e.event === "http.body") return undefined
    if (e.event === "http") return `${"app http".padEnd(30)}${httpLine(e, mine.find((x) => x.event === "http.body" && x.req === e.req))}`
    return describeStartEvent(e)
  }
  const title = `Session start ${trace}${request ? ` — ${request.via ?? "?"}, ${request.agent ?? "?"}` : ""}`
  const lines = [header(mine, title), ...table(rows(events, trace, origin, describe, describeStartEvent))]

  lines.push("", "Where the time went (largest first; steps under 10 ms left out):")
  for (const [label, v] of [...f.phases].sort((a, b) => b[1] - a[1])) if (v >= 10) lines.push(`  ${label.padEnd(40)} ${dur(v).padStart(8)}`)

  const parts: string[] = []
  if (f.created !== undefined) parts.push(`created in ${secs(f.created)}`)
  if (f.response !== undefined) parts.push(`app had the answer at ${secs(f.response)}`)
  if (f.shown !== undefined) parts.push(`chat on screen at ${secs(f.shown)}`)
  if (f.reply !== undefined) parts.push(`first reply at ${secs(f.reply)}`)
  if (f.drawn !== undefined) parts.push(`drawn at ${secs(f.drawn)}`)
  if (f.failed) parts.push(`FAILED: ${f.failed}`)
  lines.push("", `Summary: ${parts.join(" · ")}`)
  return lines.join("\n")
}

/** Several starts side by side: each step's median, fastest and slowest, largest median first. */
export function renderStartSummary(events: PerfEvent[], traces: string[]): string {
  const facts = traces.map((t) => startFacts(events, t)).filter((f) => f.mine.length > 0)
  if (facts.length === 0) return "No session starts."
  const byPhase = new Map<string, number[]>()
  const add = (label: string, v: number | undefined) => {
    if (v === undefined) return
    byPhase.set(label, [...(byPhase.get(label) ?? []), v])
  }
  for (const f of facts) for (const [label, v] of f.phases) add(label.replace(/×\d+$/, "×2"), v)
  const kinds = [...new Set(facts.map((f) => `${f.request?.via ?? "?"} ${f.request?.agent ?? "?"}`))].join(", ")
  const lines = [`${facts.length} session starts (${kinds}): median, fastest, slowest`, ""]
  const row = (label: string, xs: number[]) =>
    `  ${label.padEnd(40)} ${dur(median(xs)).padStart(8)} ${dur(Math.min(...xs)).padStart(8)} ${dur(Math.max(...xs)).padStart(8)}  (${xs.length})`
  for (const [label, xs] of [...byPhase].sort((a, b) => median(b[1]) - median(a[1]))) if (median(xs) >= 10) lines.push(row(label, xs))
  lines.push("")
  const totals: Array<[string, Array<number | undefined>]> = [
    ["created (request → agent up, broker)", facts.map((f) => f.created)],
    ["app had the answer", facts.map((f) => f.response)],
    ["chat on screen", facts.map((f) => f.shown)],
    ["first reply", facts.map((f) => f.reply)],
    ["reply on screen", facts.map((f) => f.drawn)],
  ]
  for (const [label, vs] of totals) {
    const xs = vs.filter((v): v is number => v !== undefined)
    if (xs.length) lines.push(row(label, xs))
  }
  const failed = facts.filter((f) => f.failed).length
  if (failed) lines.push("", `${failed} of ${facts.length} failed.`)
  return lines.join("\n")
}
