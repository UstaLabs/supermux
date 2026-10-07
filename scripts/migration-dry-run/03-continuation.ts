/**
 * Migration dry run, step 3: do LIVE conversations continue through this branch's resume path?
 * Run inside scripts/migration-dry-run/sandbox.sh (live state read-only), from the worktree:
 *
 *   scripts/migration-dry-run/sandbox.sh <dir> bun scripts/migration-dry-run/03-continuation.ts <dir> [claude] [grok] [opencode] [codex]
 *
 * Per agent: one real row of the live DB (step-1 copy), its conversation COPIED under a NEW id
 * into a scratch HOME (the original transcript is never opened for writing; the sandbox makes
 * that impossible anyway), a scratch broker DB holding that row (agent_session_id = the copy's
 * id, workdir / agent_home = scratch), and the REAL broker path: SessionManager.resumeSuspended
 * for the suspended tmux-era Claude row (lazy resume, tmux window retired through a recording
 * fake backend), resumeAtBoot for the active grok / opencode / codex rows. Production core-hosts
 * (getXCoreHost providers over the scratch MUX_STATE_DIR), real CLIs, cheap models.
 * Then two turns: a question only that conversation's history can answer, and "what is your
 * session name per your instructions" (the row is renamed to a fresh token, which only the
 * instructions this branch generates can carry).
 *
 * Credentials: read from the real homes, written to scratch WITHOUT refresh tokens (Claude: an
 * env access token; Grok / Codex: refresh_token removed), so nothing here can rotate a login the
 * live sessions use. OpenCode: API keys only.
 *
 * Inputs prepared beforehand (see the runbook in the report): <dir>/db.sqlite3 (step 1) and, for
 * opencode, <dir>/oc-export/session.json (`opencode export` run against a scratch COPY of
 * ~/.local/share/opencode/opencode.db).
 */
