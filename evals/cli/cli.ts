// The eval's `supermux` CLI prototype (#156): the command tree of the design page (§3) over the session socket,
// with the help texts, --json and exit codes under test. Not product code: it calls the same ops the MCP tools call.
//
// Exit codes: 0 ok, 1 the broker refused or failed, 2 usage, 3 not found, 4 denied, 5 broker unreachable.
import { connect } from "node:net"
import { appendFileSync, readFileSync } from "node:fs"
import { encodeFrame, decodeFrames } from "../../src/shared/frame-codec"
import { listTools } from "../../src/shim/tools"

const OUTBOUND = new Set(["attach", "show_html", "react", "edit_message", "download_attachment"])
const argv = process.argv.slice(2)
const logInvocation = (exit: number, help = false) => {
  if (process.env.EVAL_CLI_LOG) appendFileSync(process.env.EVAL_CLI_LOG, JSON.stringify({ t: Date.now(), argv, exit, help }) + "\n")
}
function done(code: number, help = false): never { logInvocation(code, help); process.exit(code) }
function usage(msg: string, line?: string): never { console.error(`supermux: ${msg}`); if (line) console.error(`usage: ${line}`); done(2) }

const HELP: Record<string, string> = {
  "": `supermux — supermux from your session's shell (signed in as this session). <cmd> --help for flags.

  session ls|show|find|read|spawn|kill|rename|models    msg SESSION... TEXT
  delegate TASK --agent A --access ID                   council start|round|add|remove|end
  card show FILE --title T     attach FILE...           file get FILE_ID
  port ls|expose|unexpose|public|private               memory search QUERY
  comments ls|reply|resolve|dismiss    display start|stop    device ls    docs TOPIC    api [OP]

--json for JSON. Exit: 0 ok, 1 failed, 2 usage, 3 not found, 4 denied, 5 broker down.`,
  session: `supermux session ls [--state working|idle|suspended]     live sessions
supermux session show SESSION                  details (SESSION: name, id or prefix; live or past)
supermux session find QUERY [--agent A] [--limit N]   past and live sessions by what was discussed
supermux session read SESSION [--grep TEXT]    a transcript (live or past)
supermux session spawn DIR [--name N] [--agent A] [--model M]   new visible session; brief it with msg
supermux session kill SESSION · rename NAME · models [AGENT]
Examples: supermux docs session`,
  msg: `supermux msg SESSION... TEXT|- [--interrupt] [--stop-now]
Send TEXT to one or more sessions' inboxes; SESSION is a name, an id or an id prefix. Each one wakes up and reads it.
--interrupt ends its current turn after the running tool call; with --stop-now it is cut at once. Prints: sent → N sessions.

  supermux msg "Git cleanup" "done testing, go ahead"
  supermux session ls --state idle --json id | jq -r '.[].id' | xargs -I{} supermux msg {} "restart at 22:00"`,
  delegate: `supermux delegate TASK|- --agent A --access ID [--checkout shared|worktree] [--model M] [--effort E] [--title T]
Start a hidden child session (on any agent) with a task. Returns at once; the child works on its own and its
report arrives in your inbox as a new turn (don't wait or poll). You are told if it crashes.
--access must be one that never waits for approval:
  claude: bypass (full) · dont-ask (read-only)       codex: full-access · auto (edit) · read-only
  opencode: allow · read-only    cursor: force · auto-review    grok: always-approve    antigravity: bypass · plan (read-only)
--checkout shared (default) works in your folder; worktree makes it a new git worktree. Prints its name and id.

  supermux delegate "review src/cli.ts for argv bugs and report back" --agent claude --access dont-ask`,
  council: `supermux council start QUESTION --member AGENT:ACCESS[:MODEL[:EFFORT]]... [--checkout shared|worktree]
supermux council round COUNCIL FOCUS · add COUNCIL --member ... · remove COUNCIL LETTER · end COUNCIL VERDICT
Returns at once. Each member's answer arrives in your inbox as a new turn (don't wait or poll; the user sees them
live on the council card). Run follow-up rounds, then end with a verdict and tell the user once.

  supermux council start "Is SQLite enough for the board?" --member claude:dont-ask --member codex:read-only`,
  card: `supermux card show FILE|- --title T [--height PX]
Show the user a live HTML page as a card in the chat: a chart, a comparison, a small tool. FILE is an .html file in your
folder ('-' reads HTML from stdin). Prints 'shown', or the problems: then the user saw nothing, so fix and show again.
Page rules (full guide: supermux docs card): one self-contained page; colours from var(--sm-fg), --sm-muted-fg,
--sm-card, --sm-border, --sm-muted, --sm-primary, --sm-chart-1…5; no background on html/body; fluid width; px chart heights.

  supermux card show rides.html --title "Rides this week"
supermux card walkthrough FILE.json        publish this session's code walkthrough`,
  attach: `supermux attach FILE... [-m CAPTION]
Send files (images, video, documents) to the user's chat. Paths are relative to your folder. Prints: sent N files.
Your normal reply already reaches the user, so this is for files, not text.

  supermux attach out/home.png -m "the home screen"`,
  file: `supermux file get FILE_ID
Download a file the user sent you. The message's meta has attachment_file_id (or attachments=[…] for several).
Prints the local path; read the file from there.

  supermux file get f_1a2b3c && cat "$(supermux file get f_1a2b3c)"`,
  port: `supermux port ls                              your exposed ports: link, port, public or not
supermux port expose PORT [--name SLUG] [--public]
    A link to a local port, e.g. a dev server. Without --public only the user's paired devices can open it;
    --public: anyone with the link can open it, no sign-in. Prints the URL.
supermux port public SLUG · private SLUG · unexpose SLUG
The user's own devices can already open any local port at <broker>/p/<port>/; expose is for a named or shareable link.

  supermux port expose 5173 --name demo --public`,
  memory: `supermux memory search QUERY [--limit N] [--json]
Search the shared knowledge base (every domain's notes and the inbox). Prints file · heading and a snippet per hit.
To SAVE something, append a dated "## title (YYYY-MM-DD)" entry to <memory home>/domains/<topic>.md yourself.

  supermux memory search "codex usage limit"`,
  comments: `supermux comments ls                          the user's open comments on browser pages (id, page, element, text)
supermux comments reply ID TEXT · resolve ID [TEXT] · dismiss ID · thread-reply ID TEXT [--resolve]`,
  display: `supermux display start [--provider linux-xvfb|macos-screen] [--size WxH]   prints the display id and DISPLAY
supermux display stop ID`,
  device: `supermux device ls        attached Android devices and emulators`,
  docs: `supermux docs TOPIC   topics: card, port, delegate, council, memory, session`,
  api: `supermux api                       list the broker ops
supermux api OP [-f key=value]... [--input FILE]   call one with raw args (values parse as JSON when they can); prints JSON`,
}

