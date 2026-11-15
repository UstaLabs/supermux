// `supermux <noun> …` (#156): supermux from a session's shell, for agents and people. It talks to the broker over the
// same per-session socket the mux-shim MCP server uses (MUX_SOCKETS_DIR + MUX_SESSION_ID, set in every agent's env),
// sends the same ops as the MCP tools, and needs no broker endpoint of its own.
//
// What the CLI says and does was shaped by the MCP-vs-CLI eval (evals/cli, design page /p/cli-156/). Each rule below
// fixed a cost that showed in the agents' transcripts:
//   - `supermux help` is the WHOLE reference on one page; `<cmd> --help` is that command's line plus one example; no
//     page points to another (agents survey help first and follow every pointer).
//   - Background work (delegate, council, replies to msg) says in its OUTPUT that it has returned and answers arrive in
//     the inbox (without it, agents slept and polled).
//   - A file argument is read here, with the agent's own access (pages written to /tmp failed broker-side).
//   - Errors state the real rule; JSON rows carry every field (null when unknown).
//
// Exit codes: 0 ok, 1 the broker refused or failed, 2 usage, 3 not found, 4 denied, 5 broker unreachable.
import { connect } from "node:net"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"
import { encodeFrame, decodeFrames } from "../shared/frame-codec"
import { localEndpoint } from "../core/local-endpoint"
import { listTools } from "../shim/tools"

export interface AgentCliIo {
  env: Record<string, string | undefined>
  cwd: string
  out: (s: string) => void
  err: (s: string) => void
  /** Reads stdin ('-' arguments). */
  stdin: () => string
  /** Broker call timeout, ms. */
  timeoutMs?: number
}

class Exit extends Error { constructor(readonly code: number) { super(`exit ${code}`) } }

const ASYNC = "It runs in the background: this command has returned. Answers arrive in your inbox as new turns (\"Message from …\"); don't wait, poll or sleep for them."

/** [noun, verb, usage line, example]: one line per command, all flags. */
const REF: Array<[string, string, string, string?]> = [
  ["session", "ls", "session ls [--state working|idle|suspended] [--all]   live sessions: id, name, agent, model, state, workdir", "supermux session ls --state idle --json id,name"],
  ["session", "show", "session show SESSION                            one session, live or past (SESSION = name, id or id prefix)"],
  ["session", "find", "session find QUERY [--agent A] [--since ISO] [--limit N]   sessions by what was discussed", 'supermux session find "zmx crash"'],
  ["session", "read", "session read SESSION [--grep TEXT] [--limit N]  a session's messages (live or past)", "supermux session read a1b2c3d4 --grep fix"],
  ["session", "spawn", "session spawn DIR [--name N] [--agent A] [--model M] [--effort E]   new visible session; brief it with msg", 'supermux session spawn ~/projects/app --name "Fix login"'],
  ["session", "kill", "session kill SESSION                            stop and archive a session (and its children)"],
  ["session", "rename", "session rename NAME                             rename YOUR session (a worker: once)", 'supermux session rename "Fix login flow"'],
  ["session", "models", "session models [AGENT]                          agents, models, efforts, access ids"],
  ["msg", "", "msg SESSION... TEXT|- [--interrupt [--stop-now]]   message one or more sessions (name or id); replies come to your inbox", 'supermux msg "Git cleanup" "Tally" "done testing"'],
  ["delegate", "", "delegate TASK|- --agent A --access ID [--checkout shared|worktree] [--model M] [--effort E] [--title T]   hidden child session; reports to your inbox (access ids: supermux session models)", 'supermux delegate "review src/cli.ts" --agent claude --access dont-ask'],
  ["council", "start", "council start QUESTION|- --member AGENT:ACCESS[:MODEL[:EFFORT]]... [--checkout shared|worktree]   several agents answer into your inbox", 'supermux council start "SQLite enough?" --member claude:dont-ask --member codex:read-only'],
  ["council", "", "council round COUNCIL FOCUS | add COUNCIL --member ... | remove COUNCIL LETTER | end COUNCIL VERDICT"],
  ["card", "show", "card show FILE|- --title T [--height PX]        show the user an HTML page as a card (chart, comparison, small tool); 'card --help' has the page rules", 'supermux card show chart.html --title "Rides"'],
  ["attach", "", "attach FILE... [-m CAPTION]                     send files to the user", "supermux attach out/home.png"],
  ["file", "get", "file get FILE_ID                                download a file the user sent (meta: attachment_file_id); prints its path"],
  ["port", "ls", "port ls                                         exposed links: name, port, public or paired-only, url"],
  ["port", "expose", "port expose PORT [--name SLUG] [--public]       a link to a local port; --public: anyone with the link, no sign-in", "supermux port expose 5173 --name demo"],
  ["port", "", "port public|private|unexpose SLUG               change or remove one of your links"],
  ["memory", "search", "memory search QUERY [--limit N]                 search shared memory; to SAVE, append '## title (date)' to the domain file yourself", 'supermux memory search "codex limit"'],
  ["comments", "", "comments ls | reply ID TEXT | resolve ID [TEXT] | dismiss ID [TEXT]   the user's comments on browser pages"],
  ["display", "", "display start [--provider P] [--size WxH] | stop ID · device ls     virtual displays, Android devices"],
  ["api", "", "api OP [-f key=value]... [--input FILE]         raw broker op (rarely needed); 'api OP --help' lists its params"],
]
const LINE = (u: string) => `  supermux ${u}`
const FULL_REF = "supermux — the whole reference (you are signed in as this session; nothing else to read):\n" +
  REF.map(([, , u]) => LINE(u)).join("\n") +
  "\nAll commands: --json for JSON (--json a,b picks fields); '-' as TEXT reads stdin; <cmd> --help adds one example.\n" +
  "Background work (delegate, council, msg replies) arrives in your inbox as new turns: never wait or poll for it.\n" +
  "Exit: 0 ok, 1 failed, 2 usage, 3 not found, 4 denied, 5 broker unreachable."
