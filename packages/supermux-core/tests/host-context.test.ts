// createHost + session context (C3): host drivers declare context support like createCore
// drivers, registrations / prepare carry a SessionContext, records created before instructions
// were snapshotted get them on their first launch, and a ready handle follows a reload.
import { afterEach, expect, test } from "bun:test"
import { mkdir, mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore, createHost, type Host } from "../src/index.js"
import type { AgentDriver, AgentRuntime, CoreEvent, DriverContext, DriverContextSupport, HostRegistration, SessionContext } from "../src/index.js"
import { TEST_LIMITS } from "./helpers.js"

const SUPPORT: DriverContextSupport = {
  capabilities: {
    instructions: { support: "supported", note: "test" },
    skills: { support: "supported", note: "test" },
    plugins: { support: "unsupported", note: "test: no plugins" },
    mcpServers: { support: "supported", note: "test" },
  },
  update: {
    skills: { add: "reload", remove: "reload", note: "test" },
    plugins: { add: "reload", remove: "reload", note: "test" },
    mcpServers: { add: "reload", remove: "reload", note: "test" },
  },
}

function fakeFactory() {
  const opens: DriverContext[] = []
  const registrations: HostRegistration[] = []
  const driver = async (registered: HostRegistration): Promise<AgentDriver> => {
    registrations.push(structuredClone(registered))
    return {
      id: "test",
      async open(ctx) {
        opens.push(ctx)
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? `native-${opens.length}`,
          capabilities: { resume: true, steer: false, fork: false, detach: false },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {},
        }
        return runtime
      },
    }
  }
  return { driver, opens, registrations }
}

const dirs: string[] = []
const hosts: Host[] = []
async function tmp(prefix: string) {
  const dir = await mkdtemp(join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}
afterEach(async () => {
  await Promise.all(hosts.splice(0).map((h) => h.close({ agents: "shutdown" }).catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true })))
})

async function makeHost(options: { context?: DriverContextSupport; contextPolicy?: "error" | "warn"; prepare?: (r: HostRegistration) => Promise<void | { env?: Record<string, string>; args?: string[]; context?: SessionContext }>; stateDirectory?: string } = {}) {
  const fake = fakeFactory()
  const stateDirectory = options.stateDirectory ?? await tmp("host-ctx-state-")
  const workdir = await tmp("host-ctx-wd-")
  const host = createHost({
    stateDirectory, limits: TEST_LIMITS, agent: "test", driver: fake.driver,
    ...(options.context ? { context: options.context } : {}),
    ...(options.contextPolicy ? { contextPolicy: options.contextPolicy } : {}),
    ...(options.prepare ? { prepare: options.prepare } : {}),
  })
  hosts.push(host)
  return { host, fake, workdir, stateDirectory }
}

async function skillsDir() {
  const dir = await tmp("host-ctx-skills-")
  await mkdir(join(dir, "s1"), { recursive: true })
  return dir
}

test("a host without context support declares none, and a launch without context carries none", async () => {
  const { host, fake, workdir } = await makeHost()
  expect(host.core.capabilities("test").context.instructions.support).toBe("unsupported")
  await host.register({ id: "plain", env: {} }).start({ cwd: workdir })
  expect(fake.opens[0]!.sessionContext).toBeUndefined()
  expect((await host.core.sessions.get("plain"))!.createdInstructions).toBeUndefined()
})

test("the host driver declares the given context support; a registration's context reaches the launch and instructions are snapshotted", async () => {
  const { host, fake, workdir } = await makeHost({ context: SUPPORT })
  expect(host.core.capabilities("test").context).toEqual(SUPPORT.capabilities)
  const skills = await skillsDir()
  const mcp = { name: "ext", command: "/bin/true", args: ["a"], env: { K: "v" } }
  await host.register({ id: "ctx", env: {}, context: { instructions: "BE NICE", skills: [skills], mcpServers: [mcp] } }).start({ cwd: workdir })
  const launched = fake.opens[0]!.sessionContext!
  expect(launched.instructions).toBe("BE NICE")
  expect(launched.skills).toEqual([skills])
  expect(launched.mcpServers).toEqual([mcp])
  expect(launched.launch).toBe("create")
  const record = (await host.core.sessions.get("ctx"))!
  expect(record.createdInstructions).toBe("BE NICE")
  // The driver factory never sees the context (it is the core's to apply).
  expect(fake.registrations[0]!.context).toBeUndefined()
})

test("prepare may return a context; it replaces the registration's for this and later opens", async () => {
  const { host, fake, workdir } = await makeHost({
    context: SUPPORT,
    prepare: async (registration) => ({ context: { instructions: `prepared for ${registration.id}` } }),
  })
  await host.register({ id: "prep", env: {}, context: { instructions: "registered" } }).start({ cwd: workdir })
  expect(fake.opens[0]!.sessionContext!.instructions).toBe("prepared for prep")
})

