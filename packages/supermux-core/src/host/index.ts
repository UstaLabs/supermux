import { createCore, Core } from "../core.js"
import { CoreError, asError } from "../errors.js"
import { requireAgentsCloseMode, requireCloseMode, type CloseMode, type CoreLimits, type DriverContext, type SessionConfiguration } from "../types.js"
import type { AgentDriver } from "../types.js"
import type { Session } from "../session.js"

export type HostRegistration = {
  id: string
  env: Record<string, string>
  command?: string
  args?: string[]
  extra?: Record<string, unknown>
}

export type HostStartOptions = {
  cwd: string
  configuration?: SessionConfiguration
  nativeSessionId?: string
  /** Runs after the session opened, still inside the start: a rejection is a
   * failed start (the session is closed; a failed close leaves failed-cleanup). */
  onOpened?: (session: Session) => Promise<void>
}

export type HostHandle = {
  readonly id: string
  start(options: HostStartOptions): Promise<Session>
  resume(options?: { configuration?: SessionConfiguration }): Promise<Session>
  stop(options: { mode: CloseMode }): Promise<void>
  readonly session: Session | undefined
}

export type HostOptions = {
  stateDirectory: string
  limits: CoreLimits
  agent: string
  driver: (registered: HostRegistration, context: DriverContext) => AgentDriver | Promise<AgentDriver>
  /** Runs after admission and before the driver opens (credential/config/home
   * writes). May return an env patch that replaces the registration's env for
   * this and later opens. */
  prepare?: (registration: HostRegistration) => Promise<void | { env?: Record<string, string>; args?: string[] }>
}

export type Host = {
  register(registration: HostRegistration): HostHandle
  readonly core: Core
  close(options: { agents: CloseMode }): Promise<void>
}

type SlotState = "admission" | "recovering" | "starting" | "ready" | "failed-cleanup"

type OwnedSlot =
  | { state: "admission"; token: symbol; handle?: undefined }
  | { state: "recovering"; token: symbol; handle: HostHandleImpl }
  | { state: "starting" | "ready" | "failed-cleanup"; handle: HostHandleImpl }

function cloneRegistration(registration: HostRegistration): HostRegistration {
  return {
    id: registration.id,
    env: { ...registration.env },
    command: registration.command,
    args: registration.args ? [...registration.args] : undefined,
    extra: registration.extra ? { ...registration.extra } : undefined,
  }
}

function alreadyOwnedError(id: string, state: SlotState): CoreError {
  const phase = state === "ready"
    ? "live"
    : state === "failed-cleanup" || state === "recovering"
      ? "awaiting failed-start cleanup"
      : "starting"
  const code = state === "ready" ? "already_live" : "session_busy"
  return new CoreError(code, `session ${id} is already ${phase}`)
}

class HostHandleImpl implements HostHandle {
  readonly id: string
  session: Session | undefined
  terminal = false
  released = false
  stopping?: Promise<void>
  starting?: Promise<Session>
  fenceTerminal = (): void => { this.terminal = true }

  constructor(
    readonly host: HostImpl,
    readonly registration: HostRegistration,
  ) {
    this.id = registration.id
  }

  start(options: HostStartOptions): Promise<Session> {
    return this.host.startHandle(this, options)
  }

  resume(options?: { configuration?: SessionConfiguration }): Promise<Session> {
    return this.host.resumeHandle(this, options)
  }

  stop(options: { mode: CloseMode }): Promise<void> {
    return this.host.stopHandle(this, options)
  }
}

class HostImpl implements Host {
  readonly core: Core
  private readonly agent: string
  private readonly driverFactory: HostOptions["driver"]
  private readonly prepare?: HostOptions["prepare"]
  private readonly handles = new Map<string, HostHandleImpl>()
  private readonly slots = new Map<string, OwnedSlot>()
  private readonly tokens = new Map<string, symbol>()
  private closing = false
  private closed = false
  private closeTail?: Promise<void>

  constructor(options: HostOptions) {
    if (!options.stateDirectory) throw new CoreError("invalid_options", "stateDirectory is required")
    if (!options.agent) throw new CoreError("invalid_options", "agent is required")
    if (typeof options.driver !== "function") throw new CoreError("invalid_options", "driver is required")
    this.agent = options.agent
    this.driverFactory = options.driver
    this.prepare = options.prepare
    this.core = createCore({
      stateDirectory: options.stateDirectory,
      agents: [this.createHostDriver()],
      limits: options.limits,
    })
  }

  register(registration: HostRegistration): HostHandle {
    if (this.closing || this.closed) {
      throw new CoreError("host_closing", "core host is closing or closed")
    }
    const id = registration.id
    const slot = this.slots.get(id)
    if (slot && slot.state !== "failed-cleanup") {
      throw alreadyOwnedError(id, slot.state)
    }
    // A failed-cleanup slot's handle is terminal; a fresh registration may take
    // over the id and its start() retries the leftover cleanup first.
    const live = this.handles.get(id)
    if (live && slot?.state !== "failed-cleanup") {
      throw new CoreError("already_live", `adapter ${id} is already live`)
    }
    const cloned = cloneRegistration(registration)
    const handle = new HostHandleImpl(this, cloned)
    this.handles.set(id, handle)
    return handle
  }