const CARD_RULES = `Page rules: one self-contained page (inline CSS/JS; CDN libraries fine; no local files, localhost or localStorage).
Colours only from var(--sm-fg) var(--sm-muted-fg) var(--sm-card) var(--sm-border) var(--sm-muted) var(--sm-primary)
var(--sm-chart-1..5). No background on html/body. Fluid width (390-720 px); charts a fixed px height; no vh.
A <button onclick="supermux.send('text')"> sends that text to you as the user's reply.
Prints 'shown', or the problems: then the user saw nothing; fix and show again. Use a card when numbers or options have
a shape worth seeing, and answer in a sentence beside it. Not for a plain fact, code, or an image (attach a PNG).`

export function helpText(noun = "", verb?: string): string {
  if (!noun || noun === "help") return FULL_REF
  if (noun === "card") return LINE(REF.find(([n, v]) => n === "card" && v === "show")![2].replace(/; 'card --help' has the page rules/, "")) + "\n" + CARD_RULES + "\nExample:\n  supermux card show chart.html --title \"Rides\""
  const rows = REF.filter(([n]) => n === noun)
  const pick = verb ? rows.filter(([, v]) => v === verb) : rows
  const use = pick.length ? pick : rows
  if (!use.length) return FULL_REF
  const ex = use.map(([, , , e]) => e).filter(Boolean)
  return use.map(([, , u]) => LINE(u)).join("\n") + (ex.length ? `\nExample${ex.length > 1 ? "s" : ""}:\n` + ex.map((e) => `  ${e}`).join("\n") : "")
}

const OUTBOUND = new Set(["attach", "show_html", "react", "edit_message", "download_attachment"])
const BOOL = new Set(["json", "interrupt", "stop-now", "public", "all", "help", "h", "yes"])
const MULTI = new Set(["member", "f"])
/** Output field names, so `--json id` is a field list and `--json codex` is not. */
const FIELDS = new Set(["id", "name", "agent", "model", "state", "workdir", "mute", "parent", "children", "council", "status", "created_at", "snippet", "path", "heading", "scope", "url", "port", "public", "session", "messages", "session_id"])

