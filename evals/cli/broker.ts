// The eval's throwaway broker (#156). It runs in a Docker container with no network and serves ONE run: the per-session
// socket of the session under test, speaking the real frame protocol (src/shared/frame-codec.ts). The real shim
// registers on it and gets the arm's tool list (the real shimTools()); the eval CLI calls ops on it without registering.
// Every op mutates a fake world (seed.ts) and is logged to calls.jsonl; state.json is the end state the checkers grade.
//
//   bun broker.ts <rundir> <privdir>   (env: ARM=mcp|cli). <privdir> holds what only the broker sees: the world, the
//   end state, the call log and the files the user "sent"; the agent's shell never gets its path.
import { createServer, type Socket } from "node:net"
import { appendFileSync, copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, unlinkSync, writeFileSync } from "node:fs"
import { isAbsolute, join, resolve } from "node:path"
import { encodeFrame, decodeFrames } from "../../src/shared/frame-codec"
import { shimTools, type ToolAgentChoices } from "../../src/shim/tools"
import { SELF_ID, type World } from "./seed"

const dir = process.argv[2]!
const priv = process.argv[3]!
const ARM = process.env.ARM === "cli" ? "cli" : "mcp"
/** Links point at the runner's local relay, so an agent that checks its link gets a real answer (Stage 2: fake URLs sent Grok into TLS debugging). */
const RELAY = process.env.RELAY_BASE ?? "https://h-eval.relay.supermux.dev"
const HOST = process.env.HOST_RUNDIR ?? dir
/** The tools that stay on MCP in the CLI arm (§7). */
export const KEPT = new Set(["message_session", "show_html", "attach"])

const world: World = JSON.parse(readFileSync(join(priv, "world.json"), "utf8"))
const save = () => writeFileSync(join(priv, "state.json"), JSON.stringify(world, null, 1))
const log = (rec: Record<string, unknown>) => appendFileSync(join(priv, "calls.jsonl"), JSON.stringify({ t: Date.now(), ...rec }) + "\n")
/** A path the agent gave (host view, relative to its workdir) as this container sees it. */
const local = (p: string) => {
  const abs = isAbsolute(p) ? p : resolve(world.self.workdir, p)
  return abs.startsWith(HOST) ? join(dir, abs.slice(HOST.length)) : abs
}

const a = (id: string, level: string, description: string) => ({ id, level, description })
const CHOICES: ToolAgentChoices = {
  claude: { access: [a("bypass", "full", "Skip tool-call approval (Claude bypassPermissions)."), a("dont-ask", "read-only", "Refuse instead of asking (dontAsk).")], efforts: ["low", "medium", "high", "xhigh", "max"] },
  codex: { access: [a("full-access", "full", "Codex's Full Access preset: never asks, no sandbox."), a("auto", "edit", "Codex's Auto preset: works in the workspace without asking; asks only to leave it (network, other paths)."), a("read-only", "read-only", "Codex's Read Only preset: no edits, no commands with side effects; asks to escalate.")], efforts: ["low", "medium", "high", "xhigh", "max", "ultra"] },
  cursor: { access: [a("force", "full", "Auto-approve tools in agent mode."), a("auto-review", "full", "Auto-approve over ACP. Cursor's classifier is unavailable over ACP; the catalog id is kept.")], efforts: [] },
  opencode: { access: [a("allow", "full", "Allow edit, bash, and webfetch via the live policy (environment always asks)."), a("read-only", "read-only", "Read-only policy plus OpenCode native plan mode.")], efforts: [] },
  grok: { access: [a("always-approve", "full", "Auto-approve tool calls.")], efforts: ["xhigh", "high", "medium", "low"] },
  antigravity: { access: [a("bypass", "full", "Skip all permission checks (agy --dangerously-skip-permissions)."), a("plan", "read-only", "Plan mode: analyze and propose, no edits (agy --mode plan).")], efforts: [] },
}
const tools = shimTools("mux-shim", { choices: CHOICES })!.filter((t) => ARM === "mcp" || KEPT.has(t.name))