/** Longer guides, `supermux docs TOPIC`: what --help leaves out. */
const DOCS: Record<string, string> = {
  card: `Cards: supermux card show FILE --title T

Use a card when a visual or interactive result explains better than text: numbers with a shape (a series, figures by
month, a comparison of options) become a chart, and you answer in a sentence or two beside it. Not for a fact or a
sentence, not for code, not for an image to post (make a PNG and attach it).

The page: one self-contained document or fragment. Inline CSS and JS; libraries from public CDNs are fine; no local
files, no localhost, no localStorage or cookies.
Colours: only the theme variables, so the card matches light and dark mode:
  var(--sm-fg) text · var(--sm-muted-fg) secondary text · var(--sm-card) surfaces · var(--sm-border) lines
  var(--sm-muted) subtle fills · var(--sm-primary) accent · var(--sm-chart-1) … var(--sm-chart-5) data series
No background on html or body. Fluid width (390 px phone to 720 px desktop). Charts get a fixed px height; no vh units
or height:100% on html/body.
Buttons: a <button> calling supermux.send("text") sends that text to you as the user's reply (only real buttons).

  <style>.bar{height:14px;border-radius:4px;background:var(--sm-chart-1)}</style>
  <div class="bar" style="width:62%"></div>
  <button onclick="supermux.send('Use B')">Use B</button>

The result is 'shown' or a list of problems; with problems the user saw nothing: fix and show again.`,
  port: HELP.port!, delegate: HELP.delegate!, council: HELP.council!, memory: HELP.memory!,
  session: HELP.session!.replace("Examples: supermux docs session", `Examples:
  supermux session ls --state idle --json id,name
  supermux session find "zmx crash" && supermux session read a1b2c3d4 --grep fix
  supermux session spawn ~/projects/app --name "Fix login" && supermux msg "Fix login" "look into …"`),
}
const ASYNC = "It runs in the background: this command has returned. Answers arrive in your inbox as new turns (\"Message from …\"); don't wait, poll or sleep for them. Tell the user it's started and end your turn."
const OPS = ["list_sessions", "find_sessions", "read_session", "spawn_session", "kill_session", "rename_session", "list_models", "message_session", "delegate", "council", "council_round", "council_add", "council_remove", "council_end", "show_html", "walkthrough", "attach", "download_attachment", "expose_port", "unexpose_port", "set_proxy_public", "list_proxies", "memory_search", "browser_comments", "reply_browser_comment", "resolve_browser_comment", "dismiss_browser_comment", "reply_comment", "list_devices", "start_display", "stop_display", "preview_avatar", "set_avatar"]

