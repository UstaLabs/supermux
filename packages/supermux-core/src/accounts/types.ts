/**
 * Accounts: several logins per agent, chosen per session. Secrets live only in a Vault;
 * Account metadata and SessionRecord carry ids, never credentials.
 */
export type AccountMethod = "system" | "api_key" | "token" | "subscription"

export type AccountIdentity = { email?: string; org?: string; accountId?: string }

export type Account = {
  id: string
  /** Core agent id (driver id). The adapter is chosen by this id (claude, codex, cursor, grok, opencode). */
  agent: string
  method: AccountMethod
  label?: string
  identity?: AccountIdentity
  /** Own history root, never linked to the shared one; never picked by the switch policy. */
  isolated?: boolean
  /** OpenCode api_key accounts: the provider id the key belongs to (e.g. "anthropic"). */
  provider?: string
  createdAt: string
}

export type AddAccountOptions = {
  id?: string
  agent: string
  method: Exclude<AccountMethod, "system">
  secret?: string
  label?: string
  isolated?: boolean
  identity?: AccountIdentity
  provider?: string
}

/** Host-pluggable secret storage. Ids are account ids (`[A-Za-z0-9_-]{1,128}`). */
export interface Vault {
  get(id: string): Promise<string | undefined>
  put(id: string, secret: string): Promise<void>
  delete(id: string): Promise<void>
}

/** One entry of an agent's history root that every non-isolated account home links to. */
export type SharedEntry = { name: string; type: "dir" | "file"; /** Content when the root file must be created. */ initial?: string }

export type HomeLayout = { root(): string; shared: SharedEntry[] }

/** What an adapter adds to a launch. `unset` keys are removed after env merging; `args` are appended to the CLI args. */
export type Materialized = { env: Record<string, string>; unset?: string[]; args?: string[] }

export type MaterializeContext = {
  /** The vault secret for api_key/token accounts. */
  secret?: string
  /** Creates (idempotently) and returns the account home for this adapter's layout. */
  home(): Promise<string>
}

export type HomeRoots = { claudeRoot?: string; codexRoot?: string }

export type AuthAdapter = {
  readonly kind: "claude" | "codex" | "cursor" | "grok" | "opencode" | "generic"
  readonly methods: readonly AccountMethod[]
  /** Subscription homes: which entries link to the shared history root (undefined: private home, no links). */
  layout?(roots: HomeRoots): HomeLayout
  materialize(account: Account, context: MaterializeContext): Promise<Materialized>
  /** Who is logged in: `home` for subscription accounts, undefined for the system login. Never throws. */
  readIdentity?(home: string | undefined, roots: HomeRoots): Promise<AccountIdentity | undefined>
  /** Identity carried by a vault secret (Codex ChatGPT token: account_id). */
  secretIdentity?(secret: string): AccountIdentity | undefined
  /** Whether a secret holds a refresh token (a rotating login that must not exist twice). */
  secretRotates?(secret: string): boolean
  /** Rate-limit payload of a normalized `usage` body → windows. Unknown shapes → []. */
  usage?(rateLimits: unknown): UsageWindow[]
  /** Validates an add() before anything is stored. */
  validate?(options: AddAccountOptions): void
}

export type UsageWindow = { name: string; usedPercent: number; resetsAt?: Date }

export type AccountsOptions = {
  /** Default: fileVault(<stateDirectory>/accounts/vault). */
  vault?: Vault
  /** History roots. Defaults: claude $CLAUDE_CONFIG_DIR or ~/.claude; codex $CODEX_HOME or ~/.codex. */
  homes?: HomeRoots
  /** Switch a session to another account of its agent when a rate-limit window reaches 100%. */
  autoSwitch?: boolean
}