import { spawnSync } from "node:child_process"
import { createHash, randomUUID } from "node:crypto"
import { chmodSync, copyFileSync, cpSync, existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { dirname, join, resolve } from "node:path"

const REAL_HOME = homedir()
const dir = resolve(process.argv[2] ?? join(REAL_HOME, ".cache", "migration-dry"))
if (dir.startsWith(join(REAL_HOME, ".mux"))) throw new Error("refusing to work inside ~/.mux")
type Agent = "claude" | "grok" | "opencode" | "codex"
const ALL: Agent[] = ["claude", "grok", "opencode", "codex"]
const wanted = process.argv.slice(3).filter((a): a is Agent => (ALL as string[]).includes(a))
const agentsToRun = wanted.length ? wanted : ALL
const stamp = new Date().toISOString().replace(/[:.]/g, "-")
const RUN = join(dir, `live-${stamp}`)
const HOME = join(RUN, "home"), MUX = join(RUN, "mux")
for (const d of [HOME, MUX, join(MUX, "state")]) mkdirSync(d, { recursive: true })
const NOUNS = ["MAPLE", "OTTER", "QUARTZ", "FALCON", "CEDAR", "BISON", "COBALT", "HERON", "TUNDRA", "LYNX"]
const token = (agent: string) => `migdry-${agent}-${NOUNS[Math.floor(Math.random() * NOUNS.length)]}${Math.floor(Math.random() * 9000 + 1000)}`
const sha = (p: string) => createHash("sha256").update(readFileSync(p)).digest("hex").slice(0, 16)
const fileState = (p: string) => ({ path: p, sha: sha(p), mtime: statSync(p).mtimeMs, size: statSync(p).size })

// ------------------------------------------------------------------ credentials (scratch, no refresh tokens)
const claudeAccess: string = JSON.parse(readFileSync(join(REAL_HOME, ".claude", ".credentials.json"), "utf8")).claudeAiOauth.accessToken
{
  const grok = JSON.parse(readFileSync(join(REAL_HOME, ".grok", "auth.json"), "utf8")) as Record<string, Record<string, unknown>>
  for (const v of Object.values(grok)) delete v.refresh_token
  mkdirSync(join(HOME, ".grok"), { recursive: true, mode: 0o700 })
  writeFileSync(join(HOME, ".grok", "auth.json"), JSON.stringify(grok, null, 2), { mode: 0o600 })
  const codex = JSON.parse(readFileSync(join(REAL_HOME, ".codex", "auth.json"), "utf8"))
  codex.tokens.refresh_token = ""
  codex.last_refresh = new Date().toISOString()
  mkdirSync(join(HOME, ".codex"), { recursive: true, mode: 0o700 })
  writeFileSync(join(HOME, ".codex", "auth.json"), JSON.stringify(codex, null, 2), { mode: 0o600 })
  mkdirSync(join(HOME, ".local", "share", "opencode"), { recursive: true, mode: 0o700 })
  // OpenCode 1.18 keeps the active provider keys in account.json (auth.json is the older file): both are API keys.
  for (const f of ["auth.json", "account.json"]) {
    const from = join(REAL_HOME, ".local", "share", "opencode", f)
    if (existsSync(from)) { copyFileSync(from, join(HOME, ".local", "share", "opencode", f)); chmodSync(join(HOME, ".local", "share", "opencode", f), 0o600) }
  }
}

process.env.HOME = HOME
process.env.MUX_HOME = MUX
process.env.MUX_STATE_DIR = join(MUX, "state")
process.env.XDG_CONFIG_HOME = join(HOME, ".config")
process.env.XDG_DATA_HOME = join(HOME, ".local", "share")
process.env.XDG_STATE_HOME = join(HOME, ".local", "state")
process.env.XDG_CACHE_HOME = join(HOME, ".cache")
process.env.TMPDIR = join(RUN, "tmp"); mkdirSync(process.env.TMPDIR, { recursive: true })
for (const k of Object.keys(process.env)) if (/^(MUX_SESSION|MUX_DISPLAY|MUX_AGENT|MUX_SOCKETS|MUX_SHIM|CLAUDECODE|CLAUDE_CODE_|CODEX_|OPENAI_|CURSOR_|GROK_|OPENCODE|SUPERMUX_MCP)/.test(k)) delete process.env[k]
process.env.CLAUDE_CODE_OAUTH_TOKEN = claudeAccess

const { Database } = await import("bun:sqlite")
const { openDb, runMigrations } = await import("../../src/core/storage/db")
const { MIGRATIONS } = await import("../../src/core/storage/migrations")
const { Registry } = await import("../../src/core/session-manager/registry")
const { SessionManager } = await import("../../src/core/session-manager/manager")
const { fakePorts } = await import("../../tests/helpers/session-manager-ports")
const { setMuxShimMode } = await import("../../src/core/mux-tools/mode")
const { bindMuxTools } = await import("../../src/core/mux-tools/server")
const { SOCKETS_DIR } = await import("../../src/shared/paths")
const { getClaudeCoreHost, closeClaudeCoreHost } = await import("../../src/core/agents/claude/core-host-provider")
const { getGrokCoreHost, closeGrokCoreHost } = await import("../../src/core/agents/grok/core-host-provider") as any
const { getOpenCodeCoreHost, closeOpenCodeCoreHost } = await import("../../src/core/agents/opencode/core-host-provider") as any
const { getCodexCoreHost, closeCodexCoreHost } = await import("../../src/core/agents/codex/core-host-provider") as any
type CoreEvent = import("../../packages/supermux-core/src/index.js").CoreEvent
type Session = import("../../packages/supermux-core/src/index.js").Session
mkdirSync(SOCKETS_DIR, { recursive: true })
// The broker's tools as a host MCP server bound to this scratch manager (no socket server here).
setMuxShimMode("host")

// ------------------------------------------------------------------ the live rows (step-1 copy, read-only)
const live = new Database(join(dir, "db.sqlite3"), { readonly: true })
const liveRow = (idPrefix: string) => live.prepare("SELECT * FROM sessions WHERE id LIKE ? || '%'").get(idPrefix) as Record<string, any>
const PICK: Record<Agent, string> = { claude: "fe381ce5", grok: "6bcd60a9", opencode: "58bd13c2", codex: "efb9f16e" }

// ------------------------------------------------------------------ scratch broker
const db = openDb(join(RUN, "db.sqlite3"))
runMigrations(db, MIGRATIONS)
const ports = fakePorts(db)
const killed: string[] = []
ports.backend = { runtimeTargetIdOf: async (s) => s.tmux_window_id ?? null, kill: async (id) => { killed.push(id) } }
ports.resume.sessionEffort = (s) => s.reasoningLevel || undefined
const registry = new Registry(db)
const manager = new SessionManager(registry, ports)
bindMuxTools({ outbound: (s, op) => manager.outbound(s, op), orchestration: (s, op) => manager.orchestration(s, op) })

type Result = { agent: Agent; ok: boolean; [k: string]: unknown }
const results: Result[] = []
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

async function ask(session: Session, events: CoreEvent[], text: string, timeoutMs = 300_000): Promise<string> {
  const start = events.length
  const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "reject" })
  const completion = await Promise.race([receipt.completed, new Promise<never>((_, reject) => setTimeout(() => reject(new Error("turn timed out")), timeoutMs))])
  if (completion.status !== "completed") throw new Error(`turn ${completion.status}: ${completion.status === "failed" ? completion.error.message : ""}`)
  await sleep(300)
  return events.slice(start).flatMap((e) => e.type === "session.event" && e.sessionId === session.id && e.event.kind === "assistant-message" && !(e.event as { subagentId?: string }).subagentId ? [e.event.text] : []).join("\n").trim()
}

