import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { CodexOptions } from "../../../../packages/supermux-core/src/codex/index.js"
import { createCodexCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

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

test("codex ask mode uses untrusted/workspace-write/host", async () => {
  const captured: CodexOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "codex-host-"))
  dirs.push(dir)
  const host = createCodexCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: dir, cwd: dir, permissionMode: "ask",
  }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.approvalPolicy).toBe("untrusted")
  expect(captured[0]?.sandbox).toBe("workspace-write")
  expect(captured[0]?.permissionPrompts).toBe("host")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("codex default mode uses never/full-access with host-routed prompts", async () => {
  const captured: CodexOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "codex-host-"))
  dirs.push(dir)
  const host = createCodexCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id2", workdir: dir, cwd: dir,
  }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.approvalPolicy).toBe("never")
  expect(captured[0]?.sandbox).toBe("danger-full-access")
  expect(captured[0]?.permissionPrompts).toBe("host")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})
