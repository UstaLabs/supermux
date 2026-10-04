import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { mkdtempSync } from "node:fs"
import { readFile, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"
import { acp } from "../src/acp/index.js"
import { claude } from "../src/claude/index.js"
import { codex } from "../src/codex/index.js"
import type { AgentDriver, AuthProfile, DriverContext } from "../src/types.js"
import { TEST_ACP_PERMISSIONS, TEST_CLAUDE_PERMISSIONS, TEST_CODEX_PERMISSIONS } from "./helpers.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
afterEach(async () => { await Promise.all(dirs.splice(0).map(dir => rm(dir, { recursive: true, force: true }))) })
function scratch(): string { const dir = mkdtempSync(join(tmpdir(), "accounts-drivers-")); dirs.push(dir); return dir }
const keeper = () => ({ stateDirectory: scratch(), limits: { parkedDeadlineMs: 15_000, journalMaxBytes: 1_000_000, connectTimeoutMs: 4000 } })
const fixture = (name: string) => fileURLToPath(new URL(`./fixtures/${name}`, import.meta.url))
const ctx = (profile: AuthProfile): DriverContext => ({
  sessionId: "acct-1", cwd: process.cwd(), signal: new AbortController().signal, profile, account: "work",
  onUpdate() {}, onExit() {},
  requestPermission: async () => ({ outcome: { outcome: "cancelled" } }), requestAnswers: async () => ({ outcome: "cancelled" as const }),
})

const families: Array<[string, (env: Record<string, string>) => AgentDriver]> = [
  ["claude", env => claude({
    id: "claude", command: process.execPath, args: [fixture("claude-agent.mjs")], env, inheritEnv: true, tools: [], permissionPrompts: "none",
    permissions: TEST_CLAUDE_PERMISSIONS, partialMessages: false, setupTimeoutMs: 5000, requestTimeoutMs: 3000, shutdownTimeoutMs: 30,
    maxFrameBytes: 16 * 1024 * 1024, keeper: keeper(),
  })],
  ["codex", env => codex({
    id: "codex", command: process.execPath, args: [fixture("codex-agent.mjs")], env, inheritEnv: true, sandbox: "read-only", approvalPolicy: "never",
    permissionPrompts: "none", permissions: TEST_CODEX_PERMISSIONS, setupTimeoutMs: 5000, requestTimeoutMs: 3000, shutdownTimeoutMs: 500,
    maxFrameBytes: 16 * 1024 * 1024, keeper: keeper(),
  })],
  ["acp", env => acp({
    id: "fixture", command: process.execPath, args: [fixture("acp-agent.mjs")], env, inheritEnv: true, mcpServers: [], setupTimeoutMs: 3000,
    shutdownTimeoutMs: 40, maxFrameBytes: 16 * 1024 * 1024, maxOutstandingActivity: 256, cancelRetryIntervalMs: 250, cancelRetryTimeoutMs: 10_000,
    keeper: keeper(), captureStderr: false, permissions: TEST_ACP_PERMISSIONS,
  })],
]

for (const [name, make] of families) {
  test(`${name} driver: profile env merged, unsetEnv removed after the merge, args appended`, async () => {
    const trace = join(scratch(), "env.json")
    process.env.ACCOUNTS_INHERITED_SECRET = "inherited"
    try {
      const driver = make({ ENV_TRACE: trace, ACCOUNTS_DRIVER_SECRET: "driver", ACCOUNTS_KEEP: "keep" })
      const runtime = await driver.open(ctx({
        agent: name, env: { ACCOUNTS_PROFILE: "profile", ACCOUNTS_DROP_ME: "x" },
        unsetEnv: ["ACCOUNTS_INHERITED_SECRET", "ACCOUNTS_DRIVER_SECRET", "ACCOUNTS_DROP_ME"],
        args: ["--account-arg", "value"],
      }))
      try {
        const { argv, env } = JSON.parse(await readFile(trace, "utf8")) as { argv: string[]; env: Record<string, string> }
        expect(env.ACCOUNTS_PROFILE).toBe("profile")
        expect(env.ACCOUNTS_KEEP).toBe("keep")
        for (const key of ["ACCOUNTS_INHERITED_SECRET", "ACCOUNTS_DRIVER_SECRET", "ACCOUNTS_DROP_ME"]) expect(key in env).toBe(false)
        const at = argv.indexOf("--account-arg")
        expect(at).toBeGreaterThanOrEqual(0)
        expect(argv[at + 1]).toBe("value")
        if (name === "claude") expect(argv.slice(-2)).toEqual(["--account-arg", "value"])
      } finally { await runtime.close({ mode: "shutdown" }) }
    } finally { delete process.env.ACCOUNTS_INHERITED_SECRET }
  })
}
