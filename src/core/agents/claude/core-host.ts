import { readFileSync } from "fs"
import { join } from "path"
import { createHost, type AccountsOptions, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { claude, type ClaudeOptions } from "../../../../packages/supermux-core/src/claude/index.js"
import type { AgentDriver, ExternalMcpServer, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareClaudeEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { CLAUDE_CONTEXT } from "../../../../packages/supermux-core/src/context/agents.js"
import { sessionInstructions } from "../instructions"
import { sessionPlugins } from "../../plugins"
import { SESSION_INBOX_DIR, SOCKETS_DIR, STATE_DIR, inboxFilePath } from "../../../shared/paths"
import { shellReadsFor } from "../shell-reads"
import { makeLogger } from "../../../shared/log"
import { driverSettingsFor, extraPermissionMode } from "../permission-modes"
import { claudeAccountArgs } from "../account-env"
import { claudeCommand } from "./command"
import { ensureBrokerShimEntries } from "../../session-manager/trust"
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
  /** The session inbox folder every session may read without a prompt. Default: SESSION_INBOX_DIR (a test seam). */
  inboxDir?: string
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
  fastMode?: boolean
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

/**
 * A `Read` permission rule for everything under `dir` (Claude's rule syntax: `Read(<path>)`, where an
 * absolute path starts with `//`). Reading only: `--add-dir` would make the folder part of the
 * workspace, and accept-edits / auto modes then allow edits and file-shell commands in it without
 * asking and load its `.claude` skills, commands and agents.
 * Undefined for a folder whose name the rule syntax cannot carry: parentheses, backslashes (the rule's
 * escape), glob characters, control characters, commas, and every non-POSIX path (Windows). Its sessions ask
 * for inbox reads, as they did before, and the host logs a warning (once) that the rule was dropped.
 * Spaces are fine: Claude splits the `--allowedTools` value at spaces and commas OUTSIDE parentheses only
 * (2.1.308), so they survive inside `Read(...)` in the one comma-joined value the core passes. Commas would
 * too, but no real path needs one and refusing them costs nothing if that splitter ever stops honouring parentheses.
 */
export function claudeReadRule(dir: string): string | undefined {
  const folder = dir.replace(/\/+$/, "")
  if (!folder.startsWith("/") || /[()*?[\]\\,\u0000-\u001f\u007f]/.test(folder)) return undefined
  return `Read(/${folder}/**)`
}

/**
 * Exact `Bash` rules that let a session's shell read ONE file, its own inbox: whichever of `cat`, `head`, `tail`
 * and `wc -l` shellReadsFor allows Claude for the file. Exact (no `*`, no `:*`): `cat <file> /etc/passwd` and
 * `tail -n 1 <file>` still ask. Claude compares the command text as typed, so the rules exist only for a path a
 * shell takes unquoted (shellReadsFor says so: the same answer the preamble uses). The spaces inside the rules are
 * fine in the comma-joined `--allowedTools` value (see claudeReadRule), and the inbox folder's `Read` rule sits next to them.
 */
export function claudeInboxShellRules(file: string): string[] {
  const reads = shellReadsFor("claude", file)
  return [
    ...(reads.cat ? [`Bash(${reads.cat} ${file})`] : []),
    ...(reads.head ? [`Bash(${reads.head} ${file})`] : []),
    ...(reads.tail ? [`Bash(${reads.tail} ${file})`] : []),
    ...(reads.wc ? [`Bash(${reads.wc} -l ${file})`] : []),
  ]
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
    fastMode: extra.fastMode === true,
    permissionMode: extraPermissionMode(extra, "claude"),
    pa: extra.pa === true,
    rpcMcpConfig: typeof rpc === "string" ? rpc : undefined,
  }
}

export function createClaudeCoreHost(options: ClaudeCoreHostOptions): ClaudeCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  // The inbox read rule is the same for every session of this host: decided here, and said once when it
  // has to be dropped (a folder name the rule cannot carry, e.g. one with parentheses; any Windows path).
  const inboxDir = options.inboxDir ?? SESSION_INBOX_DIR
  const inboxRule = claudeReadRule(inboxDir)
  if (inboxRule === undefined) log.warn("claude_inbox_read_rule_omitted", { path: inboxDir })
  // The shell rules depend on the session id too, but ids are plain words: the folder decides.
  else if (claudeInboxShellRules(inboxFilePath("session", inboxDir)).length === 0) log.warn("claude_inbox_shell_rules_omitted", { path: inboxDir })
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
        command: claudeCommand(process.env),
        args: await claudeAccountArgs(registration.args ? [...registration.args] : [], {
          sessionHome: typeof registration.extra?.sessionHome === "string" ? registration.extra.sessionHome : claudeSessionHome(String(registration.extra?.sessionName ?? registration.id)),
          account: ctx.account,
        }),
        env: registration.env,
        inheritEnv: true,
        model: extraModel,
        effort: extraEffort,
        fastMode: registration.extra?.fastMode === true,
        tools: "default",
        // The rules every session launches with (--allowedTools): reading its inbox, with the Read tool (the whole
        // inbox folder) and from a shell (exact commands on its own file only).
        allowedTools: [...(inboxRule ? [inboxRule] : []), ...claudeInboxShellRules(inboxFilePath(registration.id, inboxDir))],
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
      // An rpc worker has only the rpc tools (no message_session), so it gets no inbox block.
      const instructions = sessionInstructions({ agent: "claude", sessionName: extra.sessionName, workdir: extra.workdir, id: registration.id, sessionId: extra.rpcMcpConfig ? undefined : registration.id, inboxDir })
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
      // A system-account session reads mux-shim from ~/.claude.json: make sure it is still ours.
      if (!host && !extra.rpcMcpConfig) ensureBrokerShimEntries()
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
