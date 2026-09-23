import { afterEach, describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, existsSync, rmSync, writeFileSync, mkdirSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { applyConfig, resumeGrokSession, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import { createGrokCoreHost, type GrokCoreHost } from "./core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { GrokOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../../shared/agents"
import { openDb, runMigrations } from "../../storage/db"
import { Registry } from "../../session-manager/registry"
import { CoreAdapter } from "../core-bridge/core-adapter"

const ctx = (adapter?: CoreAdapter): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  adapter,
})

const row = { id: "s1", workdir: "/tmp" }

function adapterWith(model?: string): { adapter: CoreAdapter; patches: { model?: string; effort?: string }[] } {
  const patches: { model?: string; effort?: string }[] = []
  const adapter = {
    kind: "grok" as const,
    model,
    effort: undefined as string | undefined,
    async setConfiguration(patch: { model?: string; effort?: string }) {
      if ("model" in patch) this.model = patch.model
      if ("effort" in patch) this.effort = patch.effort
      patches.push(patch)
    },
  }
  return { adapter: adapter as unknown as CoreAdapter, patches }
}

describe("grok applyConfig dialect", () => {
  test("no live adapter → the exact error", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { model: "grok-4" })
    expect(r).toEqual({ ok: false, error: "grok session has no live adapter" })
  })

  test("model half applies live; a masked effort half is untouched", async () => {
    const { adapter, patches } = adapterWith("grok-4")
    const r = await applyConfig(ctx(adapter), row, "n", {
      model: "grok-4-fast", effort: "high", changed: { model: true, effort: false },
    })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("grok-4-fast")
    expect(patches).toEqual([{ model: "grok-4-fast" }])
  })

  test("effort half goes through setConfiguration; a masked model half is untouched", async () => {
    const { adapter, patches } = adapterWith("grok-4")
    const r = await applyConfig(ctx(adapter), row, "n", {
      model: "grok-4-fast", effort: "low", changed: { model: false, effort: true },
    })
    expect(r).toEqual({ ok: true })
    expect(adapter.model).toBe("grok-4")
    expect(patches).toEqual([{ effort: "low" }])
  })
})

function fakeChildFactory(options: {
  nativeId?: string
  failOpens?: number
  failCloses?: number
  configureBusy?: boolean
  configureFail?: boolean
  holdFirstOpen?: Promise<void>
} = {}) {
  const opens: DriverContext[] = []
  const grokCalls: { options: GrokOptions; overrides: SessionConfiguration }[] = []
  let closes = 0
  let closeAttempts = 0
  let openAttempts = 0
  let liveConfig: SessionConfiguration = {}

  const factory = (gopts: GrokOptions, overrides: SessionConfiguration): AgentDriver => {
    grokCalls.push({ options: { ...gopts, env: { ...gopts.env } }, overrides: { ...overrides } })
    return {
      id: "grok",
      async open(ctx) {
        openAttempts++
        if (openAttempts === 1 && options.holdFirstOpen) await options.holdFirstOpen
        if (openAttempts <= (options.failOpens ?? 0)) throw new Error("open failed")
        opens.push(ctx)
        if (ctx.configuration) liveConfig = { ...ctx.configuration }
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
          capabilities: {
            resume: true, steer: false, fork: false, detach: false,
            configure: true, history: false,
          },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {
            closeAttempts++
            if (closeAttempts <= (options.failCloses ?? 0)) throw new Error("close failed")
            closes++
          },
          async configure(configuration) {
            if (options.configureBusy) throw new CoreError("session_busy", "Session is busy")
            if (options.configureFail) throw new Error("native configure failed")
            liveConfig = { ...configuration }
          },
          configuration: () => ({ ...liveConfig }),
        }
        return runtime
      },
    }
  }

  return { factory, opens, grokCalls, get closes() { return closes }, get closeAttempts() { return closeAttempts }, get openAttempts() { return openAttempts } }
}

const hosts: GrokCoreHost[] = []
const dirs: string[] = []

async function makeHost(factory: ReturnType<typeof fakeChildFactory>["factory"]) {
  const dir = mkdtempSync(join(tmpdir(), "mux-grok-core-"))
  dirs.push(dir)
  const host = createGrokCoreHost({ stateDirectory: dir, driverFactory: factory })
  hosts.push(host)
  return host
}

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../../storage/migrations"))
  return new Registry(db)
}

