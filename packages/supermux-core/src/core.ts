import { mkdir, rm, stat } from "node:fs/promises"
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
import {
  contextDrops, contextFingerprint, isContextEmpty, isEmptyContext, isHostEntry, joinInstructions, mergeContexts, noContextCapabilities, normalizeContext, normalizeContextUpdate, normalizePolicy,
  resolveContext, sameContext, unsupportedError, withoutInstructions, type HostResolver,
} from "./context/index.js"
import { isHostMcpServer } from "./mcp/brand.js"
import { BRIDGE_ENV, McpHost, bridgeEntry, type HostLookup } from "./mcp/host.js"
import type { HostMcpServer, ToolChange } from "./mcp/server.js"
import { applyChanges, changeKey, normalizePatch, normalizeUpdateOptions, patchChanges, planChanges, reloadOnlyUpdates } from "./context/update.js"
import type {
  AgentCapabilities, ContextApplied, ContextDrop, ContextMcpServer, ContextPatch, ContextPolicy, LaunchContext, ResolvedContext, ResolvedMcpServer, SessionContext,
  ContextUpdateKind, ToolChangeApplied, UpdateContextOptions, UpdateContextResult,
} from "./context/types.js"
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
  private readonly autoSwitch: () => boolean
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
  private readonly defaultContext: SessionContext | undefined
  private readonly defaultPolicy: ContextPolicy
  /** updateContext calls per session, run one after another. */
  private readonly contextUpdates = new Map<string, Promise<unknown>>()
  /** Host MCP servers by name (core.mcp), each with its tool-change subscription. */
  private readonly hostServers = new Map<string, { server: HostMcpServer; unsubscribe: () => void }>()
  private mcpHostInstance?: McpHost
  /** Sessions being launched: a bridge may connect before the record exists. */
  private readonly hostLaunching = new Map<string, { agent: string; account?: string; servers: Set<string> }>()
  /** Tool changes waiting for a session's relaunch (agents that ignore tools/list_changed). */
  private readonly toolReloads = new Map<string, ToolChangeApplied[]>()

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
    if (options.mcpServers !== undefined) {
      if (!Array.isArray(options.mcpServers)) throw new CoreError("invalid_options", "mcpServers must be an array of mcpServer() values")
      for (const server of options.mcpServers) this.registerHost(server, "invalid_options")
    }
    this.defaultContext = this.adoptHosts(normalizeContext(options.context, "options.context"))
    this.defaultPolicy = normalizePolicy(options.contextPolicy, "options.contextPolicy") ?? "error"
    this.store = new SessionStore(options.stateDirectory)
    this.events = new Events(options.onObserverError)
    const accounts = options.accounts ?? {}
    const autoSwitch = accounts.autoSwitch
    if (autoSwitch !== undefined && typeof autoSwitch !== "boolean" && typeof autoSwitch !== "function") throw new TypeError("accounts.autoSwitch must be a boolean or a function")
    this.autoSwitch = typeof autoSwitch === "function"
      ? () => { try { return autoSwitch() === true } catch { return false } }
      : () => autoSwitch === true
    if (accounts.continueAfterSwitch !== undefined && typeof accounts.continueAfterSwitch !== "boolean") throw new TypeError("accounts.continueAfterSwitch must be a boolean")
    if (accounts.continuePrompt !== undefined && (typeof accounts.continuePrompt !== "string" || !accounts.continuePrompt.trim())) throw new TypeError("accounts.continuePrompt must be a nonempty string")
    this.continuePrompt = accounts.continueAfterSwitch === false ? undefined : accounts.continuePrompt ?? DEFAULT_CONTINUE_PROMPT
    const login = accounts.login
    if (login !== undefined && (!login || typeof login !== "object")) throw new TypeError("accounts.login must be an object")
    if (login?.timeoutMs !== undefined) requirePositiveSafeInteger(login.timeoutMs, "accounts.login.timeoutMs")
    if (login?.runner !== undefined && typeof login.runner !== "function") throw new TypeError("accounts.login.runner must be a function")
    if (accounts.fetch !== undefined && typeof accounts.fetch !== "function") throw new TypeError("accounts.fetch must be a function")
    if (accounts.registry !== undefined) {
      if (!(accounts.registry instanceof AccountRegistry)) throw new TypeError("accounts.registry must be an AccountRegistry")
      const missing = [...this.drivers.keys()].filter(agent => !accounts.registry!.covers(agent))
      if (missing.length) throw new CoreError("invalid_options", `accounts.registry does not know agent(s) ${missing.join(", ")}`)
    }
    if (accounts.usage !== undefined && !(accounts.usage instanceof UsageStore)) throw new TypeError("accounts.usage must be a UsageStore")
    this.registry = accounts.registry ?? new AccountRegistry(options.stateDirectory, [...this.drivers.keys()], accounts)
    this.usage = accounts.usage ?? new UsageStore(join(this.registry.directory, "usage.json"))
    this.events.subscribe(event => this.observeAccounts(event))
    // A session interrupt aborts its in-flight host tool calls too.
    this.events.subscribe(event => {
      if (event.type === "session.stateChanged" && event.state === "interrupting") this.mcpHostInstance?.interrupt(event.sessionId)
    })
  }

  subscribe(observer: Observer): () => void { return this.events.subscribe(observer) }

  /** What `agent`'s driver can apply (today: the session context table). */
  capabilities(agent: string): AgentCapabilities {
    const support = this.driver(agent).context
    const context = support?.capabilities ?? noContextCapabilities()
    return {
      context: structuredClone(context), contextUpdate: structuredClone(support?.update ?? reloadOnlyUpdates(context)),
      hostToolChanges: support?.mcpListChanged ? "live" : "reload",
    }
  }

  /**
   * Host MCP servers (see API.md "Host MCP servers"). Sessions name them in their context; every
   * launch (create, resume, reload, keeper relaunch) resolves the names here, and a missing one
   * is `missing_mcp_servers`. A server object passed in a context is registered automatically.
   */
  readonly mcp = {
    register: (server: HostMcpServer): void => { this.assertOpen(); this.registerHost(server, "invalid_input") },
    /** Removes a server: later launches that name it fail with missing_mcp_servers; its open connections are closed (their bridges answer "host unavailable"). */
    unregister: (name: string): boolean => {
      const entry = this.hostServers.get(name)
      if (!entry) return false
      entry.unsubscribe()
      this.hostServers.delete(name)
      this.mcpHostInstance?.disconnectServer(name)
      return true
    },
    get: (name: string): HostMcpServer | undefined => this.hostServers.get(name)?.server,
    list: (): string[] => [...this.hostServers.keys()],
    /** The Unix socket the bridges connect to (undefined until a session with a host server launched). */
    socket: (): string | undefined => this.mcpHostInstance?.socket,
  }

  readonly sessions = {
    create: (options: CreateOptions): Promise<Session> => this.operation(() => {
      // `context` may hold host MCP server objects (code): registered and replaced by references, never cloned.
      const { context: rawContext, ...rest } = options ?? {} as CreateOptions
      const input: CreateOptions = structuredClone(rest) as CreateOptions
      input.configuration = normalizeRequestedConfiguration(input.configuration)
      this.driver(input.agent)
      this.profile(input.agent, input.authProfile)
      assertAccountChoice(input)
      input.context = this.adoptHosts(normalizeContext(rawContext))
      input.contextPolicy = normalizePolicy(options.contextPolicy)
      if (input.context === undefined) delete input.context
      if (input.contextPolicy === undefined) delete input.contextPolicy
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
    /**
     * The open Session for `id` right now, without any lifecycle work (undefined when none is open
     * or it is closed). After `account.switched` / `account.refreshed` this is the reopened one.
     */
    live: (id: string): Session | undefined => {
      const session = this.live.get(id)
      return session && session.snapshot().state !== "closed" ? session : undefined
    },
    list: (filter: { agent?: string } = {}): Promise<SessionRecord[]> => this.operation(async () => {
      await this.ready()
      return (await this.store.list()).filter(record => !filter.agent || record.agent === filter.agent)
    }),
    resume: (id: string, options?: ResumeOptions): Promise<Session> => this.resume(id, options, "manual"),
    /**
     * True while the core itself is replacing the session's agent process (a context or
     * host-tool reload, an account limit switch, a token refresh): the old `Session` closes and
     * `live(id)` returns the new one once it is open. A host can tell this apart from an agent
     * that died.
     */
    reopening: (id: string): boolean => this.switching.has(id),
    /**
     * `session.updateContext` by id. For a session that is not open it only updates the record
     * (every applicable change is `reload`: the next launch applies it); an open session is
     * updated like `session.updateContext`.
     */
    updateContext: (id: string, patch: ContextPatch, options?: UpdateContextOptions): Promise<UpdateContextResult> => this.updateContext(id, patch, options),
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
    for (const entry of this.hostServers.values()) entry.unsubscribe()
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
    // Detached agents keep their bridges: they answer "host unavailable" and reconnect to the next core.
    await this.mcpHostInstance?.close().catch(() => {})
    if (errors.length) throw new AggregateError(errors, "One or more agent runtimes failed to close")
    if (this.started) await this.started.catch(() => {})
    await Promise.allSettled(logins.map(login => login.done))
    await this.usage.flush()
    await this.store.close()
    this.live.clear()
    this.events.clear()
  }

  private resume(id: string, options: ResumeOptions | undefined, reason: "manual" | "limit" | "refresh" | "context"): Promise<Session> {
    if (this.shuttingDown) return Promise.reject(new CoreError("core_closed", "Core is closing or closed"))
    let patch: SessionConfiguration | undefined
    let requestedAccount: string | undefined
    let requestedContext: SessionContext | undefined
    let adoptInstructions: string[] | undefined
    try {
      if (options && options.context !== undefined) requestedContext = this.adoptHosts(normalizeContextUpdate(options.context))
      if (options && options.adoptInstructions !== undefined) adoptInstructions = normalizeContext({ instructions: options.adoptInstructions }, "options.adoptInstructions")!.instructions as string[] | undefined
      if (adoptInstructions !== undefined && !Array.isArray(adoptInstructions)) adoptInstructions = [adoptInstructions]
      if (options && options.configuration !== undefined) {
        const snapshot = structuredClone({ configuration: options.configuration })
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
    const overriding = explicit || requestedAccount !== undefined || requestedContext !== undefined
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
      let contextChange = false
      if (requestedContext !== undefined) {
        const saved = await this.store.get(id)
        if (!saved) throw new CoreError("session_not_found", `Session ${id} was not found`)
        contextChange = !sameContext(withoutInstructions(saved.context), requestedContext)
      }
      const current = this.live.get(id)
      if (current && current.snapshot().state !== "closed") {
        const state = current.snapshot().state
        // A context is applied at launch: a changed one relaunches an idle session (never mid-turn).
        if (contextChange && (state === "running" || state === "interrupting")) throw new CoreError("session_busy", "Wait for the turn to end before changing the session context")
        if (state === "closing" || state === "failed" || switchFrom !== undefined || reason === "refresh" || reason === "context" || contextChange) {
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
      // The session's own instructions stay as given at creation (launches use createdInstructions).
      let ownContext = requestedContext !== undefined ? keepInstructions(record.context, requestedContext) : record.context
      // A record from before instructions were snapshotted takes the host's current ones, once.
      let adopted: { createdInstructions: string } | undefined
      if (adoptInstructions !== undefined && record.createdInstructions === undefined && record.context === undefined) {
        const joined = joinInstructions(mergeContexts(this.defaultContext, { instructions: adoptInstructions }).instructions)
        const driver = this.driver(record.agent)
        if (joined !== undefined && (driver.context?.capabilities ?? noContextCapabilities()).instructions.support !== "unsupported") {
          adopted = { createdInstructions: joined }
          ownContext = { instructions: [...adoptInstructions], ...(ownContext ?? {}) }
        }
      }
      const { context: _previous, ...rest } = record
      const session = await this.openSession({
        ...rest, configuration, ...(switchFrom !== undefined ? { account: requestedAccount } : {}),
        ...(ownContext !== undefined ? { context: ownContext } : {}),
        ...(adopted ?? {}),
      }, record.agentSessionId, undefined, original)
      if (reason === "refresh") this.events.emit({ type: "account.refreshed", sessionId: id, account: requestedAccount! })
      else if (switchFrom !== undefined && reason !== "context") this.events.emit({ type: "account.switched", sessionId: id, from: switchFrom, to: requestedAccount!, reason })
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
      if (limited(windows) && this.autoSwitch()) {
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
    input: CreateOptions & { id: string; createdAt: string; lineage?: SessionRecord["lineage"]; configuration?: SessionConfiguration; createdInstructions?: string },
    resumeId?: string,
    forkFrom?: ForkSource,
    originalRecord?: SessionRecord,
  ): Promise<Session> {
    this.assertOpen()
    const driver = this.driver(input.agent)
    if (input.account !== undefined && input.authProfile !== undefined) throw new CoreError("invalid_input", "account and authProfile are mutually exclusive")
    const profile = input.account !== undefined ? await this.accountProfile(input.agent, input.account) : this.profile(input.agent, input.authProfile)
    // Resolved (and refused under policy "error") before anything launches.
    const launch = forkFrom ? "fork" : resumeId ? "resume" : "create"
    const prepared = await this.prepareContext(driver, input, launch)
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
      const hostNames = (prepared.sessionContext?.mcpServers ?? []).filter(server => server.host).map(server => server.name)
      if (hostNames.length) this.hostLaunching.set(input.id, { agent: input.agent, ...(input.account !== undefined ? { account: input.account } : {}), servers: new Set(hostNames) })
      runtime = await driver.open({
        sessionId: input.id, cwd: input.cwd, profile, resumeId, forkFrom, signal: this.lifetime.signal,
        ...(input.account !== undefined ? { account: input.account } : {}),
        ...(subagents.length ? { subagents: structuredClone(subagents) } : {}),
        ...(prepared.sessionContext ? { sessionContext: structuredClone(prepared.sessionContext) } : {}),
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
        ...(!isContextEmpty(input.context) ? { context: structuredClone(input.context!) } : {}),
        ...(input.contextPolicy ? { contextPolicy: input.contextPolicy } : {}),
        ...(prepared.createdInstructions !== undefined ? { createdInstructions: prepared.createdInstructions } : {}),
      }
      persistenceAttempted = true
      await this.store.put(record)
      this.hostLaunching.delete(input.id)
      this.assertOpen()
      if (failed) throw failed
      session = new Session(record, runtime, event => this.events.emit(event), this.interruptTimeoutMs, this.maxPending, this.outstandingActivity, () => {
        if (this.live.get(record.id) === session) this.live.delete(record.id)
      }, options => this.operation(() => {
        if (typeof options.id !== "string") throw new TypeError("id is required")
        const id = options.id
        assertSessionId(id)
        const forkContext = options.context !== undefined ? keepInstructions(record.context, this.adoptHosts(normalizeContextUpdate(options.context))!) : record.context
        return this.withReservation(id, () => this.openSession({
          agent: record.agent, cwd: record.cwd, authProfile: record.authProfile,
          ...(record.account !== undefined ? { account: record.account } : {}),
          id, createdAt: new Date().toISOString(),
          lineage: { parentSessionId: record.id, ...(options.at ? {nativeTurnId: options.at.nativeTurnId} : {}) },
          ...(record.configuration ? { configuration: structuredClone(record.configuration) } : {}),
          ...(record.permissions ? { permissions: structuredClone(record.permissions) } : {}),
          ...(forkContext !== undefined ? { context: forkContext } : {}),
          ...(record.contextPolicy ? { contextPolicy: record.contextPolicy } : {}),
          ...(record.createdInstructions !== undefined ? { createdInstructions: record.createdInstructions } : {}),
        }, undefined, { agentSessionId: record.agentSessionId, ...(options.at ? { at: options.at } : {}) }))
      }), recordToSave => this.store.put(recordToSave), {
        subagents: structuredClone(runtime.restoredSubagents ?? subagents),
        persistSubagents: list => this.store.putSubagents(record.id, list),
        updateContext: (patch, options) => this.updateContext(record.id, patch, options),
      })
      this.live.set(record.id, session)
      if (record.account !== undefined) void this.scheduleRefresh(session).catch(error => this.reportObserverError(error))
      attachSession(session)
      for (const notice of outstandingActivity.values()) session.reportActivity(notice)
      outstandingActivity.clear()
      if (failed) throw failed
      this.events.emit({ type: resumeId ? "session.resumed" : "session.created", sessionId: record.id, record: structuredClone(record) })
      if (prepared.dropped.length) this.events.emit({ type: "context.degraded", sessionId: record.id, dropped: structuredClone(prepared.dropped) })
      return session
    } catch (error) {
      this.hostLaunching.delete(input.id)
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
      } else if (!resumeId && !persistenceAttempted && prepared.sessionContext) {
        await rm(prepared.sessionContext.directory, { recursive: true, force: true }).catch(() => {})
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

  /**
   * Merges the core default with the session's own context, validates it, and resolves it against
   * the driver: under policy "error" anything it cannot apply is `context_unsupported` before any
   * launch. A nonempty context gets a fresh core-owned folder; an empty one gets none (the launch
   * is then exactly what it was without context).
   */
  /**
   * Merges the core default with the session's own context, validates it, and resolves it against
   * the driver: under policy "error" anything it cannot apply is `context_unsupported` before any
   * launch. Instructions are merged only on create and snapshotted as `createdInstructions`;
   * resume and fork use that snapshot and never re-merge (a changed core default only reaches new
   * sessions). A nonempty context gets a fresh core-owned folder; an empty one gets none (the
   * launch is then exactly what it was without context).
   */
  private async prepareContext(
    driver: AgentDriver,
    input: { id: string; context?: SessionContext; contextPolicy?: ContextPolicy; createdInstructions?: string },
    launch: "create" | "resume" | "fork",
  ): Promise<{ sessionContext?: LaunchContext; dropped: ContextDrop[]; createdInstructions?: string }> {
    const resolved = await this.resolveLaunchContext(input.context, launch === "create" ? undefined : { createdInstructions: input.createdInstructions }, this.hostResolver(input.id, driver))
    const directory = this.store.contextDirectory(input.id)
    const dropped = contextDrops(resolved, driver.context)
    if (dropped.length && (input.contextPolicy ?? this.defaultPolicy) === "error") throw unsupportedError(driver.id, dropped)
    const instructionsApplied = resolved.instructions !== undefined && !dropped.some(drop => drop.kind === "instructions")
    const createdInstructions = launch === "create" ? (instructionsApplied ? resolved.instructions : undefined) : input.createdInstructions
    const snapshot = createdInstructions !== undefined ? { createdInstructions } : {}
    if (isEmptyContext(resolved)) {
      await rm(directory, { recursive: true, force: true })
      return { dropped, ...snapshot }
    }
    await rm(directory, { recursive: true, force: true })
    await mkdir(directory, { recursive: true, mode: 0o700 })
    return { sessionContext: { ...resolved, directory, launch, dropped, fingerprint: contextFingerprint(resolved, dropped) }, dropped, ...snapshot }
  }

  /** Create: the core default merged with `own`. Later launches (`fixed` given): the same without instructions, plus the snapshot. */
  private async resolveLaunchContext(own: SessionContext | undefined, fixed: { createdInstructions: string | undefined } | undefined, hosts: HostResolver): Promise<ResolvedContext> {
    if (!fixed) return resolveContext(mergeContexts(this.defaultContext, own), hosts)
    const resolved = await resolveContext(mergeContexts(withoutInstructions(this.defaultContext), withoutInstructions(own)), hosts)
    return fixed.createdInstructions !== undefined ? { ...resolved, instructions: fixed.createdInstructions } : resolved
  }

  private updateContext(id: string, patch: unknown, options: unknown): Promise<UpdateContextResult> {
    let normalized: ContextPatch, opts: UpdateContextOptions
    try {
      assertSessionId(id)
      normalized = normalizePatch(patch)
      if (normalized.mcpServers?.add) normalized.mcpServers.add = this.adoptHosts({ mcpServers: normalized.mcpServers.add })!.mcpServers!
      opts = normalizeUpdateOptions(options)
    } catch (error) { return Promise.reject(asError(error)) }
    const previous = this.contextUpdates.get(id) ?? Promise.resolve()
    const run = this.operation(() => previous.catch(() => {}).then(() => this.applyContextUpdate(id, normalized, opts)))
    const settled = run.catch(() => {})
    this.contextUpdates.set(id, settled)
    void settled.finally(() => { if (this.contextUpdates.get(id) === settled) this.contextUpdates.delete(id) })
    return run
  }

  /**
   * One updateContext: validate, plan per change, refuse everything under policy "error" when
   * anything is unsupported, persist the new own context, then (open session) wait for the turn
   * to end with the queue held and apply live, append, or relaunch on the same conversation.
   */
  private async applyContextUpdate(id: string, patch: ContextPatch, options: UpdateContextOptions): Promise<UpdateContextResult> {
    await this.ready()
    if (this.lifecycleBusy(id) || this.reserved.has(id) || this.forgetting.has(id) || this.restoring.has(id)) {
      throw new CoreError("session_busy", "Session has an outstanding lifecycle operation")
    }
    const record = await this.store.get(id)
    if (!record) throw new CoreError("session_not_found", `Session ${id} was not found`)
    const driver = this.driver(record.agent)
    // A failed or closing session is treated as not open: only the record changes (its next launch applies it).
    const open = this.sessions.live(id)
    const live = open && open.snapshot().state !== "failed" && open.snapshot().state !== "closing" ? open : undefined
    // Busy when the call arrives: the change then reaches the agent after the current turn.
    const busy = live !== undefined && live.snapshot().state !== "idle"
    const changes = patchChanges(record.context, this.defaultContext, patch)
    if (!changes.length) return { applied: [], effective: "now" }
    const hosts = this.hostResolver(id, driver)
    // Every added path must exist, every server name stay unique and every host server be registered, supported or not.
    await resolveContext(mergeContexts(this.defaultContext, applyChanges(record.context, patch, changes)), hosts)
    const capabilities = driver.context?.capabilities ?? noContextCapabilities()
    const runtime = live?.contextControl()
    const launchFor = async (own: SessionContext) => {
      const resolved = await this.resolveLaunchContext(own, { createdInstructions: record.createdInstructions }, hosts)
      const dropped = contextDrops(resolved, driver.context)
      const next: LaunchContext = {
        ...resolved, directory: this.store.contextDirectory(id), launch: "resume", dropped, fingerprint: contextFingerprint(resolved, dropped),
      }
      return { resolved, dropped, next }
    }
    const statically = changes.filter(change => capabilities[change.kind].support !== "unsupported")
    const preliminary = await launchFor(applyChanges(record.context, patch, statically))
    let applied = planChanges({
      agent: driver.id, changes, support: driver.context, capabilities, runtime, next: preliminary.next, options, open: live !== undefined,
    })
    const before = contextDrops(await this.resolveLaunchContext(record.context, { createdInstructions: record.createdInstructions }, hosts), driver.context)
    const known = new Set(before.map(drop => `${drop.kind}\0${drop.item}`))
    let outcome!: Awaited<ReturnType<typeof launchFor>>
    let own!: SessionContext
    let parts: ContextApplied[] = []
    for (let pass = 0; pass < 3; pass++) {
      own = applyChanges(record.context, patch, applied.filter(entry => entry.how !== "unsupported"))
      outcome = await launchFor(own)
      // Drops the new context brings (e.g. a plugin part this agent cannot map, a server name the driver already uses).
      const fresh = outcome.dropped.filter((drop): drop is ContextDrop & { kind: ContextUpdateKind } => drop.kind !== "instructions" && !known.has(`${drop.kind}\0${drop.item}`))
      const whole = fresh.filter(drop => applied.some(entry => entry.how !== "unsupported" && entry.kind === drop.kind && entry.item === drop.item))
      parts = fresh.filter(drop => !whole.includes(drop)).map(drop => ({ kind: drop.kind, op: "add" as const, item: drop.item, how: "unsupported" as const, reason: drop.reason }))
      if (!whole.length) break
      applied = applied.map(entry => {
        const drop = whole.find(candidate => candidate.kind === entry.kind && candidate.item === entry.item)
        return drop ? { ...entry, how: "unsupported" as const, reason: drop.reason } : entry
      })
    }
    applied = [...applied, ...parts]
    const unsupported = applied.filter(entry => entry.how === "unsupported")
    if (unsupported.length && (record.contextPolicy ?? this.defaultPolicy) === "error") {
      throw unsupportedError(driver.id, unsupported.map(entry => ({ kind: entry.kind, item: entry.item, reason: entry.reason ?? "unsupported" })))
    }
    const effective = applied.filter(entry => entry.how !== "unsupported")
    if (!effective.length) {
      this.events.emit({ type: "context.updated", sessionId: id, applied: structuredClone(applied) })
      return { applied, effective: "now" }
    }
    // The record first: a crash from here on never leaves it older than what is live.
    const persist = async (context: SessionContext) => {
      // Re-read: other fields (permissions, configuration) may have been saved meanwhile.
      const { context: _context, ...rest } = await this.store.get(id) ?? record
      const saved: SessionRecord = { ...rest, ...(!isContextEmpty(context) ? { context: structuredClone(context) } : {}) }
      await this.store.put(saved)
      const current = this.live.get(id)
      if (current && current.snapshot().state !== "closed") current.setContextRecord(saved.context)
    }
    await persist(own)
    let when: UpdateContextResult["effective"] = "next_turn"
    if (live) {
      const session = live
      const release = session.holdQueue()
      try {
        await session.whenIdle()
        const relaunch = (reason: string, which: (entry: ContextApplied) => boolean) => {
          applied = applied.map(entry => which(entry) ? { ...entry, how: "reload" as const, reason } : entry)
        }
        if (effective.some(entry => entry.how === "reload")) {
          relaunch("Applied by the relaunch another change in this update needed", entry => entry.how === "live")
          await this.reloadForContext(id, session)
        } else {
          const liveChanges = effective.filter(entry => entry.how === "live").map(({ kind, op, item }) => ({ kind, op, item }))
          let reloaded = false
          if (liveChanges.length) {
            let refused: Array<{ change: { kind: ContextApplied["kind"]; op: ContextApplied["op"]; item: string }; reason: string }> = []
            try {
              refused = await runtime!.apply(outcome.next, liveChanges, options)
            } catch (error) {
              if (options.reload === "never") throw error
              relaunch(`The live change failed (${asError(error).message}); the agent was relaunched instead`, entry => entry.how === "live")
              await this.reloadForContext(id, session)
              reloaded = true
            }
            if (refused.length) {
              const keys = new Set(refused.map(entry => changeKey(entry.change)))
              applied = applied.map(entry => {
                const hit = refused.find(candidate => changeKey(candidate.change) === changeKey(entry))
                return hit ? { ...entry, how: "unsupported" as const, reason: hit.reason } : entry
              })
              own = applyChanges(record.context, patch, applied.filter(entry => entry.how !== "unsupported" && !keys.has(changeKey(entry))))
              outcome = await launchFor(own)
              await persist(own)
            }
          }
          if (!reloaded) {
            await runtime?.recordFingerprint?.(outcome.next.fingerprint)
            // "now": the process took every applied change without waiting for a turn to end.
            if (!busy && applied.every(entry => entry.how === "live" || entry.how === "unsupported")) when = "now"
          }
        }
      } finally { release() }
    }
    this.events.emit({ type: "context.updated", sessionId: id, applied: structuredClone(applied) })
    return { applied, effective: when }
  }

  /** Relaunches an idle session on the same conversation with the record's context; queued input moves to the new session. */
  private async reloadForContext(id: string, session: Session): Promise<Session> {
    const carried = session.takeQueue()
    this.switching.add(id)
    try {
      const next = await this.resume(id, undefined, "context")
      next.adoptQueue(carried)
      return next
    } catch (error) {
      Session.failCarried(carried, asError(error))
      throw error
    } finally { this.switching.delete(id) }
  }

  // ---------------------------------------------------------------- host MCP servers (C2)

  private registerHost(server: HostMcpServer, code: "invalid_input" | "invalid_context" | "invalid_options"): void {
    if (!isHostMcpServer(server)) throw new CoreError(code, "A host MCP server must be built with mcpServer()")
    const existing = this.hostServers.get(server.name)
    if (existing?.server === server) return
    if (existing) throw new CoreError(code, `Another host MCP server named ${server.name} is already registered`)
    const unsubscribe = server.onChange(change => this.hostToolsChanged(server, change))
    this.hostServers.set(server.name, { server, unsubscribe })
  }

  /** A normalized context with its host server objects registered and replaced by `{ kind: "host", name }`. */
  private adoptHosts<T extends SessionContext | undefined>(context: T): T {
    if (!context?.mcpServers?.some(server => isHostMcpServer(server))) return context
    const mcpServers: ContextMcpServer[] = context.mcpServers.map(server => {
      if (!isHostMcpServer(server)) return server
      this.registerHost(server, "invalid_context")
      return { kind: "host" as const, name: server.name }
    })
    return { ...context, mcpServers }
  }

  /** The socket host, started on first use (a launch that has a host server). */
  private async mcpHost(): Promise<McpHost> {
    this.mcpHostInstance ??= new McpHost({
      stateDirectory: this.options.stateDirectory,
      lookup: this.hostLookup,
      onEvent: event => this.events.emit(event),
      onError: error => this.reportObserverError(error),
    })
    await this.mcpHostInstance.start()
    return this.mcpHostInstance
  }

  /** A host server of session `id` as the stdio command the agent runs: the bridge, with the socket and the session's token. */
  private hostResolver(id: string, driver: AgentDriver): HostResolver {
    return async name => {
      const server = this.hostServers.get(name)?.server
      if (!server) return undefined
      const host = await this.mcpHost()
      // Agents that ignore tools/list_changed carry the tool set in the fingerprint: a changed set relaunches them.
      const tools = !driver.context?.mcpListChanged && server.toolChanges === "reload" && !server.create ? { tools: server.tools } : {}
      const resolved: ResolvedMcpServer = {
        name, command: process.execPath, args: [bridgeEntry(), "--server", name],
        env: { [BRIDGE_ENV.socket]: host.socket, [BRIDGE_ENV.session]: id, [BRIDGE_ENV.token]: await host.token(id, name) },
        host: tools,
      }
      return resolved
    }
  }

  /** Who a bridge's hello speaks for: a session being launched, or a stored session that has the server. */
  private readonly hostLookup: HostLookup = async (sessionId, name) => {
    const server = this.hostServers.get(name)?.server
    if (!server) return { ok: false, code: "unknown_server", message: `No host MCP server ${name} is registered` }
    const launching = this.hostLaunching.get(sessionId)
    if (launching) {
      if (!launching.servers.has(name)) return { ok: false, code: "not_attached", message: `Session ${sessionId} does not have ${name}` }
      return { ok: true, server, agent: launching.agent, ...(launching.account !== undefined ? { account: launching.account } : {}) }
    }
    let record: SessionRecord | undefined
    try { record = await this.store.get(sessionId) } catch { record = undefined }
    if (!record) return { ok: false, code: "unknown_session", message: `Session ${sessionId} was not found` }
    const names = [...this.defaultContext?.mcpServers ?? [], ...record.context?.mcpServers ?? []].filter(isHostEntry).map(entry => entry.name)
    if (!names.includes(name)) return { ok: false, code: "not_attached", message: `Session ${sessionId} does not have ${name}` }
    return { ok: true, server, agent: record.agent, ...(record.account !== undefined ? { account: record.account } : {}) }
  }

  /** Open sessions that have host server `name` (core default or their own context). */
  private sessionsWithHost(name: string): Session[] {
    const inDefault = (this.defaultContext?.mcpServers ?? []).some(server => isHostEntry(server) && server.name === name)
    return [...this.live.values()].filter(session => {
      const snapshot = session.snapshot()
      if (snapshot.state === "closed" || snapshot.state === "closing" || snapshot.state === "failed") return false
      return inDefault || (snapshot.context?.mcpServers ?? []).some(server => isHostEntry(server) && server.name === name)
    })
  }

  /**
   * A tool was added to / removed from a host server. Live connections already got it (the SDK
   * sent list_changed). Agents that ignore list_changed are relaunched between turns, unless the
   * server is `toolChanges: "live-only"`.
   */
  private hostToolsChanged(server: HostMcpServer, change: ToolChange): void {
    if (this.shuttingDown) return
    for (const session of this.sessionsWithHost(server.name)) {
      const id = session.id
      const driver = this.drivers.get(session.snapshot().agent)
      const entry = { kind: "tools" as const, op: change.op, item: `${server.name}/${change.tool}` }
      if (driver?.context?.mcpListChanged) {
        this.events.emit({ type: "context.updated", sessionId: id, applied: [{ ...entry, how: "live" }] })
        continue
      }
      if (server.toolChanges === "live-only") {
        this.events.emit({ type: "context.updated", sessionId: id, applied: [{ ...entry, how: "unsupported", reason: `${session.snapshot().agent} ignores tools/list_changed and ${server.name} is toolChanges "live-only": the agent sees the change at its next launch` }] })
        continue
      }
      const queued = this.toolReloads.get(id)
      if (queued) { queued.push({ ...entry, how: "reload" }); continue }
      this.toolReloads.set(id, [{ ...entry, how: "reload" }])
      void this.reloadForTools(id)
    }
  }

  /** Relaunches a session for its pending tool changes once it is idle (queued with its updateContext calls; never mid-turn). */
  private reloadForTools(id: string): Promise<void> {
    const previous = this.contextUpdates.get(id) ?? Promise.resolve()
    const run = this.operation(() => previous.catch(() => {}).then(async () => {
      const session = this.live.get(id)
      const state = session?.snapshot().state
      if (!session || state === "closed" || state === "closing" || state === "failed") { this.toolReloads.delete(id); return }
      const release = session.holdQueue()
      let applied: ToolChangeApplied[] = []
      try {
        await session.whenIdle()
        // Taken now: every change made while the turn ran rides this one relaunch.
        applied = this.toolReloads.get(id) ?? []
        this.toolReloads.delete(id)
        if (!applied.length) return
        await this.reloadForContext(id, session)
      } catch (error) {
        applied = applied.map(entry => ({ ...entry, how: "unsupported" as const, reason: `The relaunch failed: ${asError(error).message}` }))
        this.reportObserverError(error)
      } finally { release() }
      if (applied.length) this.events.emit({ type: "context.updated", sessionId: id, applied })
    }))
    const settled = run.catch(error => this.reportObserverError(error))
    this.contextUpdates.set(id, settled)
    void settled.finally(() => { if (this.contextUpdates.get(id) === settled) this.contextUpdates.delete(id) })
    return settled
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

/** `next` (no instructions) with the session's own instructions from `current` kept (informational; launches use createdInstructions). */
function keepInstructions(current: SessionContext | undefined, next: SessionContext): SessionContext {
  return current?.instructions !== undefined ? { instructions: structuredClone(current.instructions), ...next } : next
}

const DEFAULT_CONTINUE_PROMPT = "Continue from where you stopped. Your previous turn hit a usage limit and you are now on another account."
/** A turn whose stop reason names a limit ended because of it. */
const LIMIT_STOP_REASON = /limit|quota/i
/** Token sessions open with at least this much validity and are reopened (when idle) this long before expiry. */
const SESSION_TOKEN_MARGIN_MS = 30 * 60_000
const LOGIN_TIMEOUT_MS = 30 * 60_000
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
