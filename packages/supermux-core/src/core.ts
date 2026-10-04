import { stat } from "node:fs/promises"
import { isAbsolute } from "node:path"
import { ACTIVITY_OVERFLOW, applyBufferedActivity, copyActivityNotice } from "./activity.js"
import { assertConfiguration, mergeConfiguration, nonemptyConfiguration, normalizeRequestedConfiguration } from "./configuration.js"
import { CoreError, UnsupportedOperation, asError } from "./errors.js"
import { Events } from "./events.js"
import { requireCloseMode, requireAgentsCloseMode } from "./types.js"
import { Session } from "./session.js"
import { SessionStore } from "./store.js"
import { AccountRegistry, systemAccountId } from "./accounts/registry.js"
import { adapterFor } from "./accounts/adapters/index.js"
import { limited, pickAccount, switchable } from "./accounts/policy.js"
import type { Account, AddAccountOptions, UsageWindow } from "./accounts/types.js"
import type {
  ActivityNotice, AgentDriver, AgentRuntime, AuthProfile, CoreEvent, CoreOptions, CreateOptions, ResumeOptions, AdoptOptions, Observer, SessionRecord, ForkSource,
  SessionConfiguration, CloseMode, CloseOptions, CoreCloseOptions, PermissionsSpec,
} from "./types.js"

export function createCore(options: CoreOptions): Core { return new Core(options) }

export class Core {
  private readonly drivers = new Map<string, AgentDriver>()
  private readonly profiles: Record<string, AuthProfile>
  private readonly store: SessionStore
  private readonly events: Events
  private readonly live = new Map<string, Session>()
  /** Failed-open / leftover runtimes keyed by Core session ID. Deleted only after confirmed close. */
  private readonly cleanup = new Map<string, AgentRuntime>()
  private readonly leftoverClosing = new Map<string, Promise<void>>()
  private readonly restoring = new Map<string, Promise<Session>>()
  /** Resume calls that supplied an explicit `configuration` patch (including `{}`). */
  private readonly restoringOverride = new Set<string>()
  private readonly forgetting = new Set<string>()
  /** IDs claimed by create/adopt (and checked by resume/forget) until persistence or failure. */
  private readonly reserved = new Set<string>()
  /** In-flight create/adopt/fork (and any withReservation body), joinable by sessions.close(id). */
  private readonly opening = new Map<string, Promise<unknown>>()
  private readonly closingById = new Map<string, Promise<void>>()
  /** Set synchronously before awaiting an existing same-ID lifecycle so a new op cannot slip in. */
  private readonly closePending = new Set<string>()
  private readonly operations = new Set<Promise<unknown>>()
  private readonly lifetime = new AbortController()
  private started?: Promise<void>
  private closing?: Promise<void>
  private shuttingDown = false
  private readonly interruptTimeoutMs: number
  private readonly maxPending: number
  private readonly outstandingActivity: number
  private readonly registry: AccountRegistry
  private readonly autoSwitch: boolean
  /** Latest rate-limit windows per account id (from `usage` events). */
  private readonly usage = new Map<string, UsageWindow[]>()
  /** Sessions that hit a limit and switch accounts once their current turn ends. */
  private readonly switchQueue = new Set<string>()
  private readonly switching = new Set<string>()

  constructor(private readonly options: CoreOptions) {
    if (!options.stateDirectory) throw new CoreError("invalid_options", "stateDirectory is required")
    for (const driver of options.agents) {
      if (!driver.id || this.drivers.has(driver.id)) throw new CoreError("invalid_options", "Agent IDs must be nonempty and unique")
      this.drivers.set(driver.id, driver)
    }
    const limits = options.limits
    if (!limits || typeof limits !== "object") throw new TypeError("limits is required")
    this.interruptTimeoutMs = requirePositiveSafeInteger(limits.interruptTimeoutMs, "interruptTimeoutMs")
    this.maxPending = requirePositiveSafeInteger(limits.maxPending, "maxPending")
    this.outstandingActivity = requirePositiveSafeInteger(limits.outstandingActivity, "outstandingActivity")
    this.profiles = structuredClone(options.profiles ?? {})
    this.store = new SessionStore(options.stateDirectory)
    this.events = new Events(options.onObserverError)
    const accounts = options.accounts ?? {}
    if (accounts.autoSwitch !== undefined && typeof accounts.autoSwitch !== "boolean") throw new TypeError("accounts.autoSwitch must be a boolean")
    this.autoSwitch = accounts.autoSwitch === true
    this.registry = new AccountRegistry(options.stateDirectory, [...this.drivers.keys()], accounts)
    this.events.subscribe(event => this.observeAccounts(event))
  }

