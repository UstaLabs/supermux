import { chmod, mkdir, open, readFile, rename, rm } from "node:fs/promises"
import { randomUUID } from "node:crypto"
import { join, resolve } from "node:path"
import { CoreError } from "../errors.js"
import { adapterFor } from "./adapters/index.js"
import { ensureHome, homePath } from "./homes.js"
import { assertVaultId, fileVault } from "./vault.js"
import type { Account, AccountIdentity, AccountsOptions, AddAccountOptions, HomeRoots, Materialized, Vault } from "./types.js"

const SYSTEM_CREATED_AT = new Date(0).toISOString()

export function systemAccountId(agent: string): string { return `${agent}:system` }

/**
 * Account metadata in `<stateDirectory>/accounts/accounts.json` (atomic, 0600); secrets only in the
 * vault; subscription homes under `<stateDirectory>/accounts/homes/<agent>/<id>/`. One built-in
 * `system` account per agent (the CLI's own login, never moved or copied).
 */
export class AccountRegistry {
  readonly directory: string
  readonly homesDirectory: string
  readonly vault: Vault
  readonly roots: HomeRoots
  private readonly file: string
  private loaded?: Promise<Map<string, Account>>
  private mutation: Promise<unknown> = Promise.resolve()

  constructor(stateDirectory: string, private readonly agents: readonly string[], options: AccountsOptions = {}) {
    this.directory = join(resolve(stateDirectory), "accounts")
    this.homesDirectory = join(this.directory, "homes")
    this.file = join(this.directory, "accounts.json")
    this.vault = options.vault ?? fileVault(join(this.directory, "vault"))
    this.roots = structuredClone(options.homes ?? {})
  }

  async list(agent?: string): Promise<Account[]> {
    const stored = [...(await this.load()).values()]
    const all = [...this.agents.map(id => this.system(id)), ...stored.sort((a, b) => a.createdAt.localeCompare(b.createdAt) || a.id.localeCompare(b.id))]
    return Promise.all(all.filter(account => !agent || account.agent === agent).map(account => this.withIdentity(account)))
  }

  async get(id: string): Promise<Account | undefined> {
    const account = this.systemById(id) ?? (await this.load()).get(id)
    return account ? this.withIdentity(structuredClone(account)) : undefined
  }

  /** The built-in account for a configured agent. Its identity is read lazily by get/list. */
  system(agent: string): Account {
    if (!this.agents.includes(agent)) throw new CoreError("unknown_agent", `Agent ${agent} is not registered`)
    return { id: systemAccountId(agent), agent, method: "system", createdAt: SYSTEM_CREATED_AT }
  }