const NO_TOOLS = "Answer from this conversation's own history only. Do not run commands, do not read files, do not call any tool."
const QUESTION: Record<Agent, { q: string; expect: RegExp[] }> = {
  claude: {
    q: `Earlier in this conversation I asked you to add something to the hackathon page. What exactly was the label of the button you added, which file did you edit, and how long was the countdown? ${NO_TOOLS}`,
    expect: [/SUNUM BA[SŞ]LAT/i, /hackathon-slides\/index\.html/, /3(:00| ?min)/i],
  },
  grok: {
    q: `Earlier in this conversation you showed me what the files \`test\` and \`test.md\` contained, and then removed one of them. What did each file contain, and which one did you remove? ${NO_TOOLS}`,
    expect: [/hiii/i, /hello/i, /remov\w*[^.]*`?test`?(?!\.md)/i],
  },
  opencode: {
    q: `Earlier in this conversation you made a git commit. What was its short hash, which files did it include, and what did you have to find out before you could commit? ${NO_TOOLS}`,
    expect: [/8354634/, /haha/, /test\.md/, /(identity|email|user\.?name|author)/i],
  },
  codex: {
    q: `Earlier in this conversation you told me where Jenkins is. What host IP and port did you give, and what username? Do not repeat any password. ${NO_TOOLS}`,
    expect: [/10\.16\.2\.138/, /8080/, /admin/],
  },
}
const NAME_Q = `According to your system instructions / rules for this session (not this conversation's earlier messages), what is the name of this supermux session? Reply with only the name, or NONE if your instructions do not state one. ${NO_TOOLS}`

function initGit(work: string) {
  mkdirSync(work, { recursive: true })
  spawnSync("git", ["init", "-q", work])
}

// ------------------------------------------------------------------ per agent: copy the conversation under a new id
function prepareClaude(row: Record<string, any>, work: string) {
  const slug = (p: string) => p.replace(/[^A-Za-z0-9]/g, "-")
  const src = join(REAL_HOME, ".claude", "projects", slug(row.workdir), `${row.agent_session_id}.jsonl`)
  const newId = randomUUID()
  const dstDir = join(HOME, ".claude", "projects", slug(work))
  mkdirSync(dstDir, { recursive: true })
  const text = readFileSync(src, "utf8").split(row.agent_session_id).join(newId).split(row.workdir).join(work)
  writeFileSync(join(dstDir, `${newId}.jsonl`), text)
  return { original: fileState(src), copyId: newId, home: undefined as string | undefined }
}

