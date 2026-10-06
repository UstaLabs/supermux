import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createCodexCoreHost, type CodexCoreHost } from "../../src/core/agents/codex/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../packages/supermux-core/src/index.js"
import type { CodexOptions } from "../../packages/supermux-core/src/codex/index.js"

/** A REAL library-backed Codex host whose native child is a fake driver: the
 * broker's spawn/resume path runs unchanged (register → prepare → start), only
 * the `codex app-server` process is replaced. Credentials take the api-key
 * branch so no ~/.codex/auth.json is needed on the test machine. */
export function fakeCodexHost(nativeId = "codex-thread-id"): {
  host: CodexCoreHost
  opens: DriverContext[]
  prompts: string[][]
  close(): Promise<void>
} {
  const opens: DriverContext[] = []
  const prompts: string[][] = []
  const stateDirectory = mkdtempSync(join(tmpdir(), "mux-fake-codex-host-"))
  const prevKey = process.env.OPENAI_API_KEY
  process.env.OPENAI_API_KEY = "test-key"
  const factory = (_options: CodexOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "codex",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? nativeId,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true, history: false },
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
  const host = createCodexCoreHost({ stateDirectory, driverFactory: factory })
  return {
    host,
    opens,
    prompts,
    async close() {
      await host.close({ agents: "shutdown" }).catch(() => {})
      rmSync(stateDirectory, { recursive: true, force: true })
      if (prevKey === undefined) delete process.env.OPENAI_API_KEY
      else process.env.OPENAI_API_KEY = prevKey
    },
  }
}