  add(options: AddAccountOptions): Promise<Account> {
    return this.serialize(async () => {
      const input = structuredClone(options)
      if (!input || typeof input !== "object") throw new CoreError("invalid_input", "Account options are required")
      if (!this.agents.includes(input.agent)) throw new CoreError("unknown_agent", `Agent ${input.agent} is not registered`)
      if ((input.method as string) === "system") throw new CoreError("invalid_input", "System accounts are built in")
      const adapter = adapterFor(input.agent)
      if (!adapter.methods.includes(input.method)) throw new CoreError("unsupported_operation", `${input.agent} accounts do not support method ${input.method}`)
      const id = input.id ?? `${input.agent.replace(/[^a-zA-Z0-9_-]/g, "") || "account"}-${randomUUID().slice(0, 8)}`
      assertVaultId(id)
      const needsSecret = input.method === "api_key" || input.method === "token"
      if (needsSecret && (typeof input.secret !== "string" || !input.secret)) throw new CoreError("invalid_input", `${input.method} accounts need a secret`)
      if (!needsSecret && input.secret !== undefined) throw new CoreError("invalid_input", "Subscription credentials stay in the account home, not the vault")
      for (const field of ["label", "provider"] as const) {
        if (input[field] !== undefined && (typeof input[field] !== "string" || !input[field])) throw new CoreError("invalid_input", `${field} must be a nonempty string`)
      }
      if (input.isolated !== undefined && typeof input.isolated !== "boolean") throw new CoreError("invalid_input", "isolated must be a boolean")
      adapter.validate?.(input)
      const accounts = await this.load()
      if (accounts.has(id)) throw new CoreError("account_exists", `Account ${id} already exists`)
      const identity = cleanIdentity(input.identity) ?? (input.secret ? adapter.secretIdentity?.(input.secret) : undefined)
      if (identity && await this.rotates(input.method, input.agent, input.secret)) {
        for (const other of [this.system(input.agent), ...accounts.values()]) {
          if (other.agent !== input.agent) continue
          const otherAccount = await this.withIdentity(structuredClone(other))
          if (!otherAccount.identity || !sameIdentity(identity, otherAccount.identity)) continue
          if (!await this.rotates(other.method, other.agent, other.method === "token" ? await this.vault.get(other.id) : undefined)) continue
          throw new CoreError("account_exists", `Account ${other.id} is already logged in as this identity; a second copy of a rotating login would log one of them out`)
        }
      }
      const account: Account = {
        id, agent: input.agent, method: input.method, createdAt: new Date().toISOString(),
        ...(input.label ? { label: input.label } : {}),
        ...(identity ? { identity } : {}),
        ...(input.isolated ? { isolated: true } : {}),
        ...(input.provider ? { provider: input.provider } : {}),
      }
      if (input.method === "subscription") await ensureHome(this.homesDirectory, account, adapter.layout?.(this.roots))
      if (needsSecret) await this.vault.put(id, input.secret!)
      const next = new Map(accounts).set(id, account)
      try { await this.save(next) } catch (error) {
        if (needsSecret) await this.vault.delete(id).catch(() => {})
        throw error
      }
      this.loaded = Promise.resolve(next)
      return structuredClone(account)
    })
  }

  /** Deletes the metadata and vault secret. A subscription home stays on disk (it holds that login). */
  remove(id: string): Promise<void> {
    return this.serialize(async () => {
      if (this.systemById(id)) throw new CoreError("invalid_input", "System accounts cannot be removed")
      const accounts = await this.load()
      if (!accounts.has(id)) throw new CoreError("unknown_account", `Account ${id} was not found`)
      const next = new Map(accounts)
      next.delete(id)
      await this.save(next)
      this.loaded = Promise.resolve(next)
      await this.vault.delete(id)
    })
  }

  /** Creates (idempotently) and returns a subscription account's home, e.g. to run the CLI's login in it. */
  async home(id: string): Promise<string> {
    const account = (await this.load()).get(id)
    if (!account) throw new CoreError("unknown_account", `Account ${id} was not found`)
    if (account.method !== "subscription") throw new CoreError("invalid_input", `Account ${id} has no home (method ${account.method})`)
    return ensureHome(this.homesDirectory, account, adapterFor(account.agent).layout?.(this.roots))
  }

  async materialize(account: Account): Promise<Materialized> {
    const adapter = adapterFor(account.agent)
    if (!adapter.methods.includes(account.method)) throw new CoreError("unsupported_operation", `${account.agent} accounts do not support method ${account.method}`)
    const secret = account.method === "api_key" || account.method === "token" ? await this.vault.get(account.id) : undefined
    return adapter.materialize(account, {
      ...(secret !== undefined ? { secret } : {}),
      home: () => ensureHome(this.homesDirectory, account, adapter.layout?.(this.roots)),
    })
  }

  private systemById(id: string): Account | undefined {
    const agent = this.agents.find(agent => systemAccountId(agent) === id)
    return agent === undefined ? undefined : this.system(agent)
  }