/**
 * One line per command, all flags, no prose: the WHOLE reference (#156 Stage 2b). Transcripts showed Grok opens most
 * runs with a survey (help + several `<cmd> --help` + docs in one shell command) and reads help for commands it never
 * uses. So `supermux help` is complete on its own, `<cmd> --help` prints just that command's lines plus one example,
 * and no page points to another page.
 */
const REF: Array<[string, string, string, string?]> = [
  // [noun, verb, usage, example]
  ["session", "ls", "session ls [--state working|idle|suspended]      live sessions: id, name, agent, model, state, workdir", "supermux session ls --state idle --json id,name"],
  ["session", "show", "session show SESSION                            one session, live or past (SESSION = name, id or id prefix)"],
  ["session", "find", "session find QUERY [--agent A] [--limit N]      sessions by what was discussed (past and live)", 'supermux session find "zmx crash"'],
  ["session", "read", "session read SESSION [--grep TEXT]              a session's transcript (past or live)", "supermux session read a1b2c3d4 --grep fix"],
  ["session", "spawn", "session spawn DIR [--name N] [--agent A] [--model M]   new visible session; then brief it with msg", 'supermux session spawn ~/projects/app --name "Fix login"'],
  ["session", "kill", "session kill SESSION                            stop and archive a session"],
  ["session", "rename", "session rename NAME                             rename YOUR session (a worker: once)", 'supermux session rename "Fix login flow"'],
  ["session", "models", "session models [AGENT]                          agents, models, efforts, access ids"],
  ["msg", "", "msg SESSION... TEXT|- [--interrupt]             message one or more sessions (name or id); replies come to your inbox", 'supermux msg "Git cleanup" "Tally" "done testing"'],
  ["delegate", "", "delegate TASK|- --agent A --access ID [--checkout shared|worktree] [--title T]   hidden child session; reports to your inbox. access: claude bypass|dont-ask, codex full-access|auto|read-only, opencode allow|read-only, cursor force, grok always-approve, antigravity bypass|plan", 'supermux delegate "review src/cli.ts" --agent claude --access dont-ask'],
  ["council", "start", "council start QUESTION --member AGENT:ACCESS... [--checkout shared|worktree]   several agents answer into your inbox", 'supermux council start "SQLite enough?" --member claude:dont-ask --member grok:always-approve'],
  ["council", "", "council round|add|remove|end COUNCIL ...       next round (FOCUS), add --member, remove LETTER, end VERDICT"],
  ["card", "show", "card show FILE|- --title T [--height PX]        show the user an HTML page as a card (chart, comparison, small tool); 'card --help' has the page rules", 'supermux card show chart.html --title "Rides"'],
  ["attach", "", "attach FILE... [-m CAPTION]                     send files to the user", "supermux attach out/home.png"],
  ["file", "get", "file get FILE_ID                                download a file the user sent (meta: attachment_file_id); prints its path"],
  ["port", "ls", "port ls                                         exposed links: name, port, public or paired-only"],
  ["port", "expose", "port expose PORT [--name SLUG] [--public]       a link to a local port; --public: anyone with the link, no sign-in", "supermux port expose 5173 --name demo"],
  ["port", "", "port public|private|unexpose SLUG               change or remove a link"],
  ["memory", "search", "memory search QUERY [--limit N]                 search shared memory; to SAVE, append '## title (date)' to <memory home>/domains/<topic>.md", 'supermux memory search "codex limit"'],
  ["comments", "", "comments ls | reply ID TEXT | resolve ID | dismiss ID   the user's comments on browser pages"],
  ["display", "", "display start [--size WxH] | stop ID · device ls     virtual displays, Android devices"],
  ["api", "", "api OP [-f key=value]...                        raw broker op (rarely needed); 'api OP --help' lists its params"],
]
const LINE = (u: string) => `  supermux ${u}`
const FULL_REF = `supermux — the whole reference (signed in as this session; nothing else to read):\n` +
  REF.map(([, , u]) => LINE(u)).join("\n") +
  `\nAll commands: --json for JSON (--json a,b picks fields); '-' as TEXT reads stdin.\n` +
  `Background work (delegate, council, msg replies) arrives in your inbox as new turns: never wait or poll for it.\n` +
  `Exit: 0 ok, 1 failed, 2 usage, 3 not found, 4 denied, 5 broker down.`