describe("grok core spawn/resume dialect", () => {
  test("create registers the row before the native-id callback", async () => {
    const child = fakeChildFactory({ nativeId: "native-new" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const order: string[] = []
    await spawn({
      registry: reg,
      bind: async () => { order.push("bind") },
      tmuxSession: "mux",
      grokHost: host,
      onGrokSessionId: (name, sid) => {
        order.push(`persist:${sid}`)
        expect(reg.resolveName(name)?.id).toBeDefined()
      },
    }, {
      workdir,
      requestedName: "gk-create",
      agent: AgentKind.Grok,
      id: "broker-id-1",
    })
    expect(order[0]).toBe("bind")
    expect(order).toContain("persist:native-new")
    expect(reg.get("broker-id-1")?.id).toBe("broker-id-1")
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  test("resume of an existing native id adopts then exact-resumes (no session/new fallback)", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    dirs.push(sessionHome)

    const { adapter } = await resumeGrokSession(
      { grokHost: host },
      {
        id: "existing-row",
        name: "gk-resume",
        workdir,
        agent_home: sessionHome,
        agent_session_id: "native-keep",
        model: "grok-4",
      },
    )
    expect(adapter).toBeInstanceOf(CoreAdapter)
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
    expect(child.opens[0]?.sessionId).toBe("existing-row")
  })

  test("private HOME env and config.toml shim are preserved", async () => {
    const child = fakeChildFactory({ nativeId: "n1" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const result = await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      grokHost: host,
    }, {
      workdir,
      requestedName: "gk-shim",
      agent: AgentKind.Grok,
    })
    const sessionHome = reg.get(result.session_id)!.agent_home!
    expect(child.grokCalls[0]?.options.env?.HOME).toBe(sessionHome)
    expect(child.grokCalls[0]?.options.permissions).toEqual({ kind: "acp", policy: "auto-approve", nativeMode: null })
    expect(child.grokCalls[0]?.options.command).toBe("grok")
    expect(child.grokCalls[0]?.options.noLeader).toBe(false)
    const toml = readFileSync(join(sessionHome, ".grok", "config.toml"), "utf8")
    expect(toml).toContain("[mcp_servers.mux-shim]")
    expect(existsSync(join(workdir, "AGENTS.md"))).toBe(true)
  })

  test("CoreAdapter setConfiguration session_busy is typed busy, native errors are not", async () => {
    const busyAdapter = {
      setConfiguration: async () => { throw new CoreError("session_busy", "Session is busy") },
    }
    const busy = await applyConfig({ ...ctx(), adapter: busyAdapter as never }, row, "n", { model: "grok-4-fast" })
    expect(busy).toEqual({ ok: false, busy: true })

    const failAdapter = {
      setConfiguration: async () => { throw new Error("native configure failed") },
    }
    const failed = await applyConfig({ ...ctx(), adapter: failAdapter as never }, row, "n", { model: "x" })
    expect(failed).toEqual({ ok: false, error: "native configure failed" })
  })

  test("held first open: concurrent same-id resume rejects without closing first or opening second", async () => {
    let releaseOpen!: () => void
    const holdFirstOpen = new Promise<void>((r) => { releaseOpen = r })
    const child = fakeChildFactory({ holdFirstOpen })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const home = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    dirs.push(home)
    const session = { id: "same-id", name: "gk-adm", workdir, agent_home: home, agent_session_id: "native-keep" }

    const first = resumeGrokSession({ grokHost: host }, session)
    void first.catch(() => {})
    const waitUntil = Date.now() + 2000
    while (child.openAttempts < 1 && Date.now() < waitUntil) await new Promise((r) => setTimeout(r, 10))
    expect(child.openAttempts).toBe(1)
    const grokDir = join(home, ".grok")
    mkdirSync(grokDir, { recursive: true })
    writeFileSync(join(grokDir, "config.toml"), "SENTINEL", "utf8")

    const second = resumeGrokSession({ grokHost: host }, session)
    await expect(second).rejects.toThrow(/already starting|already live/)
    expect(readFileSync(join(grokDir, "config.toml"), "utf8")).toBe("SENTINEL")
    expect(child.closeAttempts).toBe(0)
    expect(child.openAttempts).toBe(1)

    releaseOpen()
    const { adapter } = await first
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
    await adapter.stop()
  })

  test("user stop of a healthy adapter permits a later resume with a new handle", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const home = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    dirs.push(home)
    const session = { id: "reuse-id", name: "gk-reuse", workdir, agent_home: home, agent_session_id: "native-keep" }
    const first = await resumeGrokSession({ grokHost: host }, session)
    await first.adapter.stop()
    const second = await resumeGrokSession({ grokHost: host }, session)
    expect(second.adapter).not.toBe(first.adapter)
    expect(child.opens).toHaveLength(2)
    expect(child.opens[1]?.resumeId).toBe("native-keep")
    await second.adapter.stop()
  })

  test("failed start + failed cleanup retry does not rewrite config until cleanup succeeds", async () => {
    const child = fakeChildFactory({ failCloses: 5 })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const home = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    dirs.push(home)
    const session = { id: "cleanup-order", name: "gk-order", workdir, agent_home: home }
    await expect(resumeGrokSession({
      grokHost: host,
      onGrokSessionId: () => { throw new Error("persist failed") },
    }, session)).rejects.toThrow(/persist failed; cleanup failed/)
    const toml = join(home, ".grok", "config.toml")
    writeFileSync(toml, "SENTINEL", "utf8")
    await expect(resumeGrokSession({ grokHost: host }, session)).rejects.toThrow(/close failed/)
    expect(readFileSync(toml, "utf8")).toBe("SENTINEL")
  })

  test("registry failure after allocation allows retry of the same id", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const reg = registry()
    const orig = reg.register.bind(reg)
    let blows = 1
    reg.register = ((row: Parameters<Registry["register"]>[0]) => {
      if (blows-- > 0) throw new Error("register exploded")
      return orig(row)
    }) as typeof reg.register
    await expect(spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      grokHost: host,
    }, {
      workdir,
      requestedName: "gk-reg",
      agent: AgentKind.Grok,
      id: "fixed-id",
    })).rejects.toThrow(/register exploded/)
    const result = await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      grokHost: host,
    }, {
      workdir,
      requestedName: "gk-reg2",
      agent: AgentKind.Grok,
      id: "fixed-id",
    })
    expect(result.session_id).toBe("fixed-id")
    expect(child.opens).toHaveLength(1)
  })

  test("concurrent failed-cleanup retries cannot both recover or overwrite private home", async () => {
    const child = fakeChildFactory({ failCloses: 1 })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const home = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    dirs.push(home)
    const session = { id: "race-id", name: "gk-race", workdir, agent_home: home }
    await expect(resumeGrokSession({
      grokHost: host,
      onGrokSessionId: () => { throw new Error("persist failed") },
    }, session)).rejects.toThrow(/persist failed; cleanup failed/)
    const toml = join(home, ".grok", "config.toml")
    writeFileSync(toml, "SENTINEL", "utf8")
    const [a, b] = await Promise.allSettled([
      resumeGrokSession({ grokHost: host }, session),
      resumeGrokSession({ grokHost: host }, session),
    ])
    const fulfilled = [a, b].filter((r) => r.status === "fulfilled")
    const rejected = [a, b].filter((r) => r.status === "rejected")
    expect(fulfilled).toHaveLength(1)
    expect(rejected).toHaveLength(1)
    expect(String((rejected[0] as PromiseRejectedResult).reason)).toMatch(/already awaiting failed-start cleanup|already starting|already live/)
    const winner = (fulfilled[0] as PromiseFulfilledResult<{ adapter: CoreAdapter }>).value
    expect(readFileSync(toml, "utf8")).not.toBe("SENTINEL")
    await winner.adapter.stop()
    const replacement = await resumeGrokSession({ grokHost: host }, session)
    const closesBeforeStale = child.closeAttempts
    await winner.adapter.stop()
    expect(child.closeAttempts).toBe(closesBeforeStale)
    await replacement.adapter.stop()
  })

  test("independent hosts and sibling ids are unaffected by another session's admission", async () => {
    const a = fakeChildFactory()
    const b = fakeChildFactory()
    const hostA = await makeHost(a.factory)
    const hostB = await makeHost(b.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-grok-wd-"))
    dirs.push(workdir)
    const home1 = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    const home2 = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    const home3 = mkdtempSync(join(tmpdir(), "mux-grok-home-"))
    dirs.push(home1, home2, home3)
    const s1 = { id: "id-shared", name: "gk-a", workdir, agent_home: home1, agent_session_id: "n-a" }
    const s2 = { id: "id-shared", name: "gk-b", workdir, agent_home: home2, agent_session_id: "n-b" }
    const s3 = { id: "id-sibling", name: "gk-sib", workdir, agent_home: home3, agent_session_id: "n-c" }
    const [r1, r2, r3] = await Promise.all([
      resumeGrokSession({ grokHost: hostA }, s1),
      resumeGrokSession({ grokHost: hostB }, s2),
      resumeGrokSession({ grokHost: hostA }, s3),
    ])
    expect(a.opens).toHaveLength(2)
    expect(b.opens).toHaveLength(1)
    await r1.adapter.stop()
    await r2.adapter.stop()
    await r3.adapter.stop()
  })
})
