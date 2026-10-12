// Codex instructions come only from the session context (thread/start developerInstructions).
// A <CODEX_HOME>/AGENTS.md left by the pre-C3 broker is removed on every launch: Codex re-reads
// that file, so it would add a second, stale set of instructions.
import { afterAll, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"

const root = mkdtempSync(join(tmpdir(), "codex-legacy-"))
process.env.HOME = join(root, "home")
process.env.MUX_HOME = join(root, "mux")
mkdirSync(join(root, "home", ".codex"), { recursive: true })
writeFileSync(join(root, "home", ".codex", "auth.json"), JSON.stringify({ tokens: { access_token: "x", account_id: "a" } }))
const { createCodexCoreHost } = await import("./core-host")
type Opened = { sessionContext?: { instructions?: string; launch: string } ; resumeId?: string }
afterAll(() => rmSync(root, { recursive: true, force: true }))

function host(state: string, opens: Opened[]) {
  return createCodexCoreHost({
    stateDirectory: state,
    driverFactory: () => ({
      id: "codex",
      async open(ctx) {
        opens.push({ sessionContext: ctx.sessionContext as never, resumeId: ctx.resumeId })
        return { agentSessionId: ctx.resumeId ?? "thread-new", capabilities: { resume: true, steer: true, fork: true, detach: true }, async prompt() { return { stopReason: "end_turn" as const } }, async interrupt() {}, async close() {} }
      },
    }),
  })
}

test("a new session: developerInstructions, and a stale AGENTS.md in its reused home is removed", async () => {
  const home = join(root, "agents", "codex", "reused")
  mkdirSync(home, { recursive: true })
  writeFileSync(join(home, "AGENTS.md"), "stale instructions of an earlier session named 'reused'")
  const work = mkdtempSync(join(root, "wd-"))
  const opens: Opened[] = []
  const h = host(join(root, "state-new"), opens)
  const extra = { sessionHome: home, sessionName: "reused", sessionId: "new-1", workdir: work, cwd: work }
  await h.register({ id: "new-1", env: {}, extra }).start({ cwd: work })
  await h.close({ agents: "shutdown" })
  expect(opens[0]!.sessionContext!.launch).toBe("create")
  expect(opens[0]!.sessionContext!.instructions).toContain('"reused"')
  expect(existsSync(join(home, "AGENTS.md"))).toBe(false)
})

test("a pre-C3 session's AGENTS.md and marker are removed too", async () => {
  const home = join(root, "agents", "codex", "legacy")
  mkdirSync(home, { recursive: true })
  writeFileSync(join(home, "AGENTS.md"), "what the old broker regenerated on every launch")
  writeFileSync(join(home, ".supermux-agents-md-session"), "old-1")
  const work = mkdtempSync(join(root, "wd-"))
  const opens: Opened[] = []
  const h = host(join(root, "state-legacy"), opens)
  const extra = { sessionHome: home, sessionName: "legacy", sessionId: "old-1", workdir: work, cwd: work, nativeSessionId: "thread-old" }
  await h.register({ id: "old-1", env: {}, extra }).start({ cwd: work, nativeSessionId: "thread-old" })
  await h.close({ agents: "shutdown" })
  expect(opens[0]!.resumeId).toBe("thread-old")
  expect(existsSync(join(home, "AGENTS.md"))).toBe(false)
  expect(existsSync(join(home, ".supermux-agents-md-session"))).toBe(false)
})
