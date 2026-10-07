// The broker's one account registry (spec: docs/core-design/auth-accounts.md, slice A3a).
//
// Every agent has its own Core host (src/core/agents/<kind>/core-host-provider.ts), but accounts
// must have ONE source of truth: this module owns a single AccountRegistry + UsageStore under
// <STATE_DIR>/accounts that every host's Core shares (CoreOptions.accounts.registry/usage).
// Secrets live only in the registry's file vault; nothing here returns one.

import { join } from "path"
import { randomUUID } from "crypto"
import { AccountRegistry, UsageStore, systemAccountId } from "../../../packages/supermux-core/src/accounts/index.js"
import type { Account, AccountsOptions, LoginHandle, LoginState } from "../../../packages/supermux-core/src/accounts/index.js"
import type { Core } from "../../../packages/supermux-core/src/index.js"
import { STATE_DIR } from "../../shared/paths"
import { makeLogger } from "../../shared/log"

const log = makeLogger("accounts")

/** Agents the broker runs (one Core host each); every one has a built-in system account. */
export const ACCOUNT_AGENTS = ["claude", "codex", "cursor", "grok", "opencode"] as const
export type AccountAgent = (typeof ACCOUNT_AGENTS)[number]

/** A guided login includes the user's part on a phone: 30 minutes. */
export const ACCOUNT_LOGIN_TIMEOUT_MS = 30 * 60_000
/** A finished login's last state stays readable (GET) this long. */
const LOGIN_STATE_TTL_MS = 10 * 60_000

export const SYSTEM_ACCOUNT_LABEL = "System login"

/** Settings key "accounts": the user's toggles plus the one-time migration marker. */
export const SETTINGS_KEY_ACCOUNTS = "accounts"
export type AccountsSettings = { autoSwitch: boolean; settingsMigrated?: boolean }

export function parseAccountsSettings(value: unknown): AccountsSettings {
  const o = (value ?? {}) as Record<string, unknown>
  return { autoSwitch: o.autoSwitch === true, ...(o.settingsMigrated === true ? { settingsMigrated: true } : {}) }
}

export function isAccountAgent(agent: unknown): agent is AccountAgent {
  return typeof agent === "string" && (ACCOUNT_AGENTS as readonly string[]).includes(agent)
}

export function isSystemAccount(id: string | undefined | null): boolean {
  return !id || id.endsWith(":system")
}

type Shared = { registry: AccountRegistry; usage: UsageStore }
let shared: Shared | undefined
let autoSwitchSource: () => boolean = () => false

/** The process-wide registry + usage cache (created lazily under `<stateDir>/accounts`). */
export function sharedAccounts(stateDir: string = STATE_DIR): Shared {
  if (!shared) {
    const registry = new AccountRegistry(stateDir, [...ACCOUNT_AGENTS])
    shared = { registry, usage: new UsageStore(join(registry.directory, "usage.json")) }
  }
  return shared
}

/** What every broker Core host gets as `CoreOptions.accounts`. */
export function brokerAccountsOptions(): AccountsOptions {
  const { registry, usage } = sharedAccounts()
  return {
    registry,
    usage,
    autoSwitch: () => autoSwitchSource(),
    login: { timeoutMs: ACCOUNT_LOGIN_TIMEOUT_MS },
  }
}

/** main.ts points this at the `accounts.autoSwitch` setting (default off). */
export function setAccountsAutoSwitchSource(source: () => boolean): void {
  autoSwitchSource = source
}

export function resetSharedAccountsForTests(next?: Shared): void {
  shared = next
  autoSwitchSource = () => false
}

/** The account's method/kind as the host needs it at launch (undefined: unknown or system). */
export async function accountMethod(id: string | undefined): Promise<Account["method"] | undefined> {
  if (isSystemAccount(id)) return "system"
  try {
    return (await sharedAccounts().registry.get(id!))?.method
  } catch {
    return undefined
  }
}

// ── Wire views (no secrets) ──────────────────────────────────────────────────

export type UsageWindowView = { name: string; usedPercent: number; resetsAt?: string }

