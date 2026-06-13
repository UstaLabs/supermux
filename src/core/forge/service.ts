// src/core/forge/service.ts
import { ForgeError, type CreateRepoInput, type ForgeAdapter, type ForgeConnection, type ForgeKind, type RemoteRepo } from "./types"
import type { ForgeStore } from "./store"
import { adapterFor } from "./registry"
import { projectDir, gitClone } from "../git/clone"
import { bindHttpsCredentials } from "./credential-helper"
import { ensureKeypair, seedKnownHosts, sshCommandFor, bindSshCommand, removeKeypair } from "./ssh-keys"
import { join } from "path"
import { mkdirSync } from "fs"
import { execFileSync } from "child_process"
import { scanCloned, removeCloned as rmCloned, isInsideRoot, type ClonedRepo } from "./cloned"
import { pullBranch, type PullResult } from "../git/remote"

export interface ForgeServiceConfig { projectsRoot: string; sshRoot: string }
export interface ConnError { connectionId: string; code: string; message: string }

export class ForgeService {
  private adapterFactory: (kind: ForgeKind) => ForgeAdapter

  constructor(
    private readonly store: ForgeStore,
    private readonly cfg: ForgeServiceConfig,
    adapterFactory: (kind: ForgeKind) => ForgeAdapter = adapterFor,
  ) { this.adapterFactory = adapterFactory }

  /** Test seam. */
  setAdapterFactory(f: (kind: ForgeKind) => ForgeAdapter): void { this.adapterFactory = f }

  connections(): ForgeConnection[] { return this.store.list() }

  async addConnection(opts: { kind: ForgeKind; host?: string; apiBase?: string; token: string; source: "pat" | "cli"; transport?: "https" | "ssh" }): Promise<ForgeConnection> {
    const host = opts.host ?? (opts.kind === "github" ? "github.com" : "gitlab.com")
    const adapter = this.adapterFactory(opts.kind)
    const apiBase = opts.apiBase ?? adapter.apiBaseFor(host)
    const probe = { id: "", kind: opts.kind, host, apiBase, label: "", account: { login: "" },
      source: opts.source, transport: opts.transport ?? "https", status: "ok", token: opts.token } as const
    const account = await adapter.verify(probe as any)
    const id = `${opts.kind}:${host}:${account.login}`.toLowerCase()
    const conn = this.store.add({ ...(probe as any), id, account, label: `${host} · @${account.login}` })
    if ((opts.transport ?? "https") === "ssh") await this.provisionSsh(id)
    return conn
  }

  removeConnection(id: string): void {
    if (this.store.getCredential(id)) removeKeypair(this.cfg.sshRoot, id)
    this.store.remove(id)
  }

  async provisionSsh(connectionId: string): Promise<{ publicKey: string; registered: boolean }> {
    const c = this.store.getCredential(connectionId)
    if (!c) throw new ForgeError("not_found", "connection not found")
    const kp = ensureKeypair(this.cfg.sshRoot, connectionId)
    let keyId: string | undefined
    try {
      const adapter = this.adapterFactory(c.kind)
      keyId = (await adapter.registerSshKey(c, kp.publicKey, `supermux:${c.host}`)).id
    } catch (e) { if (!(e instanceof ForgeError && e.code === "scope_missing")) throw e }
    this.store.setSsh(connectionId, { keyPath: kp.privatePath, keyId, fingerprint: kp.fingerprint })
    return { publicKey: kp.publicKey, registered: !!keyId }
  }

  async search(query: string): Promise<{ repos: RemoteRepo[]; errors: ConnError[] }> {
    const out: RemoteRepo[] = []; const errors: ConnError[] = []
    await Promise.all(this.store.list().map(async (pub) => {
      const c = this.store.getCredential(pub.id); if (!c) return
      try {
        const repos = await this.adapterFactory(c.kind).listRepos(c, { query })
        out.push(...repos)
      } catch (e) {
        const code = e instanceof ForgeError ? e.code : "unknown"
        errors.push({ connectionId: pub.id, code, message: String((e as Error).message) })
      }
    }))
    const seen = new Set<string>()
    const deduped = out.filter((r) => { const k = `${r.connectionId}:${r.fullName}`; if (seen.has(k)) return false; seen.add(k); return true })
    return { repos: deduped, errors }
  }

  async clone(connectionId: string, owner: string, name: string): Promise<{ localPath: string }> {
    const c = this.store.getCredential(connectionId)
    if (!c) throw new ForgeError("not_found", "connection not found")
    const adapter = this.adapterFactory(c.kind)
    const target = projectDir(this.cfg.projectsRoot, c.host, owner, name)
    const repo = { kind: c.kind, host: c.host, owner, name } as RemoteRepo
    if (c.transport === "ssh") {
      const kp = ensureKeypair(this.cfg.sshRoot, connectionId)
      const kh = seedKnownHosts(this.cfg.sshRoot, await adapter.hostKeys(c).catch(() => []))
      const ssh = sshCommandFor(kp.privatePath, kh)
      const res = await gitClone({ url: adapter.sshRemoteUrl(repo), targetDir: target, sshCommand: ssh })
      if (!res.reused) bindSshCommand(target, ssh)
    } else {
      const res = await gitClone({ url: `https://${c.host}/${owner}/${name}.git`, targetDir: target,
        https: { user: adapter.gitUser(), token: c.token } })
      if (!res.reused) bindHttpsCredentials(target, c.host, connectionId)
    }
    return { localPath: target }
  }

  async create(input: CreateRepoInput): Promise<{ repo: RemoteRepo; localPath: string }> {
    const c = this.store.getCredential(input.connectionId)
    if (!c) throw new ForgeError("not_found", "connection not found")
    const repo = await this.adapterFactory(c.kind).createRepo(c, input)
    const { localPath } = await this.clone(input.connectionId, repo.owner, repo.name)
    return { repo, localPath }
  }

  /** Create an empty local git repo (no remote) under projectsRoot/local/<name>. */
  async createLocal(name: string): Promise<{ localPath: string }> {
    if (!/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(name) || name === "..") throw new ForgeError("invalid_input", "invalid repo name")
    const dir = join(this.cfg.projectsRoot, "local", name)
    mkdirSync(dir, { recursive: true })
    execFileSync("git", ["init", "-q", dir])
    return { localPath: dir }
  }

  listCloned(): ClonedRepo[] { return scanCloned(this.cfg.projectsRoot) }

  removeCloned(path: string): void { rmCloned(this.cfg.projectsRoot, path) }

  pullCloned(path: string): PullResult {
    if (!isInsideRoot(this.cfg.projectsRoot, path)) throw new ForgeError("not_found", "not a managed repo")
    return pullBranch(path)
  }
}
