import { afterEach, expect, test } from "bun:test"
import { mkdtemp, readFile, readdir, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createHost, type Host } from "../src/index.js"
import type { AgentDriver, AgentRuntime, DriverContext, HostRegistration } from "../src/index.js"
import { CoreError } from "../src/errors.js"
import { TEST_LIMITS } from "./helpers.js"

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
  const registrations: HostRegistration[] = []
  const prompts: string[][] = []
  let closes = 0
  let closeAttempts = 0
  let holdOpen = options.holdOpen === true
  let holdClose = options.holdClose === true
  const openGate = deferred<void>()
  const closeGate = deferred<void>()
  const closeEntered = deferred<void>()

  const driver = async (registered: HostRegistration, ctx: DriverContext): Promise<AgentDriver> => {
    registrations.push({
      ...registered,
      env: { ...registered.env },
      args: registered.args ? [...registered.args] : undefined,
    })
    return {
      id: "test",
      async open(openCtx) {
        if (holdOpen) {
          await new Promise<void>((resolve, reject) => {
            const done = () => { openCtx.signal.removeEventListener("abort", onAbort); resolve() }
            const onAbort = () => { openCtx.signal.removeEventListener("abort", onAbort); reject(new Error("aborted")) }
            if (openCtx.signal.aborted) { onAbort(); return }
            openCtx.signal.addEventListener("abort", onAbort, { once: true })
            openGate.promise.then(done, (e) => { openCtx.signal.removeEventListener("abort", onAbort); reject(e) })
          })
        }
        opens.push(openCtx)
        const runtime: AgentRuntime = {
          agentSessionId: openCtx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
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
          async configure() {},
          configuration: () => ({}),
        }
        return runtime
      },
    }
  }

  return {
    driver, opens, registrations, prompts,
    get closes() { return closes },
    get closeAttempts() { return closeAttempts },
    releaseOpen() { holdOpen = false; openGate.resolve() },
    waitCloseEntered: () => closeEntered.promise,
    releaseClose() { holdClose = false; closeGate.resolve() },
  }
}

const dirs: string[] = []
const hosts: Host[] = []

async function tmp(prefix: string) {
  const dir = await mkdtemp(join(tmpdir(), prefix))
  dirs.push(dir)
  return dir
}

async function makeHost(fake = fakeChildFactory(), extra?: { prepare?: (r: HostRegistration) => Promise<void> }) {
  const stateDirectory = await tmp("host-state-")
  const workdir = await tmp("host-wd-")
  const host = createHost({
    stateDirectory,
    limits: TEST_LIMITS,
    agent: "test",
    driver: fake.driver,
    prepare: extra?.prepare,
  })
  hosts.push(host)
  return { host, stateDirectory, workdir, fake }
}

afterEach(async () => {
  await Promise.all(hosts.splice(0).map((h) => h.close({ agents: "shutdown" }).catch(() => {})))
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
  const wd2 = await tmp("host-wd2-")
  const a = host.register({ id: "sess-a", env: { A: "1" } })
  const b = host.register({ id: "sess-b", env: { B: "2" } })
  await a.start({ cwd: workdir })
  await b.start({ cwd: wd2 })
  expect(fake.opens.map((o) => o.sessionId).sort()).toEqual(["sess-a", "sess-b"])
  expect(new Set(fake.opens.map((o) => o.cwd)).size).toBe(2)
  await a.session!.send({ content: [{ type: "text", text: "hello-a" }], whenBusy: "queue" })
  await b.session!.send({ content: [{ type: "text", text: "hello-b" }], whenBusy: "queue" })
  await flush()
  expect(fake.prompts.flat()).toEqual(["hello-a", "hello-b"])
})

test("per-session command/args reach the driver factory", async () => {
  const { host, workdir, fake } = await makeHost()
  const handle = host.register({
    id: "sess-cmd",
    env: { K: "1" },
    command: "/opt/agent",
    args: ["app-server", "-c", 'approval_policy="never"'],
  })
  await handle.start({ cwd: workdir })
  expect(fake.registrations[0]?.command).toBe("/opt/agent")
  expect(fake.registrations[0]?.args).toEqual(["app-server", "-c", 'approval_policy="never"'])
})