export type AccountView = {
  id: string
  agent: string
  method: Account["method"]
  /** What the UI shows: the user's label, else the signed-in email, else the id. */
  label: string
  /** The user's own label, when one was given. */
  customLabel?: string
  identity?: { email?: string; org?: string; accountId?: string }
  isolated: boolean
  system: boolean
  createdAt: string
  usage: UsageWindowView[]
}

export function displayLabel(account: Pick<Account, "id" | "method" | "label" | "identity">): string {
  if (account.label) return account.label
  if (account.method === "system") return SYSTEM_ACCOUNT_LABEL
  return account.identity?.email ?? account.id
}

export function toAccountView(account: Account, usage: UsageStore): AccountView {
  const windows = usage.get(account.id) ?? []
  return {
    id: account.id,
    agent: account.agent,
    method: account.method,
    label: displayLabel(account),
    ...(account.label ? { customLabel: account.label } : {}),
    ...(account.identity ? { identity: { ...account.identity } } : {}),
    isolated: account.isolated === true,
    system: account.method === "system",
    createdAt: account.createdAt,
    usage: windows.map((w) => ({ name: w.name, usedPercent: w.usedPercent, ...(w.resetsAt ? { resetsAt: w.resetsAt.toISOString() } : {}) })),
  }
}

export type AccountLoginStateView = {
  loginId: string
  agent: string
  phase: LoginState["phase"]
  url?: string
  code?: string
  needsCode?: boolean
  error?: string
  errorCode?: string
  /** The new account, once phase is "done". */
  account?: AccountView
}

/** Typed broker-side failure with an HTTP status for the web layer. */
export class AccountsApiError extends Error {
  constructor(readonly status: number, message: string, readonly code?: string) {
    super(message)
  }
}

function codeOf(err: unknown): string | undefined {
  return typeof err === "object" && err !== null && "code" in err ? String((err as { code: unknown }).code) : undefined
}

/** Maps a core CoreError to an AccountsApiError (400/404/409). */
export function toApiError(err: unknown): AccountsApiError {
  if (err instanceof AccountsApiError) return err
  const code = codeOf(err)
  const message = err instanceof Error ? err.message : String(err)
  if (code === "unknown_account" || code === "session_not_found") return new AccountsApiError(404, message, code)
  if (code === "account_exists" || code === "session_busy" || code === "account_home_conflict") return new AccountsApiError(409, message, code)
  if (code) return new AccountsApiError(400, message, code)
  return new AccountsApiError(500, message)
}

/**
 * Codex subscription accounts point CODEX_HOME at the account home, but a broker Codex session's
 * CODEX_HOME is its own (MCP config, instructions and its thread history live there). Until the
 * session config moves to `-c` overrides, Codex logins become `token` accounts instead.
 */
export function assertSessionAccountSupported(agent: string, account: Pick<Account, "id" | "method"> | undefined): void {
  if (agent === "codex" && account?.method === "subscription") {
    throw new AccountsApiError(400, `Codex subscription account ${account.id} cannot run broker sessions yet; sign in again as a token account`, "account_unsupported")
  }
}

// ── Accounts service ────────────────────────────────────────────────────────

export type BrokerAccountsDeps = {
  registry: AccountRegistry
  usage: UsageStore
  /** The Core of an agent's host (logins run there). */
  coreFor(agent: AccountAgent): Core
  /** Broadcast a frame to every web client. */
  broadcast(frame: Record<string, unknown>): void
  now?: () => number
}

type LoginEntry = { agent: string; handle: LoginHandle; state: AccountLoginStateView; expires?: ReturnType<typeof setTimeout> }

/** list/add/remove/login over the shared registry, plus a sync label cache for session frames. */
export class BrokerAccounts {
  private readonly labels = new Map<string, string>()
  private readonly logins = new Map<string, LoginEntry>()

  constructor(private readonly deps: BrokerAccountsDeps) {}

  /** Display label for a session's account (sync; refreshed on every change). */
  label(id: string | undefined | null): string {
    if (isSystemAccount(id)) return SYSTEM_ACCOUNT_LABEL
    return this.labels.get(id!) ?? id!
  }

