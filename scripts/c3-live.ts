/**
 * C3 live check: one worker-style session per agent through the BROKER's core-host code path
 * (createXCoreHost: prepare → SessionContext → the core's real drivers), NOT the live broker.
 *
 *   bun scripts/c3-live.ts [claude] [codex] [grok] [opencode]
 *
 * Everything lives under ~/.cache/context-c3/live-<stamp>/: HOME, MUX_HOME (plugins registry),
 * MUX_STATE_DIR (sockets, core state), XDG dirs. Real homes are only READ for credentials:
 *   claude    a token account (current access token, no refresh token) in a scratch registry
 *   codex     a token account (access token + account id, no refresh token)
 *   grok      the system login: a scratch copy of ~/.grok/auth.json under the scratch HOME
 *   opencode  a scratch copy of the API-key auth.json under the scratch XDG_DATA_HOME
 * Per agent, each with its own code word (the prompts never contain the words):
 *   instructions  the broker's generated instructions carry the session name (`You are "<name>"`)
 *   plugin skill  a registry plugin `c3probe` whose skill holds a word
 *   mux-shim      launched by the agent with the broker's exact command/env; a fake broker socket
 *                 (the shim's own protocol) answers list_sessions with a word
 * Also recorded: whether the plugin's SessionStart hook ran (Grok/Claude load whole plugins),
 * the core's context folder, and that nothing was written into the workdir.
 */
import { chmodSync, copyFileSync, existsSync, mkdirSync, readdirSync, readFileSync, writeFileSync } from "node:fs"
import { createServer, type Server } from "node:net"
import { homedir } from "node:os"
import { join } from "node:path"