  subscribe(observer: Observer): () => void { return this.events.subscribe(observer) }

  readonly sessions = {
    create: (options: CreateOptions): Promise<Session> => this.operation(() => {
      const input = structuredClone(options)
      input.configuration = normalizeRequestedConfiguration(input.configuration)
      this.driver(input.agent)
      this.profile(input.agent, input.authProfile)
      assertAccountChoice(input)
      if (typeof input.id !== "string") throw new TypeError("id is required")
      const id = input.id
      assertSessionId(id)
      return this.withReservation(id, async () => {
        await assertWorkdir(input.cwd)
        await this.ready()
        if (input.account !== undefined) await this.account(input.agent, input.account)
        if (await this.store.get(id)) throw new CoreError("session_exists", `Session ${id} already exists`)
        return this.openSession({ ...input, id, createdAt: new Date().toISOString() })
      })
    }),
    adopt: (options: AdoptOptions): Promise<SessionRecord> => this.operation(() => {
      const input = structuredClone(options)
      assertSessionId(input.id)
      if (typeof input.agentSessionId !== "string" || !input.agentSessionId) {
        throw new CoreError("invalid_input", "agentSessionId must be a nonempty string")
      }
      this.driver(input.agent)
      this.profile(input.agent, input.authProfile)
      const createdAt = input.createdAt ?? new Date().toISOString()
      if (typeof createdAt !== "string" || !Number.isFinite(Date.parse(createdAt))) {
        throw new CoreError("invalid_input", "createdAt must be a finite timestamp")
      }
      input.configuration = normalizeRequestedConfiguration(input.configuration)
      return this.withReservation(input.id, async () => {
        await assertWorkdir(input.cwd)
        await this.ready()
        if (await this.store.get(input.id)) throw new CoreError("session_exists", `Session ${input.id} already exists`)
        const record: SessionRecord = {
          version: 1, id: input.id, agent: input.agent, cwd: input.cwd,
          createdAt, agentSessionId: input.agentSessionId,
          ...(input.authProfile ? { authProfile: input.authProfile } : {}),
          ...(nonemptyConfiguration(input.configuration) ? { configuration: structuredClone(input.configuration) } : {}),
        }
        await this.store.put(record)
        this.events.emit({ type: "session.created", sessionId: record.id, record: structuredClone(record) })
        return structuredClone(record)
      })
    }),
    get: (id: string): Promise<SessionRecord | undefined> => this.operation(async () => {
      await this.ready()
      return this.store.get(id)
    }),
    list: (filter: { agent?: string } = {}): Promise<SessionRecord[]> => this.operation(async () => {
      await this.ready()
      return (await this.store.list()).filter(record => !filter.agent || record.agent === filter.agent)
    }),
    resume: (id: string, options?: ResumeOptions): Promise<Session> => this.resume(id, options, "manual"),
    forget: (id: string): Promise<void> => {
      if (this.lifecycleBusy(id) || this.forgetting.has(id) || this.restoring.has(id) || this.reserved.has(id)) {
        return Promise.reject(new CoreError("session_busy", "Session has an outstanding lifecycle operation"))
      }
      this.forgetting.add(id)
      const operation = this.operation(async () => {
        await this.ready()
        if (this.live.has(id) && this.live.get(id)!.snapshot().state !== "closed") throw new CoreError("session_busy", "Close the session before forgetting its record")
        await this.store.remove(id)
        this.live.delete(id)
      })
      void operation.finally(() => this.forgetting.delete(id)).catch(() => {})
      return operation
    },
    /**
     * Close one session id without opening or resuming a native runtime.
     * Joins an in-flight same-id close. Waits any already-started create/adopt/resume
     * (ignoring that setup rejection), then closes a live Session or failed-open leftover.
     * Does not abort a pending custom-driver `open`; waiting that startup then closing
     * the owned runtime is the per-id contract. `core.close({ agents })` still aborts the core lifetime.
     * Unknown ids are idempotent. Failed close retains leftover ownership until a later
     * `sessions.close(id, { mode })` or `core.close({ agents })` confirms cleanup.
     */
    close: (id: string, options: CloseOptions): Promise<void> => {
      try {
        requireCloseMode(options)
        assertSessionId(id)
        const existing = this.closingById.get(id)
        if (existing) return existing
        this.assertOpen()
      } catch (error) { return Promise.reject(asError(error)) }
      const mode = options.mode
      this.closePending.add(id)
      const operation = this.operation(() => Promise.resolve().then(async () => {
        try {
          const opening = this.opening.get(id)
          if (opening) await opening.catch(() => {})
          const restoring = this.restoring.get(id)
          if (restoring) await restoring.catch(() => {})
          const live = this.live.get(id)
          if (live && live.snapshot().state !== "closed") await live.close({ mode })
          await this.closeOwnedRuntime(id, mode)
        } finally {
          this.closePending.delete(id)
        }
      }))
      this.closingById.set(id, operation)
      void operation.finally(() => {
        if (this.closingById.get(id) === operation) this.closingById.delete(id)
      }).catch(() => {})
      return operation
    },
  }