  async refreshLabels(): Promise<void> {
    try {
      const all = await this.deps.registry.list()
      this.labels.clear()
      for (const account of all) this.labels.set(account.id, displayLabel(account))
    } catch (err) {
      log.warn("accounts_label_refresh_failed", { err: String(err) })
    }
  }

  async list(agent?: string): Promise<AccountView[]> {
    if (agent !== undefined && !isAccountAgent(agent)) throw new AccountsApiError(400, `unknown agent: ${agent}`, "unknown_agent")
    await this.deps.usage.load()
    const accounts = await this.deps.registry.list(agent)
    return accounts.map((a) => toAccountView(a, this.deps.usage))
  }

  async get(id: string): Promise<Account | undefined> {
    return this.deps.registry.get(id)
  }

  async add(input: { agent?: unknown; method?: unknown; secret?: unknown; label?: unknown; provider?: unknown; isolated?: unknown }): Promise<AccountView> {
    if (!isAccountAgent(input.agent)) throw new AccountsApiError(400, `unknown agent: ${String(input.agent)}`, "unknown_agent")
    if (input.method !== "api_key" && input.method !== "token") throw new AccountsApiError(400, "method must be api_key or token (subscriptions are added by login)", "invalid_input")
    if (typeof input.secret !== "string" || !input.secret.trim()) throw new AccountsApiError(400, "secret required", "invalid_input")
    const label = typeof input.label === "string" && input.label.trim() ? input.label.trim() : undefined
    const provider = typeof input.provider === "string" && input.provider.trim() ? input.provider.trim() : undefined
    let account: Account
    try {
      account = await this.deps.registry.add({
        agent: input.agent,
        method: input.method,
        secret: input.secret.trim(),
        ...(label ? { label } : {}),
        ...(provider ? { provider } : {}),
        ...(input.isolated === true ? { isolated: true } : {}),
      })
    } catch (err) {
      throw toApiError(err)
    }
    await this.changed()
    return toAccountView(account, this.deps.usage)
  }

  async remove(id: string, options: { deleteHome?: boolean } = {}): Promise<void> {
    try {
      await this.deps.registry.remove(id, options)
    } catch (err) {
      throw toApiError(err)
    }
    this.deps.usage.delete(id)
    await this.changed()
  }

  startLogin(input: { agent?: unknown; as?: unknown; email?: unknown; label?: unknown; isolated?: unknown }): AccountLoginStateView {
    if (!isAccountAgent(input.agent)) throw new AccountsApiError(400, `unknown agent: ${String(input.agent)}`, "unknown_agent")
    const agent = input.agent
    // Codex logins default to token accounts (vault-owned refresh): a Codex subscription home
    // cannot run broker sessions yet (see assertSessionAccountSupported).
    const as = input.as === "subscription" || input.as === "token" ? input.as : agent === "codex" ? "token" : "subscription"
    const email = typeof input.email === "string" && input.email.trim() ? input.email.trim() : undefined
    const label = typeof input.label === "string" && input.label.trim() ? input.label.trim() : undefined
    let handle: LoginHandle
    try {
      handle = this.deps.coreFor(agent).accounts.login({
        agent,
        as,
        ...(email ? { email } : {}),
        ...(label ? { label } : {}),
        ...(input.isolated === true ? { isolated: true } : {}),
      })
    } catch (err) {
      throw toApiError(err)
    }
    const loginId = randomUUID()
    const entry: LoginEntry = { agent, handle, state: { loginId, agent, ...handle.state() } }
    this.logins.set(loginId, entry)
    const publish = () => this.deps.broadcast({ type: "account_login_state", ...entry.state })
    handle.on((state) => {
      entry.state = { loginId, agent, ...state, ...(entry.state.account ? { account: entry.state.account } : {}) }
      if (state.phase !== "done") publish()
    })
    void handle.done.then(async (account) => {
      await this.changed()
      entry.state = { ...entry.state, phase: "done", account: toAccountView(account, this.deps.usage) }
      publish()
    }, (err) => {
      log.info("account_login_ended", { agent, code: codeOf(err) ?? "error" })
    }).finally(() => {
      entry.expires = setTimeout(() => { if (this.logins.get(loginId) === entry) this.logins.delete(loginId) }, LOGIN_STATE_TTL_MS)
      entry.expires.unref?.()
    })
    return { ...entry.state }
  }

