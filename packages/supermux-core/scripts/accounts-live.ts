/**
 * Live check for accounts (A1): a session switches account and keeps its history.
 *
 *   SUPERMUX_TEST_CLAUDE_TOKEN_FILE=<setup-token file> bun scripts/accounts-live.ts [claude] [codex]
 *
 * Scratch lives under ~/.cache/accounts-live/<run>/ (history roots, account homes, core state).
 * Claude: two subscription-style homes sharing projects/ with a scratch root, both authenticated
 * by the setup-token (injected here; the token is never written to disk or printed).
 * Codex: two token-mode accounts built READ-ONLY from ~/.codex/auth.json (access token + account
 * id only, never the refresh token), each with its own CODEX_HOME sharing sessions/ with a
 * scratch root; checks app-server thread/resume across the two homes.
 * Never touches ~/.claude, ~/.claude.json or ~/.codex (except reading auth.json).
 */
import { mkdirSync, readdirSync, readFileSync, existsSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, CoreEvent, DriverContext, Session } from "../src/index.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import { codexLayout, ensureHome } from "../src/accounts/index.js"

const LIMITS = { interruptTimeoutMs: 10_000, maxPending: 16, outstandingActivity: 256 }
const KEEPER_LIMITS = { parkedDeadlineMs: 60_000, journalMaxBytes: 4_000_000, connectTimeoutMs: 10_000 }
const run = new Date().toISOString().replace(/[:.]/g, "-")
const root = join(homedir(), ".cache", "accounts-live", run)
const wanted = process.argv.slice(2)
const results: Record<string, unknown> = { root }

async function ask(session: Session, events: CoreEvent[], text: string): Promise<string> {
  const start = events.length
  const receipt = await session.send({ content: [{ type: "text", text }], whenBusy: "reject" })
  const completion = await receipt.completed
  if (completion.status !== "completed") throw new Error(`turn ${completion.status}: ${completion.status === "failed" ? completion.error.message : ""}`)
  await new Promise(resolve => setTimeout(resolve, 100))
  return events.slice(start).flatMap(e => e.type === "session.event" && e.sessionId === session.id && e.event.kind === "assistant-message" && !(e.event as { subagentId?: string }).subagentId ? [e.event.text] : []).join("\n").trim()
}

function wordCheck(word: string, reply: string) { return reply.toUpperCase().includes(word) }

async function claudeCheck(): Promise<unknown> {
  const tokenFile = process.env.SUPERMUX_TEST_CLAUDE_TOKEN_FILE
  if (!tokenFile) return { skipped: "SUPERMUX_TEST_CLAUDE_TOKEN_FILE unset" }
  const token = readFileSync(tokenFile, "utf8").trim()
  const base = join(root, "claude")
  const claudeRoot = join(base, "history-root")
  const work = join(base, "work")
  mkdirSync(work, { recursive: true })
  const real = claude({
    id: "claude", command: "claude", args: [], inheritEnv: true, tools: [], permissionPrompts: "none",
    permissions: { kind: "claude", permissionMode: "dontAsk" }, partialMessages: false, model: "haiku",
    setupTimeoutMs: 90_000, requestTimeoutMs: 60_000, shutdownTimeoutMs: 3_000, maxFrameBytes: 16 << 20,
    keeper: { stateDirectory: join(base, "keeper"), limits: KEEPER_LIMITS },
  })
  const opened: Array<{ account?: string; configDir?: string; resume?: string }> = []
  // Test-only: these subscription homes are authenticated by the setup-token instead of a login.
  const driver: AgentDriver = {
    id: "claude",
    open(context: DriverContext) {
      const profile = context.profile!
      opened.push({ account: context.account, configDir: profile.env?.CLAUDE_CONFIG_DIR, resume: context.resumeId })
      return real.open({ ...context, profile: { ...profile, env: { ...profile.env, CLAUDE_CODE_OAUTH_TOKEN: token }, unsetEnv: (profile.unsetEnv ?? []).filter(key => key !== "CLAUDE_CODE_OAUTH_TOKEN") } })
    },
  }
  const core = createCore({ stateDirectory: join(base, "state"), agents: [driver], limits: LIMITS, accounts: { homes: { claudeRoot } } })
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  try {
    await core.accounts.add({ id: "claude-a", agent: "claude", method: "subscription", label: "A" })
    await core.accounts.add({ id: "claude-b", agent: "claude", method: "subscription", label: "B" })
    const word = `ZEBRA${Math.floor(Math.random() * 9000 + 1000)}`
    const session = await core.sessions.create({ id: "live-claude", agent: "claude", cwd: work, account: "claude-a" })
    const first = await ask(session, events, `Remember this secret word: ${word}. Reply with just OK. Do not use tools.`)
    const switched = await core.sessions.resume("live-claude", { account: "claude-b" })
    const second = await ask(switched, events, "What was the secret word I gave you? Reply with the word only. Do not use tools.")
    const record = await core.sessions.get("live-claude")
    const homeA = join(base, "state", "accounts", "homes", "claude", "claude-a")
    return {
      ok: wordCheck(word, second) && record?.account === "claude-b" && opened.length === 2 && opened[1]!.resume === record?.agentSessionId,
      firstReply: first, secondReply: second, opened,
      switched: events.filter(e => e.type === "account.switched"),
      record: record && { account: record.account, agentSessionId: record.agentSessionId },
      sharedProjects: readdirSync(join(claudeRoot, "projects")),
      homeA: readdirSync(homeA),
      credentialsFileWritten: existsSync(join(homeA, ".credentials.json")) || existsSync(join(base, "state", "accounts", "homes", "claude", "claude-b", ".credentials.json")),
    }
  } finally { await core.close({ agents: "shutdown" }) }
}

