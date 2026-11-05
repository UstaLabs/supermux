import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { mkdtempSync, existsSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { applyConfig, resumeCursorSession, resume, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import { createCursorCoreHost, type CursorCoreHost } from "./core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { CursorOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../../shared/agents"
import { openDb, runMigrations } from "../../storage/db"
import { Registry } from "../../session-manager/registry"
import { CoreAdapter } from "../core-bridge/core-adapter"
import { AccountRegistry, UsageStore } from "../../../../packages/supermux-core/src/accounts/index.js"
import { memoryVault } from "../../../../packages/supermux-core/src/index.js"
import { ACCOUNT_AGENTS, resetSharedAccountsForTests, setAccountsChangedListener } from "../../accounts/broker-accounts"

const ctx = (adapter?: CoreAdapter): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  adapter,
})

const row = { id: "s1", workdir: "/tmp" }

function fakeChildFactory(options: { nativeId?: string; unloadableIds?: string[] } = {}) {
  const opens: DriverContext[] = []
  const ocCalls: { options: CursorOptions; overrides: SessionConfiguration }[] = []
  const factory = (gopts: CursorOptions, overrides: SessionConfiguration): AgentDriver => {
    ocCalls.push({ options: { ...gopts, env: { ...gopts.env } }, overrides: { ...overrides } })
    return {
      id: "cursor",
      async open(ctx) {
        if (ctx.resumeId && options.unloadableIds?.includes(ctx.resumeId)) throw new Error("Invalid params")
        opens.push(ctx)
        const runtime: AgentRuntime = {
          agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
          capabilities: {
            resume: true, steer: false, fork: false, detach: false,
            configure: false, history: false, permissions: true,
          },
          async prompt() { return { stopReason: "end_turn" } },
          async interrupt() {},
          async close() {},
          async configure() {},
          async setPermissions() { return { applied: "now" as const } },
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
  // Per TEST, not once at collection: the restore below runs after every test, so a key set
  // only in the describe body was gone from the second test on, which then fell through to the
  // developer's real ~/.config/cursor/auth.json (and failed on CI, which has none).
  const prevKey = process.env.CURSOR_API_KEY
  beforeEach(() => { process.env.CURSOR_API_KEY = "test-key" })
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

  test("default uses auto-approve; setPermissionMode(ask) is live", async () => {
    const child = fakeChildFactory({ nativeId: "native-prompts" })
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
      requestedName: "cur-prompts",
      agent: AgentKind.Cursor,
      id: "broker-id-prompts",
    })
    expect(child.ocCalls[0]?.options.permissions).toEqual({ kind: "acp", policy: "auto-approve", nativeMode: "agent" })
    await adapter!.setPermissionMode("ask")
    expect(child.opens).toHaveLength(1)
  })

  // A per-turn-era chat id is not an ACP session: the agent answers "Invalid
  // params" on session/load. The row must come back as a fresh conversation
  // (same name/home), not stay dead.
  test("an unloadable native id falls back to a fresh session under the same row", async () => {
    const child = fakeChildFactory({ unloadableIds: ["old-chat-id"] })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-cur-home-"))
    dirs.push(sessionHome)
    const persisted: string[] = []
    const { adapter } = await resumeCursorSession(
      { cursorHost: host, onCursorSessionId: (_n, sid) => persisted.push(sid) },
      { id: "legacy-row", name: "cur-legacy", workdir, agent_home: sessionHome, agent_session_id: "old-chat-id", model: "composer-1" },
    )
    expect(adapter).toBeInstanceOf(CoreAdapter)
    const opened = child.opens.filter((o) => o.sessionId === "legacy-row")
    expect(opened.at(-1)?.resumeId).toBeUndefined()
    expect(persisted).toHaveLength(1)
    expect(persisted[0]).not.toBe("old-chat-id")
    await adapter.stop()
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

// What cursor-agent's ACP server answers session/new and session/load with once its login expired or was
// revoked (captured 2026-11-04 with an expired token): JSON-RPC -32000, ACP's auth_required.
function authRequired(): Error {
  return Object.assign(new Error("Authentication required"), {
    name: "RequestError",
    code: -32000,
    data: { message: "Authentication required. Please run 'agent login' first, then call authenticate() with methodId 'cursor_login'." },
  })
}

function refusingFactory(error: () => Error) {
  const opens: DriverContext[] = []
  const factory = (_gopts: CursorOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "cursor",
    async open(ctx) { opens.push(ctx); throw error() },
  })
  return { factory, opens }
}

describe("a login Cursor refuses", () => {
  let usage: UsageStore
  let changed = 0
  beforeEach(() => {
    const dir = mkdtempSync(join(tmpdir(), "mux-cur-accounts-"))
    dirs.push(dir)
    const registry = new AccountRegistry(join(dir, "state"), [...ACCOUNT_AGENTS], { vault: memoryVault() })
    usage = new UsageStore(join(registry.directory, "usage.json"))
    resetSharedAccountsForTests({ registry, usage })
    changed = 0
    setAccountsChangedListener(() => { changed++ })
  })
  afterEach(() => {
    resetSharedAccountsForTests()
    setAccountsChangedListener(() => {})
  })

  test("a new chat fails as account_expired in plain words and marks the account its login is refused on", async () => {
    const child = refusingFactory(authRequired)
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    const failure = await spawn({ registry: registry(), bind: async () => {}, tmuxSession: "mux", cursorHost: host }, {
      workdir, requestedName: "cur-expired", agent: AgentKind.Cursor, id: "broker-id-expired",
    }).then(() => undefined, (err: unknown) => err as Error & { code?: string })
    expect(failure?.code).toBe("account_expired")
    expect(failure?.message).toBe("Cursor sign-in expired — sign in to Cursor again.")
    expect(usage.unavailable("cursor:system")).toEqual({ reason: "Cursor sign-in expired — sign in to Cursor again.", since: expect.any(Date), code: "account_expired" })
    // Every client reloads its accounts once: Settings, the chat's account pill.
    expect(changed).toBe(1)
  })

  test("a resume fails the same way, and does not start a fresh conversation in its place", async () => {
    const child = refusingFactory(authRequired)
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-cur-home-"))
    dirs.push(sessionHome)
    const failure = await resumeCursorSession({ cursorHost: host }, {
      id: "row-expired", name: "cur-expired", workdir, agent_home: sessionHome, agent_session_id: "native-1",
    }).then(() => undefined, (err: unknown) => err as Error & { code?: string })
    expect(failure?.code).toBe("account_expired")
    expect(child.opens.map((o) => o.resumeId)).toEqual(["native-1"])
    expect(usage.unavailable("cursor:system")?.code).toBe("account_expired")
  })

  test("any other failure to open passes through as it was, and marks nothing", async () => {
    const child = refusingFactory(() => Object.assign(new Error("Internal error"), { code: -32603, data: { message: "boom" } }))
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-cur-wd-"))
    dirs.push(workdir)
    const failure = await spawn({ registry: registry(), bind: async () => {}, tmuxSession: "mux", cursorHost: host }, {
      workdir, requestedName: "cur-broken", agent: AgentKind.Cursor, id: "broker-id-broken",
    }).then(() => undefined, (err: unknown) => err as Error & { code?: unknown })
    expect(failure?.code).toBe(-32603)
    expect(usage.unavailable("cursor:system")).toBeUndefined()
    expect(changed).toBe(0)
  })
})

// Card #75: a session is born asleep and its first start is a resume of a row that never ran.
test("cursor: resume of a never-started row starts fresh under the row's account", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const accounts: unknown[] = []
    const register = host.register.bind(host)
    host.register = ((r: Parameters<typeof register>[0]) => {
      accounts.push(r.account)
      const { account: _skip, ...rest } = r
      return register(rest)
    }) as typeof host.register
    const workdir = mkdtempSync(join(tmpdir(), "mux-cursor-born-"))
    dirs.push(workdir)
    const { adapter } = await resume(
      { sessionEffort: () => undefined, resolveAttachment: async () => "", persistAgentSessionId: () => {}, cursorHost: host },
      { id: "born-asleep", workdir, agent_home: join(workdir, ".home"), account: "acc-cursor" } as never,
      "born-asleep",
    )
    expect(accounts).toEqual(["acc-cursor"])
    expect(child.opens.at(-1)?.resumeId).toBeUndefined()
    await adapter.stop()
})
