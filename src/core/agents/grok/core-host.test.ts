import { afterEach, expect, test } from "bun:test"
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { GrokOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import { createGrokCoreHost, type GrokCoreHost } from "./core-host"

const tick = () => new Promise<void>((r) => setTimeout(r, 0))
const flush = async () => { await tick(); await tick() }

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: Error) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function fakeChildFactory(options: { nativeId?: string; holdOpen?: boolean; holdClose?: boolean; failCloses?: number } = {}) {
  const opens: DriverContext[] = []
  const grokCalls: { options: GrokOptions; overrides: SessionConfiguration }[] = []
  const prompts: string[][] = []
  let closes = 0
  let closeAttempts = 0
  let liveConfig: SessionConfiguration = {}
  let holdOpen = options.holdOpen === true
  let holdClose = options.holdClose === true
  const openGate = deferred<void>()
  const closeGate = deferred<void>()
  const closeEntered = deferred<void>()

  const factory = (gopts: GrokOptions, overrides: SessionConfiguration): AgentDriver => {
    grokCalls.push({ options: { ...gopts, env: { ...gopts.env } }, overrides: { ...overrides } })
    return {
      id: "grok",
      async open(ctx) {
        if (holdOpen) await openGate.promise
        opens.push(ctx)
        if (ctx.configuration) liveConfig = { ...ctx.configuration }
        else if (Object.keys(overrides).length) liveConfig = { ...overrides }
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
          capabilities: {
            resume: true, steer: false, fork: false, detach: false,
            configure: true, history: false,
          },
          async prompt(content) {
            prompts.push(content.map((c) => ("text" in c ? String(c.text) : "")))
            return { stopReason: "end_turn" }
          },
          async interrupt() {},
          async close() {
            closeAttempts++
            if (holdClose) {
              closeEntered.resolve()
              await closeGate.promise
            }
            if (closeAttempts <= (options.failCloses ?? 0)) throw new Error("close failed")
            closes++
          },
          async configure(configuration) { liveConfig = { ...configuration } },
          configuration: () => ({ ...liveConfig }),
        }
        return runtime
      },
    }
  }

  return {
    factory, opens, grokCalls, prompts,
    get closes() { return closes },
    get closeAttempts() { return closeAttempts },
    releaseOpen() { holdOpen = false; openGate.resolve() },
    waitCloseEntered: () => closeEntered.promise,
    releaseClose() { holdClose = false; closeGate.resolve() },
  }
}

const dirs: string[] = []
const hosts: GrokCoreHost[] = []

async function tmp(prefix: string) {
  const dir = await mkdtemp(join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}

async function makeHost(fake = fakeChildFactory()) {
  const stateDirectory = await tmp("grok-host-state-")
  const workdir = await tmp("grok-host-wd-")
  const host = createGrokCoreHost({ stateDirectory, driverFactory: fake.factory })
  hosts.push(host)
  return { host, stateDirectory, workdir, fake }
}

afterEach(async () => {
  await Promise.all(hosts.splice(0).map((h) => h.close().catch(() => {})))
  await Promise.all(dirs.splice(0).map((d) => rm(d, { recursive: true, force: true })))
})

async function sessionFiles(stateDirectory: string) {
  const root = join(stateDirectory, "sessions")
  const names = await readdir(root).catch(() => [] as string[])
  const texts: string[] = []
  for (const name of names) {
    texts.push(await readFile(join(root, name), "utf8"))
  }
  return texts
}

test("two sessions stay independent on one shared core", async () => {
  const { host, workdir, fake } = await makeHost()
  const wd2 = await tmp("grok-host-wd2-")
  const a = host.createAdapter({
    id: "sess-a", sessionName: "A", workdir, persistSessionId: async () => {}, env: { A: "1" },
  })
  const b = host.createAdapter({
    id: "sess-b", sessionName: "B", workdir: wd2, persistSessionId: async () => {}, env: { B: "2" },
  })
  await a.start()
  await b.start()
  expect(fake.opens.map((o) => o.sessionId).sort()).toEqual(["sess-a", "sess-b"])
  expect(new Set(fake.opens.map((o) => o.cwd)).size).toBe(2)
  await a.send("hello-a")
  await b.send("hello-b")
  await flush()
  expect(fake.prompts.flat()).toEqual(["hello-a", "hello-b"])
})

test("factory grok() gets explicit broker policy and cloned env, not sticky model defaults", async () => {
  const { host, workdir, fake } = await makeHost()
  const env = { GROK_API_KEY: "secret-live" }
  const persisted: string[] = []
  const adapter = host.createAdapter({
    id: "sess-cfg", sessionName: "cfg", workdir,
    persistSessionId: async (id) => { persisted.push(id) },
    env, model: "grok-4", effort: "high",
  })
  env.GROK_API_KEY = "mutated"
  await adapter.start()
  const first = fake.grokCalls[0]!
  expect(first.options.noLeader).toBe(false)
  expect(first.options.alwaysApprove).toBe(true)
  expect(first.options.env).toEqual({ GROK_API_KEY: "secret-live" })
  expect(first.options.model).toBeUndefined()
  expect(first.options.reasoningEffort).toBeUndefined()
  expect(persisted.length).toBe(1)
  const configureCall = fake.grokCalls.find((c) => c.overrides.model === "grok-4")
  expect(configureCall?.overrides).toEqual({ model: "grok-4", reasoningEffort: "high" })
})

test("env secrets never land in disk session records", async () => {
  const { host, workdir, stateDirectory } = await makeHost()
  const adapter = host.createAdapter({
    id: "sess-secret", sessionName: "s", workdir,
    persistSessionId: async () => {},
    env: { GROK_API_KEY: "do-not-write", TOKEN: "shh" },
  })
  await adapter.start()
  const texts = await sessionFiles(stateDirectory)
  expect(texts.length).toBeGreaterThan(0)
  expect(texts.join("\n")).not.toContain("do-not-write")
  expect(texts.join("\n")).not.toContain("shh")
  expect(texts.join("\n")).not.toContain("GROK_API_KEY")
})

test("duplicate live handle is rejected; replacement is allowed only after confirmed stop", async () => {
  const { host, workdir } = await makeHost()
  const first = host.createAdapter({
    id: "same", sessionName: "one", workdir, persistSessionId: async () => {}, env: { K: "1" },
  })
  await first.start()
  expect(() => host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "2" },
  })).toThrow(/already live/)
  await first.stop()
  const second = host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "2" },
  })
  await second.start()
  expect(second).not.toBe(first)
})