test("factory gets cloned env, not sticky model defaults on the registration", async () => {
  const { host, workdir, fake } = await makeHost()
  const env = { OPENAI_API_KEY: "secret-live" }
  const handle = host.register({ id: "sess-cfg", env, extra: { model: "gpt-5" } })
  env.OPENAI_API_KEY = "mutated"
  await handle.start({ cwd: workdir, configuration: { model: "gpt-5", reasoningEffort: "high" } })
  expect(fake.registrations[0]?.env).toEqual({ OPENAI_API_KEY: "secret-live" })
  expect(fake.opens[0]?.configuration).toEqual({ model: "gpt-5", reasoningEffort: "high" })
})

test("env secrets never land in disk session records", async () => {
  const { host, workdir, stateDirectory } = await makeHost()
  const handle = host.register({
    id: "sess-secret",
    env: { OPENAI_API_KEY: "do-not-write", TOKEN: "shh" },
  })
  await handle.start({ cwd: workdir })
  const texts = await sessionFiles(stateDirectory)
  expect(texts.length).toBeGreaterThan(0)
  expect(texts.join("\n")).not.toContain("do-not-write")
  expect(texts.join("\n")).not.toContain("shh")
  expect(texts.join("\n")).not.toContain("OPENAI_API_KEY")
})

test("duplicate live handle is rejected; replacement is allowed only after confirmed stop", async () => {
  const { host, workdir } = await makeHost()
  const first = host.register({ id: "same", env: { K: "1" } })
  await first.start({ cwd: workdir })
  expect(() => host.register({ id: "same", env: { K: "2" } })).toThrow(/already live|already/)
  await first.stop({ mode: "shutdown" })
  const second = host.register({ id: "same", env: { K: "2" } })
  await second.start({ cwd: workdir })
  expect(second).not.toBe(first)
})

test("host.close stops every adapter and retains metadata for a new host resume", async () => {
  const fake = fakeChildFactory({ nativeId: "native-keep" })
  const stateDirectory = await tmp("host-state-")
  const workdir = await tmp("host-wd-")
  const host = createHost({
    stateDirectory, limits: TEST_LIMITS, agent: "test", driver: fake.driver,
  })
  hosts.push(host)
  const adapter = host.register({ id: "sess-keep", env: { X: "1" } })
  await adapter.start({ cwd: workdir })
  expect(adapter.session!.snapshot().agentSessionId).toBe("native-keep")
  await host.close({ agents: "shutdown" })
  const texts = await sessionFiles(stateDirectory)
  expect(texts.join("\n")).toContain("native-keep")
  expect(texts.join("\n")).toContain("sess-keep")

  const fake2 = fakeChildFactory()
  const host2 = createHost({
    stateDirectory, limits: TEST_LIMITS, agent: "test", driver: fake2.driver,
  })
  hosts.push(host2)
  const resumed = host2.register({ id: "sess-keep", env: { X: "1" } })
  await resumed.start({ cwd: workdir, nativeSessionId: "native-keep" })
  expect(fake2.opens[0]?.resumeId).toBe("native-keep")
  expect(fake2.opens[0]?.sessionId).toBe("sess-keep")
})

test("single adapter.stop never closes sibling sessions", async () => {
  const { host, workdir, fake } = await makeHost()
  const wd2 = await tmp("host-wd2-")
  const a = host.register({ id: "left", env: {} })
  const b = host.register({ id: "right", env: {} })
  await a.start({ cwd: workdir })
  await b.start({ cwd: wd2 })
  await a.stop({ mode: "shutdown" })
  await b.session!.send({ content: [{ type: "text", text: "still-alive" }], whenBusy: "queue" })
  await flush()
  expect(fake.prompts.flat()).toContain("still-alive")
})

test("register is rejected while the host is closing", async () => {
  const fake = fakeChildFactory({ holdOpen: true })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.register({ id: "pending", env: {} })
  const starting = adapter.start({ cwd: workdir }).catch(() => {})
  const closing = host.close({ agents: "shutdown" })
  await tick()
  expect(() => host.register({ id: "late", env: {} })).toThrow(/closing or closed/)
  fake.releaseOpen()
  await starting
  await closing
})

