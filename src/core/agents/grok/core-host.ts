import { mkdirSync } from "fs"
import { join } from "path"
import { createHost, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { grok, type GrokOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { resolveGrokAuth } from "./auth"
import { writeGrokConfig } from "./config-writer"
import { writeGrokPreamble } from "./preamble-writer"
import { grokConfigEntries } from "../../plugins"
import { shimSpawnSpec } from "../../session-manager/shim-spawn"
import { HOME } from "../../session-manager/spawn-helper"
import { SOCKETS_DIR } from "../../../shared/paths"

export type GrokDriverFactory = (options: GrokOptions, overrides: SessionConfiguration) => AgentDriver

export type GrokCoreHostOptions = {
  stateDirectory: string
  driverFactory?: GrokDriverFactory
  /** Test seam: production always uses the broker policy below. */
  limits?: CoreLimits
}

export type GrokCoreHost = Host

export type GrokPrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
}

function grokOpts(stateDirectory: string, env: Record<string, string>): GrokOptions {
  return {
    id: "grok",
    command: "grok",
    commandArgs: [],
    env,
    inheritEnv: true,
    mcpServers: [],
    noLeader: false,
    alwaysApprove: true,
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
    driver: (registration) => {
      const opts = grokOpts(stateDirectory, registration.env)
      return factory ? grok(opts, factory) : grok(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      mkdirSync(extra.sessionHome, { recursive: true, mode: 0o700 })
      const auth = await resolveGrokAuth({ userGrokDir: join(HOME, ".grok"), sessionHome: extra.sessionHome })
      writeGrokConfig({
        sessionHome: extra.sessionHome,
        ...shimSpawnSpec(),
        sessionName: extra.sessionName,
        sessionId: extra.sessionId,
        socketsDir: SOCKETS_DIR,
        skillsPaths: grokConfigEntries({ sessionName: extra.sessionName }).skillsPaths,
      })
      writeGrokPreamble({ workdir: extra.workdir, sessionName: extra.sessionName })
      return { env: auth.env }
    },
  })
}
