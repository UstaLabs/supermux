import { afterEach, describe, expect, test } from "bun:test"
import { applyConfig, resumeClaudeSession, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import type { SessionBackend } from "../../runtime/session-backend"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { AgentKind } from "../../../shared/agents"
import { openDb, runMigrations } from "../../storage/db"
import { Registry } from "../../session-manager/registry"
import { createClaudeCoreHost, type ClaudeCoreHost } from "./core-host"
import type { AgentDriver, AgentRuntime, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import type { ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import { CoreClaudeAdapter } from "./core-adapter"
import { CoreError } from "../../../../packages/supermux-core/src/errors.js"

// Contract of the claude applyConfig DIALECT: it types into the live TUI via
// applyClaudeLiveSwitch (never restarts), narrows to the halves the user
// actually changed, and fails explicitly when the window is unknown. The
// type-in mechanics themselves are covered by live-switch.test.ts.

const ctx = (extra?: Partial<ApplyConfigCtx>): ApplyConfigCtx => ({
  sessionEffort: () => undefined,
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
  ...extra,
})

const row = { id: "s1", workdir: "/tmp" }

describe("claude applyConfig dialect", () => {
  test("no window id → explicit error, nothing typed", async () => {
    const r = await applyConfig(ctx(), row, "n", { model: "m1" })
    expect(r).toEqual({ ok: false, error: "session window not found" })
  })

  test("changed:false masks both halves → success without touching the pane", async () => {
    const backend = {
      capture: async () => { throw new Error("must not capture when nothing changed") },
    } as unknown as SessionBackend
    const r = await applyConfig(ctx({ windowId: "@1", backend }), row, "n", {
      model: "m1", effort: "high", changed: { model: false, effort: false },
    })
    expect(r).toEqual({ ok: true })
  })

  test("an unmasked model switch types into the live pane (backend consulted)", async () => {
    let captures = 0
    const backend = {
      capture: async () => { captures++; return null },
    } as unknown as SessionBackend
    const r = await applyConfig(ctx({ windowId: "@1", backend }), row, "n", {
      model: "m1", changed: { model: true, effort: false },
    })
    expect(captures).toBeGreaterThan(0)
    // A vanished pane is an explicit failure (the caller rolls back) — proof
    // the dialect went for the live type-in rather than any restart path.
    expect(r).toEqual({ ok: false, error: "session window gone (no pane to capture)" })
  })

  test("CoreClaudeAdapter setConfiguration session_busy is typed busy", async () => {
    const busyAdapter = {
      setConfiguration: async () => { throw new CoreError("session_busy", "Session is busy") },
    }
    const busy = await applyConfig(ctx({ adapter: busyAdapter as never }), row, "n", { model: "m1" })
    expect(busy).toEqual({ ok: false, busy: true })
  })
})

function fakeChildFactory(options: { nativeId?: string; failOpens?: number } = {}) {
  const opens: DriverContext[] = []
  let openAttempts = 0
  const factory = (_gopts: ClaudeOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "claude",
    async open(ctx) {
      openAttempts++
      if (openAttempts <= (options.failOpens ?? 0)) throw new Error("open failed")
      opens.push(ctx)
      const runtime: AgentRuntime = {
        agentSessionId: ctx.resumeId ?? options.nativeId ?? `native-${opens.length}`,
        capabilities: { resume: true, steer: false, fork: false, detach: true, configure: false, history: false },
        async prompt() { return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
      }
      return runtime
    },
  })
  return { factory, opens }
}

const hosts: ClaudeCoreHost[] = []
const dirs: string[] = []

function registry(): Registry {
  const db = openDb(":memory:")
  runMigrations(db, join(import.meta.dirname, "../../storage/migrations"))
  return new Registry(db)
}

async function makeHost(factory: ReturnType<typeof fakeChildFactory>["factory"]) {
  const dir = mkdtempSync(join(tmpdir(), "mux-claude-core-"))
  dirs.push(dir)
  const host = createClaudeCoreHost({ stateDirectory: dir, driverFactory: factory })
  hosts.push(host)
  return host
}

afterEach(async () => {
  for (const h of hosts.splice(0)) await h.close({ agents: "shutdown" }).catch(() => {})
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

describe("claude core spawn/resume dialect", () => {
  test("create registers the row before the native-id callback", async () => {
    const child = fakeChildFactory({ nativeId: "native-new" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-claude-wd-"))
    dirs.push(workdir)
    const order: string[] = []
    await spawn({
      registry: reg,
      bind: async () => { order.push("bind") },
      tmuxSession: "mux",
      claudeHost: host,
      onClaudeSessionId: (name, sid) => {
        order.push(`persist:${sid}`)
        expect(reg.resolveName(name)?.id).toBeDefined()
      },
    }, {
      workdir,
      requestedName: "cl-create",
      agent: AgentKind.Claude,
      id: "broker-id-1",
    })
    expect(order[0]).toBe("bind")
    expect(order).toContain("persist:native-new")
    expect(reg.get("broker-id-1")?.core).toBe(true)
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })

  test("resume of an existing native id adopts then exact-resumes", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-claude-wd-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-claude-home-"))
    dirs.push(sessionHome)
    const { adapter } = await resumeClaudeSession(
      { claudeHost: host },
      {
        id: "existing-row",
        name: "cl-resume",
        workdir,
        agent_home: sessionHome,
        agent_session_id: "native-keep",
      },
    )
    expect(adapter).toBeInstanceOf(CoreClaudeAdapter)
    expect(child.opens).toHaveLength(1)
    expect(child.opens[0]?.resumeId).toBe("native-keep")
  })
})