  close(options: { agents: CloseMode }): Promise<void> {
    const agents = requireAgentsCloseMode(options)
    if (this.closed && !this.closeTail) return Promise.resolve()
    this.closing = true
    if (this.closeTail) return this.closeTail
    this.closeTail = this.shutdown(agents).then(
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

  async startHandle(handle: HostHandleImpl, options: HostStartOptions): Promise<Session> {
    this.assertCanOpen(handle)
    if (handle.starting) return handle.starting
    if (handle.session) {
      const state = handle.session.snapshot().state
      if (state !== "closed" && state !== "failed") return handle.session
    }
    const work = this.openWithAdmission(handle, options, "start")
    handle.starting = work
    try {
      return await work
    } finally {
      if (handle.starting === work) handle.starting = undefined
    }
  }

  async resumeHandle(handle: HostHandleImpl, options?: { configuration?: SessionConfiguration }): Promise<Session> {
    this.assertCanOpen(handle)
    const cwd = (handle.registration.extra?.cwd as string | undefined)
    const existing = await this.core.sessions.get(handle.id)
    const nativeSessionId = (handle.registration.extra?.nativeSessionId as string | undefined)
    if (!existing && !nativeSessionId) {
      if (!cwd) throw new CoreError("invalid_input", "cwd is required to start")
      return this.startHandle(handle, { cwd, configuration: options?.configuration })
    }
    if (!cwd && !existing) throw new CoreError("invalid_input", "cwd is required to resume")
    return this.startHandle(handle, {
      cwd: cwd ?? existing!.cwd,
      configuration: options?.configuration,
      nativeSessionId,
    })
  }

  stopHandle(handle: HostHandleImpl, options: { mode: CloseMode }): Promise<void> {
    const mode = requireCloseMode(options)
    if (handle.released) return Promise.resolve()
    handle.terminal = true
    if (handle.stopping) return handle.stopping
    let work: Promise<void> | undefined
    work = (async () => {
      try {
        const inflight = handle.starting
        let startError: unknown
        if (inflight && !this.closing) {
          try { await inflight } catch (err) { startError = err }
        }
        // Only the slot owner may close the core session: a stale handle (a
        // lost takeover race, an evicted registration) would otherwise close
        // the session its replacement is running under the same id.
        const slot = this.slots.get(handle.id)
        const owns = slot !== undefined && "handle" in slot && slot.handle === handle
        if (!owns) {
          handle.session = undefined
          handle.released = true
          if (this.handles.get(handle.id) === handle) this.handles.delete(handle.id)
          return
        }
        try {
          await this.core.sessions.close(handle.id, { mode })
        } catch (err) {
          // The leftover could not be released: the id is failed-cleanup so a
          // retry on this handle or a takeover registration recovers it first.
          this.markFailedCleanup(handle)
          throw startError != null
            ? new Error(`${asError(startError).message}; cleanup failed: ${asError(err).message}`, { cause: asError(startError) })
            : asError(err)
        }
        handle.session = undefined
        handle.released = true
        const current = this.handles.get(handle.id)
        if (current === handle) this.handles.delete(handle.id)
        this.dropOwned(handle)
      } finally {
        if (handle.stopping === work) handle.stopping = undefined
      }
    })()
    handle.stopping = work
    return work
  }

  private assertCanOpen(handle: HostHandleImpl): void {
    if (this.closing || this.closed) {
      throw new CoreError("host_closing", "core host is closing or closed")
    }
    const slot = this.slots.get(handle.id)
    // The failed owner may retry its own cleanup; while one retry is in flight
    // a concurrent one falls through to reserveAdmission's "already awaiting
    // failed-start cleanup" rejection rather than a misleading "stopped".
    const recoveringOwner = (slot?.state === "failed-cleanup" || slot?.state === "recovering") && slot.handle === handle
    if (recoveringOwner) return
    if (handle.terminal || this.handles.get(handle.id) !== handle) {
      throw new CoreError("session_closed", "adapter is stopped")
    }
  }

  private async openWithAdmission(handle: HostHandleImpl, options: HostStartOptions, _mode: "start" | "resume"): Promise<Session> {
    const token = await this.reserveAdmission(handle)
    try {
      if (this.prepare) {
        const patch = await this.prepare(cloneRegistration(handle.registration))
        if (patch && patch.env) handle.registration.env = { ...patch.env }
        if (patch && patch.args) handle.registration.args = [...patch.args]
      }
      this.attachStarting(handle, token)
      const session = await this.openSession(handle, options)
      if (options.onOpened) await options.onOpened(session)
      this.markReady(handle)
      handle.session = session
      return session
    } catch (err) {
      return await this.cleanupFailedStart(handle, err)
    }
  }

  private async openSession(handle: HostHandleImpl, options: HostStartOptions): Promise<Session> {
    const configuration = options.configuration
    const existing = await this.core.sessions.get(handle.id)
    if (existing) {
      return this.core.sessions.resume(handle.id, configuration !== undefined ? { configuration } : undefined)
    }
    if (options.nativeSessionId) {
      await this.core.sessions.adopt({
        id: handle.id,
        agent: this.agent,
        agentSessionId: options.nativeSessionId,
        cwd: options.cwd,
        configuration,
      })
      return this.core.sessions.resume(handle.id, configuration !== undefined ? { configuration } : undefined)
    }
    return this.core.sessions.create({
      id: handle.id,
      agent: this.agent,
      cwd: options.cwd,
      configuration,
    })
  }

  private async reserveAdmission(handle: HostHandleImpl): Promise<symbol> {
    const id = handle.id
    const prior = this.slots.get(id)
    if (prior?.state === "failed-cleanup") {
      const recoverToken = Symbol("host-recover")
      const failedOwner = prior.handle
      this.slots.set(id, { state: "recovering", token: recoverToken, handle: failedOwner })
      try {
        await this.stopHandle(failedOwner, { mode: "shutdown" })
      } catch (stopErr) {
        const cur = this.slots.get(id)
        if (cur?.state === "recovering" && cur.token === recoverToken && cur.handle === failedOwner) {
          this.slots.set(id, { state: "failed-cleanup", handle: failedOwner })
        }
        throw asError(stopErr)
      }
      const cur = this.slots.get(id)
      if (cur?.state !== "recovering" || cur.token !== recoverToken || cur.handle !== failedOwner) {
        throw alreadyOwnedError(id, cur?.state ?? "ready")
      }
      const token = Symbol("host-admission")
      this.slots.set(id, { state: "admission", token })
      this.tokens.set(id, token)
      if (failedOwner === handle) {
        handle.released = false
        handle.terminal = false
        this.handles.set(id, handle)
      }
      return token
    }
    if (prior) throw alreadyOwnedError(id, prior.state)
    const token = Symbol("host-admission")
    this.slots.set(id, { state: "admission", token })
    this.tokens.set(id, token)
    return token
  }

  private attachStarting(handle: HostHandleImpl, token: symbol): void {
    const slot = this.slots.get(handle.id)
    const stored = this.tokens.get(handle.id)
    if (!slot || slot.state !== "admission" || slot.token !== token || stored !== token) {
      throw alreadyOwnedError(handle.id, slot?.state ?? "ready")
    }
    this.slots.set(handle.id, { state: "starting", handle })
  }

  private markReady(handle: HostHandleImpl): void {
    const slot = this.slots.get(handle.id)
    if (slot?.state === "starting" && slot.handle === handle) {
      this.slots.set(handle.id, { state: "ready", handle })
      this.tokens.delete(handle.id)
    }
  }

  private markFailedCleanup(handle: HostHandleImpl): void {
    const slot = this.slots.get(handle.id)
    if (slot && "handle" in slot && slot.handle === handle) {
      this.slots.set(handle.id, { state: "failed-cleanup", handle })
      this.tokens.delete(handle.id)
    }
  }

  private dropOwned(handle: HostHandleImpl): void {
    const slot = this.slots.get(handle.id)
    if (slot && "handle" in slot && slot.handle === handle && slot.state !== "recovering") {
      this.slots.delete(handle.id)
      this.tokens.delete(handle.id)
    }
  }

  private releaseOwnAdmission(id: string, token: symbol): void {
    const slot = this.slots.get(id)
    if (slot?.state === "admission" && slot.token === token) {
      this.slots.delete(id)
      this.tokens.delete(id)
    }
  }

  /** Close leftover without joining `handle.starting` (that would deadlock). */
  private async cleanupFailedStart(handle: HostHandleImpl, cause: unknown): Promise<never> {
    const token = this.tokens.get(handle.id)
    try {
      await this.core.sessions.close(handle.id, { mode: "shutdown" })
      handle.session = undefined
      handle.released = true
      handle.terminal = true
      const current = this.handles.get(handle.id)
      if (current === handle) this.handles.delete(handle.id)
      this.dropOwned(handle)
    } catch (stopErr) {
      handle.terminal = true
      this.markFailedCleanup(handle)
      throw new Error(`${asError(cause).message}; cleanup failed: ${asError(stopErr).message}`, { cause: asError(cause) })
    }
    if (token) this.releaseOwnAdmission(handle.id, token)
    throw asError(cause)
  }

  private createHostDriver(): AgentDriver {
    const id = this.agent
    return {
      id,
      open: async (ctx) => {
        const handle = this.handles.get(ctx.sessionId)
        if (!handle) throw new CoreError("session_not_found", `No registered context for session ${ctx.sessionId}`)
        const driver = await this.driverFactory(cloneRegistration(handle.registration), ctx)
        return driver.open(ctx)
      },
    }
  }

  private async shutdown(agents: CloseMode): Promise<void> {
    const entries = [...this.handles.values()]
    for (const entry of entries) entry.fenceTerminal()
    await this.core.close({ agents })
    this.handles.clear()
    this.slots.clear()
    this.tokens.clear()
    await Promise.allSettled(entries.map((entry) => this.stopHandle(entry, { mode: agents }).catch(() => {})))
  }
}

export function createHost(options: HostOptions): Host {
  return new HostImpl(options)
}
