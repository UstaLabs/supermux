// Storage init that waits out a lock. A broker restarted right after a crash can find db.sqlite3 still held by a
// process of the old one's (a git credential helper, `supermux connect`, a reader) or by WAL recovery; failing boot
// then turns a crash into a restart loop. A lock is retried with a growing wait for a bounded time; anything else
// fails at once.

/** SQLite's "database is locked" / "database table is locked". */
export function isLockedError(err: unknown): boolean {
  const code = (err as { code?: unknown } | null)?.code
  if (code === "SQLITE_BUSY" || code === "SQLITE_LOCKED") return true
  return /database (?:table )?is locked/i.test(err instanceof Error ? err.message : String(err))
}

export interface InitStorageOptions {
  now?: () => number
  sleep?: (ms: number) => void
  /** Called before each wait: the retry's number (from 1), the wait ahead and the lock error. */
  onRetry: (attempt: number, waitMs: number, err: unknown) => void
  /** How long to keep retrying in all (default 60 s). */
  budgetMs?: number
}

/** [init], retried while the database is locked: 250 ms, doubling up to 5 s a wait, for [budgetMs] in all. */
export function initStorage<T>(init: () => T, options: InitStorageOptions): T {
  const now = options.now ?? Date.now
  const sleep = options.sleep ?? Bun.sleepSync
  const deadline = now() + (options.budgetMs ?? 60_000)
  let wait = 250
  for (let attempt = 1; ; attempt++) {
    try {
      return init()
    } catch (err) {
      if (!isLockedError(err) || now() >= deadline) throw err
      options.onRetry(attempt, wait, err)
      sleep(wait)
      wait = Math.min(wait * 2, 5_000)
    }
  }
}
