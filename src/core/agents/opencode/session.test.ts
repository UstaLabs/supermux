import { afterEach, describe, expect, test } from "bun:test"
import { mkdtempSync, readFileSync, existsSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { applyConfig, resumeOpenCodeSession, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import { createOpenCodeCoreHost, type OpenCodeCoreHost } from "./core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { OpenCodeOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../../shared/agents"
import { openDb, runMigrations } from "../../storage/db"
import { Registry } from "../../session-manager/registry"
import { CoreOpenCodeAdapter } from "./core-adapter"

const ctx = (adapter?: CoreOpenCodeAdapter): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  adapter,
})

const row = { id: "s1", workdir: "/tmp" }

function fakeChildFactory(options: { nativeId?: string } = {}) {
  const opens: DriverContext[] = []
  const ocCalls: { options: OpenCodeOptions; overrides: SessionConfiguration }[] = []
  const factory = (gopts: OpenCodeOptions, overrides: SessionConfiguration): AgentDriver => {
    ocCalls.push({ options: { ...gopts, env: { ...gopts.env } }, overrides: { ...overrides } })
    return {
      id: "opencode",
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

const hosts: OpenCodeCoreHost[] = []
const dirs: string[] = []

async function makeHost(factory: ReturnType<typeof fakeChildFactory>["factory"]) {
  const dir = mkdtempSync(join(tmpdir(), "mux-oc-core-"))
  dirs.push(dir)
  const host = createOpenCodeCoreHost({ stateDirectory: dir, driverFactory: factory })
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

describe("opencode applyConfig dialect", () => {
  test("effort changes are unsupported", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { effort: "high", changed: { model: false, effort: true } })
    expect(r).toEqual({ ok: false, error: "opencode does not support reasoning effort" })
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

describe("opencode core spawn/resume dialect", () => {
  test("create registers the row before the native-id callback", async () => {
    const child = fakeChildFactory({ nativeId: "native-new" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-oc-wd-"))
    dirs.push(workdir)
    const order: string[] = []
    await spawn({
      registry: reg,
      bind: async () => { order.push("bind") },
      tmuxSession: "mux",
      opencodeHost: host,
      onOpenCodeSessionId: (name, sid) => {
        order.push(`persist:${sid}`)
        expect(reg.resolveName(name)?.id).toBeDefined()
      },
    }, {
      workdir,
      requestedName: "oc-create",
      agent: AgentKind.OpenCode,
      id: "broker-id-1",
    })
    expect(order[0]).toBe("bind")
    expect(order).toContain("persist:native-new")
    expect(reg.get("broker-id-1")?.pid).toBe(0)
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  // OpenCode has no live configure: the model is a driver option, so the
  // session layer must hand it to the driver factory on spawn and again, with
  // the same native id, when applyConfig restarts the child for a new model.
  test("the session model reaches the OpenCode driver options on spawn and on a model change", async () => {
    const child = fakeChildFactory({ nativeId: "native-model" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-oc-wd-"))
    dirs.push(workdir)
    let adapter: CoreOpenCodeAdapter | undefined
    await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      opencodeHost: host,
      registerAdapter: (_name, registered) => { adapter = registered as CoreOpenCodeAdapter },
    }, {
      workdir,
      requestedName: "oc-model",
      agent: AgentKind.OpenCode,
      id: "broker-id-model",
      model: "opencode-go/glm-5.3-flash",
    })
    expect(child.ocCalls.at(-1)?.options.model).toBe("opencode-go/glm-5.3-flash")
    expect(child.opens).toHaveLength(1)

    const result = await applyConfig(
      { adapter: adapter!, sessionEffort: () => undefined } as unknown as ApplyConfigCtx,
      { id: "broker-id-model", model: "opencode-go/glm-5.3-flash" } as never,
      "oc-model",
      { model: "opencode-go/deepseek-v4-flash", changed: { model: true, effort: false } },
    )
    expect(result).toEqual({ ok: true })
    expect(child.opens).toHaveLength(2)
    expect(child.opens[1]?.resumeId).toBe("native-model")
    expect(child.ocCalls.at(-1)?.options.model).toBe("opencode-go/deepseek-v4-flash")
    expect(adapter!.model).toBe("opencode-go/deepseek-v4-flash")
  })

  test("resume of an existing native id exact-resumes", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-oc-wd-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-oc-home-"))
    dirs.push(sessionHome)

    const { adapter } = await resumeOpenCodeSession(
      { opencodeHost: host },
      {
        id: "existing-row",
        name: "oc-resume",
        workdir,
        agent_home: sessionHome,
        agent_session_id: "native-keep",
        model: "opencode/gpt",
      },
    )
    expect(adapter).toBeInstanceOf(CoreOpenCodeAdapter)
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
    expect(child.opens[0]?.sessionId).toBe("existing-row")
  })

  test("XDG_CONFIG_HOME and opencode.json shim are preserved", async () => {
    const child = fakeChildFactory({ nativeId: "n1" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-oc-wd-"))
    dirs.push(workdir)
    const result = await spawn({
      registry: reg,
      bind: async () => {},
      tmuxSession: "mux",
      opencodeHost: host,
    }, {
      workdir,
      requestedName: "oc-shim",
      agent: AgentKind.OpenCode,
    })
    const sessionHome = reg.get(result.session_id)!.agent_home!
    expect(child.ocCalls[0]?.options.env?.XDG_CONFIG_HOME).toBe(join(sessionHome, "config"))
    const json = readFileSync(join(sessionHome, "config", "opencode", "opencode.json"), "utf8")
    expect(json).toContain("mux-shim")
    expect(existsSync(join(sessionHome, "AGENTS.md"))).toBe(true)
  })
})