  private async withIdentity(account: Account): Promise<Account> {
    if (account.identity) return account
    const adapter = adapterFor(account.agent)
    let identity: AccountIdentity | undefined
    if (account.method === "system") identity = await adapter.readIdentity?.(undefined, this.roots)
    else if (account.method === "subscription") identity = await adapter.readIdentity?.(homePath(this.homesDirectory, account), this.roots)
    return identity ? { ...account, identity } : account
  }

  /** Rotating logins (refresh tokens are single-use) must exist once. The system login counts as one. */
  private async rotates(method: Account["method"], agent: string, secret: string | undefined): Promise<boolean> {
    if (method === "system" || method === "subscription") return true
    return method === "token" && !!secret && !!adapterFor(agent).secretRotates?.(secret)
  }

  private serialize<T>(body: () => Promise<T>): Promise<T> {
    const run = this.mutation.then(body, body)
    this.mutation = run.catch(() => {})
    return run
  }

  private load(): Promise<Map<string, Account>> {
    return this.loaded ??= (async () => {
      let text: string
      try { text = await readFile(this.file, "utf8") } catch (error) {
        if ((error as NodeJS.ErrnoException).code === "ENOENT") return new Map<string, Account>()
        throw error
      }
      try {
        const value = JSON.parse(text)
        if (!value || value.version !== 1 || !Array.isArray(value.accounts) || !value.accounts.every(validAccount)) throw new Error("Invalid accounts file shape")
        return new Map((value.accounts as Account[]).map(account => [account.id, account]))
      } catch (cause) {
        throw new CoreError("invalid_account_record", "Cannot read saved accounts", { cause })
      }
    })().catch(error => { this.loaded = undefined; throw error })
  }

  private async save(accounts: Map<string, Account>): Promise<void> {
    await mkdir(this.directory, { recursive: true, mode: 0o700 })
    await chmod(this.directory, 0o700)
    const temp = `${this.file}.${randomUUID()}.tmp`
    try {
      const file = await open(temp, "wx", 0o600)
      try { await file.writeFile(JSON.stringify({ version: 1, accounts: [...accounts.values()] })); await file.sync() } finally { await file.close() }
      await rename(temp, this.file)
    } finally { await rm(temp, { force: true }) }
  }
}

function cleanIdentity(identity: AccountIdentity | undefined): AccountIdentity | undefined {
  if (identity === undefined) return undefined
  if (!identity || typeof identity !== "object") throw new CoreError("invalid_input", "identity must be an object")
  const out: AccountIdentity = {}
  for (const key of ["email", "org", "accountId"] as const) {
    const value = identity[key]
    if (value === undefined) continue
    if (typeof value !== "string" || !value) throw new CoreError("invalid_input", `identity.${key} must be a nonempty string`)
    out[key] = value
  }
  return Object.keys(out).length ? out : undefined
}

/** Same login: equal account ids, or equal emails within the same (or unknown on both sides) org. */
export function sameIdentity(a: AccountIdentity, b: AccountIdentity): boolean {
  if (a.accountId && b.accountId && a.accountId === b.accountId) return true
  if (!a.email || !b.email || a.email.toLowerCase() !== b.email.toLowerCase()) return false
  return a.org === b.org || !a.org || !b.org
}

function validAccount(value: unknown): value is Account {
  if (!value || typeof value !== "object" || Array.isArray(value)) return false
  const a = value as Record<string, unknown>
  return typeof a.id === "string" && /^[a-zA-Z0-9_-]{1,128}$/.test(a.id)
    && typeof a.agent === "string" && !!a.agent
    && (a.method === "api_key" || a.method === "token" || a.method === "subscription")
    && typeof a.createdAt === "string" && Number.isFinite(Date.parse(a.createdAt))
    && (a.label === undefined || typeof a.label === "string")
    && (a.provider === undefined || typeof a.provider === "string")
    && (a.isolated === undefined || typeof a.isolated === "boolean")
    && (a.identity === undefined || (!!a.identity && typeof a.identity === "object"))
}
