import { join } from "path"
import { createHost, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { codex, type CodexOptions } from "../../../../packages/supermux-core/src/codex/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareCodexEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { codexInstructions } from "./preamble-writer"
import { codexPrepareSessionHome } from "../../plugins"
import { muxShimServer } from "../mux-shim-server"
import { HOME } from "../../session-manager/spawn-helper"

export type CodexDriverFactory = (options: CodexOptions, overrides: SessionConfiguration) => AgentDriver

export type CodexCoreHostOptions = {
  stateDirectory: string
  driverFactory?: CodexDriverFactory
  /** Test seam: production always uses the broker policy below. */
  limits?: CoreLimits
}

export type CodexCoreHost = Host

export type CodexPrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
}

type RuntimeAttachable = {
  attachRuntimeRequest: (request: (method: string, params: unknown) => Promise<unknown>) => void
}

const runtimeAdapters = new Map<string, RuntimeAttachable>()

export function attachCodexRuntimeAdapter(id: string, adapter: RuntimeAttachable): void {
  runtimeAdapters.set(id, adapter)
}

export function detachCodexRuntimeAdapter(id: string): void {
  runtimeAdapters.delete(id)
}

const BROKER_CODEX_OPTIONS: Pick<CodexOptions, "approvalPolicy" | "sandbox" | "permissionPrompts" | "inheritEnv" | "setupTimeoutMs" | "requestTimeoutMs" | "shutdownTimeoutMs" | "maxFrameBytes"> = {
  approvalPolicy: "never",
  sandbox: "danger-full-access",
  permissionPrompts: "none",
  inheritEnv: true,
  setupTimeoutMs: 30_000,
  requestTimeoutMs: 30_000,
  shutdownTimeoutMs: 2_000,
  maxFrameBytes: 16 * 1024 * 1024,
}

function asPrepareExtra(registration: HostRegistration): CodexPrepareExtra {
  const extra = registration.extra
  if (!extra) throw new Error("codex host registration extra is required")
  const sessionHome = extra.sessionHome
  const sessionName = extra.sessionName
  const sessionId = extra.sessionId
  const workdir = extra.workdir
  const cwd = extra.cwd
  if (typeof sessionHome !== "string" || !sessionHome) throw new Error("codex extra.sessionHome is required")
  if (typeof sessionName !== "string" || !sessionName) throw new Error("codex extra.sessionName is required")
  if (typeof sessionId !== "string" || !sessionId) throw new Error("codex extra.sessionId is required")
  if (typeof workdir !== "string" || !workdir) throw new Error("codex extra.workdir is required")
  if (typeof cwd !== "string" || !cwd) throw new Error("codex extra.cwd is required")
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

export function createCodexCoreHost(options: CodexCoreHostOptions): CodexCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  return createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "codex",
    driver: (registration, ctx) => {
      const opts: CodexOptions = {
        ...BROKER_CODEX_OPTIONS,
        id: "codex",
        command: registration.command ?? "codex",
        args: registration.args ? [...registration.args] : ["app-server"],
        env: registration.env,
        keeper: {
          stateDirectory,
          limits: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 4_000 },
        },
        onRuntimeRequest: (info, request) => {
          if (info.sessionId === ctx.sessionId) {
            runtimeAdapters.get(ctx.sessionId)?.attachRuntimeRequest(request)
          }
        },
      }
      const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
      return factory ? factory(opts, overrides) : codex(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      const prepared = await prepareCodexEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers: [muxShimServer("codex", extra.sessionId, extra.sessionName)],
        skillsPaths: [],
        instructions: codexInstructions({ sessionName: extra.sessionName, workdir: extra.workdir }),
        credentials: { apiKey: process.env.OPENAI_API_KEY ?? null, canonicalHome: join(HOME, ".codex") },
        nativeMemory: false,
      })
      await codexPrepareSessionHome(extra.sessionHome)
      return { env: prepared.env }
    },
  })
}
