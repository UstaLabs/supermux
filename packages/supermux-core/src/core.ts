import { stat } from "node:fs/promises"
import { isAbsolute, join } from "node:path"
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
import { UsageStore } from "./accounts/usage-store.js"
import { defaultLoginRunner, findCommand, startLogin, type LoginKind } from "./accounts/login.js"
import { assertVaultId } from "./accounts/vault.js"
import type { Account, AddAccountOptions, LoginHandle, LoginOptions, UsageWindow } from "./accounts/types.js"
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
  /** Latest rate-limit windows per account id (from `usage` events), persisted. */
  private readonly usage: UsageStore
  /** Sessions that hit a limit and switch accounts once their current turn ends. */
  private readonly switchQueue = new Set<string>()
  private readonly switching = new Set<string>()
  private readonly continuePrompt: string | undefined
  /** Sessions in a turn, whose current turn saw a full window, and whose last turn ended on a limit. */
  private readonly inTurn = new Set<string>()
  private readonly turnLimited = new Set<string>()
  private readonly lastTurnLimited = new Set<string>()
  /** Token-account sessions: timer to reopen before the access token expires, and sessions due for it. */
  private readonly refreshTimers = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly refreshDue = new Set<string>()
  private readonly logins = new Set<LoginHandle>()

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
    if (accounts.continueAfterSwitch !== undefined && typeof accounts.continueAfterSwitch !== "boolean") throw new TypeError("accounts.continueAfterSwitch must be a boolean")
    if (accounts.continuePrompt !== undefined && (typeof accounts.continuePrompt !== "string" || !accounts.continuePrompt.trim())) throw new TypeError("accounts.continuePrompt must be a nonempty string")
    this.continuePrompt = accounts.continueAfterSwitch === false ? undefined : accounts.continuePrompt ?? DEFAULT_CONTINUE_PROMPT
    const login = accounts.login
    if (login !== undefined && (!login || typeof login !== "object")) throw new TypeError("accounts.login must be an object")
    if (login?.timeoutMs !== undefined) requirePositiveSafeInteger(login.timeoutMs, "accounts.login.timeoutMs")
    if (login?.runner !== undefined && typeof login.runner !== "function") throw new TypeError("accounts.login.runner must be a function")
    if (accounts.fetch !== undefined && typeof accounts.fetch !== "function") throw new TypeError("accounts.fetch must be a function")
    this.registry = new AccountRegistry(options.stateDirectory, [...this.drivers.keys()], accounts)
    this.usage = new UsageStore(join(this.registry.directory, "usage.json"))
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
    /** Removes metadata, secret and usage. A subscription home stays on disk unless `deleteHome` (links are unlinked, never followed). */
    remove: (id: string, options: { deleteHome?: boolean } = {}): Promise<void> => this.operation(async () => {
      await this.ready()
      await this.registry.remove(id, options)
      this.usage.delete(id)
    }),
    /**
     * Guided login: runs the agent's own login CLI in a throwaway directory and turns the result
     * into an account (see API.md "Guided login"). Validation errors throw synchronously.
     */
    login: (options: LoginOptions): LoginHandle => this.login(options),
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
    /** Latest rate-limit windows seen for an account (persisted across restarts while unexpired). */
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
    for (const timer of this.refreshTimers.values()) clearTimeout(timer)
    this.refreshTimers.clear()
    const logins = [...this.logins]
    for (const login of logins) login.cancel()
    const initialCloses = [...this.live.values()].map(session => session.close({ mode: agents }))
    const first = Promise.allSettled(initialCloses)
    await Promise.allSettled([...this.operations])
    const remaining = await Promise.allSettled([...this.cleanup.keys()].map(id => this.closeOwnedRuntime(id, agents)))
    const results = [...await first, ...remaining]
    const errors = results.filter((r): r is PromiseRejectedResult => r.status === "rejected").map(r => r.reason)
    if (errors.length) throw new AggregateError(errors, "One or more agent runtimes failed to close")
    if (this.started) await this.started.catch(() => {})
    await Promise.allSettled(logins.map(login => login.done))
    await this.usage.flush()
    await this.store.close()
    this.live.clear()
    this.events.clear()
  }

  private resume(id: string, options: ResumeOptions | undefined, reason: "manual" | "limit" | "refresh"): Promise<Session> {
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
        if (current.snapshot().state === "closing" || current.snapshot().state === "failed" || switchFrom !== undefined || reason === "refresh") {
          // Failed/closing handle must be shut down before resume can reopen the native agent.
          // An account switch (or a token refresh) needs a fresh process with the new credentials (same native id).
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
      if (reason === "refresh") this.events.emit({ type: "account.refreshed", sessionId: id, account: requestedAccount! })
      else if (switchFrom !== undefined) this.events.emit({ type: "account.switched", sessionId: id, from: switchFrom, to: requestedAccount!, reason })
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
    // A session opens with a token that outlives the idle-reopen margin, so it is not reopened right away.
    const materialized = await this.registry.materialize(await this.account(agent, id), { minValidityMs: SESSION_TOKEN_MARGIN_MS })
    return {
      agent, env: { ...materialized.env },
      ...(materialized.unset?.length ? { unsetEnv: [...materialized.unset] } : {}),
      ...(materialized.args?.length ? { args: [...materialized.args] } : {}),
    }
  }

  /** Usage bookkeeping, the limit switch, its continuation and token refresh reopens. Never throws (it runs as an observer). */
  private observeAccounts(event: CoreEvent): void {
    if (event.type === "message.started") {
      this.inTurn.add(event.sessionId)
      this.turnLimited.delete(event.sessionId)
    } else if (event.type === "message.completed") {
      this.inTurn.delete(event.sessionId)
      const result = event.result
      const hitLimit = this.turnLimited.has(event.sessionId) || result.status === "failed"
        || (result.status === "completed" && LIMIT_STOP_REASON.test(result.stopReason))
      this.turnLimited.delete(event.sessionId)
      if (hitLimit) this.lastTurnLimited.add(event.sessionId)
      else this.lastTurnLimited.delete(event.sessionId)
    } else if (event.type === "session.event" && event.event.kind === "usage" && event.event.rateLimits !== undefined) {
      const record = this.live.get(event.sessionId)?.snapshot()
      if (!record || record.authProfile !== undefined) return
      const windows = adapterFor(record.agent).usage?.(event.event.rateLimits) ?? []
      if (!windows.length) return
      const accountId = record.account ?? systemAccountId(record.agent)
      this.usage.set(accountId, windows)
      if (limited(windows) && this.inTurn.has(event.sessionId)) this.turnLimited.add(event.sessionId)
      if (this.autoSwitch && limited(windows)) {
        this.switchQueue.add(event.sessionId)
        void this.limitSwitch(event.sessionId)
      }
    } else if (event.type === "session.stateChanged") {
      if (event.state === "closed" && !this.restoring.has(event.sessionId)) this.forgetTurns(event.sessionId)
      if (this.switchQueue.has(event.sessionId)) void this.limitSwitch(event.sessionId)
      if (this.refreshDue.has(event.sessionId)) void this.refreshSession(event.sessionId)
    }
  }

  private forgetTurns(id: string): void {
    this.inTurn.delete(id)
    this.turnLimited.delete(id)
    this.lastTurnLimited.delete(id)
    const timer = this.refreshTimers.get(id)
    if (timer) clearTimeout(timer)
    this.refreshTimers.delete(id)
    this.refreshDue.delete(id)
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
      const queued = record.pending > 0
      const from = record.account ?? systemAccountId(record.agent)
      const current = await this.registry.get(from)
      if (!current || !switchable(current)) return
      const candidates = (await this.registry.list(record.agent)).filter(account => account.id !== from)
      const next = pickAccount(candidates, account => this.usage.get(account))
      if (!next) {
        this.events.emit({ type: "account.exhausted", sessionId: id, agent: record.agent, account: from })
        return
      }
      const session = await this.resume(id, { account: next.id }, "limit")
      // The turn that hit the limit continues once on the new account, unless the host queued input itself.
      if (this.continuePrompt !== undefined && !queued && this.lastTurnLimited.delete(id)) {
        try {
          await session.send({ content: [{ type: "text", text: this.continuePrompt }], whenBusy: "reject" })
        } catch (error) {
          if (!(error instanceof CoreError && error.code === "session_busy")) throw error
        }
      }
    } catch (error) {
      try { this.options.onObserverError?.(asError(error)) } catch { /* reporting cannot own control */ }
    } finally { this.switching.delete(id) }
  }

  /** Token accounts whose vault can refresh: reopen the session when its token nears expiry (see refreshSession). */
  private async scheduleRefresh(session: Session): Promise<void> {
    const { id, account: accountId } = session.snapshot()
    const previous = this.refreshTimers.get(id)
    if (previous) clearTimeout(previous)
    this.refreshTimers.delete(id)
    if (accountId === undefined || this.shuttingDown) return
    const account = await this.registry.get(accountId)
    const expiry = account ? await this.registry.refreshableExpiry(account) : undefined
    if (!expiry || this.live.get(id) !== session || this.shuttingDown) return
    const due = expiry.getTime() - SESSION_TOKEN_MARGIN_MS
    const timer = setTimeout(() => {
      this.refreshTimers.delete(id)
      if (this.live.get(id) !== session) return
      if (Date.now() < due) { void this.scheduleRefresh(session).catch(error => this.reportObserverError(error)); return }
      this.refreshDue.add(id)
      void this.refreshSession(id)
    }, Math.min(Math.max(due - Date.now(), 0), MAX_TIMER_MS))
    timer.unref?.()
    this.refreshTimers.set(id, timer)
  }

  /** Reopens an idle token-account session on a fresh token (same account, same native id). Never mid-turn. */
  private async refreshSession(id: string): Promise<void> {
    if (!this.refreshDue.has(id) || this.switching.has(id) || this.shuttingDown) return
    const live = this.live.get(id)
    const snapshot = live?.snapshot()
    if (!live || !snapshot || snapshot.state === "closing" || snapshot.state === "closed" || snapshot.state === "failed") { this.refreshDue.delete(id); return }
    if (snapshot.state !== "idle" || snapshot.pending > 0 || snapshot.account === undefined) return
    this.refreshDue.delete(id)
    this.switching.add(id)
    try {
      await this.resume(id, { account: snapshot.account }, "refresh")
    } catch (error) {
      this.reportObserverError(error)
    } finally { this.switching.delete(id) }
  }

  private reportObserverError(error: unknown): void {
    try { this.options.onObserverError?.(asError(error)) } catch { /* reporting cannot own control */ }
  }

  private login(options: LoginOptions): LoginHandle {
    this.assertOpen()
    if (!options || typeof options !== "object") throw new CoreError("invalid_input", "Login options are required")
    const input = structuredClone(options)
    this.driver(input.agent)
    const kind = adapterFor(input.agent).kind
    if (kind !== "claude" && kind !== "codex" && kind !== "grok" && kind !== "cursor") throw new CoreError("unsupported_operation", `${input.agent} has no guided login (use accounts.add with an API key)`)
    const as = input.as ?? "subscription"
    if (as !== "subscription" && as !== "token") throw new CoreError("invalid_input", "as must be subscription or token")
    if (as === "token" && kind !== "codex") throw new CoreError("unsupported_operation", "Only Codex logins can become token accounts")
    if (input.id !== undefined) assertVaultId(input.id)
    for (const field of ["label", "email"] as const) {
      if (input[field] !== undefined && (typeof input[field] !== "string" || !input[field])) throw new CoreError("invalid_input", `${field} must be a nonempty string`)
    }
    if (input.email !== undefined && kind !== "claude") throw new CoreError("invalid_input", "email is only used by the Claude login")
    if (input.isolated !== undefined && typeof input.isolated !== "boolean") throw new CoreError("invalid_input", "isolated must be a boolean")
    const config = this.options.accounts?.login ?? {}
    const command = config.commands?.[kind] ?? (kind === "cursor" ? findCommand(["cursor-agent", "agent"]) ?? "cursor-agent" : kind)
    const handle = startLogin({
      kind: kind as LoginKind,
      options: { ...input, as },
      pendingDirectory: join(this.registry.directory, "pending"),
      command,
      runner: config.runner ?? defaultLoginRunner,
      timeoutMs: config.timeoutMs ?? LOGIN_TIMEOUT_MS,
      precheck: async () => {
        await this.ready()
        if (input.id !== undefined && await this.registry.get(input.id)) throw new CoreError("account_exists", `Account ${input.id} already exists`)
      },
      promote: async (directory, identity, tokens) => {
        this.assertOpen()
        const base = {
          agent: input.agent, identity,
          ...(input.id !== undefined ? { id: input.id } : {}),
          ...(input.label !== undefined ? { label: input.label } : {}),
          ...(input.isolated ? { isolated: true } : {}),
        }
        return as === "token"
          ? this.registry.add({ ...base, method: "token", secret: tokens! })
          : this.registry.adoptHome({ ...base, method: "subscription" }, directory)
      },
    })
    this.logins.add(handle)
    void handle.done.then(() => {}, () => {}).finally(() => this.logins.delete(handle))
    return handle
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
      if (record.account !== undefined) void this.scheduleRefresh(session).catch(error => this.reportObserverError(error))
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
    return this.started ??= Promise.all([this.store.open(), this.usage.load()]).then(() => {})
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

const DEFAULT_CONTINUE_PROMPT = "Continue from where you stopped. Your previous turn hit a usage limit and you are now on another account."
/** A turn whose stop reason names a limit ended because of it. */
const LIMIT_STOP_REASON = /limit|quota/i
/** Token sessions open with at least this much validity and are reopened (when idle) this long before expiry. */
const SESSION_TOKEN_MARGIN_MS = 30 * 60_000
const LOGIN_TIMEOUT_MS = 10 * 60_000
const MAX_TIMER_MS = 2 ** 31 - 1

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
