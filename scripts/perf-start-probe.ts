#!/usr/bin/env bun
// Card #75: start sessions on a broker the way the launcher does — POST /sessions with a first
// message — and time each one until the agent's first reply arrives. Each start carries its own
// trace id (`x-mux-trace`), and the probe ships its own marks over the socket as the apps do, so
//   bun scripts/perf-timeline.ts start <trace>
// shows the probe's half next to the broker's (the broker must be tracing: MUX_PERF=1 or
// `perf-timeline.ts on`). Point it at a throwaway or shadow broker: every run makes a real session,
// worktree and branch; they are killed and removed afterwards unless --keep.
//
//   bun scripts/perf-start-probe.ts --url http://127.0.0.1:9901 --token TOKEN --workdir ~/projects/app
//       [--agent claude] [--model M] [--runs 3] [--gap 15] [--label warm] [--message "Reply with just: ok"] [--keep]
import { execFileSync } from "child_process"
import { randomBytes } from "crypto"

const argv = process.argv.slice(2)
const opt = (name: string, fallback?: string): string | undefined => {
  const i = argv.indexOf(`--${name}`)
  return i >= 0 ? argv[i + 1] : fallback
}
const url = (opt("url") ?? "").replace(/\/$/, "")
const token = opt("token") ?? process.env.MUX_PROBE_TOKEN ?? ""
const workdir = (opt("workdir") ?? "").replace(/^~(?=\/|$)/, process.env.HOME ?? "~")
const agent = opt("agent", "claude")!
const model = opt("model")
const runs = Number(opt("runs", "1"))
const gapS = Number(opt("gap", "15"))
const label = opt("label", "run")!
const message = opt("message", "Reply with just the word: ok")!
const keep = argv.includes("--keep")
if (!url || !token || !workdir) {
  console.error("usage: bun scripts/perf-start-probe.ts --url URL --token TOKEN --workdir DIR [--agent A] [--model M] [--runs N] [--gap S] [--label L] [--keep]")
  process.exit(2)
}

const origin = performance.now()
const t = () => Math.round(performance.now() - origin)
const launch = `L${randomBytes(4).toString("hex").slice(0, 6).toUpperCase()}`
const pending: Array<{ category: "perf"; event: string; ts: number; data: Record<string, unknown> }> = []
const mark = (trace: string, event: string, data: Record<string, unknown> = {}) => {
  pending.push({ category: "perf", event, ts: t(), data: { trace, ...data } })
}

type Frame = { type: string; [k: string]: any }
let ws: WebSocket
const waiters: Array<(f: Frame) => void> = []
function connect(): Promise<void> {
  return new Promise((resolve, reject) => {
    ws = new WebSocket(`${url.replace(/^http/, "ws")}/ws?clientSurface=probe&trace=${launch}`, { headers: { Authorization: `Bearer ${token}` } } as any)
    ws.onopen = () => ws.send(JSON.stringify({ type: "subscribe", logTail: 1, trimExtras: true, slimArchived: true, lazyArchived: true, slimSubagents: true }))
    ws.onmessage = (ev) => {
      const f = JSON.parse(String(ev.data)) as Frame
      if (f.type === "snapshot") resolve()
      for (const w of [...waiters]) w(f)
    }
    ws.onerror = (e) => reject(e)
  })
}
function ship(): void {
  if (!pending.length || ws.readyState !== WebSocket.OPEN) return
  ws.send(JSON.stringify({ type: "client_logs", entries: pending.splice(0), meta: { t: t(), launch, surface: "probe", version: "perf75" } }))
}