test("host.close stops every adapter and retains metadata for a new host resume", async () => {
  const fake = fakeChildFactory({ nativeId: "native-keep" })
  const stateDirectory = await tmp("grok-host-state-")
  const workdir = await tmp("grok-host-wd-")
  const host = createGrokCoreHost({ stateDirectory, driverFactory: fake.factory })
  hosts.push(host)
  let native = ""
  const adapter = host.createAdapter({
    id: "sess-keep", sessionName: "keep", workdir,
    persistSessionId: async (id) => { native = id },
    env: { X: "1" },
  })
  await adapter.start()
  expect(native).toBe("native-keep")
  await host.close()
  const texts = await sessionFiles(stateDirectory)
  expect(texts.join("\n")).toContain("native-keep")
  expect(texts.join("\n")).toContain("sess-keep")

  const fake2 = fakeChildFactory()
  const host2 = createGrokCoreHost({ stateDirectory, driverFactory: fake2.factory })
  hosts.push(host2)
  const resumed = host2.createAdapter({
    id: "sess-keep", sessionName: "keep", workdir,
    initialSessionId: native,
    persistSessionId: async () => {},
    env: { X: "1" },
  })
  await resumed.start()
  expect(fake2.opens[0]?.resumeId).toBe("native-keep")
  expect(fake2.opens[0]?.sessionId).toBe("sess-keep")
})

test("single adapter.stop never closes sibling sessions", async () => {
  const { host, workdir, fake } = await makeHost()
  const wd2 = await tmp("grok-host-wd2-")
  const a = host.createAdapter({
    id: "left", sessionName: "L", workdir, persistSessionId: async () => {}, env: {},
  })
  const b = host.createAdapter({
    id: "right", sessionName: "R", workdir: wd2, persistSessionId: async () => {}, env: {},
  })
  await a.start()
  await b.start()
  await a.stop()
  await b.send("still-alive")
  await flush()
  expect(fake.prompts.flat()).toContain("still-alive")
})

test("createAdapter is rejected while the host is closing", async () => {
  const fake = fakeChildFactory({ holdOpen: true })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.createAdapter({
    id: "pending", sessionName: "p", workdir, persistSessionId: async () => {}, env: {},
  })
  const starting = adapter.start().catch(() => {})
  const closing = host.close()
  await tick()
  expect(() => host.createAdapter({
    id: "late", sessionName: "late", workdir, persistSessionId: async () => {}, env: {},
  })).toThrow(/closing or closed/)
  fake.releaseOpen()
  await starting
  await closing
})