  readonly auth = {
    methods: (options: { agent: string; profile?: string }) => this.operation(async () => {
      const driver = this.driver(options.agent)
      if (!driver.auth) throw new UnsupportedOperation("authentication discovery", driver.id)
      return driver.auth.methods({ profile: this.profile(driver.id, options.profile), signal: this.lifetime.signal })
    }),
    login: (options: { agent: string; profile?: string; methodId: string }): Promise<void> => this.operation(async () => {
      const driver = this.driver(options.agent)
      if (!driver.auth) throw new UnsupportedOperation("authentication", driver.id)
      if (!options.methodId) throw new CoreError("invalid_input", "methodId is required")
      await driver.auth.login({ profile: this.profile(driver.id, options.profile), signal: this.lifetime.signal }, options.methodId)
    }),
  }

  /**
   * Accounts per agent: the built-in `<agent>:system` account (the CLI's own login) plus added
   * api_key / token / subscription accounts. Secrets go to the vault, never to records or events.
   */
  readonly accounts = {
    list: (agent?: string): Promise<Account[]> => this.operation(async () => {
      if (agent !== undefined) this.driver(agent)
      await this.ready()
      return this.registry.list(agent)
    }),
    get: (id: string): Promise<Account | undefined> => this.operation(async () => {
      await this.ready()
      return this.registry.get(id)
    }),
    add: (options: AddAccountOptions): Promise<Account> => this.operation(async () => {
      await this.ready()
      return this.registry.add(options)
    }),
    /** Removes metadata and secret; a subscription home stays on disk. */
    remove: (id: string): Promise<void> => this.operation(async () => {
      await this.ready()
      return this.registry.remove(id)
    }),
    system: (agent: string): Promise<Account> => this.operation(async () => {
      this.driver(agent)
      await this.ready()
      return (await this.registry.get(systemAccountId(agent)))!
    }),
    /** A subscription account's home (created if missing), e.g. to run the CLI's login inside it. */
    home: (id: string): Promise<string> => this.operation(async () => {
      await this.ready()
      return this.registry.home(id)
    }),
    /** The policy's choice among `agent`'s accounts (see accounts/policy.ts); undefined when none is available. */
    pick: (agent: string, exclude: string[] = []): Promise<Account | undefined> => this.operation(async () => {
      this.driver(agent)
      await this.ready()
      const candidates = (await this.registry.list(agent)).filter(account => !exclude.includes(account.id))
      return pickAccount(candidates, id => this.usage.get(id))
    }),
    /** Latest rate-limit windows seen for an account in this process. */
    usage: (id: string): UsageWindow[] | undefined => {
      const windows = this.usage.get(id)
      return windows ? structuredClone(windows) : undefined
    },
  }

