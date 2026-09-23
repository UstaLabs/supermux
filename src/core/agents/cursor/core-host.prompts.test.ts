import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { CursorOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import { createCursorCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function fakeDriver(): AgentDriver {
  return {
    id: "cursor",
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

test("cursor default mode is force/agent", async () => {
  const captured: CursorOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "cur-host-"))
  dirs.push(dir)
  const host = createCursorCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
    smoke: async () => {},
    sharedRuntime: null,
  })
  const extra = { sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: dir, cwd: dir }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.permissions).toBe("force")
  expect(captured[0]?.mode).toBe("agent")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("cursor ask mode is ask/agent", async () => {
  const captured: CursorOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "cur-host-"))
  dirs.push(dir)
  const host = createCursorCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
    smoke: async () => {},
    sharedRuntime: null,
  })
  const extra = { sessionHome: dir, sessionName: "s", sessionId: "id2", workdir: dir, cwd: dir, permissionMode: "ask" }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.permissions).toBe("ask")
  expect(captured[0]?.mode).toBe("agent")
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})