test("host.close retries a failed core close", async () => {
  const fake = fakeChildFactory({ failCloses: 1 })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.createAdapter({
    id: "retry", sessionName: "r", workdir, persistSessionId: async () => {}, env: {},
  })
  await adapter.start()
  await expect(host.close()).rejects.toThrow()
  expect(() => host.createAdapter({
    id: "after-fail", sessionName: "x", workdir, persistSessionId: async () => {}, env: {},
  })).toThrow(/closing or closed/)
  await host.close()
})

test("successful old stop is a no-op after replacement; native2 stays live until replacement.stop", async () => {
  const { host, workdir, fake } = await makeHost()
  const first = host.createAdapter({
    id: "same", sessionName: "one", workdir, persistSessionId: async () => {}, env: { K: "1" },
  })
  await first.start()
  await first.stop()
  const second = host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "2" },
  })
  await second.start()
  const closesAfterReplacement = fake.closeAttempts
  await first.stop()
  await second.send("still-alive")
  await flush()
  expect(fake.prompts.flat()).toEqual(["still-alive"])
  expect(fake.closeAttempts).toBe(closesAfterReplacement)
  await second.stop()
  expect(fake.closeAttempts).toBe(closesAfterReplacement + 1)
})

test("stale handle cannot start or resume after a replacement is live", async () => {
  const { host, workdir, fake } = await makeHost()
  const first = host.createAdapter({
    id: "same", sessionName: "one", workdir, persistSessionId: async () => {}, env: { K: "old" },
  })
  await first.start()
  await first.stop()
  const second = host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "new" },
  })
  await second.start()
  await expect(first.start()).rejects.toThrow(/stopped/)
  await expect(first.resume()).rejects.toThrow(/stopped/)
  await second.send("from-replacement")
  await flush()
  expect(fake.prompts.flat()).toEqual(["from-replacement"])
  expect(fake.grokCalls.at(-1)?.options.env).toEqual({ K: "new" })
})

test("stop-start race: terminal before cleanup; replacement only after confirmed close", async () => {
  const fake = fakeChildFactory({ holdClose: true })
  const { host, workdir } = await makeHost(fake)
  const first = host.createAdapter({
    id: "same", sessionName: "one", workdir, persistSessionId: async () => {}, env: { K: "1" },
  })
  await first.start()
  const stopping = first.stop()
  await fake.waitCloseEntered()
  await expect(first.start()).rejects.toThrow(/stopped/)
  await expect(first.resume()).rejects.toThrow(/stopped/)
  expect(() => host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "2" },
  })).toThrow(/already live/)
  fake.releaseClose()
  await stopping
  const second = host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "2" },
  })
  await second.start()
  expect(fake.grokCalls.at(-1)?.options.env).toEqual({ K: "2" })
  await expect(first.start()).rejects.toThrow(/stopped/)
})

test("failed stop keeps registration; retry cleanup then replacement is allowed", async () => {
  const fake = fakeChildFactory({ failCloses: 1 })
  const { host, workdir } = await makeHost(fake)
  const first = host.createAdapter({
    id: "same", sessionName: "one", workdir, persistSessionId: async () => {}, env: { K: "1" },
  })
  await first.start()
  await expect(first.stop()).rejects.toThrow(/close failed/)
  await expect(first.start()).rejects.toThrow(/stopped/)
  await expect(first.resume()).rejects.toThrow(/stopped/)
  expect(() => host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "2" },
  })).toThrow(/already live/)
  await first.stop()
  const second = host.createAdapter({
    id: "same", sessionName: "two", workdir, persistSessionId: async () => {}, env: { K: "2" },
  })
  await second.start()
  expect(fake.closeAttempts).toBe(2)
  expect(second).not.toBe(first)
  await expect(first.start()).rejects.toThrow(/stopped/)
})

test("host closing forbids start and resume on remaining handles", async () => {
  const fake = fakeChildFactory({ holdClose: true })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.createAdapter({
    id: "sess", sessionName: "s", workdir, persistSessionId: async () => {}, env: {},
  })
  await adapter.start()
  const closing = host.close()
  await fake.waitCloseEntered()
  await expect(adapter.start()).rejects.toThrow(/closing or closed|stopped/)
  await expect(adapter.resume()).rejects.toThrow(/closing or closed|stopped/)
  expect(() => host.createAdapter({
    id: "late", sessionName: "late", workdir, persistSessionId: async () => {}, env: {},
  })).toThrow(/closing or closed/)
  fake.releaseClose()
  await closing
  await expect(adapter.start()).rejects.toThrow(/closing or closed|stopped/)
})