test("resume: the context minus instructions replaces the session's own; instructions stay as created", async () => {
  const { host, fake, workdir, stateDirectory } = await makeHost({ context: SUPPORT })
  await host.register({ id: "res", env: {}, context: { instructions: "FIRST" } }).start({ cwd: workdir })
  await host.close({ agents: "shutdown" })
  const skills = await skillsDir()
  const next = await makeHost({ context: SUPPORT, stateDirectory })
  await next.host.register({ id: "res", env: {}, context: { instructions: "SECOND", skills: [skills] } }).start({ cwd: workdir })
  const launched = next.fake.opens[0]!.sessionContext!
  expect(launched.launch).toBe("resume")
  expect(launched.instructions).toBe("FIRST")
  expect(launched.skills).toEqual([skills])
  const record = (await next.host.core.sessions.get("res"))!
  expect(record.createdInstructions).toBe("FIRST")
  expect(record.context).toEqual({ instructions: "FIRST", skills: [skills] })
  expect(fake.opens).toHaveLength(1)
})

test("a record from before instructions were snapshotted gets the registration's instructions on its first launch, then keeps them", async () => {
  const stateDirectory = await tmp("host-ctx-legacy-")
  const workdir = await tmp("host-ctx-legacy-wd-")
  // The record a pre-C3 broker left: created without any context.
  const legacy = createCore({ stateDirectory, limits: TEST_LIMITS, agents: [{ id: "test", async open(ctx) { return { agentSessionId: "native-legacy", capabilities: { resume: true, steer: false, fork: false, detach: false }, async prompt() { return { stopReason: "end_turn" as const } }, async interrupt() {}, async close() {} } } }] })
  await legacy.sessions.create({ id: "old", agent: "test", cwd: workdir })
  await legacy.close({ agents: "shutdown" })
  expect((await (async () => { const c = createCore({ stateDirectory, limits: TEST_LIMITS, agents: [{ id: "test", async open() { throw new Error("unused") } }] }); try { return await c.sessions.get("old") } finally { await c.close({ agents: "shutdown" }) } })())!.createdInstructions).toBeUndefined()

  const first = await makeHost({ context: SUPPORT, stateDirectory })
  await first.host.register({ id: "old", env: {}, context: { instructions: "GENERATED NOW" } }).start({ cwd: workdir })
  expect(first.fake.opens[0]!.sessionContext!.instructions).toBe("GENERATED NOW")
  expect(first.fake.opens[0]!.resumeId).toBe("native-legacy")
  expect((await first.host.core.sessions.get("old"))!.createdInstructions).toBe("GENERATED NOW")
  await first.host.close({ agents: "shutdown" })

  const second = await makeHost({ context: SUPPORT, stateDirectory })
  await second.host.register({ id: "old", env: {}, context: { instructions: "CHANGED LATER" } }).start({ cwd: workdir })
  expect(second.fake.opens[0]!.sessionContext!.instructions).toBe("GENERATED NOW")
})

test("an adopted native session (no record yet) snapshots the registration's instructions", async () => {
  const { host, fake, workdir } = await makeHost({ context: SUPPORT })
  await host.register({ id: "adopted", env: {}, extra: { nativeSessionId: "native-x" }, context: { instructions: "ADOPTED" } })
    .start({ cwd: workdir, nativeSessionId: "native-x" })
  expect(fake.opens[0]!.resumeId).toBe("native-x")
  expect(fake.opens[0]!.sessionContext!.instructions).toBe("ADOPTED")
  expect((await host.core.sessions.get("adopted"))!.createdInstructions).toBe("ADOPTED")
})

test("contextPolicy warn: an item the driver cannot apply is dropped with context.degraded instead of failing", async () => {
  const { host, fake, workdir } = await makeHost({ context: SUPPORT, contextPolicy: "warn" })
  const events: CoreEvent[] = []
  host.core.subscribe((e) => { events.push(e) })
  const plugin = await tmp("host-ctx-plugin-")
  await host.register({ id: "warn", env: {}, context: { instructions: "I", plugins: [plugin] } }).start({ cwd: workdir })
  expect(fake.opens[0]!.sessionContext!.dropped).toEqual([{ kind: "plugins", item: plugin, reason: "test: no plugins" }])
  expect(events.some((e) => e.type === "context.degraded")).toBe(true)
})

test("contextPolicy error (default): the same item fails the start", async () => {
  const { host, workdir } = await makeHost({ context: SUPPORT })
  const plugin = await tmp("host-ctx-plugin-")
  await expect(host.register({ id: "err", env: {}, context: { plugins: [plugin] } }).start({ cwd: workdir })).rejects.toMatchObject({ code: "context_unsupported" })
})

test("a context reload replaces the Session: the ready handle follows it and reopening(id) is true meanwhile", async () => {
  const { host, fake, workdir } = await makeHost({ context: SUPPORT })
  const handle = host.register({ id: "reload", env: {}, context: { instructions: "I" } })
  const first = await handle.start({ cwd: workdir })
  const skills = await skillsDir()
  let sawReopening = false
  host.core.subscribe((e) => {
    if (e.type === "session.stateChanged" && e.state === "closed" && host.core.sessions.reopening("reload")) sawReopening = true
  })
  const result = await host.core.sessions.updateContext("reload", { skills: { add: [skills] } })
  expect(result.applied.map((a) => a.how)).toEqual(["reload"])
  expect(fake.opens).toHaveLength(2)
  expect(sawReopening).toBe(true)
  expect(host.core.sessions.reopening("reload")).toBe(false)
  const live = host.core.sessions.live("reload")!
  expect(live).not.toBe(first)
  expect(handle.session).toBe(live)
})
