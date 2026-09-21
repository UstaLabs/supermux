import { createCore, type Core } from "../../../../packages/supermux-core/src/index.js"
import { codex, type CodexOptions } from "../../../../packages/supermux-core/src/codex/index.js"
import type { AgentDriver, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { CoreCodexAdapter, type CoreCodexAdapterOpts } from "./core-adapter"

export type CodexDriverFactory = (options: CodexOptions, overrides: SessionConfiguration) => AgentDriver

export type CodexCoreHostOptions = {
  stateDirectory: string
  driverFactory?: CodexDriverFactory
}

export type CreateCodexAdapterOptions = Omit<CoreCodexAdapterOpts, "core"> & {
  env: Record<string, string>
  command?: string
  args?: string[]
}

type Registered = {
  env: Record<string, string>
  command?: string
  args?: string[]
  adapter: CoreCodexAdapter
  fenceTerminal: () => void
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

/** Process-level owner of one supermux-core instance shared by every Codex session. */
export class CodexCoreHost {
  private readonly core: Core
  private readonly driverFactory?: CodexDriverFactory
  private readonly registered = new Map<string, Registered>()
  private closing = false
  private closed = false
  private closeTail?: Promise<void>

  constructor(private readonly options: CodexCoreHostOptions) {
    if (!options.stateDirectory) throw new Error("stateDirectory is required")
    this.driverFactory = options.driverFactory
    this.core = createCore({
      stateDirectory: options.stateDirectory,
      agents: [this.createHostDriver()],
      limits: { interruptTimeoutMs: 10_000, maxPending: 128, outstandingActivity: 256 },
    })
  }

  createAdapter(options: CreateCodexAdapterOptions): CoreCodexAdapter {
    if (this.closing || this.closed) {
      throw new Error("Codex core host is closing or closed")
    }
    const live = this.registered.get(options.id)
    if (live) {
      throw new Error(`Codex adapter ${options.id} is already live`)
    }
    const { env, command, args, ...adapterOpts } = options
    const clonedEnv = { ...env }
    const adapter = new CoreCodexAdapter({ ...adapterOpts, core: this.core })
    const fenceTerminal = this.bindHostLifetime(adapter)
    this.registered.set(options.id, {
      env: clonedEnv,
      command,
      args: args ? [...args] : undefined,
      adapter,
      fenceTerminal,
    })
    return adapter
  }

  close(): Promise<void> {
    if (this.closed && !this.closeTail) return Promise.resolve()
    this.closing = true
    if (this.closeTail) return this.closeTail
    this.closeTail = this.shutdown().then(
      () => {
        this.closed = true
        this.closeTail = undefined
      },
      (error) => {
        this.closeTail = undefined
        throw error
      },
    )
    return this.closeTail
  }

  private async shutdown(): Promise<void> {
    const entries = [...this.registered.values()]
    for (const entry of entries) entry.fenceTerminal()
    // Invariant: core.close() is the native reaper. It resolves only after every live
    // session and leftover runtime confirmed close (a failed close rejects with an
    // AggregateError and leaves this registry intact for a retry), so clearing the
    // registrations below never drops ownership of a still-running child.
    await this.core.close({ agents: "shutdown" })
    this.registered.clear()
    await Promise.allSettled(entries.map((entry) => entry.adapter.stop()))
  }

  private createHostDriver(): AgentDriver {
    return {
      id: "codex",
      open: (ctx) => this.openNative(ctx),
    }
  }

  private async openNative(ctx: DriverContext) {
    const entry = this.registered.get(ctx.sessionId)
    if (!entry) {
      throw new Error(`No registered Codex context for session ${ctx.sessionId}`)
    }
    const env = { ...entry.env }
    const factory = this.driverFactory
    const options: CodexOptions = {
      ...BROKER_CODEX_OPTIONS,
      id: "codex",
      command: entry.command ?? "codex",
      args: entry.args ? [...entry.args] : ["app-server"],
      env,
      keeper: {
        stateDirectory: this.options.stateDirectory,
        limits: { parkedDeadlineMs: 600_000, journalMaxBytes: 64_000_000, connectTimeoutMs: 4_000 },
      },
      onRuntimeRequest: (info, request) => {
        if (info.sessionId === ctx.sessionId) entry.adapter.attachRuntimeRequest(request)
      },
    }
    const overrides: SessionConfiguration = ctx.configuration ? { ...ctx.configuration } : {}
    const driver = factory ? factory(options, overrides) : codex(options)
    return driver.open(ctx)
  }

  private bindHostLifetime(adapter: CoreCodexAdapter): () => void {
    const originalStart = adapter.start.bind(adapter)
    const originalResume = adapter.resume.bind(adapter)
    const originalStop = adapter.stop.bind(adapter)
    let terminal = false
    let released = false
    let stopping: Promise<void> | undefined
    const fenceTerminal = () => {
      terminal = true
    }
    const assertCanOpen = () => {
      if (this.closing || this.closed) {
        throw new Error("Codex core host is closing or closed")
      }
      if (terminal || this.registered.get(adapter.id)?.adapter !== adapter) {
        throw new Error("Codex adapter is stopped")
      }
    }
    adapter.start = async () => {
      assertCanOpen()
      return originalStart()
    }
    adapter.resume = async () => {
      assertCanOpen()
      return originalResume()
    }
    adapter.stop = async () => {
      if (released) return
      terminal = true
      if (stopping) {
        await stopping
        return
      }
      stopping = (async () => {
        try {
          await originalStop()
          released = true
          const current = this.registered.get(adapter.id)
          if (current?.adapter === adapter) this.registered.delete(adapter.id)
        } finally {
          stopping = undefined
        }
      })()
      await stopping
    }
    return fenceTerminal
  }
}

export function createCodexCoreHost(options: CodexCoreHostOptions): CodexCoreHost {
  return new CodexCoreHost(options)
}
