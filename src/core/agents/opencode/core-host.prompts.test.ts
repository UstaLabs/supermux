import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import { createOpenCodeCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function fakeDriver(): AgentDriver {
  return {
    id: "opencode",
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

test("opencode ask mode writes per-tool ask permissions", async () => {
  const dir = mkdtempSync(join(tmpdir(), "oc-host-"))
  dirs.push(dir)
  const host = createOpenCodeCoreHost({
    stateDirectory: dir,
    driverFactory: () => fakeDriver(),
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: dir, cwd: dir, permissionMode: "ask",
  }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: dir })
  const cfg = JSON.parse(readFileSync(join(dir, "config", "opencode", "opencode.json"), "utf8")) as { permission?: unknown }
  expect(cfg.permission).toEqual({ edit: "ask", bash: "ask", webfetch: "ask" })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})

test("opencode always writes the all-ask permission block (the live policy decides)", async () => {
  const dir = mkdtempSync(join(tmpdir(), "oc-host-"))
  dirs.push(dir)
  const host = createOpenCodeCoreHost({
    stateDirectory: dir,
    driverFactory: () => fakeDriver(),
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id2", workdir: dir, cwd: dir,
  }
  const handle = host.register({ id: "id2", env: {}, extra })
  await handle.start({ cwd: dir })
  const cfg = JSON.parse(readFileSync(join(dir, "config", "opencode", "opencode.json"), "utf8")) as { permission?: unknown }
  expect(cfg.permission).toEqual({ edit: "ask", bash: "ask", webfetch: "ask" })
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})
