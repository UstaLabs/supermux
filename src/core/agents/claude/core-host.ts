import { existsSync, readFileSync } from "fs"
import { basename, join } from "path"
import { createHost, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { claude, type ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareClaudeEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import type { McpServerSpec } from "../../../../packages/supermux-core/src/environment/types.js"
import { claudePersonalAssistantInstructions, claudeWorkerInstructions, writeSessionMemoryPreamble } from "./preamble-writer"
import { claudeSpawnArgs } from "../../plugins"
import { environmentMdPath, promptsDir, replyFallbackPath } from "../../runtime-assets"
import { SOCKETS_DIR, STATE_DIR } from "../../../shared/paths"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/claude/core-host")
const CORE_PLUGIN_NAME = "mux-core"

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
  pa?: boolean
  rpcMcpConfig?: string
}

const EFFORTS = new Set(["low", "medium", "high", "xhigh", "max"])

function asEffort(value: unknown): ClaudeOptions["effort"] | undefined {
  if (typeof value !== "string" || !EFFORTS.has(value)) {
    if (value !== undefined && value !== "") log.warn("claude_effort_omitted", { effort: String(value) })
    return undefined
  }
  return value as ClaudeOptions["effort"]
}

function pluginDirsFor(sessionName: string): { dirs: string[]; coreReplyHookPresent: boolean } {
  const { args } = claudeSpawnArgs({ sessionName })
  const dirs: string[] = []
  let coreReplyHookPresent = false
  for (let i = 0; i < args.length; i++) {
    if (args[i] === "--plugin-dir" && args[i + 1]) {
      const dir = args[i + 1]!
      dirs.push(dir)
      if (basename(dir) === CORE_PLUGIN_NAME && existsSync(join(dir, "hooks", "session-start"))) {
        coreReplyHookPresent = true
      }
      i++
    }
  }
  return { dirs, coreReplyHookPresent }
}

function parseRpcMcpServers(path: string): McpServerSpec[] {
  const raw = JSON.parse(readFileSync(path, "utf8")) as { mcpServers?: Record<string, { command?: string; args?: string[]; env?: Record<string, string> }> }
  const servers = raw.mcpServers ?? {}
  return Object.entries(servers).map(([name, v]) => ({
    name,
    command: v.command ?? "",
    args: v.args ?? [],
    env: v.env ?? {},
  }))
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
  const rpc = extra.rpcMcpConfig
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
    pa: extra.pa === true,
    rpcMcpConfig: typeof rpc === "string" ? rpc : undefined,
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
      const { dirs: pluginDirs, coreReplyHookPresent } = pluginDirsFor(extra.sessionName)
      const instructions = extra.pa
        ? claudePersonalAssistantInstructions({ sessionName: extra.sessionName, workdir: extra.workdir })
        : claudeWorkerInstructions({ sessionName: extra.sessionName, workdir: extra.workdir })
      const systemPromptFiles: string[] = extra.pa
        ? [
            environmentMdPath(STATE_DIR),
            writeSessionMemoryPreamble(
              extra.sessionId,
              extra.sessionName,
              "personal_assistant",
              extra.workdir,
            ),
            ...(!coreReplyHookPresent ? [replyFallbackPath(STATE_DIR)] : []),
          ]
        : []
      const mcpServers = extra.rpcMcpConfig ? parseRpcMcpServers(extra.rpcMcpConfig) : []
      const prepared = await prepareClaudeEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers,
        skillsPaths: [],
        instructions,
        pluginDirs,
        addDirs: [promptsDir(STATE_DIR)],
        systemPromptFiles,
        strictMcp: extra.rpcMcpConfig ? true : false,
        nativeMemory: false,
        coreReplyContract: true,
      })
      // Claude keeps the user's global ~/.claude.json, whose `mux-shim` MCP entry
      // reads the session identity from the PROCESS env (the tmux-era spawn set
      // it the same way); without these the shim registers as a random id on
      // the default sockets dir and the session's tools never connect.
      const identity = {
        MUX_SESSION_ID: extra.sessionId,
        MUX_DISPLAY_NAME: extra.sessionName,
        MUX_AGENT_KIND: "claude",
        MUX_SOCKETS_DIR: SOCKETS_DIR,
        MUX_SESSION_ROLE: extra.pa ? "personal_assistant" : "worker",
      }
      return { env: { ...prepared.env, ...identity }, args: prepared.args }
    },
  })
}

export function claudeSessionHome(name: string): string {
  return join(STATE_DIR, "agents", "claude", name)
}
