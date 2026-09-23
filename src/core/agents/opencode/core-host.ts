import { join } from "path"
import { createHost, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { opencode, type OpenCodeOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareOpenCodeEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { openCodeInstructions } from "./preamble-writer"
import { opencodeConfigEntries } from "../../plugins"
import { muxShimServer } from "../mux-shim-server"
import { readGlobalProviderConfig } from "./provider-config"

export type OpenCodeDriverFactory = (options: OpenCodeOptions, overrides: SessionConfiguration) => AgentDriver

export type OpenCodeCoreHostOptions = {
  stateDirectory: string
  driverFactory?: OpenCodeDriverFactory
  /** Test seam: production always uses the broker policy below. */
  limits?: CoreLimits
}

export type OpenCodeCoreHost = Host

export type OpenCodePrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
  model?: string
  prompts?: boolean
}

function opencodeOpts(stateDirectory: string, env: Record<string, string>, model: string | undefined): OpenCodeOptions {
  return {
    id: "opencode",
    command: "opencode",
    env,
    inheritEnv: true,
    mcpServers: [],
    // A fresh session config dir makes OpenCode install its `plugin` entries
    // before it answers `initialize` (measured ~55 s on this box); the old
    // `opencode serve` path paid the same cost behind its readiness wait.
    setupTimeoutMs: 120_000,
    shutdownTimeoutMs: 2_000,
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

function asPrepareExtra(registration: HostRegistration): OpenCodePrepareExtra {
  const extra = registration.extra
  if (!extra) throw new Error("opencode host registration extra is required")
  const sessionHome = extra.sessionHome
  const sessionName = extra.sessionName
  const sessionId = extra.sessionId
  const workdir = extra.workdir
  const cwd = extra.cwd
  if (typeof sessionHome !== "string" || !sessionHome) throw new Error("opencode extra.sessionHome is required")
  if (typeof sessionName !== "string" || !sessionName) throw new Error("opencode extra.sessionName is required")
  if (typeof sessionId !== "string" || !sessionId) throw new Error("opencode extra.sessionId is required")
  if (typeof workdir !== "string" || !workdir) throw new Error("opencode extra.workdir is required")
  if (typeof cwd !== "string" || !cwd) throw new Error("opencode extra.cwd is required")
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
    prompts: extra.prompts === true,
  }
}

export function createOpenCodeCoreHost(options: OpenCodeCoreHostOptions): OpenCodeCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  return createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "opencode",
    driver: (registration, ctx) => {
      const extraModel = typeof registration.extra?.model === "string" ? registration.extra.model : undefined
      const opts = opencodeOpts(stateDirectory, registration.env, extraModel ?? ctx.configuration?.model)
      const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
      return factory ? factory(opts, overrides) : opencode(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      const configHome = joinConfigHome(extra.sessionHome)
      const { pluginPaths, skillsPaths } = opencodeConfigEntries({ sessionName: extra.sessionName })
      const prepared = await prepareOpenCodeEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers: [muxShimServer("opencode", extra.sessionId, extra.sessionName)],
        skillsPaths,
        pluginPaths,
        permissions: extra.prompts ? "ask" : "allow",
        provider: readGlobalProviderConfig() ?? null,
        instructions: openCodeInstructions({ sessionName: extra.sessionName, workdir: extra.workdir }),
        configHome,
      })
      return { env: prepared.env }
    },
  })
}

function joinConfigHome(sessionHome: string): string {
  return join(sessionHome, "config")
}