test("host.close retries a failed core close", async () => {
  const fake = fakeChildFactory({ failCloses: 1 })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.register({ id: "retry", env: {} })
  await adapter.start({ cwd: workdir })
  await expect(host.close({ agents: "shutdown" })).rejects.toThrow()
  expect(() => host.register({ id: "after-fail", env: {} })).toThrow(/closing or closed/)
  await host.close({ agents: "shutdown" })
})

test("successful old stop is a no-op after replacement; native2 stays live until replacement.stop", async () => {
  const { host, workdir, fake } = await makeHost()
  const first = host.register({ id: "same", env: { K: "1" } })
  await first.start({ cwd: workdir })
  await first.stop({ mode: "shutdown" })
  const second = host.register({ id: "same", env: { K: "2" } })
  await second.start({ cwd: workdir })
  const closesAfterReplacement = fake.closeAttempts
  await first.stop({ mode: "shutdown" })
  await second.session!.send({ content: [{ type: "text", text: "still-alive" }], whenBusy: "queue" })
  await flush()
  expect(fake.prompts.flat()).toEqual(["still-alive"])
  expect(fake.closeAttempts).toBe(closesAfterReplacement)
  await second.stop({ mode: "shutdown" })
  expect(fake.closeAttempts).toBe(closesAfterReplacement + 1)
})

test("stale handle cannot start or resume after a replacement is live", async () => {
  const { host, workdir, fake } = await makeHost()
  const first = host.register({ id: "same", env: { K: "old" } })
  await first.start({ cwd: workdir })
  await first.stop({ mode: "shutdown" })
  const second = host.register({ id: "same", env: { K: "new" } })
  await second.start({ cwd: workdir })
  await expect(first.start({ cwd: workdir })).rejects.toThrow(/stopped|closing/)
  await expect(first.resume()).rejects.toThrow(/stopped|closing/)
  await second.session!.send({ content: [{ type: "text", text: "from-replacement" }], whenBusy: "queue" })
  await flush()
  expect(fake.prompts.flat()).toEqual(["from-replacement"])
  expect(fake.registrations.at(-1)?.env).toEqual({ K: "new" })
})

test("stop-start race: terminal before cleanup; replacement only after confirmed close", async () => {
  const fake = fakeChildFactory({ holdClose: true })
  const { host, workdir } = await makeHost(fake)
  const first = host.register({ id: "same", env: { K: "1" } })
  await first.start({ cwd: workdir })
  const stopping = first.stop({ mode: "shutdown" })
  await fake.waitCloseEntered()
  await expect(first.start({ cwd: workdir })).rejects.toThrow(/stopped/)
  await expect(first.resume()).rejects.toThrow(/stopped/)
  expect(() => host.register({ id: "same", env: { K: "2" } })).toThrow(/already live|already/)
  fake.releaseClose()
  await stopping
  const second = host.register({ id: "same", env: { K: "2" } })
  await second.start({ cwd: workdir })
  expect(fake.registrations.at(-1)?.env).toEqual({ K: "2" })
  await expect(first.start({ cwd: workdir })).rejects.toThrow(/stopped/)
})

test("failed stop keeps registration; retry cleanup then replacement is allowed", async () => {
  const fake = fakeChildFactory({ failCloses: 1 })
  const { host, workdir } = await makeHost(fake)
  const first = host.register({ id: "same", env: { K: "1" } })
  await first.start({ cwd: workdir })
  await expect(first.stop({ mode: "shutdown" })).rejects.toThrow(/close failed/)
  // The id is failed-cleanup: retrying stop on the same handle releases it.
  await first.stop({ mode: "shutdown" })
  expect(fake.closeAttempts).toBe(2)
  await expect(first.start({ cwd: workdir })).rejects.toThrow(/stopped/)
  await expect(first.resume()).rejects.toThrow(/stopped/)
  const second = host.register({ id: "same", env: { K: "2" } })
  await second.start({ cwd: workdir })
  expect(fake.opens).toHaveLength(2)
  expect(second).not.toBe(first)
  await expect(first.start({ cwd: workdir })).rejects.toThrow(/stopped/)
})