const REAL_HOME = homedir()
const RUN = join(REAL_HOME, ".cache", "context-c3", `live-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const HOME = join(RUN, "home"), MUX = join(RUN, "mux")
for (const d of [HOME, MUX, join(HOME, ".grok"), join(HOME, ".local", "share", "opencode"), join(HOME, ".config")]) mkdirSync(d, { recursive: true })
// Read-only credential reads from the real homes, BEFORE HOME moves.
const claudeToken: string = JSON.parse(readFileSync(join(REAL_HOME, ".claude", ".credentials.json"), "utf8")).claudeAiOauth.accessToken
const codexTokens = JSON.parse(readFileSync(join(REAL_HOME, ".codex", "auth.json"), "utf8")).tokens
copyFileSync(join(REAL_HOME, ".grok", "auth.json"), join(HOME, ".grok", "auth.json")); chmodSync(join(HOME, ".grok", "auth.json"), 0o600)
copyFileSync(join(REAL_HOME, ".local", "share", "opencode", "auth.json"), join(HOME, ".local", "share", "opencode", "auth.json")); chmodSync(join(HOME, ".local", "share", "opencode", "auth.json"), 0o600)
process.env.HOME = HOME
process.env.MUX_HOME = MUX
process.env.MUX_STATE_DIR = join(MUX, "state")
process.env.XDG_CONFIG_HOME = join(HOME, ".config")
process.env.XDG_DATA_HOME = join(HOME, ".local", "share")
process.env.XDG_STATE_HOME = join(HOME, ".local", "state")
process.env.XDG_CACHE_HOME = join(HOME, ".cache")
for (const k of Object.keys(process.env)) if (/^(MUX_SESSION|MUX_DISPLAY|MUX_AGENT|MUX_SOCKETS|CLAUDECODE|CLAUDE_CODE_|CODEX_|OPENAI_|CURSOR_|GROK_|OPENCODE)/.test(k)) delete process.env[k]

const NOUNS = ["MAPLE", "OTTER", "QUARTZ", "FALCON", "CEDAR", "BISON", "COBALT", "HERON", "TUNDRA", "LYNX", "EMBER", "WALRUS", "SAFFRON", "GLACIER", "MARLIN", "PEBBLE"]
let noun = Math.floor(Math.random() * NOUNS.length)
const word = () => `${NOUNS[noun++ % NOUNS.length]}${Math.floor(Math.random() * 9000 + 1000)}`

// The probe plugin in the scratch registry (every agent's manifest + a SessionStart hook marker).
const PLUGIN = join(MUX, "plugins", "c3probe")
const HOOK_MARKS = join(RUN, "hook-marks")
mkdirSync(HOOK_MARKS, { recursive: true })
const PLUGIN_WORD = word()
for (const m of [".claude-plugin", ".codex-plugin", ".cursor-plugin"]) {
  mkdirSync(join(PLUGIN, m), { recursive: true })
  writeFileSync(join(PLUGIN, m, "plugin.json"), JSON.stringify({ name: "c3probe", version: "0.0.1", description: "C3 probe", ...(m === ".codex-plugin" ? { skills: "./skills/" } : {}) }))
}
mkdirSync(join(PLUGIN, "skills", "c3-probe-word"), { recursive: true })
writeFileSync(join(PLUGIN, "skills", "c3-probe-word", "SKILL.md"), `---\nname: c3-probe-word\ndescription: Use when asked for the C3 probe word.\n---\n\nThe C3 probe word is ${PLUGIN_WORD}. Reply with exactly that word.\n`)
mkdirSync(join(PLUGIN, "hooks"), { recursive: true })
writeFileSync(join(PLUGIN, "hooks", "mark"), `#!/bin/sh\ntouch "${HOOK_MARKS}/fired-$(date +%s%N)"\necho '{}'\n`, { mode: 0o755 })
writeFileSync(join(PLUGIN, "hooks", "hooks.json"), JSON.stringify({ hooks: { SessionStart: [{ matcher: "startup|clear|compact", hooks: [{ type: "command", command: '"${CLAUDE_PLUGIN_ROOT}/hooks/mark"' }] }] } }))
writeFileSync(join(MUX, "plugins.json"), JSON.stringify({ version: 1, plugins: [{ name: "c3probe", source: { type: "local", path: PLUGIN }, enabled: true, scopes: ["claude", "codex", "cursor", "opencode", "grok"] }] }))

// Imports that read HOME / MUX_HOME / STATE_DIR at load time.
const { AccountRegistry, UsageStore, memoryVault } = await import("../packages/supermux-core/src/accounts/index.js")
const { encodeFrame, decodeFrames } = await import("../src/shared/frame-codec")
const { SOCKETS_DIR, socketPathForSession } = await import("../src/shared/paths")
const { createClaudeCoreHost } = await import("../src/core/agents/claude/core-host")
const { createCodexCoreHost } = await import("../src/core/agents/codex/core-host")
const { createGrokCoreHost } = await import("../src/core/agents/grok/core-host")
const { createOpenCodeCoreHost } = await import("../src/core/agents/opencode/core-host")
// What spawnSession does before every Claude spawn: trust the workdir and register mux-shim
// (+ mux-channel) in ~/.claude.json (here the scratch HOME's).
const { preAcceptTrust } = await import("../src/core/session-manager/trust")
type CoreEvent = import("../packages/supermux-core/src/index.js").CoreEvent
type Session = import("../packages/supermux-core/src/index.js").Session
mkdirSync(SOCKETS_DIR, { recursive: true })

const registry = new AccountRegistry(join(RUN, "accounts"), ["claude", "codex", "cursor", "grok", "opencode"], {
  vault: memoryVault(), homes: { claudeRoot: join(RUN, "homes", "claude"), codexRoot: join(RUN, "homes", "codex"), grokRoot: join(RUN, "homes", "grok"), cursorRoot: join(RUN, "homes", "cursor") },
})
const usage = new UsageStore(join(registry.directory, "usage.json"))
await registry.add({ id: "claude-tok", agent: "claude", method: "token", secret: claudeToken })
await registry.add({ id: "codex-tok", agent: "codex", method: "token", secret: JSON.stringify({ access_token: codexTokens.access_token, account_id: codexTokens.account_id }) })
const accounts = { registry, usage }

type Line = { agent: string; check: string; ok: boolean; evidence: unknown }
const results: Line[] = []
function record(agent: string, check: string, ok: boolean, evidence: unknown) {
  results.push({ agent, check, ok, evidence })
  console.log(`${ok ? "PASS" : "FAIL"}  ${agent.padEnd(8)} ${check} :: ${JSON.stringify(evidence).slice(0, 400)}`)
}

/** A fake broker for one session: the shim's register / orchestration frames, list_sessions → a word. */
function fakeBroker(sessionId: string, name: string, mcpWord: string): { server: Server; calls: string[]; registered: string[] } {
  const calls: string[] = []
  const registered: string[] = []
  const server = createServer((sock) => {
    let buf = Buffer.alloc(0)
    sock.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk])
      const { messages, rest } = decodeFrames(buf)
      buf = rest
      for (const m of messages as Array<Record<string, any>>) {
        if (m.kind === "register") { registered.push(String(m.pid)); sock.write(encodeFrame({ kind: "registered", display_name: name, session_id: sessionId })) }
        else if (m.kind === "orchestration" || m.kind === "outbound") {
          calls.push(m.op?.name)
          sock.write(encodeFrame({ kind: "result", call_id: m.call_id, ok: true, value: m.op?.name === "list_sessions" ? { sessions: [{ name: mcpWord }] } : "ok" }))
        } else if (m.kind === "ping") sock.write(encodeFrame({ kind: "pong" }))
      }
    })
    sock.on("error", () => {})
  })
  server.listen(socketPathForSession(sessionId))
  return { server, calls, registered }
}

async function ask(session: Session, events: CoreEvent[], text: string, timeoutMs = 300_000): Promise<string> {
  const start = events.length
  const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "reject" })
  const completion = await Promise.race([receipt.completed, new Promise<never>((_, reject) => setTimeout(() => reject(new Error("turn timed out")), timeoutMs))])
  if (completion.status !== "completed") throw new Error(`turn ${completion.status}: ${completion.status === "failed" ? completion.error.message : ""}`)
  await new Promise(resolve => setTimeout(resolve, 300))
  return events.slice(start).flatMap(e => e.type === "session.event" && e.sessionId === session.id && e.event.kind === "assistant-message" && !(e.event as { subagentId?: string }).subagentId ? [e.event.text] : []).join("\n").trim()
}