/** The page rules, in `card --help` itself (no separate guide to fetch). */
const CARD_RULES = `Page rules: one self-contained page (inline CSS/JS; CDN libraries fine; no local files, localhost or localStorage).
Colours only from var(--sm-fg) var(--sm-muted-fg) var(--sm-card) var(--sm-border) var(--sm-muted) var(--sm-primary)
var(--sm-chart-1..5). No background on html/body. Fluid width (390-720 px); charts a fixed px height; no vh.
A <button onclick="supermux.send('text')"> sends that text to you as the user's reply.
Prints 'shown', or problems: then the user saw nothing; fix and show again. Use a card when numbers or options have a
shape worth seeing; answer in a sentence beside it. Not for a plain fact, code, or an image (attach a PNG).`
function helpFor(noun: string, verb?: string): string {
  if (noun === "card") return REF.filter(([n]) => n === "card").map(([, , u]) => LINE(u)).join("\n").replace(/; 'card --help' has the page rules/, "") + "\n" + CARD_RULES + "\nExample:\n  supermux card show chart.html --title \"Rides\""
  if (!noun) return FULL_REF
  const rows = REF.filter(([n, v]) => n === noun && (!verb || v === verb || v === ""))
  const pick = verb ? rows.filter(([, v]) => v === verb) : rows
  const use = (pick.length ? pick : rows)
  if (!use.length) return FULL_REF
  const ex = use.map(([, , , e]) => e).filter(Boolean)
  return use.map(([, , u]) => LINE(u)).join("\n") + (ex.length ? `\nExample${ex.length > 1 ? "s" : ""}:\n` + ex.map((e) => `  ${e}`).join("\n") : "")
}

function help(topic: string, verb?: string): never { console.log(topic === "docs" ? HELP.docs : helpFor(topic, verb)); done(0, true) }

