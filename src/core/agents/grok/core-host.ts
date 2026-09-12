import { createCore, type Core } from "../../../../packages/supermux-core/src/index.js"
import { grok, type GrokOptions } from "../../../../packages/supermux-core/src/agents/index.js"
import type { AgentDriver, DriverContext, SessionConfiguration } from "../../../../packages/supermux-core/src/index.js"
import { CoreGrokAdapter, type CoreGrokAdapterOpts } from "./core-adapter"

export type GrokDriverFactory = (options: GrokOptions, overrides: SessionConfiguration) => AgentDriver

export type GrokCoreHostOptions = {
  stateDirectory: string
  driverFactory?: GrokDriverFactory
}

export type CreateGrokAdapterOptions = Omit<CoreGrokAdapterOpts, "core"> & {
  env: Record<string, string>
}

type Registered = {
  env: Record<string, string>
  adapter: CoreGrokAdapter
  fenceTerminal: () => void
}

/** Process-level owner of one supermux-core instance shared by every Grok session. */
export class GrokCoreHost {
  private readonly core: Core
  private readonly driverFactory?: GrokDriverFactory
  private readonly registered = new Map<string, Registered>()
  private closing = false
  private closed = false
  private closeTail?: Promise<void>

  constructor(options: GrokCoreHostOptions) {
    if (!options.stateDirectory) throw new Error("stateDirectory is required")
    this.driverFactory = options.driverFactory
    this.core = createCore({
      stateDirectory: options.stateDirectory,
      agents: [this.createHostDriver()],
    })
  }

  createAdapter(options: CreateGrokAdapterOptions): CoreGrokAdapter {
    if (this.closing || this.closed) {
      throw new Error("Grok core host is closing or closed")
    }
    const live = this.registered.get(options.id)
    if (live) {
      throw new Error(`Grok adapter ${options.id} is already live`)
    }
    const { env, ...adapterOpts } = options
    const clonedEnv = { ...env }
    const adapter = new CoreGrokAdapter({ ...adapterOpts, core: this.core })
    const fenceTerminal = this.bindHostLifetime(adapter)
    this.registered.set(options.id, { env: clonedEnv, adapter, fenceTerminal })
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
    // Abort Core lifetime immediately so in-flight driver.open can exit.
    // Per-adapter sessions.close(id) can reject core_closed once shuttingDown;
    // do not treat that as confirmed native release.
    // Invariant: core.close() is the native reaper. It resolves only after every live
    // session and leftover runtime confirmed close (a failed close rejects with an
    // AggregateError and leaves this registry intact for a retry), so clearing the
    // registrations below never drops ownership of a still-running child.
    await this.core.close()
    this.registered.clear()
    await Promise.allSettled(entries.map((entry) => entry.adapter.stop()))
  }

  private createHostDriver(): AgentDriver {
    return {
      id: "grok",
      open: (ctx) => this.openNative(ctx),
    }
  }

  private async openNative(ctx: DriverContext) {
    const entry = this.registered.get(ctx.sessionId)
    if (!entry) {
      throw new Error(`No registered Grok context for session ${ctx.sessionId}`)
    }
    const env = { ...entry.env }
    const factory = this.driverFactory
    const driver = factory
      ? grok({ env, noLeader: false, alwaysApprove: true }, factory)
      : grok({ env, noLeader: false, alwaysApprove: true })
    return driver.open(ctx)
  }

  private bindHostLifetime(adapter: CoreGrokAdapter): () => void {
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
        throw new Error("Grok core host is closing or closed")
      }
      if (terminal || this.registered.get(adapter.id)?.adapter !== adapter) {
        throw new Error("Grok adapter is stopped")
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

export function createGrokCoreHost(options: GrokCoreHostOptions): GrokCoreHost {
  return new GrokCoreHost(options)
}
