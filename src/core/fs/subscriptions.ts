export interface SubscriptionHooks { onFirst: (real: string) => void; onLast: (real: string) => void }

/** Who watches which real folder. `S` is the socket handle (a ServerWebSocket in the broker). */
export class SubscriptionRegistry<S> {
  private readonly byDir = new Map<string, Map<S, Set<string>>>()
  private readonly bySocket = new Map<S, Map<string, string>>() // path → real
  private readonly lingering = new Map<string, ReturnType<typeof setTimeout>>()
  private readonly graceMs: number
  private readonly maxPerSocket: number

  constructor(private readonly hooks: SubscriptionHooks, opts: { graceMs?: number; maxPerSocket?: number } = {}) {
    this.graceMs = opts.graceMs ?? 10_000
    this.maxPerSocket = opts.maxPerSocket ?? 500
  }

  count(sock: S): number { return this.bySocket.get(sock)?.size ?? 0 }
  isWatched(real: string): boolean { return this.byDir.has(real) || this.lingering.has(real) }
  realOf(sock: S, path: string): string | undefined { return this.bySocket.get(sock)?.get(path) }

  /** Every real folder that currently has at least one subscriber (lingering ones have none to notify). */
  watchedReals(): string[] { return [...this.byDir.keys()] }

  add(sock: S, path: string, real: string): "ok" | "limit" {
    const mine = this.bySocket.get(sock) ?? new Map<string, string>()
    if (mine.get(path) === real) return "ok"
    if (!mine.has(path) && mine.size >= this.maxPerSocket) return "limit"
    if (mine.has(path)) this.remove(sock, path)
    mine.set(path, real)
    this.bySocket.set(sock, mine)

    let subs = this.byDir.get(real)
    if (!subs) {
      subs = new Map()
      this.byDir.set(real, subs)
      const t = this.lingering.get(real)
      if (t) { clearTimeout(t); this.lingering.delete(real) } else this.hooks.onFirst(real)
    }
    const paths = subs.get(sock) ?? new Set<string>()
    paths.add(path)
    subs.set(sock, paths)
    return "ok"
  }

  remove(sock: S, path: string): void {
    const mine = this.bySocket.get(sock)
    const real = mine?.get(path)
    if (!mine || real === undefined) return
    mine.delete(path)
    if (mine.size === 0) this.bySocket.delete(sock)
    const subs = this.byDir.get(real)
    const paths = subs?.get(sock)
    paths?.delete(path)
    if (paths && paths.size === 0) subs!.delete(sock)
    if (subs && subs.size === 0) {
      this.byDir.delete(real)
      const t = setTimeout(() => {
        this.lingering.delete(real)
        if (!this.byDir.has(real)) this.hooks.onLast(real)
      }, this.graceMs)
      ;(t as { unref?: () => void }).unref?.() // a pending teardown must not hold the process open
      this.lingering.set(real, t)
    }
  }

  dropSocket(sock: S): void {
    for (const path of [...(this.bySocket.get(sock)?.keys() ?? [])]) this.remove(sock, path)
  }

  subscribersOf(real: string): Array<{ sock: S; path: string }> {
    const out: Array<{ sock: S; path: string }> = []
    for (const [sock, paths] of this.byDir.get(real) ?? []) for (const path of paths) out.push({ sock, path })
    return out
  }

  /** The folder is gone: drop it for everyone immediately (no grace). */
  removeAllFor(real: string): Array<{ sock: S; path: string }> {
    const subs = this.subscribersOf(real)
    for (const { sock, path } of subs) {
      const mine = this.bySocket.get(sock)
      mine?.delete(path)
      if (mine && mine.size === 0) this.bySocket.delete(sock)
    }
    const had = this.byDir.delete(real)
    const t = this.lingering.get(real)
    if (t) { clearTimeout(t); this.lingering.delete(real) }
    if (had || t) this.hooks.onLast(real)
    return subs
  }
}
