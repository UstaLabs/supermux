// Background liveness poller for exposed proxies. TCP-probes each registered
// local port and fires onChange only when a proxy's status transitions, so the
// PWA can surface a "Down" badge without polling itself.
//
// Status semantics (the contract — see the proxy-down-badge spec):
//   - "up"      : a TCP connect to 127.0.0.1:<port> succeeded.
//   - "down"    : the connect was refused or timed out.
//   - "unknown" : not yet probed (default for any domain absent from the cache).
//
// ProxyEntry and the registry stay pure; status is merged into the WS payload
// only at the boundary in main.ts via getStatus().

type LiveStatus = "up" | "down"

export type ProxyStatus = LiveStatus | "unknown"

export interface ProxyTarget {
  domain: string
  port: number
}

export interface ProxyLivenessOpts {
  /** Current set of proxies to probe (read fresh every refresh). */
  listTargets: () => ProxyTarget[]
  /** Fired only on a status transition vs the cached value. */
  onChange: (domain: string, status: LiveStatus) => void
  /** Poll interval. Default 10s. */
  intervalMs?: number
  /** Per-probe TCP connect timeout. Default 1s. */
  timeoutMs?: number
  /**
   * Injectable probe so tests need not open real sockets. Resolves true if the
   * port accepts a connection, false otherwise. Must never reject. Defaults to
   * the real Bun.connect probe.
   */
  connect?: (port: number, timeoutMs: number) => Promise<boolean>
}

// Real probe: open a TCP connection to 127.0.0.1:<port>, resolve true on the
// first `open` (then immediately close), false on `error`, racing a timeout
// timer that resolves false. Never rejects — a thrown Bun.connect (rare) also
// resolves false.
function realConnect(port: number, timeoutMs: number): Promise<boolean> {
  return new Promise<boolean>((resolve) => {
    let settled = false
    const finish = (value: boolean) => {
      if (settled) return
      settled = true
      clearTimeout(timer)
      resolve(value)
    }
    const timer = setTimeout(() => finish(false), timeoutMs)
    try {
      Bun.connect({
        hostname: "127.0.0.1",
        port,
        socket: {
          // Resolve true BEFORE closing: socket.end() can fire `close`
          // synchronously, which would otherwise settle the promise false first.
          open: (socket) => { finish(true); try { socket.end() } catch {} },
          error: () => finish(false),
          // A connection closed before `open` is a failure; once `open` has
          // settled true, this is a no-op (the only success path is `open`).
          close: () => finish(false),
          // Bun.connect requires a `data` (or `drain`) handler or it throws
          // synchronously; we never read, so this is a no-op.
          data: () => {},
        },
      }).catch(() => finish(false))
    } catch {
      finish(false)
    }
  })
}

export class ProxyLivenessMonitor {
  private readonly listTargets: () => ProxyTarget[]
  private readonly onChange: (domain: string, status: LiveStatus) => void
  private readonly intervalMs: number
  private readonly timeoutMs: number
  private readonly connect: (port: number, timeoutMs: number) => Promise<boolean>
  private readonly cache = new Map<string, LiveStatus>()
  private timer: ReturnType<typeof setInterval> | undefined

  constructor(opts: ProxyLivenessOpts) {
    this.listTargets = opts.listTargets
    this.onChange = opts.onChange
    this.intervalMs = opts.intervalMs ?? 10_000
    this.timeoutMs = opts.timeoutMs ?? 1_000
    this.connect = opts.connect ?? realConnect
  }

  /** Cached status for a domain, or "unknown" if it has never been probed. */
  getStatus(domain: string): ProxyStatus {
    return this.cache.get(domain) ?? "unknown"
  }

  /** Kick an immediate refresh, then poll on the interval. Idempotent. */
  start(): void {
    if (this.timer) return
    void this.refresh()
    this.timer = setInterval(() => { void this.refresh() }, this.intervalMs)
  }

  stop(): void {
    if (!this.timer) return
    clearInterval(this.timer)
    this.timer = undefined
  }

  /**
   * Probe every current target concurrently, fire onChange on transitions, and
   * prune cache entries whose domain has left listTargets(). A throw never
   * escapes (so a buggy probe can't kill the interval).
   */
  async refresh(): Promise<void> {
    try {
      const targets = this.listTargets()
      await Promise.all(
        targets.map(async ({ domain, port }) => {
          const up = await this.connect(port, this.timeoutMs)
          const status: LiveStatus = up ? "up" : "down"
          if (this.cache.get(domain) !== status) {
            this.cache.set(domain, status)
            this.onChange(domain, status)
          }
        }),
      )
      // Prune: drop cached entries for domains no longer exposed, so a re-add
      // re-probes from scratch (and never broadcasts a stale status).
      const live = new Set(targets.map((t) => t.domain))
      for (const domain of this.cache.keys()) {
        if (!live.has(domain)) this.cache.delete(domain)
      }
    } catch {
      // Swallow: refresh runs on a timer; a throw must not stop the poller.
    }
  }
}
