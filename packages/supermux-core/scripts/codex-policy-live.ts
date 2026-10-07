/**
 * C3 live check of the Codex policy fix, through `core.sessions.create` with the real `codex()`
 * driver (token mode, gpt-5.6-luna low). Scratch under ~/.cache/context-c3/.
 *
 *   bun scripts/codex-policy-live.ts
 *
 * The CODEX_HOME is pre-seeded with `sandbox_mode = "read-only"` / `approval_policy = "never"`,
 * exactly what the old driver of ANOTHER session on a shared account home would have written.
 * The session runs danger-full-access; its parent asks one spawn_agent child to write a file
 * outside the workspace. Passing means: the child's write lands (the child got the SESSION's
 * policy, not the shared file's), and CODEX_HOME/config.toml is byte-for-byte unchanged.
 */
import { existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { CoreEvent } from "../src/index.js"
import { codex } from "../src/codex/index.js"
import { codexTokenArgs, CODEX_TOKEN_ENV } from "../src/accounts/adapters/codex.js"

const HOME = homedir()
const RUN = join(HOME, ".cache", "context-c3", `codex-policy-live-${new Date().toISOString().replace(/[:.]/g, "-")}`)
const tokens = JSON.parse(readFileSync(join(HOME, ".codex", "auth.json"), "utf8")).tokens
const codexHome = join(RUN, "codex-home"), home = join(RUN, "home"), work = join(RUN, "work"), outside = join(RUN, "outside")
for (const d of [codexHome, home, work, outside]) mkdirSync(d, { recursive: true })
const SHARED = `sandbox_mode = "read-only"\napproval_policy = "never"\n`
writeFileSync(join(codexHome, "config.toml"), SHARED)

const env: Record<string, string> = {}
for (const [k, v] of Object.entries(process.env)) if (v !== undefined && !/^(MUX_|CLAUDECODE|CLAUDE_CODE_|CODEX_|OPENAI_)/.test(k)) env[k] = v
const core = createCore({
  stateDirectory: join(RUN, "state"),
  limits: { interruptTimeoutMs: 10_000, maxPending: 16, outstandingActivity: 256 },
  agents: [codex({
    id: "codex", command: "codex", args: ["app-server"], inheritEnv: false, env: { ...env, HOME: home, CODEX_HOME: codexHome },
    sandbox: "danger-full-access", approvalPolicy: "never", permissionPrompts: "host",
    permissions: { kind: "codex", approvalPolicy: "never", sandbox: "danger-full-access" },
    model: "gpt-5.6-luna", reasoningEffort: "low", setupTimeoutMs: 120_000, requestTimeoutMs: 120_000, shutdownTimeoutMs: 5_000, maxFrameBytes: 32 << 20,
    keeper: { stateDirectory: join(RUN, "keeper"), limits: { parkedDeadlineMs: 60_000, journalMaxBytes: 8_000_000, connectTimeoutMs: 15_000 } },
  })],
  profiles: { tok: { agent: "codex", env: { [CODEX_TOKEN_ENV]: tokens.access_token }, args: codexTokenArgs(tokens.account_id) } },
})
const events: CoreEvent[] = []
core.subscribe(e => { events.push(e) })
const target = join(outside, "child.txt")
let ok = false
try {
  const session = await core.sessions.create({ id: "policy-live", agent: "codex", cwd: work, authProfile: "tok" })
  const receipt = await session.send({ content: [{ type: "text", text: `Do NOT run any command yourself. Use your spawn_agent tool to start exactly one subagent with this task: "Run the shell command: echo child-wrote > ${target}  and report the exit status." Then wait for that subagent to finish and reply with one line: CHILD=<what it reported>.` }], whenBusy: "queue" })
  const done = await receipt.completed
  const childCommands = events.filter(e => e.type === "session.event" && e.event.subagentId && e.event.kind === "tool-call").map(e => (e as { event: { subagentId?: string; title?: string; phase?: string } }).event)
  const config = readFileSync(join(codexHome, "config.toml"), "utf8")
  ok = existsSync(target) && config === SHARED
  console.log(JSON.stringify({
    pass: ok, turn: done.status, error: (done as { error?: Error }).error?.message, childWriteLanded: existsSync(target), childFile: existsSync(target) ? readFileSync(target, "utf8").trim() : null,
    codexHomeConfigUnchanged: config === SHARED, childToolCalls: childCommands.map(c => ({ subagentId: c.subagentId, phase: c.phase, title: c.title })).slice(0, 6),
  }, null, 2))
} finally {
  await core.close({ agents: "shutdown" })
}
console.log("scratch:", RUN)
process.exit(ok ? 0 : 1)
