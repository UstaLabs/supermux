import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import type { AgentDriver, AgentRuntime } from "../../../../packages/supermux-core/src/index.js"
import type { OpenCodeOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import { createOpenCodeCoreHost } from "./core-host"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

test("opencode has no permission-mode option; prompts means whatever the agent asks", async () => {
  const captured: OpenCodeOptions[] = []
  const dir = mkdtempSync(join(tmpdir(), "oc-host-"))
  dirs.push(dir)
  const host = createOpenCodeCoreHost({
    stateDirectory: dir,
    driverFactory: (options) => {
      captured.push(options)
      const driver: AgentDriver = {
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
      return driver
    },
  })
  const extra = {
    sessionHome: dir, sessionName: "s", sessionId: "id1", workdir: dir, cwd: dir, prompts: true,
  }
  const handle = host.register({ id: "id1", env: {}, extra })
  await handle.start({ cwd: dir })
  expect("alwaysApprove" in (captured[0] ?? {})).toBe(false)
  expect("permissionPrompts" in (captured[0] ?? {})).toBe(false)
  expect("approvalPolicy" in (captured[0] ?? {})).toBe(false)
  await handle.stop({ mode: "shutdown" })
  await host.close({ agents: "shutdown" })
})
