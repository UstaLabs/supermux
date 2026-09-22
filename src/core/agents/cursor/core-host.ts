import { join } from "path"
import { createHost, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { cursor, type CursorOptions } from "../../../../packages/supermux-core/src/cursor/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareCursorEnvironment, sharedCursorDir } from "../../../../packages/supermux-core/src/environment/index.js"
import { cursorInstructions } from "./preamble-writer"
import { cursorSpawnArgs } from "../../plugins"
import { muxShimServer } from "../mux-shim-server"
import { smokeCursorAgent } from "./smoke"
import { HOME } from "../../session-manager/spawn-helper"
import { STATE_DIR } from "../../../shared/paths"

export type CursorDriverFactory = (options: CursorOptions, overrides: SessionConfiguration) => AgentDriver

export type CursorSmoke = (opts: { home: string; authEnv: Record<string, string> }) => Promise<void>

export type CursorCoreHostOptions = {
  stateDirectory: string
  driverFactory?: CursorDriverFactory
  /** Test seam: production always uses the broker policy below. */
  limits?: CoreLimits
  /** Test seam: production always smokes cursor-agent after the environment is written. */
  smoke?: CursorSmoke
  /** Test seam: production always links the shared cursor-agent runtime. Seeding it copies
   *  ~0.9 GB from the user's install, which no test should pay for. */
  sharedRuntime?: { source: string } | null
}

export type CursorCoreHost = Host

export type CursorPrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
  model?: string
}

function cursorOpts(env: Record<string, string>, pluginArgs: string[], model: string | undefined): CursorOptions {
  return {
    id: "cursor",
    command: "cursor-agent",
    args: pluginArgs,
    env,
    inheritEnv: true,
    sandbox: "enabled",
    trust: true,
    force: true,
    approveMcps: true,
    setupTimeoutMs: 60_000,
    shutdownTimeoutMs: 5_000,
    maxFrameBytes: 16 * 1024 * 1024,
    ...(model ? { model } : {}),
  }
}

function asPrepareExtra(registration: HostRegistration): CursorPrepareExtra {
  const extra = registration.extra
  if (!extra) throw new Error("cursor host registration extra is required")
  const sessionHome = extra.sessionHome
  const sessionName = extra.sessionName
  const sessionId = extra.sessionId
  const workdir = extra.workdir
  const cwd = extra.cwd
  if (typeof sessionHome !== "string" || !sessionHome) throw new Error("cursor extra.sessionHome is required")
  if (typeof sessionName !== "string" || !sessionName) throw new Error("cursor extra.sessionName is required")
  if (typeof sessionId !== "string" || !sessionId) throw new Error("cursor extra.sessionId is required")
  if (typeof workdir !== "string" || !workdir) throw new Error("cursor extra.workdir is required")
  if (typeof cwd !== "string" || !cwd) throw new Error("cursor extra.cwd is required")
  const native = extra.nativeSessionId
  const model = extra.model
  return {
    sessionHome,
    sessionName,
    sessionId,
    workdir,
    cwd,
    nativeSessionId: typeof native === "string" ? native : undefined,
    model: typeof model === "string" ? model : undefined,
  }
}

function userConfigDir(): string {
  if (process.platform === "win32") {
    return process.env.APPDATA || join(HOME, "AppData", "Roaming")
  }
  return process.env.XDG_CONFIG_HOME || join(HOME, ".config")
}

export function createCursorCoreHost(options: CursorCoreHostOptions): CursorCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const factory = options.driverFactory
  const smoke = options.smoke ?? smokeCursorAgent
  return createHost({
    stateDirectory: options.stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "cursor",
    driver: (registration, ctx) => {
      const extra = asPrepareExtra(registration)
      const extraModel = typeof registration.extra?.model === "string" ? registration.extra.model : undefined
      const pluginArgs = cursorSpawnArgs({ sessionName: extra.sessionName }).args
      const opts = cursorOpts(registration.env, pluginArgs, extraModel ?? ctx.configuration?.model)
      const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
      return factory ? factory(opts, overrides) : cursor(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      const prepared = await prepareCursorEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers: [muxShimServer("cursor", extra.sessionId, extra.sessionName)],
        skillsPaths: [],
        instructions: cursorInstructions({ sessionName: extra.sessionName, workdir: extra.workdir }),
        credentials: {
          apiKey: process.env.CURSOR_API_KEY ?? null,
          userCursorDir: join(HOME, ".cursor"),
          userConfigDir: userConfigDir(),
        },
        sharedRuntime: options.sharedRuntime !== undefined
          ? options.sharedRuntime
          : (process.platform === "win32" ? null : { source: sharedCursorDir(STATE_DIR) }),
        platform: process.platform,
      })
      // Smoke is content: fail early on a broken install, after the environment is written.
      await smoke({ home: extra.sessionHome, authEnv: prepared.env })
      return { env: prepared.env }
    },
  })
}