// ---- args
const flags: Record<string, string | boolean | string[]> = {}
const pos: string[] = []
const MULTI = new Set(["member", "f"])
const BOOL = new Set(["json", "interrupt", "stop-now", "public", "no-tools", "resolve", "help", "h", "yes"])
for (let i = 0; i < argv.length; i++) {
  const a = argv[i]!
  if (a === "-") { pos.push(a); continue }
  if (a.startsWith("-")) {
    const [k0, inline] = a.replace(/^--?/, "").split(/=(.*)/s)
    const k = k0 === "m" ? "message" : k0!
    const takesValue = !BOOL.has(k) || (k === "json" && argv[i + 1] && /^[a-z_]+(,[a-z_]+)*$/.test(argv[i + 1]!) && !HELP[argv[i + 1]!])
    const v = inline !== undefined ? inline : takesValue ? argv[++i] : true
    if (v === undefined) usage(`--${k} needs a value`)
    if (MULTI.has(k)) ((flags[k] ??= []) as string[]).push(String(v))
    else flags[k] = v as string | boolean
  } else pos.push(a)
}
const json = flags.json !== undefined && flags.json !== false
const fields = typeof flags.json === "string" ? flags.json.split(",") : undefined
const str = (k: string) => (typeof flags[k] === "string" ? (flags[k] as string) : undefined)
const text = (v: string | undefined) => (v === "-" ? readFileSync(0, "utf8") : v)

