#!/usr/bin/env bun
// Interactive console over supermux-core against a REAL agent: create or re-attach a session,
// watch the normalized event stream, answer permission requests and questions, steer,
// interrupt, detach (agent keeps running) or shut down. No broker code involved.
//
// Usage (all flags required — the library has no defaults and neither does this script):
//   bun examples/console.mjs --agent codex|claude|grok|opencode|cursor --cwd <dir> --state <dir> --session <id> [--model <m>] [--deltas]
//
// Re-run with the same --state and --session to re-attach (Codex/Claude/Grok keep running behind the keeper).
// Type text to send it. Commands: /steer <text>  /interrupt  /requests  /allow <n> [always]  /reject <n> [message]
//   /answer <n> <optionId|free text>  /decline <n>  /status  /detach  /quit
import { createInterface } from "node:readline"
import { resolve } from "node:path"
import { createCore } from "supermux-core"
import { codex } from "supermux-core/codex"
import { claude } from "supermux-core/claude"
import { grok, opencode, cursor } from "supermux-core/agents"

const args = Object.fromEntries(process.argv.slice(2).map((a, i, all) => a.startsWith("--") ? [a.slice(2), all[i + 1]?.startsWith("--") || all[i + 1] === undefined ? true : all[i + 1]] : []).filter(Boolean))
const need = (k) => { if (args[k] === undefined || args[k] === true) { console.error(`--${k} is required`); process.exit(2) } return String(args[k]) }
const agentName = need("agent"), cwd = resolve(need("cwd")), stateDirectory = resolve(need("state")), sessionId = need("session")
const model = typeof args.model === "string" ? args.model : undefined
let showDeltas = args.deltas === true

const keeper = { stateDirectory: `${stateDirectory}/keepers`, limits: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 15_000 } }
const timeouts = { setupTimeoutMs: 30_000, shutdownTimeoutMs: 5_000, maxFrameBytes: 16 * 1024 * 1024 }
const drivers = {
  codex: () => codex({ id: "codex", command: "codex", args: ["app-server"], inheritEnv: true, sandbox: "workspace-write", approvalPolicy: "on-request", permissionPrompts: "host", requestTimeoutMs: 120_000, ...timeouts, keeper, ...(model ? { model } : {}) }),
  claude: () => claude({ id: "claude", command: "claude", args: [], inheritEnv: true, tools: "default", permissionPrompts: "host", partialMessages: true, requestTimeoutMs: 120_000, ...timeouts, keeper, ...(model ? { model } : {}) }),
  grok: () => grok({ id: "grok", command: "grok", commandArgs: [], alwaysApprove: false, noLeader: true, inheritEnv: true, mcpServers: [], cancelRetryIntervalMs: 500, cancelRetryTimeoutMs: 10_000, maxOutstandingActivity: 64, ...timeouts, keeper, ...(model ? { model } : {}) }),
  opencode: () => opencode({ id: "opencode", command: "opencode", inheritEnv: true, mcpServers: [], cancelRetryIntervalMs: 500, cancelRetryTimeoutMs: 10_000, maxOutstandingActivity: 64, ...timeouts, keeper, ...(model ? { model } : {}) }),
  cursor: () => cursor({ id: "cursor", command: "cursor-agent", commandArgs: [], permissions: "force", inheritEnv: true, mcpServers: [], cancelRetryIntervalMs: 500, cancelRetryTimeoutMs: 10_000, maxOutstandingActivity: 64, ...timeouts, keeper, ...(model ? { model } : {}) }),
}
if (!drivers[agentName]) { console.error(`unknown agent ${agentName}`); process.exit(2) }

const core = createCore({ stateDirectory, agents: [drivers[agentName]()], limits: { interruptTimeoutMs: 10_000, maxPending: 32, outstandingActivity: 256 } })
const requestIndex = new Map()   // n -> requestId
let n = 0
const dim = (s) => `\x1b[2m${s}\x1b[0m`, bold = (s) => `\x1b[1m${s}\x1b[0m`, cyan = (s) => `\x1b[36m${s}\x1b[0m`, yellow = (s) => `\x1b[33m${s}\x1b[0m`, red = (s) => `\x1b[31m${s}\x1b[0m`
const say = (s) => { process.stdout.write(`\r${s}\n> `) }

