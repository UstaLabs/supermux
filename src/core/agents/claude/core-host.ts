import { join } from "path"
import { createHost, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { claude, type ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareClaudeEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { claudeWorkerInstructions } from "./preamble-writer"
import { claudeSpawnArgs } from "../../plugins"
import { promptsDir } from "../../runtime-assets"
import { STATE_DIR } from "../../../shared/paths"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/claude/core-host")

export type ClaudeDriverFactory = (options: ClaudeOptions, overrides: SessionConfiguration) => AgentDriver

export type ClaudeCoreHostOptions = {
  stateDirectory: string
  driverFactory?: ClaudeDriverFactory
  limits?: CoreLimits
}

export type ClaudeCoreHost = Host

export type ClaudePrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
  model?: string
  effort?: string
  prompts?: boolean
}

const EFFORTS = new Set(["low", "medium", "high", "xhigh", "max"])

function asEffort(value: unknown): ClaudeOptions["effort"] | undefined {
  if (typeof value !== "string" || !EFFORTS.has(value)) {
    if (value !== undefined && value !== "") log.warn("claude_effort_omitted", { effort: String(value) })
    return undefined
  }
  return value as ClaudeOptions["effort"]
}

function pluginDirsFor(sessionName: string): string[] {
  const { args } = claudeSpawnArgs({ sessionName })
  const dirs: string[] = []
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--plugin-dir" && args[i + 1]) {
      dirs.push(args[i + 1]!)
      i++
    }
  }
  return dirs
}

function asPrepareExtra(registration: HostRegistration): ClaudePrepareExtra {
  const extra = registration.extra
  if (!extra) throw new Error("claude host registration extra is required")
  const sessionHome = extra.sessionHome
  const sessionName = extra.sessionName
  const sessionId = extra.sessionId
  const workdir = extra.workdir
  const cwd = extra.cwd
  if (typeof sessionHome !== "string" || !sessionHome) throw new Error("claude extra.sessionHome is required")
  if (typeof sessionName !== "string" || !sessionName) throw new Error("claude extra.sessionName is required")
  if (typeof sessionId !== "string" || !sessionId) throw new Error("claude extra.sessionId is required")
  if (typeof workdir !== "string" || !workdir) throw new Error("claude extra.workdir is required")
  if (typeof cwd !== "string" || !cwd) throw new Error("claude extra.cwd is required")
  const native = extra.nativeSessionId
  const model = extra.model
  const effort = extra.effort
  return {
    sessionHome,
    sessionName,
    sessionId,
    workdir,
    cwd,
    nativeSessionId: typeof native === "string" ? native : undefined,
    model: typeof model === "string" ? model : undefined,
    effort: typeof effort === "string" ? effort : undefined,
    prompts: extra.prompts === true,
  }
}

export function createClaudeCoreHost(options: ClaudeCoreHostOptions): ClaudeCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  return createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "claude",
    driver: (registration, ctx) => {
      const prompts = registration.extra?.prompts === true
      const extraModel = typeof registration.extra?.model === "string" ? registration.extra.model : undefined
      const extraEffort = asEffort(registration.extra?.effort)
      const opts: ClaudeOptions = {
        id: "claude",
        command: "claude",
        args: registration.args ? [...registration.args] : [],
        env: registration.env,
        inheritEnv: true,
        model: extraModel,
        effort: extraEffort,
        tools: "default",
        permissionMode: prompts ? undefined : "bypassPermissions",
        permissionPrompts: prompts ? "host" : "none",
        partialMessages: true,
        setupTimeoutMs: 60_000,
        requestTimeoutMs: 30_000,
        shutdownTimeoutMs: 2_000,
        maxFrameBytes: 16 * 1024 * 1024,
        keeper: {
          stateDirectory,
          limits: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 4_000 },
        },
      }
      const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
      return factory ? factory(opts, overrides) : claude(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      const prepared = await prepareClaudeEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers: [],
        skillsPaths: [],
        instructions: claudeWorkerInstructions({ sessionName: extra.sessionName, workdir: extra.workdir }),
        pluginDirs: pluginDirsFor(extra.sessionName),
        addDirs: [promptsDir(STATE_DIR)],
        systemPromptFiles: [],
        strictMcp: false,
        nativeMemory: false,
      })
      return { env: prepared.env, args: prepared.args }
    },
  })
}

export function claudeSessionHome(name: string): string {
  return join(STATE_DIR, "agents", "claude", name)
}