function prepareGrok(row: Record<string, any>, work: string, name: string) {
  const enc = (p: string) => encodeURIComponent(p)
  const srcDir = join(row.agent_home, ".grok", "sessions", enc(row.workdir), row.agent_session_id)
  const newId = randomUUID().replace(/^.{8}/, row.agent_session_id.slice(0, 8)) // grok ids are uuid v7: keep the time prefix
  const home = join(RUN, "agents", "grok", name)
  const dstDir = join(home, ".grok", "sessions", enc(work), newId)
  mkdirSync(dstDir, { recursive: true })
  const originals = readdirSync(srcDir).filter((f) => statSync(join(srcDir, f)).isFile()).map((f) => fileState(join(srcDir, f)))
  for (const f of readdirSync(srcDir)) {
    const from = join(srcDir, f)
    if (!statSync(from).isFile()) { cpSync(from, join(dstDir, f), { recursive: true }); continue }
    const t = readFileSync(from, "utf8")
      .split(row.agent_session_id).join(newId)
      .split(join(row.agent_home, ".grok")).join(join(home, ".grok"))
      .split(row.workdir).join(work)
    writeFileSync(join(dstDir, f), t)
  }
  return { original: originals, copyId: newId, home }
}

function prepareOpenCode(row: Record<string, any>, work: string, name: string) {
  const exported = readFileSync(join(dir, "oc-export", "session.json"), "utf8")
  const oldId = row.agent_session_id as string
  const newId = oldId.slice(0, 16) + Array.from({ length: oldId.length - 16 }, () => "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"[Math.floor(Math.random() * 56)]).join("")
  const json = exported.split(oldId).join(newId).split(row.workdir).join(work)
  const file = join(RUN, "opencode-session.json")
  writeFileSync(file, json)
  const r = spawnSync("opencode", ["import", file], { env: process.env, encoding: "utf8", cwd: work, timeout: 120_000 })
  if (r.status !== 0) throw new Error(`opencode import failed: ${r.stderr || r.stdout}`)
  const home = join(RUN, "agents", "opencode", name)
  mkdirSync(home, { recursive: true })
  return { original: { exportedFrom: "scratch copy of ~/.local/share/opencode/opencode.db", import: r.stdout.trim().slice(-200) }, copyId: newId, home }
}

function prepareCodex(row: Record<string, any>, work: string, name: string) {
  const sessions = join(row.agent_home, "sessions")
  const find = (d: string): string | undefined => {
    for (const e of readdirSync(d, { withFileTypes: true })) {
      const p = join(d, e.name)
      if (e.isDirectory()) { const f = find(p); if (f) return f }
      else if (e.name.includes(row.agent_session_id)) return p
    }
  }
  const src = find(sessions)
  if (!src) throw new Error("codex rollout not found")
  const v7 = randomUUID().split("-")
  const old = (row.agent_session_id as string).split("-")
  const newId = [old[0], old[1], "7" + v7[2]!.slice(1), v7[3], v7[4]].join("-") // same time prefix, new random part
  const home = join(RUN, "agents", "codex", name)
  const rel = src.slice(sessions.length + 1).split(row.agent_session_id).join(newId)
  mkdirSync(join(home, "sessions", dirname(rel)), { recursive: true })
  writeFileSync(join(home, "sessions", rel), readFileSync(src, "utf8").split(row.agent_session_id).join(newId).split(row.workdir).join(work))
  return { original: fileState(src), copyId: newId, home }
}

// ------------------------------------------------------------------ run
const hostOf = (agent: Agent) => agent === "claude" ? getClaudeCoreHost() : agent === "grok" ? getGrokCoreHost() : agent === "opencode" ? getOpenCodeCoreHost() : getCodexCoreHost()
const MODEL: Record<Agent, { model?: string; reasoning_level?: string }> = {
  claude: { model: "haiku" },
  grok: { reasoning_level: "low" },
  // The row's own opencode-go model answers "An active OpenCode Go subscription is required" (the live
  // key too, not a migration issue): a free Zen model for the check.
  opencode: { model: process.env.MIGDRY_OPENCODE_MODEL ?? "opencode/fledge-alpha-free" },
  codex: { model: "gpt-5.6-luna", reasoning_level: "low" },
}

