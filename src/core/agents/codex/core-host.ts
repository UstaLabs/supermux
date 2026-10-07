import { rmSync } from "fs"
import { join } from "path"
import { createHost, type AccountsOptions, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { codex, type CodexOptions } from "../../../../packages/supermux-core/src/codex/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareCodexEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { CODEX_CONTEXT } from "../../../../packages/supermux-core/src/context/agents.js"
import { sessionInstructions } from "../instructions"
import { sessionPlugins } from "../../plugins"
import { muxShimContextServer } from "../mux-shim-server"
import { MUX_HOST_SERVERS } from "../../mux-tools/server"
import { makeLogger } from "../../../shared/log"
import { HOME } from "../../session-manager/spawn-helper"
import { driverSettingsFor, extraPermissionMode } from "../permission-modes"
import { syncCodexSessionCredential } from "../account-env"
import { isSystemAccount } from "../../accounts/broker-accounts"

const log = makeLogger("agents/codex/core-host")

/**
 * Instructions reach Codex only as thread/start developerInstructions (session context). Before
 * C3 they were written to <CODEX_HOME>/AGENTS.md, which Codex re-reads on every launch; a file (and
 * its marker) left in a reused session home would show a session its instructions twice, so they
 * are removed on prepare.
 */
function removeLegacyAgentsMd(home: string): void {
  rmSync(join(home, "AGENTS.md"), { force: true })
  rmSync(join(home, ".supermux-agents-md-session"), { force: true })
}

export type CodexDriverFactory = (options: CodexOptions, overrides: SessionConfiguration) => AgentDriver

export type CodexCoreHostOptions = {
  stateDirectory: string
  driverFactory?: CodexDriverFactory
  /** Test seam: production always uses the broker policy below. */
  limits?: CoreLimits
  /** The broker's shared account registry (production); tests may omit it. */
  accounts?: AccountsOptions
}

export type CodexCoreHost = Host

export type CodexPrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
  permissionMode?: string
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

/** The broker's app-server command line. The session's sandbox / approval policy are added by
 *  the core per process (`-c sandbox_mode / approval_policy`), plugins and mux-shim by the
 *  session context. */
export const BROKER_CODEX_ARGS = ["app-server"]

const BROKER_CODEX_OPTIONS: Pick<CodexOptions, "approvalPolicy" | "sandbox" | "permissionPrompts" | "inheritEnv" | "setupTimeoutMs" | "requestTimeoutMs" | "shutdownTimeoutMs" | "maxFrameBytes" | "permissions"> = {
  approvalPolicy: "never",
  sandbox: "danger-full-access",
  permissionPrompts: "host",
  permissions: { kind: "codex", approvalPolicy: "never", sandbox: "danger-full-access" },
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
    permissionMode: extraPermissionMode(extra, "codex"),
  }
}

export function createCodexCoreHost(options: CodexCoreHostOptions): CodexCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  const host = createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "codex",
    // Instructions, plugins and mux-shim are session context, applied by the core (C3).
    // "warn": plugin parts Codex cannot map (hooks, commands, agents) are dropped with a
    // context.degraded event instead of refusing the launch.
    context: CODEX_CONTEXT,
    contextPolicy: "warn",
    ...(options.accounts ? { accounts: options.accounts } : {}),
    // The broker's host MCP servers (C3b), registered in BOTH mux-shim modes so a record that
    // names one resumes after a flip back to "external" (see mux-tools/mode.ts).
    mcpServers: MUX_HOST_SERVERS,
    driver: (registration, ctx) => {
      // Every open (also a Core-internal account switch): no credential copy on an account.
      const sessionHome = registration.extra?.sessionHome
      if (typeof sessionHome === "string" && sessionHome) {
        syncCodexSessionCredential({ sessionHome, canonicalHome: join(HOME, ".codex"), account: ctx.account, systemApiKey: process.env.OPENAI_API_KEY })
      }
      const settings = driverSettingsFor("codex", extraPermissionMode(registration.extra, "codex"))
      if (settings.initial.kind !== "codex") throw new Error("codex driver settings mismatch")
      const opts: CodexOptions = {
        ...BROKER_CODEX_OPTIONS,
        approvalPolicy: settings.initial.approvalPolicy,
        sandbox: settings.initial.sandbox,
        permissionPrompts: settings.permissionPrompts,
        permissions: settings.initial,
        id: "codex",
        command: registration.command ?? "codex",
        args: registration.args ? [...registration.args] : [...BROKER_CODEX_ARGS],
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
        mcpServers: [],
        skillsPaths: [],
        // On an account the adapter injects the credential; the session gets no copy.
        credentials: isSystemAccount(registration.account)
          ? { apiKey: process.env.OPENAI_API_KEY ?? null, canonicalHome: join(HOME, ".codex") }
          : { apiKey: null, canonicalHome: join(HOME, ".codex"), account: true },
        nativeMemory: false,
      })
      removeLegacyAgentsMd(extra.sessionHome)
      return {
        env: prepared.env,
        context: {
          instructions: sessionInstructions({ agent: "codex", sessionName: extra.sessionName, workdir: extra.workdir }),
          plugins: sessionPlugins("codex", extra.sessionName, { onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) }),
          mcpServers: [muxShimContextServer("codex", extra.sessionId, extra.sessionName)],
        },
      }
    },
  })
  return host
}
