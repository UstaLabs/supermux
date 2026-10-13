import { afterEach, describe, expect, test } from "bun:test"
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { applyConfig, commandContext, resumeAntigravitySession, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import { createAntigravityCoreHost, type AntigravityCoreHost } from "./core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { AntigravityOptions } from "../../../../packages/supermux-core/src/antigravity/index.js"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"
import { AgentKind } from "../../../shared/agents"
import { STATE_DIR } from "../../../shared/paths"
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
    kind: "antigravity" as const,
    model,
    async setConfiguration(patch: { model?: string; effort?: string }) {
      if ("model" in patch) this.model = patch.model
      patches.push(patch)
    },
  }
  return { adapter: adapter as unknown as CoreAdapter, patches }
}

describe("antigravity applyConfig dialect", () => {
  test("no live adapter → the exact error", async () => {
    const r = await applyConfig(ctx(undefined), row, "n", { model: "gemini-3.8-flash-high" })
    expect(r).toEqual({ ok: false, error: "antigravity session has no live adapter" })
  })

  test("a model change goes through setConfiguration({model}); effort is never patched", async () => {
    const { adapter, patches } = adapterWith("gemini-3.8-flash-low")
    const r = await applyConfig(ctx(adapter), row, "n", {
      model: "gemini-3.8-flash-high", effort: "high", changed: { model: true, effort: true },
    })
    expect(r).toEqual({ ok: true })
    expect(patches).toEqual([{ model: "gemini-3.8-flash-high" }])
  })

  test("effort-only → ok with no patch (effort is part of the model id)", async () => {
    const { adapter, patches } = adapterWith("gemini-3.8-flash-low")
    const r = await applyConfig(ctx(adapter), row, "n", {
      model: "gemini-3.8-flash-low", effort: "high", changed: { model: false, effort: true },
    })
    expect(r).toEqual({ ok: true })
    expect(patches).toEqual([])
  })

  test("session_busy is typed busy, native errors are not", async () => {
    const busy = await applyConfig({ ...ctx(), adapter: { setConfiguration: async () => { throw new CoreError("session_busy", "Session is busy") } } as never }, row, "n", { model: "m" })
    expect(busy).toEqual({ ok: false, busy: true })
    const failed = await applyConfig({ ...ctx(), adapter: { setConfiguration: async () => { throw new Error("restart failed") } } as never }, row, "n", { model: "m" })
    expect(failed).toEqual({ ok: false, error: "restart failed" })
  })
})

test("commandContext lists the session's skills dirs", () => {
  const c = commandContext({ sessionName: "agy-cmd" })
  expect(Array.isArray(c.skillsDirs)).toBe(true)
})

function fakeChildFactory(nativeId = "conv-new") {
  const opens: DriverContext[] = []
  const calls: { options: AntigravityOptions; overrides: SessionConfiguration }[] = []
  const factory = (options: AntigravityOptions, overrides: SessionConfiguration): AgentDriver => {
    calls.push({ options: { ...options, env: { ...options.env } }, overrides: { ...overrides } })
    return {
      id: "antigravity",
      async open(c) {
        opens.push(c)
        const runtime: AgentRuntime = {
          agentSessionId: c.resumeId ?? nativeId,
          capabilities: { resume: true, steer: false, fork: false, detach: false, configure: true, history: false },
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
  return { factory, opens, calls }
}

const hosts: AntigravityCoreHost[] = []
const dirs: string[] = []

function tmp(prefix: string): string {
  const d = mkdtempSync(join(tmpdir(), prefix))
  dirs.push(d)
  return d
}

function fakeUserHome(): string {
  const user = tmp("agy-user-")
  mkdirSync(join(user, ".gemini", "antigravity-cli"), { recursive: true })
  writeFileSync(join(user, ".gitconfig"), "[user]\n")
  return user
}

function makeHost(factory: ReturnType<typeof fakeChildFactory>["factory"]): AntigravityCoreHost {
  const host = createAntigravityCoreHost({ stateDirectory: tmp("mux-agy-core-"), driverFactory: factory, userHome: fakeUserHome() })
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

describe("antigravity core spawn/resume dialect", () => {
  test("spawn registers an antigravity row (pid 0, bypass) and persists the native id", async () => {
    const child = fakeChildFactory("conv-new")
    const host = makeHost(child.factory)
    const reg = registry()
    const workdir = tmp("mux-agy-wd-")
    const order: string[] = []
    const result = await spawn({
      registry: reg,
      bind: async () => { order.push("bind") },
      tmuxSession: "mux",
      antigravityHost: host,
      onAntigravitySessionId: (name, sid) => {
        order.push(`persist:${sid}`)
        expect(reg.resolveName(name)?.id).toBeDefined()
      },
    }, { workdir, requestedName: "agy-create", agent: AgentKind.Antigravity, id: "broker-id-1" })

    expect(result).toMatchObject({ name: "agy-create", session_id: "broker-id-1", pid: 0 })
    expect(order[0]).toBe("bind")
    expect(order).toContain("persist:conv-new")
    const saved = reg.get("broker-id-1")!
    expect(saved.agent).toBe(AgentKind.Antigravity)
    expect(saved.pid).toBe(0)
    expect(saved.permissionMode).toBe("bypass")
    expect(saved.agent_home).toBe(join(STATE_DIR, "agents", "antigravity", "agy-create"))
    expect(child.calls[0]!.options.home.shadow).toBe(join(saved.agent_home!, "home"))
    expect(child.calls[0]!.options.permissions).toEqual({ kind: "antigravity", mode: "bypass" })
    expect(child.opens[0]?.resumeId).toBeUndefined()
    expect(existsSync(join(workdir, "GEMINI.md"))).toBe(false)
  })

  test("a failed start releases the name and drops the row", async () => {
    const host = createAntigravityCoreHost({
      stateDirectory: tmp("mux-agy-core-"),
      userHome: fakeUserHome(),
      driverFactory: () => ({ id: "antigravity", async open() { throw new Error("open failed") } }),
    })
    hosts.push(host)
    const reg = registry()
    const workdir = tmp("mux-agy-wd-")
    await expect(spawn({ registry: reg, bind: async () => {}, tmuxSession: "mux", antigravityHost: host },
      { workdir, requestedName: "agy-fail", agent: AgentKind.Antigravity, id: "fail-id" })).rejects.toThrow(/open failed/)
    expect(reg.get("fail-id")).toBeUndefined()
    expect(reg.takenNames().has("agy-fail")).toBe(false)
  })

  test("resume of an existing conversation id exact-resumes", async () => {
    const child = fakeChildFactory()
    const host = makeHost(child.factory)
    const workdir = tmp("mux-agy-wd-")
    const agentHome = join(tmp("mux-agy-home-"), "agy-resume")
    const persisted: string[] = []
    const { adapter } = await resumeAntigravitySession(
      { antigravityHost: host, onAntigravitySessionId: (_n, sid) => persisted.push(sid) },
      { id: "existing-row", name: "agy-resume", workdir, agent_home: agentHome, agent_session_id: "conv-keep", model: "gemini-3.8-flash-high", permissionMode: "plan" },
    )
    expect(adapter).toBeInstanceOf(CoreAdapter)
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("conv-keep")
    expect(child.opens[0]?.sessionId).toBe("existing-row")
    expect(child.calls[0]!.options.home.shadow).toBe(join(agentHome, "home"))
    expect(child.calls[0]!.options.permissions).toEqual({ kind: "antigravity", mode: "plan" })
    await adapter.stop()
  })
})
