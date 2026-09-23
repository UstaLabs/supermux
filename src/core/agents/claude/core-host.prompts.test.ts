import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import { createClaudeCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function fakeDriver(): AgentDriver {
  return {
    id: "claude",
    async open() {
      const runtime: AgentRuntime = {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: false, fork: false, detach: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

test("claude default mode is bypassPermissions", async () => {
  const captured: ClaudeOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "claude-host-"))
  dirs.push(dir)
  const host = createClaudeCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = { sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: dir, cwd: dir }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.permissionMode).toBe("bypassPermissions")
  expect(captured[0]?.permissionPrompts).toBe("host")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("claude ask mode leaves permissionMode undefined", async () => {
  const captured: ClaudeOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "claude-host-"))
  dirs.push(dir)
  const host = createClaudeCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = { sessionHome: dir, sessionName: "s", sessionId: "id2", workdir: dir, cwd: dir, permissionMode: "ask" }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.permissionMode).toBeUndefined()
  expect(captured[0]?.permissionPrompts).toBe("host")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})