// ---- transport
const SOCK = process.env.MUX_SOCKETS_DIR && process.env.MUX_SESSION_ID ? `${process.env.MUX_SOCKETS_DIR}/${process.env.MUX_SESSION_ID}.sock` : undefined
async function call(name: string, args: Record<string, unknown>): Promise<any> {
  if (!SOCK) { console.error("supermux: not inside a supermux session (no session socket)"); done(5) }
  return await new Promise((res) => {
    const s = connect(SOCK)
    let buf = Buffer.alloc(0)
    const timer = setTimeout(() => { console.error("supermux: the broker did not answer"); done(5) }, 20000)
    s.on("error", () => { console.error("supermux: the broker is unreachable"); done(5) })
    s.on("connect", () => s.write(encodeFrame({ kind: OUTBOUND.has(name) ? "outbound" : "orchestration", call_id: `cli-${process.pid}`, op: { name, args } })))
    s.on("data", (c: Buffer) => {
      buf = Buffer.concat([buf, c])
      for (const m of decodeFrames(buf).messages as any[]) {
        if (m.kind !== "result") continue
        clearTimeout(timer); s.end()
        if (!m.ok) {
          const e = String(m.error ?? "failed")
          if (json) console.error(JSON.stringify({ error: { code: /unknown|no such|no transcript|no proxy/.test(e) ? "not_found" : /only agents|can't/.test(e) ? "denied" : "failed", message: e } }))
          else console.error(`supermux: ${e}`)
          done(/unknown|no such|no transcript|no proxy/.test(e) ? 3 : /only agents|can't message your own/.test(e) ? 4 : 1)
        }
        res(m.value)
      }
    })
  })
}

// ---- output
const pick = (o: any) => (fields ? Object.fromEntries(fields.map((f) => [f, o?.[f]])) : o)
function out(value: unknown, human: () => void) {
  if (json) console.log(JSON.stringify(Array.isArray(value) ? value.map(pick) : pick(value), null, 1))
  else human()
}
function table(rows: any[], cols: Array<[string, string]>) {
  if (!rows.length) { console.log("(none)"); return }
  const w = cols.map(([k, h]) => Math.max(h.length, ...rows.map((r) => String(r[k] ?? "").length)))
  console.log(cols.map(([, h], i) => h.padEnd(w[i]!)).join("  "))
  for (const r of rows) console.log(cols.map(([k], i) => String(r[k] ?? "").padEnd(w[i]!)).join("  "))
}

async function resolveSession(ref: string): Promise<string> {
  const all: any[] = await call("list_sessions", { include_archived: true })
  const hit = all.find((s) => s.id === ref) ?? all.find((s) => s.id.startsWith(ref) && ref.length >= 6)
    ?? all.find((s) => s.name === ref) ?? all.find((s) => s.name.toLowerCase() === ref.toLowerCase())
  if (hit) return hit.id
  const near = all.filter((s) => s.name.toLowerCase().includes(ref.toLowerCase()))
  if (near.length === 1) return near[0].id
  console.error(`supermux: no session '${ref}'${near.length ? ` (did you mean: ${near.map((s) => s.name).join(", ")}?)` : " (supermux session ls lists them)"}`)
  done(3)
}

// ---- commands
const [noun, verb, ...rest] = pos
if (!noun || noun === "help") help(verb ?? "", rest[0])
if (flags.help || flags.h) help(noun === "s" ? "session" : noun, verb && !verb.startsWith("-") ? verb : undefined)

async function main() {
  switch (noun) {
    case "session": case "s": {
      switch (verb) {
        case "ls": case "list": {
          let v: any[] = await call("list_sessions", {})
          if (str("state")) v = v.filter((s) => s.state === str("state"))
          return out(v, () => table(v.map((s) => ({ ...s, id: s.id.slice(0, 8) })), [["id", "ID"], ["name", "NAME"], ["agent", "AGENT"], ["model", "MODEL"], ["state", "STATE"], ["workdir", "WORKDIR"]]))
        }
        case "show": { if (!rest[0]) usage("which session?", "supermux session show SESSION"); const id = await resolveSession(rest[0]); const v = (await call("list_sessions", { include_archived: true })).find((s: any) => s.id === id); return out(v, () => console.log(Object.entries(v).map(([k, x]) => `${k}: ${JSON.stringify(x)}`).join("\n"))) }
        case "find": { if (!rest.length) usage("find what?", "supermux session find QUERY"); const v: any[] = await call("find_sessions", { query: rest.join(" "), agent: str("agent"), since: str("since"), limit: str("limit") ? Number(str("limit")) : undefined }); return out(v, () => table(v.map((s) => ({ ...s, id: s.id.slice(0, 8), last_active: String(s.last_active ?? "").slice(0, 10) })), [["id", "ID"], ["name", "NAME"], ["agent", "AGENT"], ["last_active", "LAST ACTIVE"]])) }
        case "read": { if (!rest[0]) usage("which session?", "supermux session read SESSION [--grep TEXT]"); const v = await call("read_session", { session_id: await resolveSession(rest[0]), grep: str("grep"), include_tool_calls: !flags["no-tools"] }); return out(v, () => console.log(v)) }
        case "spawn": { if (!rest[0]) usage("in which folder?", "supermux session spawn DIR [--name N] [--agent A]"); const v = await call("spawn_session", { workdir: rest[0], name: str("name"), agent: str("agent"), model: str("model"), effort: str("effort") }); return out({ ...v, note: "Brief it with: supermux msg <its name or id> \"…\"" }, () => console.log(`started ${v.name} (${v.id}). Brief it with: supermux msg ${JSON.stringify(v.name)} "…"`)) }
        case "kill": { if (!rest[0]) usage("which session?"); const id = await resolveSession(rest[0]); await call("kill_session", { id }); return out({ killed: id }, () => console.log(`killed ${id}`)) }
        case "rename": { if (!rest.length) usage("to what?", "supermux session rename NAME"); const v = await call("rename_session", { name: rest.join(" ") }); return out(v, () => console.log(`renamed to ${rest.join(" ")}`)) }
        case "models": { const v = await call("list_models", { agent: rest[0] }); return out(v, () => { for (const a of v.agents) console.log(`${a.agent}: access ${a.access.map((m: any) => `${m.id} (${m.level})`).join(", ")}; efforts ${a.models[0]?.efforts?.join(", ") || "none"}`) }) }
        default: help("session")
      }
    }
    case "msg": {
      const body = text(pos[pos.length - 1])
      const refs = pos.slice(1, -1)
      if (!refs.length || !body) usage("who to, and what?", "supermux msg SESSION... TEXT|-")
      const to = [] as string[]
      for (const r of refs) to.push(await resolveSession(r))
      const v = await call("message_session", { to, text: body, interrupt: !!flags.interrupt, ...(flags["stop-now"] ? { stop_now: true } : {}) })
      return out(v, () => console.log(`sent → ${to.length} session${to.length > 1 ? "s" : ""}. Any reply arrives in your inbox as a new turn; no need to wait.`))
    }
    case "delegate": {
      const task = text(verb)
      if (!task || !str("agent") || !str("access")) usage("task, --agent and --access are required", "supermux delegate TASK|- --agent A --access ID")
      const v = await call("delegate", { task, agent: str("agent"), access: str("access"), checkout: str("checkout") ?? "shared", model: str("model"), effort: str("effort"), title: str("title") })
      return out({ ...v, note: ASYNC }, () => console.log(`started ${v.name} (${v.session_id}).\n${ASYNC}`))
    }
    case "council": {
      const members = ((flags.member as string[]) ?? []).map((m) => { const [agent, access, model, effort] = m.split(":"); return { agent, access, model, effort } })
      if (verb === "start") { if (!rest[0] || !members.length) usage("question and --member", HELP.council!.split("\n")[0]); const v = await call("council", { question: text(rest[0]), members, checkout: str("checkout") ?? "shared" }); return out({ ...v, note: ASYNC }, () => console.log(`council ${v.council} started with members ${v.members.join(", ")}.\n${ASYNC}`)) }
      if (verb === "round") { const v = await call("council_round", { council: rest[0], focus: rest[1] }); return out(v ?? {}, () => console.log("round started")) }
      if (verb === "add") { const v = await call("council_add", { council: rest[0], members, checkout: str("checkout") ?? "shared" }); return out(v ?? {}, () => console.log("added")) }
      if (verb === "remove") { const v = await call("council_remove", { council: rest[0], letter: rest[1] }); return out(v ?? {}, () => console.log("removed")) }
      if (verb === "end") { const v = await call("council_end", { council: rest[0], verdict: rest.slice(1).join(" ") }); return out(v ?? {}, () => console.log("ended")) }
      help("council")
    }
    case "card": {
      if (verb === "show") {
        if (!rest[0] || !str("title")) usage("a FILE (or -) and --title are required", "supermux card show FILE|- --title T")
        // The CLI runs as the agent, so it reads the page itself (any path the agent can read) and sends the HTML.
        // Grok wrote pages to /tmp and the broker, which only reads inside the session's folder, said "no such file".
        let html: string
        try { html = readFileSync(rest[0] === "-" ? 0 : rest[0], "utf8") } catch { console.error(`supermux: can't read ${rest[0]}: no such file (from ${process.cwd()})`); done(3) }
        const args = { html }
        const v = await call("show_html", { title: str("title"), ...args, ...(str("height") ? { height: Number(str("height")) } : {}) })
        return out(v, () => console.log(v?.problems?.length ? `NOT shown — problems: ${v.problems.join("; ")}` : "shown"))
      }
      if (verb === "walkthrough") { const v = await call("walkthrough", JSON.parse(readFileSync(rest[0]!, "utf8"))); return out(v ?? {}, () => console.log("published")) }
      help("card")
    }
    case "attach": {
      const files = pos.slice(1)
      if (!files.length) usage("which files?", "supermux attach FILE... [-m CAPTION]")
      await call("attach", { files, text: str("message") })
      return out({ sent: files }, () => console.log(`sent ${files.length} file${files.length > 1 ? "s" : ""}`))
    }
    case "file": {
      if (verb !== "get" || !rest[0]) usage("which file id?", "supermux file get FILE_ID")
      const v = await call("download_attachment", { file_id: rest[0] })
      return out(v, () => console.log(v.path))
    }
    case "port": {
      if (verb === "expose") { if (!rest[0]) usage("which port?", "supermux port expose PORT [--name SLUG] [--public]"); const v = await call("expose_port", { port: Number(rest[0]), domain: str("name"), public: !!flags.public }); return out(v, () => console.log(`${v.url}   (${v.isPublic ? "public: anyone with the link" : "paired devices only"})`)) }
      if (verb === "unexpose") { await call("unexpose_port", { domain: rest[0] }); return out({}, () => console.log("removed")) }
      if (verb === "public" || verb === "private") { const v = await call("set_proxy_public", { domain: rest[0], public: verb === "public" }); return out(v, () => console.log(`${v.url} is now ${verb}`)) }
      if (verb === "ls") { const v: any[] = await call("list_proxies", {}); return out(v, () => table(v.map((p) => ({ ...p, public: p.public ? "public" : "paired only" })), [["domain", "NAME"], ["port", "PORT"], ["public", "ACCESS"], ["url", "URL"]])) }
      help("port")
    }
    case "memory": {
      if (verb !== "search" || !rest.length) help("memory")
      const v: any[] = await call("memory_search", { query: rest.join(" "), limit: str("limit") ? Number(str("limit")) : undefined })
      return out(v, () => { if (!v.length) console.log("(no hits)"); for (const h of v) console.log(`${h.file} · ${h.heading}\n  ${h.snippet.replace(/\n/g, "\n  ")}\n`) })
    }
    case "comments": {
      const map: Record<string, string> = { ls: "browser_comments", reply: "reply_browser_comment", resolve: "resolve_browser_comment", dismiss: "dismiss_browser_comment", "thread-reply": "reply_comment" }
      if (!verb || !map[verb]) help("comments")
      const v = await call(map[verb]!, verb === "ls" ? {} : verb === "thread-reply" ? { comment_id: rest[0], body: rest[1], resolve: !!flags.resolve } : { id: rest[0], text: rest[1] })
      return out(v ?? {}, () => console.log(Array.isArray(v) ? (v.length ? JSON.stringify(v, null, 1) : "(no open comments)") : "done"))
    }
    case "display": {
      if (verb === "start") { const [w, h] = (str("size") ?? "").split("x"); const v = await call("start_display", { provider: str("provider"), width: w ? Number(w) : undefined, height: h ? Number(h) : undefined }); return out(v, () => console.log(`${v.id} on ${v.display}`)) }
      if (verb === "stop") { await call("stop_display", { id: rest[0] }); return out({}, () => console.log("stopped")) }
      help("display")
    }
    case "device": { const v: any[] = await call("list_devices", {}); return out(v, () => console.log(v.length ? JSON.stringify(v) : "(no devices)")) }
    case "docs": { console.log(verb ? helpFor(verb) : FULL_REF); return done(0, true) }
    case "api": {
      if (!verb) { console.log(OPS.join("\n")); return done(0, true) }
      if (flags.help || flags.h || (!(flags.f as string[] | undefined)?.length && !str("input") && !["list_sessions", "list_models", "list_proxies", "browser_comments", "list_devices", "start_display"].includes(verb))) {
        const t = listTools(false, { councilMember: true }).find((x) => x.name === verb)
        if (!t) usage(`unknown op '${verb}'`, "supermux api   (lists the ops)")
        const props = (t!.inputSchema as any).properties ?? {}, req = new Set((t!.inputSchema as any).required ?? [])
        console.log(`${verb}: ${t!.description.split(". ")[0]}.\n` + Object.entries(props).map(([k, p]: [string, any]) => `  -f ${k}=${p.enum ? p.enum.join("|") : p.type}${req.has(k) ? "  (required)" : ""}`).join("\n"))
        return done(0, true)
      }
      const args: Record<string, unknown> = str("input") ? JSON.parse(readFileSync(str("input")!, "utf8")) : {}
      for (const kv of (flags.f as string[]) ?? []) { const [k, v] = kv.split(/=(.*)/s); try { args[k!] = JSON.parse(v!) } catch { args[k!] = v } }
      console.log(JSON.stringify(await call(verb, args) ?? { ok: true }, null, 1)); return
    }
    default: usage(`unknown command '${noun}'`, "supermux help")
  }
}
await main()
done(0)