  close(options: CoreCloseOptions): Promise<void> {
    const agents = requireAgentsCloseMode(options)
    if (this.closing) return this.closing
    this.shuttingDown = true
    this.closing = Promise.resolve().then(() => this.shutdown(agents)).catch(error => { this.closing = undefined; throw error })
    this.lifetime.abort(new CoreError("core_closed", "Core is closing"))
    return this.closing
  }

  private async shutdown(agents: CloseMode): Promise<void> {
    // No new operations can enter after shuttingDown becomes true.
    const initialCloses = [...this.live.values()].map(session => session.close({ mode: agents }))
    const first = Promise.allSettled(initialCloses)
    await Promise.allSettled([...this.operations])
    const remaining = await Promise.allSettled([...this.cleanup.keys()].map(id => this.closeOwnedRuntime(id, agents)))
    const results = [...await first, ...remaining]
    const errors = results.filter((r): r is PromiseRejectedResult => r.status === "rejected").map(r => r.reason)
    if (errors.length) throw new AggregateError(errors, "One or more agent runtimes failed to close")
    if (this.started) await this.started.catch(() => {})
    await this.store.close()
    this.live.clear()
    this.events.clear()
  }

  private resume(id: string, options: ResumeOptions | undefined, reason: "manual" | "limit"): Promise<Session> {
    if (this.shuttingDown) return Promise.reject(new CoreError("core_closed", "Core is closing or closed"))
    let patch: SessionConfiguration | undefined
    let requestedAccount: string | undefined
    try {
      if (options && options.configuration !== undefined) {
        const snapshot = structuredClone(options)
        assertConfiguration(snapshot.configuration)
        patch = snapshot.configuration
      }
      if (options && options.account !== undefined) {
        if (typeof options.account !== "string" || !options.account) throw new CoreError("invalid_input", "account must be a nonempty string")
        requestedAccount = options.account
      }
    } catch (error) {
      return Promise.reject(asError(error))
    }
    const requestedPatch = patch
    const explicit = requestedPatch !== undefined
    const overriding = explicit || requestedAccount !== undefined
    if (this.lifecycleBusy(id) || this.reserved.has(id) || this.forgetting.has(id)) {
      return Promise.reject(new CoreError("session_busy", "Session has an outstanding lifecycle operation"))
    }
    const pending = this.restoring.get(id)
    if (pending) {
      if (overriding || this.restoringOverride.has(id)) {
        return Promise.reject(new CoreError("session_busy", "Session has an outstanding lifecycle operation"))
      }
      return pending
    }
    const operation = this.operation(async () => {
      await this.ready()
      let switchFrom: string | undefined
      if (requestedAccount !== undefined) {
        const saved = await this.store.get(id)
        if (!saved) throw new CoreError("session_not_found", `Session ${id} was not found`)
        if (saved.authProfile !== undefined) throw new CoreError("invalid_input", "Session uses an authProfile; accounts cannot replace it")
        await this.account(saved.agent, requestedAccount)
        const from = saved.account ?? systemAccountId(saved.agent)
        if (from !== requestedAccount) switchFrom = from
      }
      const current = this.live.get(id)
      if (current && current.snapshot().state !== "closed") {
        if (current.snapshot().state === "closing" || current.snapshot().state === "failed" || switchFrom !== undefined) {
          // Failed/closing handle must be shut down before resume can reopen the native agent.
          // An account switch needs a fresh process with the new credentials (same native id).
          await current.close({ mode: "shutdown" })
        }
        else {
          if (explicit) await current.configure(requestedPatch)
          return current
        }
      }
      const record = await this.store.get(id)
      if (!record) throw new CoreError("session_not_found", `Session ${id} was not found`)
      const original = structuredClone(record)
      const configuration = explicit
        ? mergeConfiguration(record.configuration ?? {}, requestedPatch)
        : record.configuration
      const session = await this.openSession({ ...record, configuration, ...(switchFrom !== undefined ? { account: requestedAccount } : {}) }, record.agentSessionId, undefined, original)
      if (switchFrom !== undefined) this.events.emit({ type: "account.switched", sessionId: id, from: switchFrom, to: requestedAccount!, reason })
      return session
    })
    this.restoring.set(id, operation)
    if (overriding) this.restoringOverride.add(id)
    void operation.finally(() => {
      if (this.restoring.get(id) === operation) this.restoring.delete(id)
      this.restoringOverride.delete(id)
    }).catch(() => {})
    return operation
  }

