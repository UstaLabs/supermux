import { join } from "path"
import { createHost, type AccountsOptions, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { grok, type GrokOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareGrokEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { grokContext } from "../../../../packages/supermux-core/src/context/agents.js"
import { sessionInstructions } from "../instructions"
import { sessionPlugins } from "../../plugins"
import { muxShimContextServer } from "../mux-shim-server"
import { MUX_HOST_SERVERS } from "../../mux-tools/server"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/grok/core-host")
import { HOME } from "../../session-manager/spawn-helper"
import { driverSettingsFor, extraPermissionMode } from "../permission-modes"
import { grokAccountEnv } from "../account-env"

export type GrokDriverFactory = (options: GrokOptions, overrides: SessionConfiguration) => AgentDriver

export type GrokCoreHostOptions = {
  stateDirectory: string
  driverFactory?: GrokDriverFactory
  /** Test seam: production always uses the broker policy below. */
  limits?: CoreLimits
  /** The broker's shared account registry (production); tests may omit it. */
  accounts?: AccountsOptions
}

export type GrokCoreHost = Host

export type GrokPrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
  permissionMode?: string
}

function grokOpts(stateDirectory: string, env: Record<string, string>, permissions: GrokOptions["permissions"]): GrokOptions {
  return {
    id: "grok",
    command: "grok",
    commandArgs: [],
    env,
    inheritEnv: true,
    mcpServers: [],
    noLeader: false,
    permissions,
    setupTimeoutMs: 30_000,
    shutdownTimeoutMs: 2_000,
    maxFrameBytes: 16 * 1024 * 1024,
    maxOutstandingActivity: 256,
    cancelRetryIntervalMs: 250,
    cancelRetryTimeoutMs: 10_000,
    keeper: {
      stateDirectory,
      limits: { parkedDeadlineMs: 120_000, journalMaxBytes: 64 * 1024 * 1024, connectTimeoutMs: 10_000 },
    },
  }
}

function asPrepareExtra(registration: HostRegistration): GrokPrepareExtra {
  const extra = registration.extra
  if (!extra) throw new Error("grok host registration extra is required")
  const sessionHome = extra.sessionHome
  const sessionName = extra.sessionName
  const sessionId = extra.sessionId
  const workdir = extra.workdir
  const cwd = extra.cwd
  if (typeof sessionHome !== "string" || !sessionHome) throw new Error("grok extra.sessionHome is required")
  if (typeof sessionName !== "string" || !sessionName) throw new Error("grok extra.sessionName is required")
  if (typeof sessionId !== "string" || !sessionId) throw new Error("grok extra.sessionId is required")
  if (typeof workdir !== "string" || !workdir) throw new Error("grok extra.workdir is required")
  if (typeof cwd !== "string" || !cwd) throw new Error("grok extra.cwd is required")
  const native = extra.nativeSessionId
  return {
    sessionHome,
    sessionName,
    sessionId,
    workdir,
    cwd,
    nativeSessionId: typeof native === "string" ? native : undefined,
    permissionMode: extraPermissionMode(extra, "grok"),
  }
}

export function createGrokCoreHost(options: GrokCoreHostOptions): GrokCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  return createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "grok",
    // Instructions (ACP session/new _meta.rules, nothing written into the repo any more),
    // plugins (grok agent --plugin-dir) and mux-shim (ACP mcpServers) are session context (C3).
    // The driver's own mcpServers are [], so no context server name collides.
    context: grokContext([]).support,
    contextPolicy: "warn",
    ...(options.accounts ? { accounts: options.accounts } : {}),
    // The broker's host MCP servers (C3b), registered in BOTH mux-shim modes so a record that
    // names one resumes after a flip back to "external" (see mux-tools/mode.ts).
    mcpServers: MUX_HOST_SERVERS,
    driver: async (registration, ctx) => {
      const settings = driverSettingsFor("grok", extraPermissionMode(registration.extra, "grok"))
      if (settings.initial.kind !== "acp") throw new Error("grok driver settings mismatch")
      const sessionHome = typeof registration.extra?.sessionHome === "string" ? registration.extra.sessionHome : ""
      const env = sessionHome ? await grokAccountEnv(registration.env, { sessionHome, account: ctx.account }) : registration.env
      const opts = grokOpts(stateDirectory, env, settings.initial)
      const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
      return factory ? factory(opts, overrides) : grok(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      const prepared = await prepareGrokEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers: [],
        skillsPaths: [],
        credentials: { canonicalAuthPath: join(HOME, ".grok", "auth.json") },
        autoUpdate: false,
        importClaudeConfig: false,
        platform: process.platform,
      })
      return {
        env: prepared.env,
        context: {
          instructions: sessionInstructions({ agent: "grok", sessionName: extra.sessionName, workdir: extra.workdir }),
          plugins: sessionPlugins("grok", extra.sessionName, { onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) }),
          mcpServers: [muxShimContextServer("grok", extra.sessionId, extra.sessionName)],
        },
      }
    },
  })
}