async function codexCheck(): Promise<unknown> {
  const auth = JSON.parse(readFileSync(join(process.env.CODEX_HOME ?? join(homedir(), ".codex"), "auth.json"), "utf8"))
  const tokens = auth?.tokens
  if (!tokens?.access_token || !tokens?.account_id) return { skipped: "no ChatGPT tokens in ~/.codex/auth.json" }
  // Access token + account id only: no refresh token ever leaves the real home.
  const secret = JSON.stringify({ access_token: tokens.access_token, account_id: tokens.account_id })
  const base = join(root, "codex")
  const codexRoot = join(base, "history-root")
  const work = join(base, "work")
  const stateDirectory = join(base, "state")
  mkdirSync(work, { recursive: true })
  const real = codex({
    id: "codex", command: "codex", args: ["app-server"], inheritEnv: true, sandbox: "read-only", approvalPolicy: "never",
    permissionPrompts: "host", permissions: { kind: "codex", approvalPolicy: "never", sandbox: "read-only" },
    model: "gpt-5.6-luna", reasoningEffort: "low",
    setupTimeoutMs: 90_000, requestTimeoutMs: 60_000, shutdownTimeoutMs: 3_000, maxFrameBytes: 16 << 20,
    keeper: { stateDirectory: join(base, "keeper"), limits: KEEPER_LIMITS },
  })
  const opened: Array<{ account?: string; codexHome: string; resume?: string; args: number }> = []
  // Test-only: token accounts carry no home; give each one its own CODEX_HOME sharing sessions/.
  const driver: AgentDriver = {
    id: "codex",
    async open(context: DriverContext) {
      const profile = context.profile!
      const codexHome = await ensureHome(join(stateDirectory, "accounts", "homes"), { id: context.account!, agent: "codex" }, codexLayout({ codexRoot }))
      opened.push({ account: context.account, codexHome, resume: context.resumeId, args: profile.args?.length ?? 0 })
      return real.open({ ...context, profile: { ...profile, env: { ...profile.env, CODEX_HOME: codexHome } } })
    },
  }
  const core = createCore({ stateDirectory, agents: [driver], limits: LIMITS, accounts: { homes: { codexRoot } } })
  const events: CoreEvent[] = []
  core.subscribe(e => { events.push(e) })
  try {
    await core.accounts.add({ id: "codex-a", agent: "codex", method: "token", secret, label: "A" })
    await core.accounts.add({ id: "codex-b", agent: "codex", method: "token", secret, label: "B" })
    const word = `OTTER${Math.floor(Math.random() * 9000 + 1000)}`
    const session = await core.sessions.create({ id: "live-codex", agent: "codex", cwd: work, account: "codex-a", configuration: { model: "gpt-5.6-luna", reasoningEffort: "low" } })
    const first = await ask(session, events, `Remember this secret word: ${word}. Reply with just OK. Do not run any commands.`)
    let second: string | undefined
    let resumeError: string | undefined
    try {
      const switched = await core.sessions.resume("live-codex", { account: "codex-b" })
      second = await ask(switched, events, "What was the secret word I gave you? Reply with the word only. Do not run any commands.")
    } catch (error) { resumeError = (error as Error).message }
    const record = await core.sessions.get("live-codex")
    const list = (dir: string) => existsSync(dir) ? readdirSync(dir).sort() : []
    return {
      ok: !!second && wordCheck(word, second) && record?.account === "codex-b",
      firstReply: first, secondReply: second, resumeError, opened,
      switched: events.filter(e => e.type === "account.switched"),
      usage: { a: core.accounts.usage("codex-a"), b: core.accounts.usage("codex-b") },
      record: record && { account: record.account, agentSessionId: record.agentSessionId },
      homeA: list(opened[0]?.codexHome ?? ""), homeB: list(opened[1]?.codexHome ?? ""), sharedRoot: list(codexRoot),
    }
  } finally { await core.close({ agents: "shutdown" }) }
}

mkdirSync(root, { recursive: true })
for (const [name, check] of [["claude", claudeCheck], ["codex", codexCheck]] as const) {
  if (wanted.length && !wanted.includes(name)) continue
  try { results[name] = await check() } catch (error) { results[name] = { ok: false, error: (error as Error).stack ?? String(error) } }
}
console.log(JSON.stringify(results, (key, value) => key === "secret" ? "[redacted]" : value, 2))
