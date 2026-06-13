// src/core/forge/store.ts
import type { Database } from "bun:sqlite"
import type { ForgeConnection, ForgeCredential, ForgeKind } from "./types"

type Row = {
  id: string; kind: string; host: string; api_base: string; login: string
  name: string | null; avatar_url: string | null; token: string; source: string
  transport: string; ssh_key_path: string | null; ssh_key_id: string | null; created_at: number
}

/** Write-through SQLite store for forge connections. Mirrors SettingsStore. */
export class ForgeStore {
  private cache = new Map<string, ForgeCredential>()

  constructor(private readonly db: Database) {
    for (const r of this.db.query("SELECT * FROM forge_connections").all() as Row[]) {
      this.cache.set(r.id, this.fromRow(r))
    }
  }

  private fromRow(r: Row): ForgeCredential {
    return {
      id: r.id, kind: r.kind as ForgeKind, host: r.host, apiBase: r.api_base,
      label: `${r.host} · @${r.login}`,
      account: { login: r.login, name: r.name ?? undefined, avatarUrl: r.avatar_url ?? undefined },
      source: r.source as "pat" | "cli", transport: r.transport as "https" | "ssh",
      ssh: r.ssh_key_path ? { fingerprint: "", registered: !!r.ssh_key_id } : undefined,
      status: "ok", token: r.token,
      sshKeyPath: r.ssh_key_path ?? undefined, sshKeyId: r.ssh_key_id ?? undefined,
    }
  }

  /** Redacted view (no token / key paths) for the API. */
  private redact(c: ForgeCredential): ForgeConnection {
    const { token: _t, sshKeyPath: _p, sshKeyId: _i, ...pub } = c
    return pub
  }

  list(): ForgeConnection[] {
    return [...this.cache.values()].map((c) => this.redact(c))
  }

  getCredential(id: string): ForgeCredential | undefined {
    return this.cache.get(id)
  }

  add(c: ForgeCredential): ForgeConnection {
    this.cache.set(c.id, c)
    this.db.run(
      `INSERT INTO forge_connections
        (id,kind,host,api_base,login,name,avatar_url,token,source,transport,ssh_key_path,ssh_key_id,created_at)
       VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
       ON CONFLICT(id) DO UPDATE SET
        kind=excluded.kind, host=excluded.host, api_base=excluded.api_base, login=excluded.login,
        name=excluded.name, avatar_url=excluded.avatar_url, token=excluded.token, source=excluded.source,
        transport=excluded.transport, ssh_key_path=excluded.ssh_key_path, ssh_key_id=excluded.ssh_key_id`,
      [c.id, c.kind, c.host, c.apiBase, c.account.login, c.account.name ?? null, c.account.avatarUrl ?? null,
       c.token, c.source, c.transport, c.sshKeyPath ?? null, c.sshKeyId ?? null, Date.now()],
    )
    return this.redact(c)
  }

  setStatus(id: string, status: ForgeConnection["status"]): void {
    const c = this.cache.get(id); if (!c) return
    c.status = status // status is transient (not persisted) — recomputed on use
  }

  setSsh(id: string, ssh: { keyPath: string; keyId?: string; fingerprint: string }): void {
    const c = this.cache.get(id); if (!c) return
    c.sshKeyPath = ssh.keyPath; c.sshKeyId = ssh.keyId
    c.ssh = { fingerprint: ssh.fingerprint, registered: !!ssh.keyId }
    this.db.run("UPDATE forge_connections SET ssh_key_path=?, ssh_key_id=? WHERE id=?", [ssh.keyPath, ssh.keyId ?? null, id])
  }

  remove(id: string): void {
    this.cache.delete(id)
    this.db.run("DELETE FROM forge_connections WHERE id=?", [id])
  }
}