  /** The account `id` of `agent`, or unknown_account / invalid_input (wrong agent). */
  private async account(agent: string, id: string): Promise<Account> {
    if (typeof id !== "string" || !id) throw new CoreError("invalid_input", "account must be a nonempty string")
    const account = await this.registry.get(id)
    if (!account) throw new CoreError("unknown_account", `Account ${id} was not found`)
    if (account.agent !== agent) throw new CoreError("invalid_input", `Account ${id} belongs to agent ${account.agent}, not ${agent}`)
    return account
  }

  private async accountProfile(agent: string, id: string): Promise<AuthProfile> {
    const materialized = await this.registry.materialize(await this.account(agent, id))
    return {
      agent, env: { ...materialized.env },
      ...(materialized.unset?.length ? { unsetEnv: [...materialized.unset] } : {}),
      ...(materialized.args?.length ? { args: [...materialized.args] } : {}),
    }
  }

  /** Usage bookkeeping and the limit switch. Never throws (it runs as an observer). */
  private observeAccounts(event: CoreEvent): void {
    if (event.type === "session.event" && event.event.kind === "usage" && event.event.rateLimits !== undefined) {
      const record = this.live.get(event.sessionId)?.snapshot()
      if (!record || record.authProfile !== undefined) return
      const windows = adapterFor(record.agent).usage?.(event.event.rateLimits) ?? []
      if (!windows.length) return
      const accountId = record.account ?? systemAccountId(record.agent)
      this.usage.set(accountId, windows)
      if (this.autoSwitch && limited(windows)) {
        this.switchQueue.add(event.sessionId)
        void this.limitSwitch(event.sessionId)
      }
    } else if (event.type === "session.stateChanged" && this.switchQueue.has(event.sessionId)) {
      void this.limitSwitch(event.sessionId)
    }
  }

  /** Runs a queued limit switch once the session's current turn has ended (never mid-turn). */
  private async limitSwitch(id: string): Promise<void> {
    if (this.switching.has(id) || this.shuttingDown) return
    const live = this.live.get(id)
    const state = live?.snapshot().state
    if (!live || state === "closing" || state === "closed") { this.switchQueue.delete(id); return }
    if (state === "running" || state === "interrupting") return
    this.switchQueue.delete(id)
    this.switching.add(id)
    try {
      const record = live.snapshot()
      const from = record.account ?? systemAccountId(record.agent)
      const current = await this.registry.get(from)
      if (!current || !switchable(current)) return
      const candidates = (await this.registry.list(record.agent)).filter(account => account.id !== from)
      const next = pickAccount(candidates, account => this.usage.get(account))
      if (!next) {
        this.events.emit({ type: "account.exhausted", sessionId: id, agent: record.agent, account: from })
        return
      }
      await this.resume(id, { account: next.id }, "limit")
    } catch (error) {
      try { this.options.onObserverError?.(asError(error)) } catch { /* reporting cannot own control */ }
    } finally { this.switching.delete(id) }
  }

