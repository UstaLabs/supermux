// C3: Codex instructions. A session created by C3 gets them as thread/start developerInstructions
// and must have no <CODEX_HOME>/AGENTS.md (Codex re-reads that file on every launch, so a stale
// one in a reused, name-keyed home would add a second set). A session from before C3 (a native
// thread / core record without a snapshot) keeps the file, holding its creation snapshot.
import { afterAll, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "fs"
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

test("a pre-C3 session keeps AGENTS.md with its snapshot, fixed from its first C3 launch on", async () => {
  const home = join(root, "agents", "codex", "legacy")
  mkdirSync(home, { recursive: true })
  writeFileSync(join(home, "AGENTS.md"), "what the old broker regenerated on every launch")
  const work = mkdtempSync(join(root, "wd-"))
  const state = join(root, "state-legacy")
  const opens: Opened[] = []
  const extra = { sessionHome: home, sessionName: "legacy", sessionId: "old-1", workdir: work, cwd: work, nativeSessionId: "thread-old" }
  const first = host(state, opens)
  await first.register({ id: "old-1", env: {}, extra }).start({ cwd: work, nativeSessionId: "thread-old" })
  await first.close({ agents: "shutdown" })
  const snapshot = readFileSync(join(home, "AGENTS.md"), "utf8")
  expect(snapshot).toContain('"legacy"')
  expect(opens[0]!.resumeId).toBe("thread-old")
  // The broker's text changes later (here: another session name in the header); the file keeps the snapshot.
  const second = host(state, opens)
  await second.register({ id: "old-1", env: {}, extra: { ...extra, sessionName: "renamed" } }).start({ cwd: work })
  await second.close({ agents: "shutdown" })
  expect(readFileSync(join(home, "AGENTS.md"), "utf8")).toBe(snapshot)
  expect(opens[1]!.sessionContext!.instructions).toBe(snapshot)
})
