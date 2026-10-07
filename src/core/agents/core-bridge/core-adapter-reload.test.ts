// C3 (C1b open issue 6): when the core reloads a session (an updateContext reload, a host
// tool-change reload, an account switch), the old Session closes and core.sessions.live(id) is a
// new one. The CoreAdapter must follow it: input, state and events go to the new Session and
// nothing tells the user the session closed or died.
import { afterEach, expect, test } from "bun:test"
import { mkdir, mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createHost, type AgentDriver, type AgentRuntime, type DriverContext, type DriverContextSupport, type Host } from "../../../../packages/supermux-core/src/index.js"
import { CoreAdapter, CORE_ADAPTER_PROFILES } from "./core-adapter"

const SUPPORT: DriverContextSupport = {
  capabilities: {
    instructions: { support: "supported", note: "t" }, skills: { support: "supported", note: "t" },
    plugins: { support: "supported", note: "t" }, mcpServers: { support: "supported", note: "t" },
  },
  update: {
    skills: { add: "reload", remove: "reload", note: "t" }, plugins: { add: "reload", remove: "reload", note: "t" },
    mcpServers: { add: "reload", remove: "reload", note: "t" },
  },
}

const dirs: string[] = []
const hosts: Host[] = []
const adapters: CoreAdapter[] = []
afterEach(async () => {
  await Promise.all(adapters.splice(0).map((a) => a.stop().catch(() => {})))
  await Promise.all(hosts.splice(0).map((h) => h.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true })))
})
async function tmp(prefix: string) { const d = await mkdtemp(join(tmpdir(), prefix)); dirs.push(d); return d }

function fakeDriver() {
  const opens: DriverContext[] = []
  const prompts: Array<{ runtime: number; text: string }> = []
  const driver: AgentDriver = {
    id: "claude",
    async open(ctx) {
      opens.push(ctx)
      const runtimeNo = opens.length
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(content) { prompts.push({ runtime: runtimeNo, text: content.map((c) => ("text" in c ? c.text : "")).join("") }); return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  }
  return { driver, opens, prompts }
}

async function setup() {
  const fake = fakeDriver()
  const workdir = await tmp("reload-wd-")
  const host = createHost({ stateDirectory: await tmp("reload-state-"), limits: { interruptTimeoutMs: 40, maxPending: 16, outstandingActivity: 256 }, agent: "claude", driver: () => fake.driver, context: SUPPORT })
  hosts.push(host)
  const handle = host.register({ id: "s1", env: {}, context: { instructions: "I" } })
  const adapter = new CoreAdapter(CORE_ADAPTER_PROFILES.claude, {
    handle, reregister: () => host.register({ id: "s1", env: {} }), core: host.core,
    id: "s1", sessionName: "s1", workdir, persistSessionId: async () => {},
  })
  adapters.push(adapter)
  const errors: unknown[] = []
  adapter.on("error", (e) => errors.push(e))
  await adapter.start()
  return { host, adapter, fake, errors }
}

test("an updateContext reload: the adapter follows the new Session, stays alive throughout, and input reaches the new process", async () => {
  const { host, adapter, fake, errors } = await setup()
  await adapter.send("before")
  const skills = await tmp("reload-skills-")
  await mkdir(join(skills, "a"))
  const aliveDuringReload: boolean[] = []
  host.core.subscribe((e) => {
    if (e.type === "session.stateChanged" && e.state === "closed") aliveDuringReload.push(adapter.isAlive())
  })
  const result = await host.core.sessions.updateContext("s1", { skills: { add: [skills] } })
  expect(result.applied[0]!.how).toBe("reload")
  expect(fake.opens).toHaveLength(2)
  expect(aliveDuringReload).toEqual([true])
  expect(adapter.isAlive()).toBe(true)
  expect(adapter.sessionSnapshotState()).toBe("idle")
  await adapter.send("after")
  expect(fake.prompts).toEqual([{ runtime: 1, text: "before" }, { runtime: 2, text: "after" }])
  expect(errors).toEqual([])
})

test("input sent while the reload is in flight waits for the new Session instead of failing", async () => {
  const { host, adapter, fake, errors } = await setup()
  const skills = await tmp("reload-skills-")
  let sent: Promise<void> | undefined
  host.core.subscribe((e) => {
    // The old Session just closed; the new one is not open yet.
    if (e.type === "session.stateChanged" && e.state === "closed" && !sent) sent = adapter.send("during")
  })
  await host.core.sessions.updateContext("s1", { skills: { add: [skills] } })
  await sent
  expect(fake.prompts).toEqual([{ runtime: 2, text: "during" }])
  expect(errors).toEqual([])
})