test("host closing forbids start and resume on remaining handles", async () => {
  const fake = fakeChildFactory({ holdClose: true })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.register({ id: "sess", env: {} })
  await adapter.start({ cwd: workdir })
  const closing = host.close({ agents: "shutdown" })
  await fake.waitCloseEntered()
  await expect(adapter.start({ cwd: workdir })).rejects.toThrow(/closing or closed|stopped/)
  await expect(adapter.resume()).rejects.toThrow(/closing or closed|stopped/)
  expect(() => host.register({ id: "late", env: {} })).toThrow(/closing or closed/)
  fake.releaseClose()
  await closing
  await expect(adapter.start({ cwd: workdir })).rejects.toThrow(/closing or closed|stopped/)
})

test("sibling isolation: failed stop on one handle does not fence the other", async () => {
  const fake = fakeChildFactory({ failCloses: 1 })
  const { host, workdir } = await makeHost(fake)
  const wd2 = await tmp("host-wd2-")
  const a = host.register({ id: "left", env: { A: "1" } })
  const b = host.register({ id: "right", env: { B: "2" } })
  await a.start({ cwd: workdir })
  await b.start({ cwd: wd2 })
  await expect(a.stop({ mode: "shutdown" })).rejects.toThrow(/close failed/)
  await b.session!.send({ content: [{ type: "text", text: "still-alive" }], whenBusy: "queue" })
  await flush()
  expect(fake.prompts.flat()).toContain("still-alive")
  await a.stop({ mode: "shutdown" })
  await b.session!.send({ content: [{ type: "text", text: "still-alive-2" }], whenBusy: "queue" })
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
  const driver = async (_reg: HostRegistration, ctx: DriverContext): Promise<AgentDriver> => ({
    id: "test",
    async open(openCtx) {
      const n = ++opens
      const same = openCtx.sessionId === "same-id" ? ++sameOpens : 0
      if (same === 1) openCtx.onExit(new Error("setup failed"))
      return {
        agentSessionId: "native-" + n,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" as const }),
        interrupt: async () => {},
        close: async () => {
          closes.push(n)
          if (same === 1 && !allowFirstClose) throw new Error("still alive")
        },
      }
    },
  })
  const host = createHost({ stateDirectory: dirState, limits: TEST_LIMITS, agent: "test", driver })
  hosts.push(host)
  const sibling = host.register({ id: "sib", env: { S: "1" } })
  await sibling.start({ cwd: wd2 })
  const a = host.register({ id: "same-id", env: { K: "1" } })
  await expect(a.start({ cwd: wd })).rejects.toThrow(/setup failed|runtime cleanup failed|cleanup failed|opening/)
  await expect(a.stop({ mode: "shutdown" })).rejects.toThrow(/still alive|cleanup failed/)
  // A fresh registration may take over the failed-cleanup id, but its start
  // retries the leftover cleanup first and fails while the process lives on.
  const b = host.register({ id: "same-id", env: { K: "2" } })
  await expect(b.start({ cwd: wd })).rejects.toThrow(/still alive/)
  expect(sameOpens).toBe(1)
  await sibling.session!.send({ content: [{ type: "text", text: "still-alive" }], whenBusy: "queue" })
  allowFirstClose = true
  await a.stop({ mode: "shutdown" })
  await b.start({ cwd: wd })
  expect(sameOpens).toBe(2)
  expect(closes.length).toBeGreaterThan(0)
  await sibling.session!.send({ content: [{ type: "text", text: "still-alive-2" }], whenBusy: "queue" })
})

