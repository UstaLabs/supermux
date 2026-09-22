import { afterEach, describe, expect, test } from "bun:test"
import { applyConfig, resumeClaudeSession, spawn } from "./session"
import type { ApplyConfigCtx } from "../session-types"
import { existsSync, mkdtempSync, mkdirSync, readFileSync, rmSync, writeFileSync } from "fs"
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
  test("no adapter → explicit error", async () => {
    const r = await applyConfig(ctx(), row, "n", { model: "m1" })
    expect(r).toEqual({ ok: false, error: "claude adapter not found" })
  })

  test("changed:false masks both halves → success without setConfiguration", async () => {
    let called = 0
    const adapter = { setConfiguration: async () => { called++ } }
    const r = await applyConfig(ctx({ adapter: adapter as never }), row, "n", {
      model: "m1", effort: "high", changed: { model: false, effort: false },
    })
    expect(r).toEqual({ ok: true })
    expect(called).toBe(0)
  })

  test("/model + /effort go through setConfiguration", async () => {
    const patches: Array<{ model?: string; effort?: string }> = []
    const adapter = {
      setConfiguration: async (p: { model?: string; effort?: string }) => { patches.push(p) },
    }
    const r = await applyConfig(ctx({ adapter: adapter as never }), row, "n", {
      model: "m1", effort: "high", changed: { model: true, effort: true },
    })
    expect(r).toEqual({ ok: true })
    expect(patches).toEqual([{ model: "m1", effort: "high" }])
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
  const envs: Record<string, string>[] = []
  const optionsSeen: ClaudeOptions[] = []
  const factory = (gopts: ClaudeOptions, _overrides: SessionConfiguration): AgentDriver => ({
    id: "claude",
    async open(ctx) {
      openAttempts++
      envs.push({ ...(gopts.env ?? {}) })
      optionsSeen.push(gopts)
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
  return { factory, opens, envs, optionsSeen }
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

  // The user's global ~/.claude.json declares the mux-shim MCP server, which
  // reads the session identity from the process env; a Core session must set
  // it or the shim registers as a random id on the default sockets dir.
  test("the shim identity env reaches the claude driver", async () => {
    const child = fakeChildFactory({ nativeId: "native-env" })
    const host = await makeHost(child.factory)
    const reg = registry()
    const workdir = mkdtempSync(join(tmpdir(), "mux-claude-wd-"))
    dirs.push(workdir)
    await spawn({ registry: reg, bind: async () => {}, tmuxSession: "mux", claudeHost: host }, {
      workdir,
      requestedName: "cl-env",
      agent: AgentKind.Claude,
      id: "broker-id-env",
    })
    const env = child.envs[0]!
    expect(env.MUX_SESSION_ID).toBe("broker-id-env")
    expect(env.MUX_DISPLAY_NAME).toBe("cl-env")
    expect(env.MUX_AGENT_KIND).toBe("claude")
    expect(env.MUX_SESSION_ROLE).toBe("worker")
    expect(env.MUX_SOCKETS_DIR).toMatch(/sockets$/)
    expect(env.MUX_CORE).toBe("1")
    // Prompts off = bypass tool approvals, but agent questions must still reach
    // the host: headless Claude drops AskUserQuestion under prompts "none".
    expect(child.optionsSeen[0]?.permissionMode).toBe("bypassPermissions")
    expect(child.optionsSeen[0]?.permissionPrompts).toBe("host")
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

  test("PA spawn registers before open, pid 0 core=1, soul.md in instructions, not for workers", async () => {
    const soulDir = mkdtempSync(join(tmpdir(), "mux-home-"))
    dirs.push(soulDir)
    mkdirSync(join(soulDir, ".mux"), { recursive: true })
    writeFileSync(join(soulDir, ".mux", "soul.md"), "SOUL-MARKER-UNIQUE")
    const prevHome = process.env.HOME
    process.env.HOME = soulDir
    try {
      const paChild = fakeChildFactory({ nativeId: "pa-native" })
      const host = await makeHost(paChild.factory)
      const reg = registry()
      const workdir = mkdtempSync(join(tmpdir(), "mux-claude-pa-"))
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
        requestedName: "ana",
        agent: AgentKind.Claude,
        id: "pa-id",
        pa: { skipRegister: false },
      })
      expect(order[0]).toBe("bind")
      expect(order).toContain("persist:pa-native")
      const pa = reg.get("pa-id")
      expect(pa?.role).toBe("personal_assistant")
      expect(pa?.pid).toBe(0)
      expect(pa?.core).toBe(true)
      const paInstr = readFileSync(join(pa!.agent_home!, "instructions.md"), "utf8")
      expect(paInstr).toContain("SOUL-MARKER-UNIQUE")
      expect(paInstr).toContain("use the reply tool ONLY for files")

      const wChild = fakeChildFactory({ nativeId: "w-native" })
      const wHost = await makeHost(wChild.factory)
      const wReg = registry()
      const wdir = mkdtempSync(join(tmpdir(), "mux-claude-w-"))
      dirs.push(wdir)
      await spawn({
        registry: wReg,
        bind: async () => {},
        tmuxSession: "mux",
        claudeHost: wHost,
      }, {
        workdir: wdir,
        requestedName: "worker-one",
        agent: AgentKind.Claude,
      })
      const worker = wReg.resolveName("worker-one")
      const wInstr = readFileSync(join(worker!.agent_home!, "instructions.md"), "utf8")
      expect(wInstr).not.toContain("SOUL-MARKER-UNIQUE")
      expect(wInstr).toContain("use the reply tool ONLY for files")
    } finally {
      process.env.HOME = prevHome
    }
  })

  test("tmux-era row with agent_session_id resumes through Core", async () => {
    const child = fakeChildFactory()
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-claude-mig-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-claude-home-"))
    dirs.push(sessionHome)
    await resumeClaudeSession(
      { claudeHost: host },
      {
        id: "tmux-era",
        name: "legacy",
        workdir,
        agent_home: sessionHome,
        agent_session_id: "old-claude-sid",
        pa: true,
      },
    )
    expect(child.opens[0]?.resumeId).toBe("old-claude-sid")
  })

  test("tmux-era row without agent_session_id starts fresh", async () => {
    const child = fakeChildFactory({ nativeId: "fresh-native" })
    const host = await makeHost(child.factory)
    const workdir = mkdtempSync(join(tmpdir(), "mux-claude-mig2-"))
    dirs.push(workdir)
    const sessionHome = mkdtempSync(join(tmpdir(), "mux-claude-home-"))
    dirs.push(sessionHome)
    await resumeClaudeSession(
      { claudeHost: host },
      {
        id: "tmux-era-2",
        name: "legacy2",
        workdir,
        agent_home: sessionHome,
        pa: true,
      },
    )
    expect(child.opens[0]?.resumeId).toBeUndefined()
  })
})
