import { afterEach, describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, existsSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { applyConfig, commandContext, resumeCodexSession, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import { createCodexCoreHost, type CodexCoreHost } from "./core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { CodexOptions } from "../../../../packages/supermux-core/src/codex/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../../shared/agents"
import { openDb, runMigrations } from "../../storage/db"
import { Registry } from "../../session-manager/registry"
import { CoreCodexAdapter } from "./core-adapter"

const ctx = (adapter?: unknown): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  adapter: adapter as ApplyConfigCtx["adapter"],
})

const row = { id: "s1", workdir: "/tmp" }

function fakeChildFactory(options: {
  nativeId?: string
  failOpens?: number
  failCloses?: number
  configureBusy?: boolean
  configureFail?: boolean
  holdFirstOpen?: Promise<void>
} = {}) {
  const opens: DriverContext[] = []
  const codexCalls: { options: CodexOptions; overrides: SessionConfiguration }[] = []
  let closes = 0
  let closeAttempts = 0
  let openAttempts = 0
  let liveConfig: SessionConfiguration = {}

  const factory = (gopts: CodexOptions, overrides: SessionConfiguration): AgentDriver => {
    codexCalls.push({ options: { ...gopts, env: { ...gopts.env }, args: [...gopts.args] }, overrides: { ...overrides } })
    return {
      id: "codex",
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

  return { factory, opens, codexCalls, get closes() { return closes }, get closeAttempts() { return closeAttempts }, get openAttempts() { return openAttempts } }
}

const hosts: CodexCoreHost[] = []
const dirs: string[] = []
const prevKey = process.env.OPENAI_API_KEY

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../../storage/migrations"))
  return new Registry(db)
}

async function makeHost(factory: ReturnType<typeof fakeChildFactory>["factory"]) {
  const dir = mkdtempSync(join(tmpdir(), "mux-codex-core-"))
  dirs.push(dir)
  const host = createCodexCoreHost({ stateDirectory: dir, driverFactory: factory })
  hosts.push(host)
  return host
}

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close().catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
  if (prevKey === undefined) delete process.env.OPENAI_API_KEY
  else process.env.OPENAI_API_KEY = prevKey
})

describe("codex applyConfig dialect (Core)", () => {
  test("no live adapter → the exact error", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { model: "gpt-5" })
    expect(r).toEqual({ ok: false, error: "codex session has no live adapter" })
  })

  test("CoreCodexAdapter setConfiguration session_busy is typed busy, native errors are not", async () => {
    const busyAdapter = {
      setConfiguration: async () => { throw new CoreError("session_busy", "Session is busy") },
    }
    const busy = await applyConfig(ctx(busyAdapter), row, "n", { model: "gpt-5" })
    expect(busy).toEqual({ ok: false, busy: true })

    const failAdapter = {
      setConfiguration: async () => { throw new Error("native configure failed") },
    }
    const failed = await applyConfig(ctx(failAdapter), row, "n", { model: "x" })
    expect(failed).toEqual({ ok: false, error: "native configure failed" })
  })
})

