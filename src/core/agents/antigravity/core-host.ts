import { homedir } from "os"
import { join } from "path"
import { createHost, type AccountsOptions, type CoreLimits, type Host, type HostRegistration } from "../../../../packages/supermux-core/src/index.js"
import { antigravity, type AntigravityOptions } from "../../../../packages/supermux-core/src/antigravity/index.js"
import type { AgentDriver, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { prepareAntigravityEnvironment } from "../../../../packages/supermux-core/src/environment/index.js"
import { ANTIGRAVITY_CONTEXT } from "../../../../packages/supermux-core/src/context/agents.js"
import { resolveCommand } from "../../process/launcher"
import { sessionInstructions } from "../instructions"
import { sessionPlugins } from "../../plugins"
import { muxShimContextServer } from "../mux-shim-server"
import { MUX_HOST_SERVERS } from "../../mux-tools/server"
import { makeLogger } from "../../../shared/log"
import { SESSION_INBOX_DIR } from "../../../shared/paths"
import { driverSettingsFor, extraPermissionMode } from "../permission-modes"

const log = makeLogger("agents/antigravity/core-host")

export type AntigravityDriverFactory = (options: AntigravityOptions, overrides: SessionConfiguration) => AgentDriver

export type AntigravityCoreHostOptions = {
  stateDirectory: string
  driverFactory?: AntigravityDriverFactory
  /** Test seam: production always uses the broker policy below. */
  limits?: CoreLimits
  /** The broker's shared account registry (production); tests may omit it. */
  accounts?: AccountsOptions
  /** The real HOME the shadow HOME mirrors (read only). Default: os.homedir(). */
  userHome?: string
}

export type AntigravityCoreHost = Host

export type AntigravityPrepareExtra = {
  sessionHome: string
  sessionName: string
  sessionId: string
  workdir: string
  cwd: string
  nativeSessionId?: string
  permissionMode?: string
}

/** The session's shadow HOME (agy finds its config only through $HOME). */
export function antigravityShadowHome(sessionHome: string): string {
  return join(sessionHome, "home")
}

function asPrepareExtra(registration: HostRegistration): AntigravityPrepareExtra {
  const extra = registration.extra
  if (!extra) throw new Error("antigravity host registration extra is required")
  const { sessionHome, sessionName, sessionId, workdir, cwd } = extra
  if (typeof sessionHome !== "string" || !sessionHome) throw new Error("antigravity extra.sessionHome is required")
  if (typeof sessionName !== "string" || !sessionName) throw new Error("antigravity extra.sessionName is required")
  if (typeof sessionId !== "string" || !sessionId) throw new Error("antigravity extra.sessionId is required")
  if (typeof workdir !== "string" || !workdir) throw new Error("antigravity extra.workdir is required")
  if (typeof cwd !== "string" || !cwd) throw new Error("antigravity extra.cwd is required")
  const native = extra.nativeSessionId
  return {
    sessionHome,
    sessionName,
    sessionId,
    workdir,
    cwd,
    nativeSessionId: typeof native === "string" ? native : undefined,
    permissionMode: extraPermissionMode(extra, "antigravity"),
  }
}

export function createAntigravityCoreHost(options: AntigravityCoreHostOptions): AntigravityCoreHost {
  if (!options.stateDirectory) throw new Error("stateDirectory is required")
  const stateDirectory = options.stateDirectory
  const factory = options.driverFactory
  const userHome = options.userHome ?? homedir()
  return createHost({
    stateDirectory,
    limits: options.limits ?? { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    agent: "antigravity",
    // Instructions ride the first prompt of a new conversation (sessionPreamble); MCP servers and
    // plugins land in the shadow HOME's .gemini/config (never the workdir or the real ~/.gemini).
    context: ANTIGRAVITY_CONTEXT,
    contextPolicy: "warn",
    ...(options.accounts ? { accounts: options.accounts } : {}),
    mcpServers: MUX_HOST_SERVERS,
    driver: async (registration, ctx) => {
      const extra = asPrepareExtra(registration)
      const settings = driverSettingsFor("antigravity", extraPermissionMode(registration.extra, "antigravity"))
      if (settings.initial.kind !== "antigravity") throw new Error("antigravity driver settings mismatch")
      const opts: AntigravityOptions = {
        id: "antigravity",
        command: resolveCommand(["agy"], process.env, process.platform) ?? "agy",
        // agy has no read-only flag: --add-dir makes the inbox folder part of its workspace (edits there too, in accept-edits mode).
        args: ["--add-dir", SESSION_INBOX_DIR],
        env: registration.env,
        inheritEnv: true,
        permissions: settings.initial,
        home: { shadow: antigravityShadowHome(extra.sessionHome), user: userHome },
        setupTimeoutMs: 60_000,
        shutdownTimeoutMs: 2_000,
        maxFrameBytes: 16 * 1024 * 1024,
        interruptGraceMs: 5_000,
        keeper: {
          stateDirectory,
          limits: { parkedDeadlineMs: 120_000, journalMaxBytes: 64 * 1024 * 1024, connectTimeoutMs: 10_000 },
        },
      }
      const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
      return factory ? factory(opts, overrides) : antigravity(opts)
    },
    prepare: async (registration) => {
      const extra = asPrepareExtra(registration)
      const prepared = await prepareAntigravityEnvironment({
        home: antigravityShadowHome(extra.sessionHome),
        userHome,
        platform: process.platform,
      })
      return {
        env: prepared.env,
        context: {
          instructions: sessionInstructions({ agent: "antigravity", sessionName: extra.sessionName, workdir: extra.workdir, sessionId: registration.id }),
          plugins: sessionPlugins("antigravity", extra.sessionName, { onError: (msg) => log.warn("plugins_registry_invalid", { err: msg }) }),
          mcpServers: [muxShimContextServer("antigravity", extra.sessionId, extra.sessionName)],
        },
      }
    },
  })
}
