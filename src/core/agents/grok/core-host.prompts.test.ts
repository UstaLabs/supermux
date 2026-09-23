import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { GrokOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import { createGrokCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function fakeDriver(): AgentDriver {
  return {
    id: "grok",
    async open() {
      const runtime: AgentRuntime = {
        agentSessionId: "n1",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
}

test("grok ask mode sets alwaysApprove false", async () => {
  const captured: GrokOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "grok-host-"))
  dirs.push(dir)
  const host = createGrokCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir,
    sessionName: "s",
    sessionId: "id1",
    workdir: dir,
    cwd: dir,
    permissionMode: "ask",
  }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.alwaysApprove).toBe(false)
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("grok default mode keeps alwaysApprove true", async () => {
  const captured: GrokOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "grok-host-"))
  dirs.push(dir)
  const host = createGrokCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      return fakeDriver()
    },
  })
  const extra = {
    sessionHome: dir,
    sessionName: "s",
    sessionId: "id2",
    workdir: dir,
    cwd: dir,
  }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: dir })
  expect(captured[0]?.alwaysApprove).toBe(true)
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})
