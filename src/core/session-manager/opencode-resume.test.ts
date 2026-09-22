import { afterEach, test, expect } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { resumeOpenCodeSession } from "../agents/opencode/session"
import { createOpenCodeCoreHost, type OpenCodeCoreHost } from "../agents/opencode/core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../packages/supermux-core/src/index.js"
import type { OpenCodeOptions } from "../../../packages/supermux-core/src/agents/index.js"

function fakeChildFactory(nativeId = "ses_new") {
  const opens: DriverContext[] = []
  const factory = (_opts: OpenCodeOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "opencode",
    async open(ctx) {
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? nativeId,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: false, history: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
        async configure() {},
        configuration: () => ({}),
      }
      return runtime
    },
  })
  return { factory, opens }
}

const hosts: OpenCodeCoreHost[] = []
const dirs: string[] = []
afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

test("resume without a persisted id starts a FRESH opencode session", async () => {
  const child = fakeChildFactory("ses_new")
  const dir = mkdtempSync(join(tmpdir(), "oc-core-"))
  dirs.push(dir)
  const host = createOpenCodeCoreHost({ stateDirectory: dir, driverFactory: child.factory })
  hosts.push(host)
  const home = mkdtempSync(join(tmpdir(), "oc-resume-"))
  dirs.push(home)
  let persisted: { name: string; sid: string } | undefined
  await resumeOpenCodeSession(
    { onOpenCodeSessionId: (name, sid) => { persisted = { name, sid } }, opencodeHost: host },
    { id: "uuid-1", name: "demo", workdir: home, agent_home: home },
  )
  expect(child.opens[0]?.resumeId).toBeUndefined()
  expect(persisted).toEqual({ name: "demo", sid: "ses_new" })
})

test("resume WITH a persisted id reuses it WITHOUT creating a new session", async () => {
  const child = fakeChildFactory()
  const dir = mkdtempSync(join(tmpdir(), "oc-core-"))
  dirs.push(dir)
  const host = createOpenCodeCoreHost({ stateDirectory: dir, driverFactory: child.factory })
  hosts.push(host)
  const home = mkdtempSync(join(tmpdir(), "oc-resume-"))
  dirs.push(home)
  await resumeOpenCodeSession(
    { opencodeHost: host },
    { id: "uuid-1", name: "demo", workdir: home, agent_home: home, agent_session_id: "ses_prior" },
  )
  expect(child.opens[0]?.resumeId).toBe("ses_prior")
})