const fail = (error: string) => ({ ok: false, error })
const ok = (value?: unknown) => ({ ok: true, value })
const byId = (id: string) => world.sessions.find((s) => s.id === id || s.id.startsWith(String(id)) && String(id).length >= 8)
const words = (q: string) => String(q).toLowerCase().split(/[^a-z0-9#.]+/).filter((w) => w.length > 2)
const score = (text: string, q: string) => words(q).reduce((n, w) => n + (text.toLowerCase().includes(w) ? 1 : 0), 0)
let seq = 0
const newId = () => `${(Date.now() % 1e8).toString(16).padStart(8, "0")}-${(++seq).toString(16).padStart(4, "0")}-4e00-8000-${Math.random().toString(16).slice(2, 14).padEnd(12, "0")}`

function memorySections(): Array<{ file: string; heading: string; body: string }> {
  const out: Array<{ file: string; heading: string; body: string }> = []
  const root = join(dir, "mux", "domains")
  for (const f of existsSync(root) ? readdirSync(root) : []) {
    if (!f.endsWith(".md")) continue
    const text = readFileSync(join(root, f), "utf8")
    for (const part of text.split(/\n(?=## )/)) {
      const heading = part.match(/^## (.*)/)?.[1] ?? f
      out.push({ file: join(HOST, "mux", "domains", f), heading, body: part })
    }
  }
  return out
}

function op(name: string, args: Record<string, any>): { ok: boolean; value?: unknown; error?: string } {
  switch (name) {
    case "list_sessions":
      return ok(world.sessions.filter((s) => args.include_archived || !s.archived).map(({ id, name, agent, model, workdir, muted, state, parent, children }) => ({ id, name, agent, model: model ?? null, workdir, muted: muted ?? false, state, parent: parent ?? null, children: children ?? [] })))
    case "find_sessions": {
      const hits = world.sessions.filter((s) => s.transcript && (!args.agent || s.agent === args.agent))
        .map((s) => ({ s, n: score(s.name + " " + s.transcript, String(args.query ?? "")) })).filter((h) => h.n > 0).sort((x, y) => y.n - x.n)
        .slice(0, Number(args.limit) || 10)
      return ok(hits.map(({ s }) => ({ id: s.id, name: s.name, workdir: s.workdir, agent: s.agent, created_at: s.created_at, last_active: s.last_active, transcript_path: join(HOST, "transcripts", `${s.id}.jsonl`) })))
    }
    case "read_session": {
      const s = byId(args.session_id)
      if (!s || !s.transcript) return fail(`no transcript for session ${args.session_id}`)
      let lines = s.transcript.split("\n")
      if (args.grep) lines = lines.filter((l) => l.toLowerCase().includes(String(args.grep).toLowerCase()))
      return ok(lines.join("\n"))
    }
    case "memory_search": {
      const q = String(args.query ?? "")
      const hits = memorySections().map((m) => ({ ...m, n: score(m.heading + " " + m.body, q) })).filter((m) => m.n > 0).sort((x, y) => y.n - x.n).slice(0, Number(args.limit) || 8)
      return ok(hits.map((h) => ({ file: h.file, heading: h.heading, snippet: h.body.slice(0, 400) })))
    }
    case "message_session": {
      const to: string[] = Array.isArray(args.to) ? args.to : [args.to]
      if (!to.length || typeof args.text !== "string" || !args.text) return fail("to[] and text are required")
      const ids: Record<string, string> = {}
      for (const t of to) {
        const s = byId(t)
        if (!s) return fail(`unknown session id: ${t} (use list_sessions for ids)`)
        if (s.id === SELF_ID) return fail("you can't message your own session")
        const mid = `m_${newId().slice(0, 12)}`
        ;(world.inboxes[s.id] ??= []).push({ from: SELF_ID, text: args.text, interrupt: !!args.interrupt, id: mid })
        ids[s.id] = mid
      }
      return ok({ ids })
    }
    case "spawn_session": case "delegate": {
      const agent = String(args.agent ?? "claude")
      if (!CHOICES[agent]) return fail(`unknown agent: ${agent}`)
      if (name === "delegate") {
        if (!args.task) return fail("task is required")
        if (!CHOICES[agent]!.access.some((m) => m.id === args.access)) return fail(`access must be one of ${agent}'s ids: ${CHOICES[agent]!.access.map((m) => m.id).join(", ")}`)
        if (args.checkout !== "shared" && args.checkout !== "worktree") return fail("checkout must be shared or worktree")
      } else if (!args.workdir) return fail("workdir is required")
      const sname = String(args.name ?? args.title ?? (args.task ?? "New session")).slice(0, 60)
      if (world.sessions.some((s) => s.name === sname)) return fail(`a session named '${sname}' already exists`)
      const id = newId()
      world.sessions.push({ id, name: sname, agent, model: args.model ?? "default", workdir: args.workdir ?? world.self.workdir, state: "working", muted: false, parent: name === "delegate" ? SELF_ID : undefined, created_by: name, access: args.access, task: args.task, checkout: args.checkout, effort: args.effort })
      return ok(name === "delegate" ? { session_id: id, name: sname } : { id, name: sname })
    }
    case "kill_session": { const s = byId(args.id); if (!s) return fail(`unknown session id: ${args.id}`); s.archived = true; s.state = "killed"; return ok() }
    case "rename_session": { if (!args.name) return fail("name is required"); world.self.name = args.name; return ok({ name: args.name }) }
    case "mute_session": case "set_active": case "get_active": case "create_agent":
      return fail("only agents can use this; you are a worker session")
    case "list_models":
      return ok({ you: { agent: world.self.agent, model: "default" }, agents: Object.entries(CHOICES).filter(([k]) => !args.agent || k === args.agent).map(([agent, c]) => ({ agent, ready: true, access: c.access.map(({ id, level }) => ({ id, level })), models: [{ id: "default", efforts: c.efforts }] })) })
    case "council": {
      if (!Array.isArray(args.members) || !args.members.length || !args.question) return fail("question and members[] are required")
      const id = `c_${newId().slice(0, 8)}`
      world.councils.push({ id, question: args.question, members: args.members, ended: false })
      return ok({ council: id, members: args.members.map((_: unknown, i: number) => String.fromCharCode(65 + i)) })
    }
    case "council_round": case "council_add": case "council_remove": return ok()
    case "council_end": { const c = world.councils.find((c) => c.id === args.council); if (!c) return fail("unknown council"); c.ended = true; c.verdict = args.verdict; return ok() }
    case "expose_port": {
      const port = Number(args.port)
      if (!(port >= 1 && port <= 65535)) return fail("port must be 1-65535")
      const domain = String(args.domain ?? `p-${Math.random().toString(36).slice(2, 8)}`)
      if (/^\d+$/.test(domain)) return fail("domain can't be only digits")
      world.proxies = world.proxies.filter((p) => p.domain !== domain)
      const url = `${RELAY}/p/${domain}/`
      world.proxies.push({ domain, port, public: !!args.public, url })
      return ok({ url, domain, port, isPublic: !!args.public })
    }
    case "list_proxies": return ok(world.proxies)
    case "unexpose_port": { const n = world.proxies.length; world.proxies = world.proxies.filter((p) => p.domain !== args.domain); return n === world.proxies.length ? fail(`no proxy ${args.domain}`) : ok() }
    case "set_proxy_public": { const p = world.proxies.find((p) => p.domain === args.domain); if (!p) return fail(`no proxy ${args.domain}`); p.public = !!args.public; return ok({ url: p.url, isPublic: p.public }) }
    case "attach": {
      const files: string[] = Array.isArray(args.files) ? args.files : []
      if (!files.length) return fail("files[] is required")
      for (const f of files) if (!existsSync(local(f)) || !statSync(local(f)).isFile()) return fail(`no such file: ${f}`)
      world.attachments.push({ files: files.map((f) => (isAbsolute(f) ? f : resolve(world.self.workdir, f))), text: args.text })
      return ok()
    }
    case "show_html": {
      if (!args.title) return fail("title is required")
      if (!!args.html === !!args.file) return fail("exactly one of html or file")
      let html = args.html as string | undefined
      if (args.file) { const p = local(String(args.file)); if (!existsSync(p)) return fail(`no such file: ${args.file}`); html = readFileSync(p, "utf8") }
      if (!html || html.trim().length < 20) return fail("the page is blank")
      world.cards.push({ title: args.title, html })
      return ok({ shown: true, problems: [] })
    }
    case "react": case "edit_message": return fail("the web channel does not support reactions or edits")
    case "download_attachment": {
      const f = world.files[String(args.file_id)]
      if (!f) return fail(`unknown file_id: ${args.file_id}`)
      mkdirSync(join(dir, "downloads"), { recursive: true })
      copyFileSync(join(priv, "inbox-files", f), join(dir, "downloads", f))
      world.downloads.push(String(args.file_id))
      return ok({ path: join(HOST, "downloads", f) })
    }
    case "browser_comments": return ok([])
    case "walkthrough": case "reply_comment": case "resolve_browser_comment": case "reply_browser_comment": case "dismiss_browser_comment":
    case "preview_avatar": case "set_avatar": case "stop_display": return ok()
    case "list_devices": return ok([])
    case "start_display": return ok({ id: "d1", display: ":99" })
    default: return fail(`unknown op ${name}`)
  }
}

function handle(sock: Socket) {
  let buf = Buffer.alloc(0)
  let via: "mcp" | "cli" = "cli"
  sock.on("data", (chunk: Buffer) => {
    buf = Buffer.concat([buf, chunk])
    const { messages, rest } = decodeFrames(buf)
    buf = rest
    for (const m of messages as any[]) {
      if (m.kind === "register") {
        via = "mcp"
        sock.write(encodeFrame({ kind: "registered", display_name: world.self.name, session_id: SELF_ID, tools }))
      } else if (m.kind === "outbound" || m.kind === "orchestration") {
        const r = op(String(m.op?.name), m.op?.args ?? {})
        log({ via, op: m.op?.name, args: m.op?.args, ok: r.ok, error: r.error })
        save()
        sock.write(encodeFrame({ kind: "result", call_id: m.call_id, ...r }))
      } else if (m.kind === "ping") sock.write(encodeFrame({ kind: "pong" }))
    }
  })
  sock.on("error", () => {})
}

const sockPath = join(dir, "s", `${SELF_ID}.sock`)
mkdirSync(join(dir, "s"), { recursive: true })
if (existsSync(sockPath)) unlinkSync(sockPath)
save()
createServer(handle).listen(sockPath, () => { writeFileSync(join(priv, "broker.ready"), String(process.pid)); console.log(`eval broker (${ARM}, ${tools.length} tools) on ${sockPath}`) })
