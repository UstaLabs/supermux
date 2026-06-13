// src/core/forge/types.ts
export type ForgeKind = "github" | "gitlab"
export type Transport = "https" | "ssh"

/** Public connection shape — returned over the API. NEVER carries the token. */
export interface ForgeConnection {
  id: string          // `${kind}:${host}:${login}` (lowercased)
  kind: ForgeKind
  host: string        // "github.com" | "gitlab.com" | "git.acme.com"
  apiBase: string     // "https://api.github.com" | "https://gitlab.com/api/v4" | self-hosted
  label: string       // "github.com · @AhmetHuseyinDOK"
  account: { login: string; name?: string; avatarUrl?: string }
  source: "pat" | "cli"
  transport: Transport
  ssh?: { fingerprint: string; registered: boolean }
  status: "ok" | "needs_reconnect"
}

/** Server-only: connection + secret + ssh key paths. Never serialised to clients. */
export interface ForgeCredential extends ForgeConnection {
  token: string
  sshKeyPath?: string
  sshKeyId?: string
}

export interface RemoteRepo {
  connectionId: string
  kind: ForgeKind
  host: string
  owner: string
  name: string
  fullName: string    // "owner/name"
  private: boolean
  description?: string
  defaultBranch: string
  language?: string
  updatedAt?: string
  cloneUrl: string    // https, NO creds
  webUrl: string
}

export interface CreateRepoInput {
  connectionId: string
  name: string
  owner?: string      // default: authenticated user; else an org/group
  private: boolean
  description?: string
  initReadme?: boolean
}

export class ForgeError extends Error {
  constructor(
    readonly code: "auth" | "scope_missing" | "rate_limited" | "conflict" | "not_found" | "network" | "unknown" | "invalid_input",
    message: string,
    readonly status?: number,
  ) {
    super(message)
    this.name = "ForgeError"
  }
}

export interface ForgeAdapter {
  kind: ForgeKind
  /** Default apiBase for a host (e.g. github.com → https://api.github.com). */
  apiBaseFor(host: string): string
  /** Validate the token + resolve the account. Throws ForgeError on failure. */
  verify(c: ForgeCredential): Promise<ForgeConnection["account"]>
  listRepos(c: ForgeCredential, opts: { query?: string; perPage?: number }): Promise<RemoteRepo[]>
  createRepo(c: ForgeCredential, input: CreateRepoInput): Promise<RemoteRepo>
  /** Username paired with the token for HTTPS (github: "x-access-token", gitlab: "oauth2"). */
  gitUser(): string
  /** Add a public key to the account. Throws ForgeError("scope_missing") if not permitted. */
  registerSshKey(c: ForgeCredential, publicKey: string, title: string): Promise<{ id: string }>
  sshRemoteUrl(repo: RemoteRepo): string            // git@host:owner/repo.git
  hostKeys(c: ForgeCredential): Promise<string[]>   // known_hosts lines
}