  private async openSession(
    input: CreateOptions & { id: string; createdAt: string; lineage?: SessionRecord["lineage"]; configuration?: SessionConfiguration },
    resumeId?: string,
    forkFrom?: ForkSource,
    originalRecord?: SessionRecord,
  ): Promise<Session> {
    this.assertOpen()
    const driver = this.driver(input.agent)
    if (input.account !== undefined && input.authProfile !== undefined) throw new CoreError("invalid_input", "account and authProfile are mutually exclusive")
    const profile = input.account !== undefined ? await this.accountProfile(input.agent, input.account) : this.profile(input.agent, input.authProfile)
    let session: Session | undefined
    let failed: Error | undefined
    let runtime: AgentRuntime | undefined
    let persistenceAttempted = false
    let discarded = false
    const outstandingActivity = new Map<string, ActivityNotice>()
    let attachSession!: (value: Session | null) => void
    const sessionAttached = new Promise<Session | null>(resolve => { attachSession = resolve })
    const deliverActivity = (notice: ActivityNotice) => {
      try {
        if (discarded) return
        const copied = copyActivityNotice(notice)
        if (!copied) return
        if (session) {
          session.reportActivity(copied)
          return
        }
        if (applyBufferedActivity(outstandingActivity, copied, this.outstandingActivity) === "overflow") {
          failed = new CoreError(ACTIVITY_OVERFLOW.code, ACTIVITY_OVERFLOW.message)
          discarded = true
          outstandingActivity.clear()
        }
      } catch (error) {
        if (!failed) failed = asError(error)
        discarded = true
        outstandingActivity.clear()
        if (session) session.fail(asError(error))
      }
    }
    try {
      // A resumed session gets back the subagents it had, so they stay addressable.
      const subagents = resumeId ? await this.store.getSubagents(input.id) : []
      runtime = await driver.open({
        sessionId: input.id, cwd: input.cwd, profile, resumeId, forkFrom, signal: this.lifetime.signal,
        ...(input.account !== undefined ? { account: input.account } : {}),
        ...(subagents.length ? { subagents: structuredClone(subagents) } : {}),
        ...(nonemptyConfiguration(input.configuration) ? { configuration: structuredClone(input.configuration) } : {}),
        ...(input.permissions ? { permissions: structuredClone(input.permissions) } : originalRecord?.permissions ? { permissions: structuredClone(originalRecord.permissions) } : {}),
        onUpdate: update => {
          if (session) session.update(update)
          else this.events.emit({ type: "session.update", sessionId: input.id, update })
        },
        onExit: error => { if (session) session.fail(error); else failed = error },
        requestPermission: async (request, signal) => {
          if (discarded || signal.aborted) return { outcome: { outcome: "cancelled" } }
          const attached = session ?? await Promise.race([
            sessionAttached,
            new Promise<null>(resolve => {
              const done = () => resolve(null)
              if (signal.aborted) done()
              else signal.addEventListener("abort", done, { once: true })
            }),
          ])
          if (!attached || discarded || signal.aborted) return { outcome: { outcome: "cancelled" } }
          return attached.requestPermission(request, signal)
        },
        requestAnswers: async (request, signal) => {
          if (discarded || signal.aborted) return { outcome: "cancelled" as const }
          const attached = session ?? await Promise.race([
            sessionAttached,
            new Promise<null>(resolve => {
              const done = () => resolve(null)
              if (signal.aborted) done()
              else signal.addEventListener("abort", done, { once: true })
            }),
          ])
          if (!attached || discarded || signal.aborted) return { outcome: "cancelled" as const }
          return attached.requestAnswers(request, signal)
        },
        onActivity: deliverActivity,
      })
      this.assertOpen()
      if (failed) throw failed
      if (resumeId && runtime.agentSessionId !== resumeId) throw new CoreError("resume_identity_changed", "Agent returned a different conversation while resuming")
      if (forkFrom && runtime.agentSessionId === forkFrom.agentSessionId) throw new CoreError("fork_identity_unchanged", "Agent reused the source conversation instead of forking")
      if (nonemptyConfiguration(input.configuration) && (!runtime.capabilities.configure || !runtime.configure)) {
        throw new UnsupportedOperation("configure", driver.id)
      }
      const record: SessionRecord = {
        version: 1, id: input.id, agent: input.agent, cwd: input.cwd,
        createdAt: input.createdAt, agentSessionId: runtime.agentSessionId,
        ...(input.authProfile ? { authProfile: input.authProfile } : {}),
        ...(input.account ? { account: input.account } : {}),
        ...(input.lineage ? { lineage: {...input.lineage} } : {}),
        ...(nonemptyConfiguration(input.configuration) ? { configuration: structuredClone(input.configuration) } : {}),
        ...(input.permissions ? { permissions: structuredClone(input.permissions) } : originalRecord?.permissions ? { permissions: structuredClone(originalRecord.permissions) } : {}),
      }
      persistenceAttempted = true
      await this.store.put(record)
      this.assertOpen()
      if (failed) throw failed
      session = new Session(record, runtime, event => this.events.emit(event), this.interruptTimeoutMs, this.maxPending, this.outstandingActivity, () => {
        if (this.live.get(record.id) === session) this.live.delete(record.id)
      }, options => this.operation(() => {
        if (typeof options.id !== "string") throw new TypeError("id is required")
        const id = options.id
        assertSessionId(id)
        return this.withReservation(id, () => this.openSession({
          agent: record.agent, cwd: record.cwd, authProfile: record.authProfile,
          ...(record.account !== undefined ? { account: record.account } : {}),
          id, createdAt: new Date().toISOString(),
          lineage: { parentSessionId: record.id, ...(options.at ? {nativeTurnId: options.at.nativeTurnId} : {}) },
          ...(record.configuration ? { configuration: structuredClone(record.configuration) } : {}),
          ...(record.permissions ? { permissions: structuredClone(record.permissions) } : {}),
        }, undefined, { agentSessionId: record.agentSessionId, ...(options.at ? { at: options.at } : {}) }))
      }), recordToSave => this.store.put(recordToSave), {
        subagents: structuredClone(runtime.restoredSubagents ?? subagents),
        persistSubagents: list => this.store.putSubagents(record.id, list),
      })
      this.live.set(record.id, session)
      attachSession(session)
      for (const notice of outstandingActivity.values()) session.reportActivity(notice)
      outstandingActivity.clear()
      if (failed) throw failed
      this.events.emit({ type: resumeId ? "session.resumed" : "session.created", sessionId: record.id, record: structuredClone(record) })
      return session
    } catch (error) {
      discarded = true
      attachSession(null)
      outstandingActivity.clear()
      let cleanupFailure: Error | undefined
      if (runtime) {
        const owned = runtime
        const existing = this.cleanup.get(input.id)
        if (existing && existing !== owned) {
          // Failed-open duplicate leftover: shut the extra runtime down; it never became a session.
          try { await owned.close({ mode: "shutdown" }) } catch (cleanupError) { cleanupFailure = asError(cleanupError) }
        } else {
          this.cleanup.set(input.id, owned)
          try {
            // Failed open: stop the native process so the identity can be retried.
            await this.closeOwnedRuntime(input.id, "shutdown")
          } catch (cleanupError) {
            cleanupFailure = asError(cleanupError)
          }
        }
      }
      let storageFailure: Error | undefined
      if (persistenceAttempted && originalRecord) {
        try {
          await this.store.put(originalRecord)
        } catch (rollbackError) {
          storageFailure = asError(rollbackError)
        }
      } else if (persistenceAttempted && !resumeId) {
        try {
          await this.store.remove(input.id)
        } catch (removeError) {
          storageFailure = asError(removeError)
        }
      }
      if (storageFailure || cleanupFailure) {
        const errors = [asError(error)]
        if (storageFailure) errors.push(storageFailure)
        if (cleanupFailure) errors.push(cleanupFailure)
        const message = storageFailure && originalRecord
          ? "Session resume failed and the original record could not be restored"
          : cleanupFailure
            ? "Session opening and runtime cleanup failed; retry sessions.close(id) or core.close()"
            : "Session opening failed and session metadata could not be updated"
        throw new AggregateError(errors, message)
      }
      if (this.shuttingDown) throw new CoreError("core_closed", "Core closed while opening a session", { cause: error })
      throw asError(error)
    }
  }