for (const agent of agentsToRun) {
  const row = liveRow(PICK[agent])
  const work = join(RUN, `work-${agent}`)
  initGit(work)
  const name = token(agent)
  const r: Result = { agent, ok: false, liveRow: { id: row.id, name: row.name, status: row.status, agent_session_id: row.agent_session_id, tmux_window_id: row.tmux_window_id, model: row.model }, newName: name }
  results.push(r)
  try {
    const prep = agent === "claude" ? prepareClaude(row, work) : agent === "grok" ? prepareGrok(row, work, name) : agent === "opencode" ? prepareOpenCode(row, work, name) : prepareCodex(row, work, name)
    r.original = prep.original
    r.copyId = prep.copyId
    const id = randomUUID()
    registry.register({
      id, name, workdir: work, pid: 0, agent: agent as never, agent_session_id: prep.copyId,
      ...(prep.home ? { agent_home: prep.home } : {}),
      ...(agent === "claude" ? { tmux_window_id: row.tmux_window_id, core: false } : {}),
      model: MODEL[agent].model ?? row.model ?? undefined,
      reasoningLevel: MODEL[agent].reasoning_level ?? row.reasoning_level ?? undefined,
    } as never)
    if (row.status === "suspended") registry.sessions.suspend(id)
    const host = hostOf(agent)
    const events: CoreEvent[] = []
    host.core.subscribe((e: CoreEvent) => { events.push(e) })
    const t0 = Date.now()
    if (agent === "claude") {
      r.path = "SessionManager.resumeSuspended (lazy resume of a suspended tmux-era row)"
      r.resumed = await manager.resumeSuspended(registry.get(id) as never)
    } else {
      r.path = "SessionManager.resumeAtBoot (active row)"
      await manager.resumeAtBoot()
      r.resumed = !!manager.adapterFor(id)
    }
    r.resumeMs = Date.now() - t0
    const rowAfter = registry.get(id)!
    r.rowAfter = { status: rowAfter.status, core: rowAfter.core, agent_home: rowAfter.agent_home, agent_session_id: rowAfter.agent_session_id }
    if (agent === "claude") r.tmuxWindowsRetired = [...killed]
    const record = await host.core.sessions.get(id)
    r.coreRecord = { agentSessionId: (record as any)?.agentSessionId, hasCreatedInstructions: (record as any)?.createdInstructions !== undefined }
    const adapter = manager.adapterFor(id) as any
    const session: Session | undefined = adapter?.session
    if (!session) throw new Error("no open core session after resume")
    const history = await ask(session, events, QUESTION[agent].q)
    r.historyAnswer = history
    r.historyCarried = QUESTION[agent].expect.every((re) => re.test(history))
    const nameAnswer = await ask(session, events, NAME_Q)
    r.nameAnswer = nameAnswer
    r.newInstructionsArrived = nameAnswer.includes(name)
    r.nativeIdAfter = (await host.core.sessions.get(id) as any)?.agentSessionId
    r.ok = r.historyCarried === true
  } catch (err) {
    r.error = String((err as Error)?.stack ?? err).slice(0, 1500)
  }
  console.log(JSON.stringify(r, null, 2))
}

// Originals unchanged?
for (const r of results) {
  const o = r.original as any
  const list = Array.isArray(o) ? o : o?.path ? [o] : []
  r.originalUnchanged = list.every((f: any) => existsSync(f.path) && sha(f.path) === f.sha && statSync(f.path).mtimeMs === f.mtime)
}
writeFileSync(join(RUN, "report.json"), JSON.stringify({ run: RUN, results }, null, 2))
console.log("SUMMARY", JSON.stringify(results.map((r) => ({ agent: r.agent, ok: r.ok, historyCarried: r.historyCarried, newInstructionsArrived: r.newInstructionsArrived, originalUnchanged: r.originalUnchanged, error: r.error ? String(r.error).split("\n")[0] : undefined }))))
await Promise.allSettled([closeClaudeCoreHost(), closeGrokCoreHost?.(), closeOpenCodeCoreHost?.(), closeCodexCoreHost?.()].map((p) => Promise.resolve(p)))
process.exit(0)