type Agent = "claude" | "codex" | "grok" | "opencode"
const ALL: Agent[] = ["claude", "codex", "grok", "opencode"]
const wanted = process.argv.slice(2).filter((a): a is Agent => (ALL as string[]).includes(a))

async function check(agent: Agent) {
  const instrWord = word(), mcpWord = word()
  const name = `probe-${instrWord.toLowerCase()}`
  const id = `c3-live-${agent}-${Date.now()}`
  const work = join(RUN, `work-${agent}`)
  mkdirSync(join(work, ".git", "info"), { recursive: true })
  writeFileSync(join(work, "README.md"), "scratch\n")
  const state = join(RUN, "core", agent)
  const base = { stateDirectory: state, accounts }
  const host = agent === "claude" ? createClaudeCoreHost(base) : agent === "codex" ? createCodexCoreHost(base) : agent === "grok" ? createGrokCoreHost(base) : createOpenCodeCoreHost(base)
  const events: CoreEvent[] = []
  host.core.subscribe(e => { events.push(e) })
  const broker = fakeBroker(id, name, mcpWord)
  const marksBefore = readdirSync(HOOK_MARKS).length
  const extra: Record<string, unknown> = {
    sessionHome: join(RUN, "agents", agent, name), sessionName: name, sessionId: id, workdir: work, cwd: work,
    ...(agent === "claude" ? { model: "haiku" } : agent === "opencode" ? { model: "opencode-go/qwen3.7-plus" } : {}),
  }
  const account = agent === "claude" ? "claude-tok" : agent === "codex" ? "codex-tok" : undefined
  const configuration = agent === "codex" ? { model: "gpt-5.6-luna", reasoningEffort: "low" } : agent === "grok" ? { reasoningEffort: "low" } : undefined
  if (agent === "claude") preAcceptTrust(work)
  const handle = host.register({ id, env: {}, ...(agent === "codex" ? { command: "codex", args: ["app-server"] } : {}), extra, ...(account ? { account } : {}) })
  try {
    const session = await handle.start({ cwd: work, ...(configuration ? { configuration } : {}) })
    const record0 = await host.core.sessions.get(id)
    record(agent, "launched through the broker core-host with a session context", !!record0?.createdInstructions && !!record0?.context, {
      createdInstructionsHasName: record0?.createdInstructions?.includes(name), plugins: record0?.context?.plugins, mcp: record0?.context?.mcpServers?.map(s => (s as { name: string }).name),
      degraded: events.filter(e => e.type === "context.degraded").map(e => (e as { dropped: unknown }).dropped),
    })
    const step = async (label: string, prompt: string, expected: string) => {
      try { const reply = await ask(session, events, prompt); record(agent, label, reply.toUpperCase().includes(expected.toUpperCase()), { expected, reply: reply.slice(0, 300) }) }
      catch (error) { record(agent, label, false, { expected, error: (error as Error).message }) }
    }
    await step("instructions arrive (session name from the generated header)", "Without using any tools: what session name do your system instructions say you have? Reply with just the name.", name)
    await step("broker plugin's skill is usable", "Use your c3-probe-word skill (load it with your skill mechanism, or read its SKILL.md) and reply with the C3 probe word only.", PLUGIN_WORD)
    await step("mux-shim MCP server started, listed and callable", "Call the list_sessions tool of the mux-shim MCP server and reply with only the session name it returns.", mcpWord)
    record(agent, "mux-shim process registered with the (fake) broker", broker.registered.length > 0, { registeredPids: broker.registered, calls: broker.calls })
    const workFiles = readdirSync(work).filter(n => n !== ".git" && n !== "README.md")
    const exclude = existsSync(join(work, ".git", "info", "exclude")) ? readFileSync(join(work, ".git", "info", "exclude"), "utf8") : ""
    record(agent, "nothing written into the workdir", workFiles.length === 0 && exclude === "", { workFiles, exclude })
    record(agent, "plugin SessionStart hook ran (informational)", true, { fired: readdirSync(HOOK_MARKS).length - marksBefore })
    record(agent, "core context folder", existsSync(join(state, "context", id)), { files: existsSync(join(state, "context", id)) ? readdirSync(join(state, "context", id)) : [] })
  } catch (error) {
    record(agent, "launch", false, { error: (error as Error).message, degraded: events.filter(e => e.type === "context.degraded") })
  } finally {
    await handle.stop({ mode: "shutdown" }).catch(() => {})
    await host.close({ agents: "shutdown" }).catch(() => {})
    broker.server.close()
  }
}

console.log("run:", RUN)
for (const agent of wanted.length ? wanted : ALL) await check(agent)
writeFileSync(join(RUN, "results.json"), JSON.stringify(results, null, 2))
const failed = results.filter(r => !r.ok)
console.log(`\n${results.length - failed.length}/${results.length} passed  (${RUN})`)
process.exit(failed.length ? 1 : 0)