  private ready(): Promise<void> {
    this.assertOpen()
    return this.started ??= this.store.open()
  }

  private driver(id: string): AgentDriver {
    const driver = this.drivers.get(id)
    if (!driver) throw new CoreError("unknown_agent", `Agent ${id} is not registered`)
    return driver
  }

  private profile(agent: string, id?: string): AuthProfile | undefined {
    if (id === undefined) return undefined
    const profile = this.profiles[id]
    if (!profile || profile.agent !== agent) throw new CoreError("invalid_auth_profile", `Profile ${id} does not belong to agent ${agent}`)
    return structuredClone(profile)
  }

  private assertOpen(): void {
    if (this.shuttingDown) throw new CoreError("core_closed", "Core is closing or closed")
  }

  private operation<T>(body: () => Promise<T>): Promise<T> {
    try { this.assertOpen() } catch (error) { return Promise.reject(error) }
    let operation: Promise<T>
    try { operation = body() } catch (error) { return Promise.reject(asError(error)) }
    this.operations.add(operation)
    void operation.finally(() => this.operations.delete(operation)).catch(() => {})
    return operation
  }

  private lifecycleBusy(id: string): boolean {
    return this.cleanup.has(id) || this.closePending.has(id) || this.closingById.has(id)
  }

  private withReservation<T>(id: string, body: () => Promise<T>): Promise<T> {
    if (this.reserved.has(id) || this.restoring.has(id) || this.forgetting.has(id) || this.lifecycleBusy(id)) {
      throw new CoreError("session_busy", "Session has an outstanding lifecycle operation")
    }
    const live = this.live.get(id)
    if (live && live.snapshot().state !== "closed") {
      throw new CoreError("session_busy", "Session has an outstanding lifecycle operation")
    }
    this.reserved.add(id)
    const work = Promise.resolve().then(async () => {
      try { return await body() } finally { this.reserved.delete(id) }
    })
    this.opening.set(id, work)
    void work.finally(() => {
      if (this.opening.get(id) === work) this.opening.delete(id)
    }).catch(() => {})
    return work
  }

