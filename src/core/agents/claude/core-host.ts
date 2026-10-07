import { readFileSync } from "fs"
import { join } from "path"
import { createHost, type AccountsOptions, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { claude, type ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import type { AgentDriver, ExternalMcpServer, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareClaudeEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { CLAUDE_CONTEXT } from "../../../../packages/supermux-core/src/context/agents.js"
import { sessionInstructions } from "../instructions"
import { sessionPlugins } from "../../plugins"
import { SOCKETS_DIR, STATE_DIR } from "../../../shared/paths"
import { makeLogger } from "../../../shared/log"
import { driverSettingsFor, extraPermissionMode } from "../permission-modes"
import { claudeAccountArgs } from "../account-env"
import { MUX_HOST_SERVERS, muxRpcHostServer, muxShimHostServer } from "../../mux-tools/server"
import { muxShimMode, muxShimModeFor } from "../../mux-tools/mode"
import { watchClaudeMuxShimDuplicates } from "../../mux-tools/duplicates"

const log = makeLogger("agents/claude/core-host")

export type ClaudeDriverFactory = (options: ClaudeOptions, overrides: SessionConfiguration) => AgentDriver

export type ClaudeCoreHostOptions = {
  stateDirectory: string
  driverFactory?: ClaudeDriverFactory
  limits?: CoreLimits
  /** The broker's shared account registry (production); tests may omit it. */
  accounts?: AccountsOptions
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
  permissionMode?: string
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

function pluginsFor(sessionName: string): string[] {
  return sessionPlugins("claude", sessionName, { onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) })
}

function parseRpcMcpServers(path: string): ExternalMcpServer[] {
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
    permissionMode: extraPermissionMode(extra, "claude"),
    pa: extra.pa === true,
    rpcMcpConfig: typeof rpc === "string" ? rpc : undefined,
  }
}

export function createClaudeCoreHost(options: ClaudeCoreHostOptions): ClaudeCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  const host = createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "claude",
    // Instructions, plugins and the rpc MCP servers are session context, applied by the core
    // (C3). "warn": a plugin part Claude cannot load never blocks a launch (none today).
    context: CLAUDE_CONTEXT,
    contextPolicy: "warn",
    ...(options.accounts ? { accounts: options.accounts } : {}),
    // The broker's host MCP servers (C3b), registered in BOTH mux-shim modes so a record that
    // names one resumes after a flip back to "external" (see mux-tools/mode.ts).
    mcpServers: MUX_HOST_SERVERS,
    driver: async (registration, ctx) => {
      const extraModel = typeof registration.extra?.model === "string" ? registration.extra.model : undefined
      const extraEffort = asEffort(registration.extra?.effort)
      const settings = driverSettingsFor("claude", extraPermissionMode(registration.extra, "claude"))
      if (settings.initial.kind !== "claude") throw new Error("claude driver settings mismatch")
      const opts: ClaudeOptions = {
        id: "claude",
        command: "claude",
        args: await claudeAccountArgs(registration.args ? [...registration.args] : [], {
          sessionHome: typeof registration.extra?.sessionHome === "string" ? registration.extra.sessionHome : claudeSessionHome(String(registration.extra?.sessionName ?? registration.id)),
          account: ctx.account,
        }),
        env: registration.env,
        inheritEnv: true,
        model: extraModel,
        effort: extraEffort,
        tools: "default",
        permissionMode: settings.initial.permissionMode === "default" ? undefined : settings.initial.permissionMode,
        permissions: settings.initial,
        permissionPrompts: settings.permissionPrompts,
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
      const plugins = pluginsFor(extra.sessionName)
      // ONE instructions value (a PA's used to be several appended files, of which Claude kept
      // only the last). Fixed when the session is created; an existing session keeps its own.
      const instructions = sessionInstructions({ agent: "claude", sessionName: extra.sessionName, workdir: extra.workdir, pa: extra.pa })
      // "external" (C3a): mux-shim is NOT a context server for Claude: it comes from the user's
      // ~/.claude.json (system account, written by session-manager/trust.ts) or from the
      // account's extra --mcp-config (account-env.ts). Adding it here too would register its tools
      // twice. An rpc worker's own servers are context servers; --strict-mcp-config (a host arg)
      // keeps the ~/.claude.json servers out of it, as before.
      // "host" (C3b): the broker's host server is the context's mux-shim (rpc workers: mux-rpc)
      // for every account; nothing is written into ~/.claude.json, and a leftover global
      // `mux-shim` entry is shadowed: Claude keeps the --mcp-config server of the same name
      // (claude 2.1.289, probed: one mux-shim in the init frame, the --mcp-config one).
      const host = muxShimModeFor("claude") === "host"
      const mcpServers = extra.rpcMcpConfig
        ? (host ? [muxRpcHostServer] : parseRpcMcpServers(extra.rpcMcpConfig))
        : (host ? [muxShimHostServer] : [])
      const prepared = await prepareClaudeEnvironment({
        home: extra.sessionHome,
        workdir: extra.workdir,
        mcpServers: [],
        skillsPaths: [],
        pluginDirs: [],
        addDirs: [],
        systemPromptFiles: [],
        strictMcp: false,
        nativeMemory: false,
      })
      const args = extra.rpcMcpConfig ? [...(prepared.args ?? []), "--strict-mcp-config"] : (prepared.args ?? [])
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
      return {
        env: { ...prepared.env, ...identity },
        args,
        context: { instructions, plugins, ...(mcpServers.length ? { mcpServers } : {}) },
      }
    },
  })
  // C3b: log a session that ends up with two mux-shim servers (init frame).
  watchClaudeMuxShimDuplicates(host.core, log, muxShimMode)
  return host
}

export function claudeSessionHome(name: string): string {
  return join(STATE_DIR, "agents", "claude", name)
}
