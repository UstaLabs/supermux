import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createCursorCoreHost, type CursorCoreHost } from "../../src/core/agents/cursor/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../packages/supermux-core/src/index.js"
import type { CursorOptions } from "../../packages/supermux-core/src/agents/index.js"

/** A REAL library-backed Cursor host whose native child is a fake driver. */
export function fakeCursorHost(nativeId = "cursor-sid"): {
  host: CursorCoreHost
  opens: DriverContext[]
  prompts: string[][]
  close(): Promise<void>
} {
  const opens: DriverContext[] = []
  const prompts: string[][] = []
  const stateDirectory = mkdtempSync(join(tmpdir(), "mux-fake-cursor-host-"))
  const factory = (_options: CursorOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "cursor",
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
  const host = createCursorCoreHost({ stateDirectory, driverFactory: factory, smoke: async () => {}, sharedRuntime: null })
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
