import { afterEach, beforeEach, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, readdirSync, rmSync, statSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { CodexOptions } from "../../../../packages/supermux-core/src/codex/index.js"
import { codexReadRules } from "../../../../packages/supermux-core/src/environment/codex.js"
import { inboxFilePath } from "../../../shared/paths"
import { createCodexCoreHost } from "./core-host"

// Hermetic credentials: the host's prepare step takes OPENAI_API_KEY first and only falls back
// to the user's own login files without one: files CI does not have, and that a developer
// machine must not have copied (or written back) by a test. A fake key per test, restored after.
const CREDENTIAL_KEYS = ["OPENAI_API_KEY"] as const
const savedKeys = new Map<string, string | undefined>()
beforeEach(() => {
  for (const k of CREDENTIAL_KEYS) { savedKeys.set(k, process.env[k]); process.env[k] = "test-key" }
})
afterEach(() => {
  for (const k of CREDENTIAL_KEYS) {
    const prev = savedKeys.get(k)
    if (prev === undefined) delete process.env[k]
    else process.env[k] = prev
  }
})

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

/** The session's own working directory, beside its state directory: the core refuses a cwd that is the state directory. */
function workdir(): string {
  const work = mkdtempSync(join(tmpdir(), "codex-work-"))
  dirs.push(work)
  return work
}

function fakeDriver(): AgentDriver {
  return {
    id: "codex",
    async open() {
      const runtime: AgentRuntime = {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: true, fork: true, detach: true, configure: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

test("codex ask mode uses untrusted/workspace-write", async () => {
  const captured: CodexOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "codex-host-"))
  dirs.push(dir)
  const work = workdir()
  const host = createCodexCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: work, cwd: work, permissionMode: "ask",
  }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: work })
  expect(captured[0]?.approvalPolicy).toBe("untrusted")
  expect(captured[0]?.sandbox).toBe("workspace-write")
  expect(captured[0]?.permissions).toEqual({ kind: "codex", approvalPolicy: "untrusted", sandbox: "workspace-write" })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("codex default mode uses never/full-access", async () => {
  const captured: CodexOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "codex-host-"))
  dirs.push(dir)
  const work = workdir()
  const host = createCodexCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id2", workdir: work, cwd: work,
  }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: work })
  expect(captured[0]?.approvalPolicy).toBe("never")
  expect(captured[0]?.sandbox).toBe("danger-full-access")
  expect(captured[0]?.permissions).toEqual({ kind: "codex", approvalPolicy: "never", sandbox: "danger-full-access" })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

/** Starts one Codex session with `id` in its own home and returns that home. */
async function startSession(id: string): Promise<string> {
  const home = mkdtempSync(join(tmpdir(), "codex-host-"))
  dirs.push(home)
  const work = workdir()
  const host = createCodexCoreHost({ stateDirectory: mkdtempSync(join(tmpdir(), "codex-state-")), driverFactory: () => fakeDriver() })
  const handle = host.register({ id, env: {}, extra: { sessionHome: home, sessionName: `s-${id}`, sessionId: id, workdir: work, cwd: work, permissionMode: "ask" } })
  await handle.start({ cwd: work })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
  return home
}

test("codex: the session's shell may read its own inbox file through exec-policy rules in its CODEX_HOME, and no other file", async () => {
  const home = await startSession("id-rules-1")
  const rules = join(home, "rules", "supermux-inbox-id-rules-1.rules")
  expect(readFileSync(rules, "utf8")).toBe(codexReadRules([inboxFilePath("id-rules-1")]))
  expect(statSync(rules).mode & 0o777).toBe(0o600)
  expect(readdirSync(join(home, "rules"))).toEqual(["supermux-inbox-id-rules-1.rules"])
  // Another session, another file name and another inbox file in its own home: nothing of the first leaks into it.
  const other = await startSession("id-rules-2")
  expect(readdirSync(join(other, "rules"))).toEqual(["supermux-inbox-id-rules-2.rules"])
  expect(readFileSync(join(other, "rules", "supermux-inbox-id-rules-2.rules"), "utf8")).toBe(codexReadRules([inboxFilePath("id-rules-2")]))
  expect(readFileSync(join(other, "rules", "supermux-inbox-id-rules-2.rules"), "utf8")).not.toContain("id-rules-1")
})