async function api(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<{ status: number; json: any }> {
  const res = await fetch(`${url}${path}`, {
    method,
    headers: { Authorization: `Bearer ${token}`, "content-type": "application/json", ...headers },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  const text = await res.text()
  let json: any
  try { json = text ? JSON.parse(text) : undefined } catch { json = text }
  return { status: res.status, json }
}

const loadavg = () => { try { return require("fs").readFileSync("/proc/loadavg", "utf8").split(" ").slice(0, 3).join(" ") } catch { return "?" } }

async function once(n: number): Promise<Record<string, unknown>> {
  const trace = `S${launch.slice(1)}.${n}`
  const name = `perf75 ${label} ${n}`
  const seen: Record<string, number> = {}
  let sessionId: string | undefined
  let replyResolve!: () => void
  const replied = new Promise<void>((r) => { replyResolve = r })
  const watch = (f: Frame) => {
    const sid = f.session?.id ?? f.session
    if (f.type === "session_added" && f.session?.name?.startsWith(name) && !seen.session_added) { seen.session_added = t(); mark(trace, "start.session_added", { session: f.session.id }) }
    if (!sessionId || sid !== sessionId) return
    if (f.type === "message_append" && String(f.entry?.direction).startsWith("in") && !seen.echoed) { seen.echoed = t(); mark(trace, "start.message.echoed") }
    if (f.type === "agent_state" && f.working && !seen.working) { seen.working = t(); mark(trace, "start.agent.working", { detail: f.detail }) }
    if (f.type === "activity_append" && !seen.activity) { seen.activity = t(); mark(trace, "start.activity") }
    if (f.type === "commands_changed" && !seen.commands) { seen.commands = t(); mark(trace, "start.commands", { count: f.commands?.length }) }
    if (f.type === "message_append" && f.entry?.direction === "outbound" && f.entry?.op === "reply" && !seen.reply) {
      seen.reply = t()
      mark(trace, "start.reply.arrived", { chars: f.entry?.text?.length })
      replyResolve()
    }
  }
  waiters.push(watch)
  const load = loadavg()
  const t0 = t()
  mark(trace, "start.send", { agent, model, worktree: true, load })
  const res = await api("POST", "/sessions", { workdir, agent, model, worktree: true, name, firstMessage: message }, { "x-mux-trace": `${trace}/r${n}` })
  const tResp = t()
  sessionId = res.json?.id
  mark(trace, "http", { req: `r${n}`, method: "POST", path: "/sessions", status: res.status, ms: tResp - t0 })
  mark(trace, "start.response", { ms: tResp - t0, session: sessionId, status: res.status })
  if (res.status !== 200 || !sessionId) {
    waiters.splice(waiters.indexOf(watch), 1)
    ship()
    return { run: n, trace, error: `${res.status} ${JSON.stringify(res.json).slice(0, 200)}`, load }
  }
  const timeout = new Promise<void>((r) => setTimeout(r, 300_000))
  await Promise.race([replied, timeout])
  waiters.splice(waiters.indexOf(watch), 1)
  ship()
  const out = {
    run: n, trace, load, session: sessionId,
    created_s: +((tResp - t0) / 1000).toFixed(2),
    reply_s: seen.reply ? +((seen.reply - t0) / 1000).toFixed(2) : null,
    session_added_s: seen.session_added ? +((seen.session_added - t0) / 1000).toFixed(2) : null,
  }
  if (!keep) {
    const killed = await api("DELETE", `/sessions/${encodeURIComponent(sessionId)}`)
    if (killed.status >= 300) console.error(`kill ${sessionId} failed: ${killed.status} ${JSON.stringify(killed.json).slice(0, 200)}`)
    const wt = res.json?.workdir as string | undefined
    const repo = res.json?.repo_root as string | undefined
    const branch = res.json?.session_branch as string | undefined
    if (repo && wt && wt.includes("/worktrees/")) {
      try { execFileSync("git", ["-C", repo, "worktree", "remove", "--force", wt], { stdio: "ignore" }) } catch { /* already gone */ }
      if (branch) try { execFileSync("git", ["-C", repo, "branch", "-D", branch], { stdio: "ignore" }) } catch { /* already gone */ }
    }
  }
  return out
}

await connect()
const results: Record<string, unknown>[] = []
for (let n = 1; n <= runs; n++) {
  const r = await once(n)
  results.push(r)
  console.log(JSON.stringify({ label, agent, model: model ?? "default", workdir, ...r }))
  if (n < runs) await Bun.sleep(gapS * 1000)
}
ship()
await Bun.sleep(500)
ws.close()
console.log(`\nTimelines: ${results.map((r) => `bun scripts/perf-timeline.ts start ${r.trace}`).join("\n           ")}`)
process.exit(0)