describe("codex core spawn/resume dialect", () => {
  test("create registers the row before the native-id callback", async () => {
    process.env.OPENAI_API_KEY = "test-key"
    const child = fakeChildFactory({ nativeId: "native-new" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-wd-"))
    dirs.push(workdir)
    const order: string[] = []
    await spawn({
      registry: reg,
      bind: async () => { order.push("bind") },
      tmuxSession: "mux",
      codexHost: host,
      onThreadId: (name, sid) => {
        order.push(`persist:${sid}`)
        expect(reg.resolveName(name)?.id).toBeDefined()
      },
    }, {
      workdir,
      requestedName: "cx-create",
      agent: AgentKind.Codex,
      id: "broker-id-1",
    })
    expect(order[0]).toBe("bind")
    expect(order).toContain("persist:native-new")
    expect(reg.get("broker-id-1")?.id).toBe("broker-id-1")
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  test("resume of an existing native id adopts then exact-resumes (no new thread fallback)", async () => {
    process.env.OPENAI_API_KEY = "test-key"
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-wd-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-codex-home-"))
    dirs.push(sessionHome)

    const { adapter } = await resumeCodexSession(
      { codexHost: host },
      {
        id: "existing-row",
        name: "cx-resume",
        workdir,
        agent_home: sessionHome,
        agent_session_id: "native-keep",
        model: "gpt-5",
      },
    )
    expect(adapter).toBeInstanceOf(CoreCodexAdapter)
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
    expect(child.opens[0]?.sessionId).toBe("existing-row")
  })

  test("failed load does not mint a new thread", async () => {
    process.env.OPENAI_API_KEY = "test-key"
    const child = fakeChildFactory({ failOpens: 1 })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-wd-"))
    dirs.push(workdir)
    const home = mkdtempSync(join(tmpdir(), "mux-codex-home-"))
    dirs.push(home)
    const session = {
      id: "load-fail",
      name: "cx-load",
      workdir,
      agent_home: home,
      agent_session_id: "native-keep",
    }
    await expect(resumeCodexSession({ codexHost: host }, session)).rejects.toThrow(/open failed/)
    expect(child.opens).toHaveLength(0)
    const second = await resumeCodexSession({ codexHost: host }, session)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
    expect(child.opens[0]?.sessionId).toBe("load-fail")
    await second.adapter.stop()
  })

  test("private CODEX_HOME and auth env reach the driver factory, not Core records", async () => {
    process.env.OPENAI_API_KEY = "secret-live"
    const child = fakeChildFactory({ nativeId: "n1" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-wd-"))
    dirs.push(workdir)
    const result = await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      codexHost: host,
    }, {
      workdir,
      requestedName: "cx-shim",
      agent: AgentKind.Codex,
    })
    const sessionHome = reg.get(result.session_id)!.agent_home!
    expect(child.codexCalls[0]?.options.env?.CODEX_HOME).toBe(sessionHome)
    expect(child.codexCalls[0]?.options.env?.OPENAI_API_KEY).toBe("secret-live")
    const stateTexts = JSON.stringify(child.opens)
    expect(stateTexts).not.toContain("secret-live")
    const toml = readFileSync(join(sessionHome, "config.toml"), "utf8")
    expect(toml).toContain("mux-shim")
    expect(existsSync(join(sessionHome, "AGENTS.md")) || existsSync(join(workdir, "AGENTS.md"))).toBe(true)
  })

  test("command/args reach the driver factory with plugin flags and without -c model", async () => {
    process.env.OPENAI_API_KEY = "test-key"
    const child = fakeChildFactory({ nativeId: "n1" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-wd-"))
    dirs.push(workdir)
    await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      codexHost: host,
    }, {
      workdir,
      requestedName: "cx-args",
      agent: AgentKind.Codex,
      model: "gpt-5.2-codex",
      effort: "high",
    })
    const args = child.codexCalls[0]?.options.args ?? []
    expect(args[0]).toBe("app-server")
    expect(args).toContain('approval_policy="never"')
    expect(args).toContain('sandbox_mode="danger-full-access"')
    expect(args.join(" ")).not.toContain("model=")
    expect(args.join(" ")).not.toContain("model_reasoning_effort")
    expect(child.codexCalls[0]?.options.command).toBeTruthy()
  })

  test("failed start + failed cleanup retains host registration; retry stop then create", async () => {
    process.env.OPENAI_API_KEY = "test-key"
    const child = fakeChildFactory({ failCloses: 1 })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-wd-"))
    dirs.push(workdir)
    const home = mkdtempSync(join(tmpdir(), "mux-codex-home-"))
    dirs.push(home)
    const session = {
      id: "retry-id",
      name: "cx-retry",
      workdir,
      agent_home: home,
    }
    await expect(resumeCodexSession({
      codexHost: host,
      onThreadId: () => { throw new Error("persist failed") },
    }, session)).rejects.toThrow(/persist failed; cleanup failed/)
    await expect(resumeCodexSession({ codexHost: host }, session)).resolves.toMatchObject({ adapter: expect.any(CoreCodexAdapter) })
  })

  test("held first open: concurrent same-id resume rejects without closing first or opening second", async () => {
    process.env.OPENAI_API_KEY = "test-key"
    let releaseOpen!: () => void
    const holdFirstOpen = new Promise<void>((r) => { releaseOpen = r })
    const child = fakeChildFactory({ holdFirstOpen })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-codex-wd-"))
    dirs.push(workdir)
    const home = mkdtempSync(join(tmpdir(), "mux-codex-home-"))
    dirs.push(home)
    const session = { id: "same-id", name: "cx-adm", workdir, agent_home: home, agent_session_id: "native-keep" }

    const first = resumeCodexSession({ codexHost: host }, session)
    void first.catch(() => {})
    const waitUntil = Date.now() + 10_000 // auth copy + home preparation before open take ~2.5 s on a loaded host
    while (child.openAttempts < 1 && Date.now() < waitUntil) await new Promise((r) => setTimeout(r, 10))
    expect(child.openAttempts).toBe(1)
    writeFileSync(join(home, "SENTINEL"), "SENTINEL", "utf8")

    const second = resumeCodexSession({ codexHost: host }, session)
    await expect(second).rejects.toThrow(/already starting/)
    expect(readFileSync(join(home, "SENTINEL"), "utf8")).toBe("SENTINEL")
    expect(child.closeAttempts).toBe(0)
    expect(child.openAttempts).toBe(1)

    releaseOpen()
    const { adapter } = await first
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
    await adapter.stop()
  })

  test("commandContext returns adapter.rpc", () => {
    const rpc = { request: async <T = unknown>(_m?: string, _p?: unknown): Promise<T> => ({} as T), onNotification: () => {} }
    expect(commandContext({ sessionName: "cx", adapter: { rpc } })).toBe(rpc)
  })
})
