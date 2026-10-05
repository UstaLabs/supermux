import { join } from "path"
import { createHost, type AccountsOptions, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { cursor, type CursorOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareCursorEnvironment, sharedCursorDir } from "../../../../packages/supermux-core/src/environment/index.js"
import { cursorContext } from "../../../../packages/supermux-core/src/context/agents.js"
import { cursorInstructions } from "./preamble-writer"
import { sessionPlugins } from "../../plugins"
import { muxShimContextServer } from "../mux-shim-server"
import { MUX_HOST_SERVERS } from "../../mux-tools/server"
import { makeLogger } from "../../../shared/log"

import { smokeCursorAgent } from "./smoke"
import { HOME } from "../../session-manager/spawn-helper"
import { STATE_DIR } from "../../../shared/paths"
import { driverSettingsFor, extraPermissionMode } from "../permission-modes"
import { syncCursorSessionCredential } from "../account-env"
import { isSystemAccount } from "../../accounts/broker-accounts"

const log = makeLogger("agents/cursor/core-host")

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
  /** The broker's shared account registry (production); tests may omit it. */
  accounts?: AccountsOptions
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
  permissionMode?: string
}

function cursorOpts(
  stateDirectory: string,
  env: Record<string, string>,
  model: string | undefined,
  permissionMode: string,
): CursorOptions {
  const settings = driverSettingsFor("cursor", permissionMode)
  if (settings.initial.kind !== "acp") throw new Error("cursor driver settings mismatch")
  return {
    id: "cursor",
    command: "cursor-agent",
    commandArgs: [],
    env,
    inheritEnv: true,
    mcpServers: [],
    permissions: settings.initial,
    mode: settings.initial.nativeMode === "plan" || settings.initial.nativeMode === "ask" || settings.initial.nativeMode === "agent"
      ? settings.initial.nativeMode
      : undefined,
    setupTimeoutMs: 120_000,
    shutdownTimeoutMs: 5_000,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    keeper: {
      stateDirectory,
      limits: { parkedDeadlineMs: 120_000, journalMaxBytes: 64 * 1024 * 1024, connectTimeoutMs: 10_000 },
    },
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
    permissionMode: extraPermissionMode(extra, "cursor"),
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
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  const smoke = options.smoke ?? smokeCursorAgent
  return createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "cursor",
    // Instructions (the first prompt's leading block: Cursor's ACP server has no system-prompt
    // channel; nothing is written into the repo any more) and mux-shim (ACP mcpServers) are
    // session context. Plugins are passed too but Cursor's ACP server cannot load them
    // (--plugin-dir is ignored): "warn" drops them with context.degraded.
    context: cursorContext([]).support,
    contextPolicy: "warn",
    ...(options.accounts ? { accounts: options.accounts } : {}),
    // The broker's host MCP servers (C3b), registered in BOTH mux-shim modes so a record that
    // names one resumes after a flip back to "external" (see mux-tools/mode.ts).
    mcpServers: MUX_HOST_SERVERS,
    driver: (registration, ctx) => {
      const extra = asPrepareExtra(registration)
      // Every open (also a Core-internal account switch): no credential copy on an account.
      syncCursorSessionCredential({ sessionHome: extra.sessionHome, userConfigDir: userConfigDir(), account: ctx.account, systemApiKey: process.env.CURSOR_API_KEY })
      const extraModel = typeof registration.extra?.model === "string" ? registration.extra.model : undefined
      const opts = cursorOpts(stateDirectory, registration.env, extraModel ?? ctx.configuration?.model, extra.permissionMode ?? extraPermissionMode(registration.extra, "cursor"))
      const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
      return factory ? factory(opts, overrides) : cursor(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      const prepared = await prepareCursorEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers: [],
        skillsPaths: [],
        instructions: null,
        // On an account the adapter injects the credential; the session gets no auth.json copy.
        credentials: {
          apiKey: isSystemAccount(registration.account) ? process.env.CURSOR_API_KEY ?? null : null,
          userCursorDir: join(HOME, ".cursor"),
          userConfigDir: userConfigDir(),
          ...(isSystemAccount(registration.account) ? {} : { account: true }),
        },
        sharedRuntime: options.sharedRuntime !== undefined
          ? options.sharedRuntime
          : (process.platform === "win32" ? null : { source: sharedCursorDir(STATE_DIR) }),
        platform: process.platform,
      })
      // Smoke is content: fail early on a broken install, after the environment is written.
      await smoke({ home: extra.sessionHome, authEnv: prepared.env })
      return {
        env: prepared.env,
        context: {
          instructions: cursorInstructions({ sessionName: extra.sessionName, workdir: extra.workdir }),
          plugins: sessionPlugins("cursor", extra.sessionName, { onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) }),
          mcpServers: [muxShimContextServer("cursor", extra.sessionId, extra.sessionName)],
        },
      }
    },
  })
}