test("sibling isolation: failed stop on one handle does not fence the other", async () => {
  const fake = fakeChildFactory({ failCloses: 1 })
  const { host, workdir } = await makeHost(fake)
  const wd2 = await tmp("grok-host-wd2-")
  const a = host.createAdapter({
    id: "left", sessionName: "L", workdir, persistSessionId: async () => {}, env: { A: "1" },
  })
  const b = host.createAdapter({
    id: "right", sessionName: "R", workdir: wd2, persistSessionId: async () => {}, env: { B: "2" },
  })
  await a.start()
  await b.start()
  await expect(a.stop()).rejects.toThrow(/close failed/)
  await b.send("still-alive")
  await flush()
  expect(fake.prompts.flat()).toContain("still-alive")
  await a.stop()
  await b.send("still-alive-2")
  await flush()
  expect(fake.prompts.flat()).toContain("still-alive-2")
})

test("failed start leftover is cleaned by stop; sibling stays alive; replacement after confirmed cleanup", async () => {
  const dirState = await tmp("host-failed-open-state-")
  const wd = await tmp("host-failed-open-wd-")
  const wd2 = await tmp("host-failed-open-wd2-")
  let opens = 0
  let sameOpens = 0
  let allowFirstClose = false
  const closes: number[] = []
  const factory = (_opts: unknown, _ov: SessionConfiguration): AgentDriver => ({
    id: "grok",
    async open(ctx: DriverContext) {
      const n = ++opens
      const same = ctx.sessionId === "same-id" ? ++sameOpens : 0
      if (same === 1) ctx.onExit(new Error("setup failed"))
      const runtime: AgentRuntime = {
        agentSessionId: "native-" + n,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" }),
        interrupt: async () => {},
        close: async () => {
          closes.push(n)
          if (same === 1 && !allowFirstClose) throw new Error("still alive")
        },
      }
      return runtime
    },
  })
  const host = createGrokCoreHost({ stateDirectory: dirState, driverFactory: factory })
  hosts.push(host)
  const sibling = host.createAdapter({
    id: "sib", sessionName: "sib", workdir: wd2, persistSessionId: async () => {}, env: { S: "1" },
  })
  await sibling.start()
  const a = host.createAdapter({
    id: "same-id", sessionName: "s", workdir: wd, persistSessionId: async () => {}, env: { K: "1" },
  })
  await expect(a.start()).rejects.toThrow(/setup failed|runtime cleanup failed/)
  await expect(a.stop()).rejects.toThrow(/still alive|cleanup failed/)
  expect(() => host.createAdapter({
    id: "same-id", sessionName: "s2", workdir: wd, persistSessionId: async () => {}, env: { K: "2" },
  })).toThrow(/already live/)
  await sibling.send("still-alive")
  allowFirstClose = true
  await a.stop()
  const b = host.createAdapter({
    id: "same-id", sessionName: "s2", workdir: wd, persistSessionId: async () => {}, env: { K: "2" },
  })
  await b.start()
  expect(sameOpens).toBe(2)
  expect(closes.length).toBeGreaterThan(0)
  await sibling.send("still-alive-2")
})

test("host.close aborts driver.open that only exits on ctx.signal and then settles", async () => {
  const dirState = await tmp("host-abort-open-state-")
  const wd = await tmp("host-abort-open-wd-")
  let aborted = false
  let entered!: () => void
  const enteredPromise = new Promise<void>((r) => { entered = r })
  const factory = (): AgentDriver => ({
    id: "grok",
    async open(ctx: DriverContext) {
      await new Promise<void>((resolve) => {
        ctx.signal.addEventListener("abort", () => {
          aborted = true
          resolve()
        }, { once: true })
        entered()
      })
      return {
        agentSessionId: "native-abort",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        prompt: async () => ({ stopReason: "end_turn" as const }),
        interrupt: async () => {},
        close: async () => {},
      }
    },
  })
  const host = createGrokCoreHost({ stateDirectory: dirState, driverFactory: factory })
  hosts.push(host)
  const adapter = host.createAdapter({
    id: "open-wait", sessionName: "ow", workdir: wd, persistSessionId: async () => {}, env: {},
  })
  const starting = adapter.start().catch(() => {})
  await enteredPromise
  const closed = host.close()
  await closed
  expect(aborted).toBe(true)
  await starting
})

test("pending open is aborted by host shutdown", async () => {
  const fake = fakeChildFactory({ holdOpen: true })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.createAdapter({
    id: "slow", sessionName: "s", workdir, persistSessionId: async () => {}, env: {},
  })
  const starting = adapter.start()
  const closing = host.close()
  fake.releaseOpen()
  const startResult = await starting.then(() => "ok" as const, (e) => e as Error)
  await closing
  if (startResult !== "ok") {
    expect(startResult).toBeInstanceOf(Error)
  }
})
