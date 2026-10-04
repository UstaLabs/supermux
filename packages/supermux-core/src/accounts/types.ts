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

/** History roots (claude, codex) and the system logins' locations (grok, cursor). */
export type HomeRoots = {
  claudeRoot?: string
  codexRoot?: string
  /** System Grok home (auth.json). Default: $GROK_HOME or ~/.grok. */
  grokRoot?: string
  /** System Cursor config root (cursor/auth.json). Default: $XDG_CONFIG_HOME or ~/.config. */
  cursorRoot?: string
}

/** The subset of `fetch` the refresh needs (injectable for tests). */
export type FetchLike = (url: string, init: { method: string; headers: Record<string, string>; body: string }) => Promise<{
  ok: boolean
  status: number
  text(): Promise<string>
}>

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
  /** Expiry of a vault secret's access token (token accounts). */
  secretExpiry?(secret: string): Date | undefined
  /**
   * Vault-owned refresh: exchanges the secret's refresh token and returns the new secret (rotated
   * refresh token kept, or the old one when the server omits it). Errors are CoreError
   * account_expired / account_refresh_failed carrying the server's error code, never a secret.
   */
  refreshSecret?(secret: string, fetch: FetchLike): Promise<string>
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
  /** After a limit switch whose last turn hit the limit, send `continuePrompt` once on the new account. Default true. */
  continueAfterSwitch?: boolean
  /** Default: "Continue from where you stopped. Your previous turn hit a usage limit and you are now on another account." */
  continuePrompt?: string
  /** Token refresh transport. Default: globalThis.fetch. */
  fetch?: FetchLike
  /** Guided login (core.accounts.login). */
  login?: LoginConfig
}

export type LoginPhase = "starting" | "awaiting_user" | "verifying" | "done" | "failed" | "cancelled"

export type LoginState = {
  phase: LoginPhase
  /** Where the user signs in. */
  url?: string
  /** Device code to enter at `url` (codex, grok). */
  code?: string
  /** The CLI waits for a code pasted back via submitCode (claude). */
  needsCode?: boolean
  error?: string
  /** CoreError code of a failure (account_exists, login_failed, login_timeout, ...). */
  errorCode?: string
}

export type LoginOptions = {
  agent: string
  id?: string
  label?: string
  /** Claude: pre-fills the sign-in page and must match the signed-in email. */
  email?: string
  isolated?: boolean
  /** "subscription" (default): the login becomes an account home. "token" (codex): its tokens move to the vault. */
  as?: "subscription" | "token"
}

export interface LoginHandle {
  state(): LoginState
  /** Called on every state change; returns an unsubscribe function. */
  on(listener: (state: LoginState) => void): () => void
  /** Claude: types the code from the sign-in page into the CLI (text, then Enter). */
  submitCode(text: string): void
  /** Kills the login process group and removes its temp dir. */
  cancel(): void
  /** The new account; rejects with a CoreError (login_failed, login_timeout, login_cancelled, account_exists, ...). */
  readonly done: Promise<Account>
}

/** One login process. `pty`: the CLI needs a terminal (the default runner wraps it in `script`). */
export type LoginSpawn = { command: string; args: string[]; env: Record<string, string>; cwd: string; pty: boolean }

export interface LoginProcess {
  onOutput(listener: (chunk: string) => void): void
  onExit(listener: (code: number | null) => void): void
  write(data: string): void
  /** Terminates the whole process group. */
  kill(): void
}

export type LoginRunner = (spawn: LoginSpawn) => LoginProcess

export type LoginConfig = {
  /** Default: spawns a detached process group (Claude through `script` for a PTY). */
  runner?: LoginRunner
  /** CLI per agent kind. Defaults: claude, codex, grok, cursor-agent (or agent). */
  commands?: Partial<Record<"claude" | "codex" | "grok" | "cursor", string>>
  /** Whole login, including the user's part. Default 1800000 (30 min): a link opened on a phone takes a while. */
  timeoutMs?: number
}
