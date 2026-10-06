import { chmod, mkdir, open, readFile, rm, stat } from "node:fs/promises"
import { randomUUID } from "node:crypto"
import { join } from "node:path"
import { CoreError } from "../errors.js"
import { assertVaultId } from "./vault.js"
import type { Account, AuthAdapter, FetchLike, Vault } from "./types.js"

/** A lock older than this is stale even when its pid is alive (pid reuse, hung holder). */
const LOCK_STALE_MS = 120_000
const LOCK_WAIT_MS = 30_000
const LOCK_POLL_MS = 50

/**
 * Vault-owned refresh: one refresh per account at a time, across concurrent calls (shared promise)
 * and processes (`<lockDirectory>/<id>.lock`, O_EXCL, stale when its pid is gone or it is older
 * than two minutes). The rotated secret is stored before anyone uses the new access token.
 */
export class RefreshCoordinator {
  private readonly inflight = new Map<string, Promise<string>>()

  constructor(private readonly lockDirectory: string, private readonly vault: Vault, private readonly fetch: FetchLike) {}

  /**
   * The account's secret, refreshed first when its access token expires within `minValidityMs`
   * and it carries a refresh token. Secrets without a known expiry or refresh token are returned as is.
   */
  async fresh(account: Account, adapter: AuthAdapter, minValidityMs: number): Promise<string | undefined> {
    const secret = await this.vault.get(account.id)
    if (secret === undefined || !due(adapter, secret, minValidityMs)) return secret
    const running = this.inflight.get(account.id)
    if (running) return running
    const run = this.refresh(account, adapter, minValidityMs)
    this.inflight.set(account.id, run)
    try { return await run } finally { if (this.inflight.get(account.id) === run) this.inflight.delete(account.id) }
  }

  private async refresh(account: Account, adapter: AuthAdapter, minValidityMs: number): Promise<string> {
    const release = await this.lock(account.id)
    try {
      // Another process may have refreshed while we waited for the lock.
      const current = await this.vault.get(account.id)
      if (current === undefined) throw new CoreError("account_secret_missing", `Account ${account.id} has no secret in the vault`)
      if (!due(adapter, current, minValidityMs)) return current
      const next = await adapter.refreshSecret!(current, this.fetch)
      await this.vault.put(account.id, next)
      return next
    } finally { await release() }
  }

  private async lock(id: string): Promise<() => Promise<void>> {
    assertVaultId(id)
    await mkdir(this.lockDirectory, { recursive: true, mode: 0o700 })
    await chmod(this.lockDirectory, 0o700)
    const path = join(this.lockDirectory, `${id}.lock`)
    const nonce = randomUUID()
    const deadline = Date.now() + LOCK_WAIT_MS
    for (;;) {
      try {
        const file = await open(path, "wx", 0o600)
        try { await file.writeFile(JSON.stringify({ pid: process.pid, nonce, at: new Date().toISOString() })) } finally { await file.close() }
        return async () => {
          try { if (JSON.parse(await readFile(path, "utf8")).nonce === nonce) await rm(path, { force: true }) } catch { /* already gone */ }
        }
      } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error
      }
      if (await stale(path)) { await rm(path, { force: true }); continue }
      if (Date.now() > deadline) throw new CoreError("account_refresh_failed", `Account ${id}: another process holds the refresh lock`)
      await new Promise(resolve => setTimeout(resolve, LOCK_POLL_MS))
    }
  }
}

function due(adapter: AuthAdapter, secret: string, minValidityMs: number): boolean {
  if (!adapter.refreshSecret || !adapter.secretRotates?.(secret)) return false
  const expiry = adapter.secretExpiry?.(secret)
  return !!expiry && expiry.getTime() - Date.now() < minValidityMs
}

async function stale(path: string): Promise<boolean> {
  try {
    const [info, text] = await Promise.all([stat(path), readFile(path, "utf8")])
    if (Date.now() - info.mtimeMs > LOCK_STALE_MS) return true
    let pid: unknown
    try { pid = JSON.parse(text).pid } catch { return Date.now() - info.mtimeMs > 1000 /* half-written: give the writer a moment */ }
    if (typeof pid !== "number") return true
    try { process.kill(pid, 0); return false } catch (error) { return (error as NodeJS.ErrnoException).code === "ESRCH" }
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === "ENOENT" ? false : Promise.reject(error)
  }
}