/** Runs one `supermux <noun> …` command; returns the exit code. */
export async function runAgentCli(argv: string[], io: AgentCliIo): Promise<number> {
  const flags: Record<string, string | boolean | string[]> = {}
  const pos: string[] = []
  const usage = (msg: string, line?: string): never => { io.err(`supermux: ${msg}`); if (line) io.err(`usage: supermux ${line}`); throw new Exit(2) }
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i]!
    if (a === "-" || !a.startsWith("-") || /^-\d/.test(a)) { pos.push(a); continue }
    const [k0, inline] = a.replace(/^--?/, "").split(/=(.*)/s)
    const k = k0 === "m" ? "message" : k0!
    // --json takes an optional field list ("id,name", or one known field), never a query word or a command
    const next = argv[i + 1] ?? ""
    const takesValue = !BOOL.has(k) || (k === "json" && /^[a-z_]+(,[a-z_]+)*$/.test(next) && (next.includes(",") || FIELDS.has(next)))
    const v = inline !== undefined ? inline : takesValue ? argv[++i] : true
    if (v === undefined) usage(`--${k} needs a value`)
    if (MULTI.has(k)) ((flags[k] ??= []) as string[]).push(String(v))
    else flags[k] = v as string | boolean
  }
  const json = flags.json !== undefined && flags.json !== false
  const fields = typeof flags.json === "string" ? flags.json.split(",") : undefined
  const str = (k: string) => (typeof flags[k] === "string" ? (flags[k] as string) : undefined)
  const num = (k: string) => (str(k) !== undefined && Number.isFinite(Number(str(k))) ? Number(str(k)) : undefined)
  const text = (v: string | undefined) => (v === "-" ? io.stdin() : v)
  const [noun = "", verb, ...rest] = pos
  const help = (topic: string, v?: string): never => { io.out(helpText(topic, v)); throw new Exit(0) }

  const sock = io.env.MUX_SESSION_ID && io.env.MUX_SOCKETS_DIR ? localEndpoint(io.env.MUX_SESSION_ID, { socketsDir: io.env.MUX_SOCKETS_DIR }) : undefined
  async function call(name: string, args: Record<string, unknown>): Promise<any> {
    if (!sock) { io.err("supermux: not inside a supermux session (MUX_SESSION_ID and MUX_SOCKETS_DIR are unset)"); throw new Exit(5) }
    const r = await new Promise<{ ok: boolean; value?: unknown; error?: string } | "unreachable" | "timeout">((res) => {
      const s = connect(sock)
      let buf: Buffer = Buffer.alloc(0)
      const timer = setTimeout(() => { s.destroy(); res("timeout") }, io.timeoutMs ?? 30_000)
      s.on("error", () => { clearTimeout(timer); res("unreachable") })
      s.on("connect", () => s.write(encodeFrame({ kind: OUTBOUND.has(name) ? "outbound" : "orchestration", call_id: `cli-${process.pid}-${Date.now()}`, op: { name, args } })))
      s.on("data", (c: Buffer) => {
        buf = Buffer.concat([buf, c])
        const { messages, rest: left } = decodeFrames(buf)
        buf = left
        for (const m of messages as any[]) if (m?.kind === "result") { clearTimeout(timer); s.end(); res({ ok: !!m.ok, value: m.value, error: m.error }) }
      })
    })
    if (r === "unreachable" || r === "timeout") { io.err(`supermux: the broker ${r === "timeout" ? "did not answer" : "is unreachable"} (${sock})`); throw new Exit(5) }
    if (!r.ok) {
      const e = String(r.error ?? "failed")
      const code = /unknown session|no such|not found|no proxy/i.test(e) ? 3 : /permission denied|can only|only agents|not allowed/i.test(e) ? 4 : 1
      io.err(json ? JSON.stringify({ error: { code: ["", "failed", "", "not_found", "denied"][code], message: e } }) : `supermux: ${e}`)
      throw new Exit(code)
    }
    return r.value
  }

  const pick = (o: any) => (fields ? Object.fromEntries(fields.map((f) => [f, o?.[f] ?? null])) : o)
  const out = (value: unknown, human: () => void) => { if (json) io.out(JSON.stringify(Array.isArray(value) ? value.map(pick) : pick(value), null, 1)); else human() }
  const table = (rows: any[], cols: Array<[string, string]>) => {
    if (!rows.length) { io.out("(none)"); return }
    const w = cols.map(([k, h]) => Math.max(h.length, ...rows.map((r) => String(r[k] ?? "").length)))
    io.out(cols.map(([, h], i) => h.padEnd(w[i]!)).join("  ").trimEnd())
    for (const r of rows) io.out(cols.map(([k], i) => String(r[k] ?? "").padEnd(w[i]!)).join("  ").trimEnd())
  }
  /** Every row the same shape: an agent reads the fields from the first row. */
  const sessionRow = (s: any) => ({ id: s.id, name: s.name, agent: s.agent ?? null, model: s.model ?? null, state: s.state ?? null, workdir: s.workdir ?? null, mute: s.mute ?? false, parent: s.parent ?? null, children: s.children ?? [], council: s.council ?? null })
  let sessionsCache: any[] | undefined
  const sessions = async () => (sessionsCache ??= ((await call("list_sessions", {})) as any[]).map(sessionRow))
  async function resolveSession(ref: string): Promise<string> {
    const all = await sessions()
    const hit = all.find((s) => s.id === ref) ?? all.find((s) => ref.length >= 6 && s.id.startsWith(ref))
      ?? all.find((s) => s.name === ref) ?? all.find((s) => s.name.toLowerCase() === ref.toLowerCase())
    if (hit) return hit.id
    const near = all.filter((s) => s.name.toLowerCase().includes(ref.toLowerCase()))
    if (near.length === 1) return near[0].id
    io.err(`supermux: no session '${ref}'${near.length ? ` (more than one matches: ${near.map((s) => s.name).join(", ")})` : " (supermux session ls --all lists them)"}`)
    throw new Exit(3)
  }
  const local = (p: string) => resolve(io.cwd, p)

  try {
    if (!noun || noun === "help") help(verb ?? "", rest[0])
    if (flags.help || flags.h) help(noun === "s" ? "session" : noun, verb)
    switch (noun) {
      case "session": case "s": {
        switch (verb) {
          case "ls": case "list": {
            let v = await sessions()
            if (!flags.all) v = v.filter((s) => s.state !== "archived")
            if (str("state")) v = v.filter((s) => s.state === str("state"))
            out(v, () => table(v.map((s) => ({ ...s, id: s.id.slice(0, 8) })), [["id", "ID"], ["name", "NAME"], ["agent", "AGENT"], ["model", "MODEL"], ["state", "STATE"], ["workdir", "WORKDIR"]]))
            return 0
          }
          case "show": {
            if (!rest[0]) usage("which session?", "session show SESSION")
            const id = await resolveSession(rest[0]!)
            const v = (await sessions()).find((s) => s.id === id)
            out(v, () => io.out(Object.entries(v).map(([k, x]) => `${k}: ${typeof x === "string" ? x : JSON.stringify(x)}`).join("\n")))
            return 0
          }
          case "find": {
            if (!rest.length) usage("find what?", "session find QUERY")
            const v = ((await call("find_sessions", { query: rest.join(" "), agent: str("agent"), since: str("since"), limit: num("limit") })) as any[])
              .map((h) => ({ id: h.id, name: h.name, agent: h.agent ?? null, status: h.status ?? null, workdir: h.workdir ?? null, created_at: h.created_at ?? null, snippet: h.snippet ?? null }))
            out(v, () => table(v.map((s) => ({ ...s, id: s.id.slice(0, 8), created_at: String(s.created_at ?? "").slice(0, 10), snippet: String(s.snippet ?? "").replace(/\s+/g, " ").slice(0, 60) })), [["id", "ID"], ["name", "NAME"], ["agent", "AGENT"], ["status", "STATUS"], ["created_at", "CREATED"], ["snippet", "MATCH"]]))
            return 0
          }
          case "read": {
            if (!rest[0]) usage("which session?", "session read SESSION [--grep TEXT]")
            const id = await resolveSession(rest[0]!)
            const v = await call("read_session", { session_id: id })
            const msgs: any[] = Array.isArray(v?.messages) ? v.messages : []
            let lines = typeof v === "string" ? v.split("\n") : msgs.map((m) => `${String(m.ts ?? "").slice(0, 16)} ${m.direction === "inbound" ? (m.channel === "inbox" ? "session" : "user") : "agent"}: ${String(m.text ?? "").replace(/\s+/g, " ")}`)
            if (str("grep")) lines = lines.filter((l) => l.toLowerCase().includes(str("grep")!.toLowerCase()))
            if (num("limit")) lines = lines.slice(-num("limit")!)
            out(typeof v === "string" ? lines : { session_id: id, messages: str("grep") ? msgs.filter((m) => String(m.text ?? "").toLowerCase().includes(str("grep")!.toLowerCase())) : msgs }, () => io.out(lines.length ? lines.join("\n") : "(no messages)"))
            return 0
          }
          case "spawn": {
            if (!rest[0]) usage("in which folder?", "session spawn DIR [--name N] [--agent A]")
            const v = await call("spawn_session", { workdir: local(rest[0]!), name: str("name"), agent: str("agent"), model: str("model"), effort: str("effort") })
            const next = `Brief it with: supermux msg ${JSON.stringify(v?.name ?? v?.id)} "…"`
            out({ ...v, next }, () => io.out(`started ${v?.name ?? ""} (${v?.id ?? v?.session_id}). ${next}`))
            return 0
          }
          case "kill": {
            if (!rest[0]) usage("which session?", "session kill SESSION")
            const id = await resolveSession(rest[0]!)
            await call("kill_session", { id })
            out({ killed: id }, () => io.out(`killed ${id}`))
            return 0
          }
          case "rename": {
            if (!rest.length) usage("to what?", "session rename NAME")
            const v = await call("rename_session", { name: rest.join(" ") })
            out(v ?? { name: rest.join(" ") }, () => io.out(`renamed to ${rest.join(" ")}`))
            return 0
          }
          case "models": {
            const v = await call("list_models", rest[0] ? { agent: rest[0] } : {})
            out(v, () => { for (const a of v?.agents ?? []) io.out(`${a.agent}${a.ready === false ? " (not ready)" : ""}: access ${(a.access ?? []).map((m: any) => `${m.id} (${m.level})`).join(", ")}; models ${(a.models ?? []).map((m: any) => m.id).join(", ")}`) })
            return 0
          }
          default: help("session", verb)
        }
      }
      // eslint-disable-next-line no-fallthrough
      case "msg": {
        const body = text(pos[pos.length - 1])
        const refs = pos.slice(1, -1)
        if (!refs.length || !body) usage("who to, and what?", "msg SESSION... TEXT|-")
        const to: string[] = []
        for (const r of refs) to.push(await resolveSession(r))
        const v = await call("message_session", { to, text: body, interrupt: !!flags.interrupt, ...(flags["stop-now"] ? { stop_now: true } : {}) })
        out(v ?? { sent: to }, () => io.out(`sent → ${to.length} session${to.length > 1 ? "s" : ""}. A reply arrives in your inbox as a new turn; no need to wait.`))
        return 0
      }
      case "delegate": {
        const task = text(verb)
        if (!task || !str("agent") || !str("access")) usage("TASK, --agent and --access are required (access ids: supermux session models)", "delegate TASK|- --agent A --access ID")
        const v = await call("delegate", { task, agent: str("agent"), access: str("access"), checkout: str("checkout") ?? "shared", model: str("model"), effort: str("effort"), title: str("title") })
        out({ ...v, note: ASYNC }, () => io.out(`started ${v?.name ?? ""} (${v?.session_id ?? v?.id ?? ""}).\n${ASYNC}`))
        return 0
      }
      case "council": {
        const members = ((flags.member as string[] | undefined) ?? []).map((m) => { const [agent, access, model, effort] = m.split(":"); return { agent, access, ...(model ? { model } : {}), ...(effort ? { effort } : {}) } })
        if (verb === "start") {
          const q = text(rest[0])
          if (!q || !members.length) usage("a QUESTION and at least one --member AGENT:ACCESS", "council start QUESTION --member AGENT:ACCESS...")
          const v = await call("council", { question: q, members, checkout: str("checkout") ?? "shared" })
          out({ ...v, note: ASYNC }, () => io.out(`council started.\n${ASYNC}`))
          return 0
        }
        const ops: Record<string, [string, () => Record<string, unknown>]> = {
          round: ["council_round", () => ({ council: rest[0], focus: rest.slice(1).join(" ") })],
          add: ["council_add", () => ({ council: rest[0], members, checkout: str("checkout") ?? "shared" })],
          remove: ["council_remove", () => ({ council: rest[0], letter: rest[1] })],
          end: ["council_end", () => ({ council: rest[0], verdict: rest.slice(1).join(" ") })],
        }
        if (!verb || !ops[verb] || !rest[0]) help("council")
        const [op, args] = ops[verb!]!
        const v = await call(op, args())
        out(v ?? { ok: true }, () => io.out(verb === "end" ? "council ended" : `done. ${ASYNC}`))
        return 0
      }
      case "card": {
        if (verb === "show") {
          if (!rest[0] || !str("title")) usage("a FILE (or -) and --title are required", "card show FILE|- --title T")
          let html: string
          try { html = rest[0] === "-" ? io.stdin() : readFileSync(local(rest[0]!), "utf8") }
          catch { io.err(`supermux: can't read ${local(rest[0]!)}: no such file`); throw new Exit(3) }
          const v = await call("show_html", { title: str("title"), html, ...(num("height") ? { height: num("height") } : {}) })
          // The broker answers in a sentence: "shown…", or "not shown — fix the page…" and one "- problem" per line.
          const said = typeof v === "string" ? v : Array.isArray(v?.problems) && v.problems.length ? `not shown:\n${v.problems.map((p: string) => `- ${p}`).join("\n")}` : "shown"
          const shown = !/^not shown/i.test(said)
          const problems = shown ? [] : said.split("\n").filter((l) => l.startsWith("- ")).map((l) => l.slice(2))
          out({ shown, problems, text: said }, () => io.out(shown ? said : `NOT shown, the user saw nothing. Fix the page and run supermux card show again:\n${problems.map((p) => `- ${p}`).join("\n")}`))
          return shown ? 0 : 1
        }
        if (verb === "walkthrough") {
          if (!rest[0]) usage("which FILE.json?", "card walkthrough FILE.json")
          const v = await call("walkthrough", JSON.parse(readFileSync(local(rest[0]!), "utf8")))
          out(v ?? { ok: true }, () => io.out(typeof v === "string" ? v : JSON.stringify(v, null, 1)))
          return 0
        }
        help("card")
      }
      // eslint-disable-next-line no-fallthrough
      case "attach": {
        const files = pos.slice(1)
        if (!files.length) usage("which files?", "attach FILE... [-m CAPTION]")
        const abs = files.map(local)
        await call("attach", { files: abs, ...(str("message") ? { text: str("message") } : {}) })
        out({ sent: abs }, () => io.out(`sent ${abs.length} file${abs.length > 1 ? "s" : ""}`))
        return 0
      }
      case "file": {
        if (verb !== "get" || !rest[0]) usage("which file id?", "file get FILE_ID")
        const v = await call("download_attachment", { file_id: rest[0] })
        const path = typeof v === "string" ? v : v?.path
        out({ path }, () => io.out(String(path)))
        return 0
      }
      case "port": {
        if (verb === "ls") {
          const v = ((await call("list_proxies", {})) as any[]).map((p) => ({ name: p.domain, port: p.port, public: !!p.isPublic, url: p.url ?? null, session: p.sessionName ?? null }))
          out(v, () => table(v.map((p) => ({ ...p, public: p.public ? "public" : "paired only" })), [["name", "NAME"], ["port", "PORT"], ["public", "ACCESS"], ["session", "SESSION"], ["url", "URL"]]))
          return 0
        }
        if (verb === "expose") {
          if (!rest[0] || !Number(rest[0])) usage("which port?", "port expose PORT [--name SLUG] [--public]")
          const v = await call("expose_port", { port: Number(rest[0]), ...(str("name") ? { domain: str("name") } : {}), public: !!flags.public })
          out(v, () => io.out(`${v.url}   (${v.isPublic ? "public: anyone with the link" : "paired devices only"})`))
          return 0
        }
        if (verb === "public" || verb === "private") {
          if (!rest[0]) usage("which link?", `port ${verb} SLUG   (names: supermux port ls)`)
          const v = await call("set_proxy_public", { domain: rest[0], public: verb === "public" })
          out(v, () => io.out(`${rest[0]} is now ${verb === "public" ? "public: anyone with the link" : "paired devices only"}`))
          return 0
        }
        if (verb === "unexpose") {
          if (!rest[0]) usage("which link?", "port unexpose SLUG")
          await call("unexpose_port", { domain: rest[0] })
          out({ removed: rest[0] }, () => io.out(`removed ${rest[0]}`))
          return 0
        }
        help("port", verb)
      }
      // eslint-disable-next-line no-fallthrough
      case "memory": {
        if (verb !== "search" || !rest.length) help("memory")
        const v = ((await call("memory_search", { query: rest.join(" "), ...(num("limit") ? { limit: num("limit") } : {}) })) as any[])
          .map((h) => ({ path: h.path ?? h.file ?? null, heading: h.heading ?? null, scope: h.scope ?? null, snippet: h.snippet ?? null }))
        out(v, () => { if (!v.length) io.out("(no hits)"); for (const h of v) io.out(`${h.path} · ${h.heading}\n  ${String(h.snippet ?? "").replace(/\n/g, "\n  ")}\n`) })
        return 0
      }
      case "comments": {
        const map: Record<string, [string, () => Record<string, unknown>]> = {
          ls: ["browser_comments", () => ({})],
          reply: ["reply_browser_comment", () => ({ id: rest[0], text: rest.slice(1).join(" ") })],
          resolve: ["resolve_browser_comment", () => ({ id: rest[0], ...(rest[1] ? { text: rest.slice(1).join(" ") } : {}) })],
          dismiss: ["dismiss_browser_comment", () => ({ id: rest[0], ...(rest[1] ? { text: rest.slice(1).join(" ") } : {}) })],
        }
        if (!verb || !map[verb]) help("comments")
        const [op, args] = map[verb!]!
        const v = await call(op, args())
        // browser_comments answers in markdown (the user's own words); the other verbs with a value or nothing.
        out(typeof v === "string" ? { text: v } : v ?? { ok: true }, () => io.out(typeof v === "string" ? v : Array.isArray(v) ? (v.length ? JSON.stringify(v, null, 1) : "(no open comments)") : "done"))
        return 0
      }
      case "display": {
        if (verb === "start") {
          const [w, h] = (str("size") ?? "").split("x")
          const v = await call("start_display", { ...(str("provider") ? { provider: str("provider") } : {}), ...(w ? { width: Number(w) } : {}), ...(h ? { height: Number(h) } : {}) })
          out(v, () => io.out(typeof v === "string" ? v : JSON.stringify(v)))
          return 0
        }
        if (verb === "stop" && rest[0]) { await call("stop_display", { id: rest[0] }); out({ stopped: rest[0] }, () => io.out("stopped")); return 0 }
        help("display")
      }
      // eslint-disable-next-line no-fallthrough
      case "device": {
        const v = await call("list_devices", {})
        out(v, () => io.out(Array.isArray(v) && v.length ? JSON.stringify(v, null, 1) : "(no devices)"))
        return 0
      }
      case "docs": help(verb ?? "")
      // eslint-disable-next-line no-fallthrough
      case "api": {
        const tools = listTools(false, { councilMember: true })
        if (!verb) { io.out(tools.map((t) => t.name).concat(CLI_ONLY_OPS).join("\n")); return 0 }
        const hasArgs = ((flags.f as string[] | undefined) ?? []).length > 0 || !!str("input")
        const tool = tools.find((t) => t.name === verb)
        if (flags.help || (!hasArgs && tool && ((tool.inputSchema as any).required ?? []).length)) {
          if (!tool) usage(`unknown op '${verb}'`, "api   (lists the ops)")
          const props = (tool!.inputSchema as any).properties ?? {}, req = new Set((tool!.inputSchema as any).required ?? [])
          io.out(`${verb}: ${tool!.description.split(". ")[0]}.\n` + Object.entries(props).map(([k, p]: [string, any]) => `  -f ${k}=${p.enum ? p.enum.join("|") : p.type}${req.has(k) ? "  (required)" : ""}`).join("\n"))
          return 0
        }
        const args: Record<string, unknown> = str("input") ? JSON.parse(readFileSync(local(str("input")!), "utf8")) : {}
        for (const kv of (flags.f as string[] | undefined) ?? []) { const [k, v] = kv.split(/=(.*)/s); try { args[k!] = JSON.parse(v!) } catch { args[k!] = v } }
        io.out(JSON.stringify((await call(verb, args)) ?? { ok: true }, null, 1))
        return 0
      }
      default: usage(`unknown command '${noun}'`, "help")
    }
  } catch (e) {
    if (e instanceof Exit) return e.code
    io.err(`supermux: ${e instanceof Error ? e.message : String(e)}`)
    return 1
  }
  return 0
}

/** Broker ops that are not MCP tools: only the CLI calls them (listed by a bare `supermux api`). */
export const CLI_ONLY_OPS = ["list_proxies", "cli_adoption"]

/** The CLI nouns `src/cli.ts` routes here (anything else is a broker subcommand). */
export const AGENT_CLI_NOUNS = new Set(["help", "--help", "-h", "session", "s", "msg", "delegate", "council", "card", "attach", "file", "port", "memory", "comments", "display", "device", "docs", "api"])