core.subscribe((e) => {
  if (e.type === "session.stateChanged") return say(dim(`state: ${e.state}`))
  if (e.type === "session.failed") return say(red(`session failed: ${e.error.message}`))
  if (e.type !== "session.event") return
  const ev = e.event, tag = dim(`#${ev.seq}${ev.replay ? " replay" : ""}`)
  switch (ev.kind) {
    case "assistant-delta": case "reasoning-delta": if (showDeltas) process.stdout.write(ev.text); return
    case "assistant-message": return say(`${tag} ${bold("assistant")}: ${ev.text}`)
    case "reasoning": return say(`${tag} ${dim("reasoning")}: ${ev.redacted ? dim("(redacted)") : ev.text}`)
    case "tool-call": return say(`${tag} ${cyan("tool")} ${ev.tool} ${ev.phase}${ev.exitCode !== undefined ? ` exit=${ev.exitCode}` : ""}${ev.input?.command ? ` ${dim(String(ev.input.command).slice(0, 80))}` : ""}`)
    case "command-output": return showDeltas ? process.stdout.write(ev.delta) : undefined
    case "file-diff": return say(`${tag} ${cyan("diff")} ${ev.path}\n${ev.diff.split("\n").slice(0, 12).join("\n")}`)
    case "permission-request": { const k = ++n; requestIndex.set(k, ev.requestId); return say(`${tag} ${yellow(`PERMISSION #${k}`)} ${ev.toolCall.tool}: ${ev.detail?.command ?? ev.toolCall.title}\n   options: ${ev.options.map(o => `${o.kind}(${o.id})`).join(", ")}   → /allow ${k} [always] | /reject ${k} [message]`) }
    case "user-question": { const k = ++n; requestIndex.set(k, ev.requestId); return say(`${tag} ${yellow(`QUESTION #${k}`)}${ev.blocking ? "" : " (non-blocking)"}\n` + ev.questions.map(q => `   [${q.id}] ${q.prompt}\n` + q.options.map(o => `      ${o.id}: ${o.label}`).join("\n")).join("\n") + `\n   → /answer ${k} <optionId or free text> | /decline ${k}`) }
    case "request-resolved": return say(`${tag} ${dim(`request resolved: ${ev.outcome}`)}`)
    case "turn-start": return say(`${tag} ${dim("turn start")}`)
    case "turn-complete": return say(`${tag} ${dim(`turn complete (${ev.reason})`)}`)
    case "usage": return say(`${tag} ${dim(`usage ${JSON.stringify(ev.tokens ?? ev.context ?? ev.cost ?? "rate limits")}`)}`)
    case "error": return say(`${tag} ${red(`error: ${ev.message}`)}`)
    case "warning": return say(`${tag} ${yellow(`warning: ${ev.message}`)}`)
    case "plan": return say(`${tag} ${cyan("plan")}\n` + ev.entries.map(x => `   [${x.status}] ${x.content}`).join("\n"))
    default: return say(`${tag} ${dim(ev.kind)}`)
  }
})

const existing = await core.sessions.get(sessionId)
const session = existing
  ? await core.sessions.resume(sessionId)
  : await core.sessions.create({ id: sessionId, agent: agentName, cwd, ...(model && agentName !== "opencode" && agentName !== "cursor" ? { configuration: { model } } : {}) })
say(`${existing ? "re-attached to" : "created"} ${bold(sessionId)} (${agentName}, native ${session.snapshot().agentSessionId}) state=${session.snapshot().state}`)
for (const p of session.requests.list()) { const k = ++n; requestIndex.set(k, p.requestId); say(yellow(`pending ${p.kind} #${k} from before: ${p.body.kind === "permission-request" ? p.body.toolCall.tool : p.body.questions[0]?.prompt}`)) }

const rl = createInterface({ input: process.stdin, output: process.stdout, prompt: "> " })
const idOf = (k) => { const id = requestIndex.get(Number(k)); if (!id) throw new Error(`no request #${k}`) ; return id }
async function run(line) {
  const [cmd, ...rest] = line.trim().split(/\s+/), text = rest.join(" ")
  if (!line.trim()) return
  if (!cmd.startsWith("/")) { const r = await session.send({ content: [{ type: "text", text: line }], whenBusy: "queue" }); r.completed.then(c => say(dim(`turn ${c.status}`))); return }
  switch (cmd) {
    case "/steer": return session.steer({ content: [{ type: "text", text }] })
    case "/interrupt": return say(dim(`interrupt: ${(await session.interrupt({ pending: "discard" })).status}`))
    case "/requests": return say(session.requests.list().map(p => `${p.kind} ${p.requestId} ${p.body.kind}`).join("\n") || dim("no pending requests"))
    case "/allow": { const id = idOf(rest[0]); const req = session.requests.list().find(p => p.requestId === id); const kind = rest[1] === "always" ? "allow_always" : "allow_once"; const opt = req.body.options.find(o => o.kind === kind) ?? req.body.options.find(o => o.kind.startsWith("allow")); return session.requests.respond(id, { optionId: opt.id }) }
    case "/reject": { const id = idOf(rest[0]); const req = session.requests.list().find(p => p.requestId === id); const opt = req.body.options.find(o => o.kind === "reject_once") ?? req.body.options.find(o => o.kind.startsWith("reject")); return session.requests.respond(id, { optionId: opt.id, ...(rest.slice(1).length ? { message: rest.slice(1).join(" ") } : {}) }) }
    case "/answer": { const id = idOf(rest[0]); const req = session.requests.list().find(p => p.requestId === id); const q = req.body.questions[0]; const value = rest.slice(1).join(" "); return session.requests.respond(id, { answers: { [q.id]: value } }) }
    case "/decline": return session.requests.respond(idOf(rest[0]), { decline: true })
    case "/status": return say(JSON.stringify(session.snapshot()))
    case "/deltas": showDeltas = !showDeltas; return say(dim(`deltas ${showDeltas ? "on" : "off"}`))
    case "/detach": await session.close({ mode: "detach" }); await core.close({ agents: "detach" }); say(dim("detached; the agent keeps running. Re-run with the same --state and --session to re-attach.")); process.exit(0)
    case "/quit": await session.close({ mode: "shutdown" }); await core.close({ agents: "shutdown" }); say(dim("shut down")); process.exit(0)
    default: return say(red(`unknown command ${cmd}`))
  }
}
rl.on("line", (line) => { run(line).catch(err => say(red(err.message))).finally(() => rl.prompt()) })
rl.on("close", () => { core.close({ agents: "detach" }).finally(() => process.exit(0)) })
rl.prompt()