  private closeOwnedRuntime(id: string, mode: CloseMode): Promise<void> {
    const inflight = this.leftoverClosing.get(id)
    if (inflight) return inflight
    const owned = this.cleanup.get(id)
    if (!owned) return Promise.resolve()
    const work = Promise.resolve().then(() => owned.close({ mode })).then(() => {
      if (this.cleanup.get(id) === owned) this.cleanup.delete(id)
    }).finally(() => {
      if (this.leftoverClosing.get(id) === work) this.leftoverClosing.delete(id)
    })
    this.leftoverClosing.set(id, work)
    return work
  }
}

async function assertWorkdir(cwd: string): Promise<void> {
  try {
    if (!isAbsolute(cwd) || !(await stat(cwd)).isDirectory()) throw new CoreError("invalid_workdir", "cwd must be an existing absolute directory")
  } catch (error) {
    if (error instanceof CoreError) throw error
    throw new CoreError("invalid_workdir", "cwd must be an existing absolute directory", { cause: error })
  }
}

function assertAccountChoice(input: { account?: unknown; authProfile?: unknown }): void {
  if (input.account === undefined) return
  if (typeof input.account !== "string" || !input.account) throw new CoreError("invalid_input", "account must be a nonempty string")
  if (input.authProfile !== undefined) throw new CoreError("invalid_input", "account and authProfile are mutually exclusive")
}

const SESSION_ID = /^[a-zA-Z0-9_-]{1,128}$/

function assertSessionId(id: string): void {
  if (typeof id !== "string" || !SESSION_ID.test(id)) throw new CoreError("invalid_session_id", "Invalid session ID")
}

function requirePositiveSafeInteger(value: unknown, field: string): number {
  if (!Number.isSafeInteger(value) || (value as number) <= 0) {
    throw new TypeError(`${field} must be a positive safe integer`)
  }
  return value as number
}
