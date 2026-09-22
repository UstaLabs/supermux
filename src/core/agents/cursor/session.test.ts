import { afterEach, describe, expect, test } from "bun:test"
import { mkdtempSync, existsSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { applyConfig, resumeCursorSession, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import { createCursorCoreHost, type CursorCoreHost } from "./core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { CursorOptions } from "../../../../packages/supermux-core/src/cursor/index.js"
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

function fakeChildFactory(options: { nativeId?: string } = {}) {
  const opens: DriverContext[] = []
  const ocCalls: { options: CursorOptions; overrides: SessionConfiguration }[] = []
  const factory = (gopts: CursorOptions, overrides: SessionConfiguration): AgentDriver => {
    ocCalls.push({ options: { ...gopts, env: { ...gopts.env } }, overrides: { ...overrides } })
    return {
      id: "cursor",
      async open(ctx) {
        opens.push(ctx)
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
          capabilities: {
            resume: true, steer: false, fork: false, detach: false,
            configure: false, history: false,
          },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {},
          async configure() {},
          configuration: () => ({}),
        }
        return runtime
      },
    }
  }
  return { factory, opens, ocCalls }
}

const hosts: CursorCoreHost[] = []
const dirs: string[] = []

async function makeHost(factory: ReturnType<typeof fakeChildFactory>["factory"]) {
  const dir = mkdtempSync(join(tmpdir(), "mux-cur-core-"))
  dirs.push(dir)
  const host = createCursorCoreHost({ stateDirectory: dir, driverFactory: factory, smoke: async () => {}, sharedRuntime: null })
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

describe("cursor applyConfig dialect", () => {
  test("effort changes are unsupported", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { effort: "high", changed: { model: false, effort: true } })
    expect(r).toEqual({ ok: false, error: "cursor sessions use model selection for reasoning depth" })
  })

  test("session_busy is typed busy", async () => {
    const busyAdapter = {
      setConfiguration: async () => { throw new CoreError("session_busy", "Session is busy") },
    }
    const busy = await applyConfig({ ...ctx(), adapter: busyAdapter as never }, row, "n", { model: "x/y" })
    expect(busy).toEqual({ ok: false, busy: true })
  })

  test("changed.model === false masks the update", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { model: "x/y", changed: { model: false, effort: false } })
    expect(r).toEqual({ ok: true })
  })
})

describe("cursor core spawn/resume dialect", () => {
  const prevKey = process.env.CURSOR_API_KEY
  process.env.CURSOR_API_KEY = "test-key"

  afterEach(() => {
    if (prevKey === undefined) delete process.env.CURSOR_API_KEY
    else process.env.CURSOR_API_KEY = prevKey
  })

  test("create registers the row before the native-id callback", async () => {
    const child = fakeChildFactory({ nativeId: "native-new" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    const order: string[] = []
    await spawn({
      registry: reg,
      bind: async () => { order.push("bind") },
      tmuxSession: "mux",
      cursorHost: host,
      onCursorSessionId: (name, sid) => {
        order.push(`persist:${sid}`)
        expect(reg.resolveName(name)?.id).toBeDefined()
      },
    }, {
      workdir,
      requestedName: "cur-create",
      agent: AgentKind.Cursor,
      id: "broker-id-1",
    })
    expect(order[0]).toBe("bind")
    expect(order).toContain("persist:native-new")
    expect(reg.get("broker-id-1")?.pid).toBe(0)
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  test("the session model reaches the Cursor driver options on spawn and on a model change", async () => {
    const child = fakeChildFactory({ nativeId: "native-model" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    let adapter: CoreAdapter | undefined
    await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      cursorHost: host,
      registerAdapter: (_name, registered) => { adapter = registered as CoreAdapter },
    }, {
      workdir,
      requestedName: "cur-model",
      agent: AgentKind.Cursor,
      id: "broker-id-model",
      model: "composer-1",
    })
    expect(child.ocCalls.at(-1)?.options.model).toBe("composer-1")
    expect(child.opens).toHaveLength(1)

    const result = await applyConfig(
      { adapter: adapter!, sessionEffort: () => undefined } as unknown as ApplyConfigCtx,
      { id: "broker-id-model", model: "composer-1" } as never,
      "cur-model",
      { model: "gpt-5", changed: { model: true, effort: false } },
    )
    expect(result).toEqual({ ok: true })
    expect(child.opens).toHaveLength(2)
    expect(child.opens[1]?.resumeId).toBe("native-model")
    expect(child.ocCalls.at(-1)?.options.model).toBe("gpt-5")
    expect(adapter!.model).toBe("gpt-5")
  })

  test("resume of an existing native id exact-resumes", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-cur-home-"))
    dirs.push(sessionHome)

    const { adapter } = await resumeCursorSession(
      { cursorHost: host },
      {
        id: "existing-row",
        name: "cur-resume",
        workdir,
        agent_home: sessionHome,
        agent_session_id: "native-keep",
        model: "composer-1",
      },
    )
    expect(adapter).toBeInstanceOf(CoreAdapter)
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
    expect(child.opens[0]?.sessionId).toBe("existing-row")
  })

  test("mcp.json shim is written under the session home", async () => {
    const child = fakeChildFactory({ nativeId: "n1" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    const result = await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      cursorHost: host,
    }, {
      workdir,
      requestedName: "cur-shim",
      agent: AgentKind.Cursor,
    })
    const sessionHome = reg.get(result.session_id)!.agent_home!
    expect(existsSync(join(sessionHome, ".cursor", "mcp.json"))).toBe(true)
    expect(child.ocCalls[0]?.options.env?.HOME).toBe(sessionHome)
  })
})
