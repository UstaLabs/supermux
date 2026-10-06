import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createOpenCodeCoreHost, type OpenCodeCoreHost } from "../../src/core/agents/opencode/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../packages/supermux-core/src/index.js"
import type { OpenCodeOptions } from "../../packages/supermux-core/src/agents/index.js"

/** A REAL library-backed OpenCode host whose native child is a fake driver. */
export function fakeOpenCodeHost(nativeId = "opencode-sid"): {
  host: OpenCodeCoreHost
  opens: DriverContext[]
  prompts: string[][]
  close(): Promise<void>
} {
  const opens: DriverContext[] = []
  const prompts: string[][] = []
  const stateDirectory = mkdtempSync(join(tmpdir(), "mux-fake-opencode-host-"))
  const factory = (_options: OpenCodeOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "opencode",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? nativeId,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: false, history: false },
        async prompt(content) {
          prompts.push(content.map((c) => ("text" in c ? String(c.text) : `<${c.type}>`)))
          return { stopReason: "end_turn" }
        },
        async interrupt() {},
        async close() {},
        async configure() {},
        configuration: () => ({}),
      }
      return runtime
    },
  })
  const host = createOpenCodeCoreHost({ stateDirectory, driverFactory: factory })
  return {
    host,
    opens,
    prompts,
    async close() {
      await host.close({ agents: "shutdown" }).catch(() => {})
      rmSync(stateDirectory, { recursive: true, force: true })
    },
  }
}