  loginState(loginId: string): AccountLoginStateView | undefined {
    const entry = this.logins.get(loginId)
    return entry ? { ...entry.state } : undefined
  }

  submitLoginCode(loginId: string, code: string): void {
    const entry = this.logins.get(loginId)
    if (!entry) throw new AccountsApiError(404, "no such login", "unknown_login")
    if (!code.trim()) throw new AccountsApiError(400, "code required", "invalid_input")
    entry.handle.submitCode(code.trim())
  }

  cancelLogin(loginId: string): void {
    const entry = this.logins.get(loginId)
    if (!entry) throw new AccountsApiError(404, "no such login", "unknown_login")
    entry.handle.cancel()
  }

  /** Cancels running logins (broker shutdown). */
  close(): void {
    for (const entry of this.logins.values()) {
      if (entry.expires) clearTimeout(entry.expires)
      const phase = entry.state.phase
      if (phase !== "done" && phase !== "failed" && phase !== "cancelled") entry.handle.cancel()
    }
    this.logins.clear()
  }

  /** Validates a session's requested account: it exists, belongs to `agent`, and can run here. */
  async resolveSessionAccount(agent: string, id: string | undefined): Promise<string | undefined> {
    if (id === undefined || id === null || id === "") return undefined
    if (typeof id !== "string") throw new AccountsApiError(400, "account must be a string", "invalid_input")
    if (id === systemAccountId(agent)) return id
    const account = await this.deps.registry.get(id)
    if (!account) throw new AccountsApiError(404, `Account ${id} was not found`, "unknown_account")
    if (account.agent !== agent) throw new AccountsApiError(400, `Account ${id} belongs to agent ${account.agent}, not ${agent}`, "invalid_input")
    assertSessionAccountSupported(agent, account)
    return id
  }

  private async changed(): Promise<void> {
    await this.refreshLabels()
    this.deps.broadcast({ type: "accounts_changed" })
  }
}

// ── One-time migration of the global credential settings ────────────────────

export type SettingsCredentials = { claudeOauthToken?: string; anthropicApiKey?: string; codexApiKey?: string; cursorApiKey?: string }

const MIGRATIONS: Array<{ field: keyof SettingsCredentials; id: string; agent: AccountAgent; method: "api_key" | "token"; label: string }> = [
  { field: "claudeOauthToken", id: "claude-settings-token", agent: "claude", method: "token", label: "Claude token (settings)" },
  { field: "anthropicApiKey", id: "claude-settings-api-key", agent: "claude", method: "api_key", label: "Anthropic API key (settings)" },
  { field: "codexApiKey", id: "codex-settings-api-key", agent: "codex", method: "api_key", label: "Codex API key (settings)" },
  { field: "cursorApiKey", id: "cursor-settings-api-key", agent: "cursor", method: "api_key", label: "Cursor API key (settings)" },
]

/**
 * Creates one token/api_key account per credential stored in the app settings, once. The global
 * env injection stays (sessions without an account keep using it); these accounts only let a
 * session pick one of those credentials explicitly. Returns the ids created.
 */
export async function migrateSettingsCredentials(registry: AccountRegistry, creds: SettingsCredentials): Promise<string[]> {
  const created: string[] = []
  for (const m of MIGRATIONS) {
    const secret = creds[m.field]
    if (!secret) continue
    if (await registry.get(m.id)) continue
    try {
      await registry.add({ id: m.id, agent: m.agent, method: m.method, secret, label: m.label })
      created.push(m.id)
    } catch (err) {
      log.warn("accounts_settings_migration_failed", { id: m.id, code: codeOf(err) ?? "error" })
    }
  }
  return created
}