test("host.close aborts driver.open that only exits on ctx.signal and then settles", async () => {
  const dirState = await tmp("host-abort-open-state-")
  const wd = await tmp("host-abort-open-wd-")
  let aborted = false
  let entered!: () => void
  const enteredPromise = new Promise<void>((r) => { entered = r })
  const driver = async (): Promise<AgentDriver> => ({
    id: "test",
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
  const host = createHost({ stateDirectory: dirState, limits: TEST_LIMITS, agent: "test", driver })
  hosts.push(host)
  const adapter = host.register({ id: "open-wait", env: {} })
  const starting = adapter.start({ cwd: wd }).catch(() => {})
  await enteredPromise
  const closed = host.close({ agents: "shutdown" })
  await closed
  expect(aborted).toBe(true)
  await starting
})

test("pending open is aborted by host shutdown", async () => {
  const fake = fakeChildFactory({ holdOpen: true })
  const { host, workdir } = await makeHost(fake)
  const adapter = host.register({ id: "slow", env: {} })
  const starting = adapter.start({ cwd: workdir })
  const closing = host.close({ agents: "shutdown" })
  fake.releaseOpen()
  const startResult = await starting.then(() => "ok" as const, (e) => e as Error)
  await closing
  if (startResult !== "ok") {
    expect(startResult).toBeInstanceOf(Error)
  }
})

test("held first open: concurrent same-id start rejects without closing first", async () => {
  const fake = fakeChildFactory({ holdOpen: true })
  const { host, workdir } = await makeHost(fake)
  const first = host.register({ id: "same-id", env: {} })
  const starting = first.start({ cwd: workdir })
  await tick()
  expect(() => host.register({ id: "same-id", env: { K: "2" } })).toThrow(CoreError)
  expect(fake.closeAttempts).toBe(0)
  fake.releaseOpen()
  await starting
  await first.stop({ mode: "shutdown" })
})

test("failed start + failed cleanup retry does not run prepare until cleanup succeeds", async () => {
  let allowClose = false
  let prepareCalls = 0
  let opens = 0
  const driver = async (): Promise<AgentDriver> => ({
    id: "test",
    async open(ctx: DriverContext) {
      const n = ++opens
      if (n === 1) ctx.onExit(new Error("setup failed"))
      return {
        agentSessionId: ctx.resumeId ?? "native-" + n,
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        prompt: async () => ({ stopReason: "end_turn" as const }),
        interrupt: async () => {},
        close: async () => { if (n === 1 && !allowClose) throw new Error("close failed") },
      }
    },
  })
  const stateDirectory = await tmp("host-order-state-")
  const workdir = await tmp("host-order-wd-")
  const host = createHost({
    stateDirectory,
    limits: TEST_LIMITS,
    agent: "test",
    driver,
    prepare: async () => { prepareCalls++ },
  })
  hosts.push(host)
  const first = host.register({ id: "cleanup-order", env: {} })
  await expect(first.start({ cwd: workdir })).rejects.toBeInstanceOf(Error)
  const afterFail = prepareCalls
  await expect(first.start({ cwd: workdir })).rejects.toBeInstanceOf(Error)
  expect(prepareCalls).toBe(afterFail)
  allowClose = true
  await first.start({ cwd: workdir })
  expect(prepareCalls).toBe(afterFail + 1)
})

test("failed start + failed cleanup retains the slot and is retried before prepare on the next start", async () => {
  let allowClose = false
  let prepareCalls = 0
  const opens: DriverContext[] = []
  const driver = async (): Promise<AgentDriver> => ({
    id: "test",
    async open(ctx) {
      opens.push(ctx)
      if (opens.length === 1) ctx.onExit(new Error("setup failed"))
      return {
        agentSessionId: ctx.resumeId ?? "native",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        async prompt() { return { stopReason: "end_turn" as const } },
        async interrupt() {},
        async close() { if (opens.length === 1 && !allowClose) throw new Error("still alive") },
      }
    },
  })
  const stateDirectory = await tmp("host-prep-state-")
  const workdir = await tmp("host-prep-wd-")
  const host = createHost({
    stateDirectory,
    limits: TEST_LIMITS,
    agent: "test",
    driver,
    prepare: async () => { prepareCalls++ },
  })
  hosts.push(host)
  const first = host.register({ id: "persist-fail-id", env: {} })
  await expect(first.start({ cwd: workdir })).rejects.toBeInstanceOf(Error)
  const preparesAfterFail = prepareCalls
  allowClose = true
  const session = await first.start({ cwd: workdir })
  expect(session).toBeDefined()
  expect(opens).toHaveLength(2)
  expect(prepareCalls).toBeGreaterThan(preparesAfterFail)
})

/** Driver whose first open fails and whose close fails until `allow()`, or for
 * the first `failCloses` attempts. Models "start handshake failed, then the
 * process refused to die" — the failed-cleanup slot state. Note the core makes
 * its own close attempt on a failed open before the host's cleanup runs, so
 * reaching failed-cleanup needs two counted failures. */
function failingFirstOpen(options: { failCloses?: number } = {}) {
  let allowClose = options.failCloses !== undefined
  let closeAttempts = 0
  const opens: DriverContext[] = []
  const driver = async (): Promise<AgentDriver> => ({
    id: "test",
    async open(ctx) {
      opens.push(ctx)
      if (opens.length === 1) ctx.onExit(new Error("setup failed"))
      return {
        agentSessionId: ctx.resumeId ?? "native",
        capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true },
        async prompt() { return { stopReason: "end_turn" as const } },
        async interrupt() {},
        async close() {
          closeAttempts++
          if (options.failCloses !== undefined && closeAttempts <= options.failCloses) throw new Error("close failed")
          if (!allowClose) throw new Error("still alive")
        },
      }
    },
  })
  return { driver, opens, get closeAttempts() { return closeAttempts }, allow() { allowClose = true } }
}

test("persistent close failure then allow: recovering stop must not drop the slot before admission", async () => {
  const fake = failingFirstOpen()
  const stateDirectory = await tmp("host-persist-state-")
  const workdir = await tmp("host-persist-wd-")
  const host = createHost({ stateDirectory, limits: TEST_LIMITS, agent: "test", driver: fake.driver })
  hosts.push(host)
  const first = host.register({ id: "persist-fail-id", env: {} })
  await expect(first.start({ cwd: workdir })).rejects.toThrow(/cleanup failed: still alive/)
  // Recovery attempt while the process still refuses to die: the recovering
  // stop fails again and the slot must stay failed-cleanup, not be dropped.
  const second = host.register({ id: "persist-fail-id", env: {} })
  await expect(second.start({ cwd: workdir })).rejects.toThrow(/still alive/)
  expect(fake.opens).toHaveLength(1)
  fake.allow()
  const third = host.register({ id: "persist-fail-id", env: {} })
  const session = await third.start({ cwd: workdir })
  expect(session.id).toBe("persist-fail-id")
  expect(fake.opens).toHaveLength(2)
  await third.stop({ mode: "shutdown" })
})

test("concurrent failed-cleanup retries cannot both recover", async () => {
  const fake = failingFirstOpen({ failCloses: 2 })
  const stateDirectory = await tmp("host-race-state-")
  const workdir = await tmp("host-race-wd-")
  const host = createHost({ stateDirectory, limits: TEST_LIMITS, agent: "test", driver: fake.driver })
  hosts.push(host)
  const owner = host.register({ id: "race-id", env: {} })
  await expect(owner.start({ cwd: workdir })).rejects.toThrow(/cleanup failed: close failed/)
  // Same-handle concurrent starts coalesce on `handle.starting`; the race the
  // broker guards against is the failed owner vs a takeover registration.
  const takeover = host.register({ id: "race-id", env: {} })
  const [a, b] = await Promise.allSettled([
    owner.start({ cwd: workdir }),
    takeover.start({ cwd: workdir }),
  ])
  const fulfilled = [a, b].filter((r) => r.status === "fulfilled")
  const rejected = [a, b].filter((r) => r.status === "rejected")
  expect(fulfilled).toHaveLength(1)
  expect(rejected).toHaveLength(1)
  expect(String((rejected[0] as PromiseRejectedResult).reason)).toMatch(/already awaiting failed-start cleanup|already starting|already live/)
  expect(fake.opens).toHaveLength(2)
  const winner = a.status === "fulfilled" ? owner : takeover
  const loser = winner === owner ? takeover : owner
  await winner.stop({ mode: "shutdown" })
  const replacement = host.register({ id: "race-id", env: {} })
  await replacement.start({ cwd: workdir })
  const closesBeforeStale = fake.closeAttempts
  await winner.stop({ mode: "shutdown" })
  await loser.stop({ mode: "shutdown" })
  expect(fake.closeAttempts).toBe(closesBeforeStale)
  expect(replacement.session?.snapshot().state).not.toBe("closed")
  await replacement.stop({ mode: "shutdown" })
})

test("independent hosts and sibling ids are unaffected by another session's admission", async () => {
  const a = fakeChildFactory()
  const b = fakeChildFactory()
  const hostA = (await makeHost(a)).host
  const hostB = (await makeHost(b)).host
  const workdir = await tmp("host-iso-wd-")
  const h1 = hostA.register({ id: "id-shared", env: {} })
  const h2 = hostB.register({ id: "id-shared", env: {} })
  const h3 = hostA.register({ id: "id-sibling", env: {} })
  await Promise.all([
    h1.start({ cwd: workdir }),
    h2.start({ cwd: workdir }),
    h3.start({ cwd: workdir }),
  ])
  expect(a.opens.map((o) => o.sessionId).sort()).toEqual(["id-shared", "id-sibling"])
  expect(b.opens.map((o) => o.sessionId)).toEqual(["id-shared"])
})

test("host.core is available for subscribe", async () => {
  const { host, workdir } = await makeHost()
  const seen: string[] = []
  host.core.subscribe((event) => { seen.push(event.type) })
  const handle = host.register({ id: "sub", env: {} })
  await handle.start({ cwd: workdir })
  expect(seen).toContain("session.created")
})

test("prepare may return an env patch that the driver sees; the registration's env is otherwise untouched", async () => {
  const fake = fakeChildFactory()
  const { host, workdir } = await makeHost(fake, {
    prepare: async (registration) => {
      expect(registration.env).toEqual({ SEED: "1" })
      return { env: { ...registration.env, GROK_TOKEN: "from-prepare" } }
    },
  })
  const handle = host.register({ id: "prep-env", env: { SEED: "1" } })
  await handle.start({ cwd: workdir })
  expect(fake.registrations[0]?.env).toEqual({ SEED: "1", GROK_TOKEN: "from-prepare" })
})

test("prepare may return args that the driver sees", async () => {
  const fake = fakeChildFactory()
  const { host, workdir } = await makeHost(fake, {
    prepare: async () => ({ args: ["--plugin-dir", "/p", "--add-dir", "/a"] }),
  })
  const handle = host.register({ id: "prep-args", env: {}, args: ["seed"] })
  await handle.start({ cwd: workdir })
  expect(fake.registrations[0]?.args).toEqual(["--plugin-dir", "/p", "--add-dir", "/a"])
})

test("onOpened runs inside the start: its rejection is a failed start and the session is closed", async () => {
  const fake = fakeChildFactory()
  const { host, workdir } = await makeHost(fake)
  const handle = host.register({ id: "opened-fail", env: {} })
  let seen: string | undefined
  await expect(handle.start({
    cwd: workdir,
    onOpened: async (session) => { seen = session.snapshot().agentSessionId; throw new Error("persist failed") },
  })).rejects.toThrow(/persist failed/)
  expect(seen).toBe("native-1")
  expect(fake.closes).toBe(1)
  expect(handle.session).toBeUndefined()
  // The id is free again: a fresh registration starts cleanly.
  const again = host.register({ id: "opened-fail", env: {} })
  await again.start({ cwd: workdir, onOpened: async () => {} })
  expect(fake.opens).toHaveLength(2)
})

test("failed stop of a live handle becomes failed-cleanup: a takeover registration retries the cleanup before opening", async () => {
  const fake = fakeChildFactory({ failCloses: 1 })
  let prepares = 0
  const { host, workdir } = await makeHost(fake, { prepare: async () => { prepares++ } })
  const first = host.register({ id: "stop-fail", env: {} })
  await first.start({ cwd: workdir })
  await expect(first.stop({ mode: "shutdown" })).rejects.toThrow(/close failed/)
  const takeover = host.register({ id: "stop-fail", env: {} })
  await takeover.start({ cwd: workdir })
  expect(fake.closeAttempts).toBe(2)
  expect(fake.closes).toBe(1)
  expect(fake.opens).toHaveLength(2)
  expect(prepares).toBe(2)
  // The old handle's stop is a no-op now; the takeover's session survives it.
  await first.stop({ mode: "shutdown" })
  expect(fake.closeAttempts).toBe(2)
  expect(takeover.session?.snapshot().state).not.toBe("closed")
})
