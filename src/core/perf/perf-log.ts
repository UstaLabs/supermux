// Load-time tracing (card #54): what the broker spent on each response, and what the apps report
// about their own launch and chat opens, in one JSONL file keyed by trace id. Off unless MUX_PERF=1
// or `<dir>/enabled` exists (`bun scripts/perf-timeline.ts on`); while off, record() is a no-op and
// nothing touches the disk. Read it back with `bun scripts/perf-timeline.ts`.
import { appendFileSync, existsSync, mkdirSync, renameSync, rmSync, statSync, writeFileSync } from "fs"
import { join } from "path"

export interface PerfEvent {
  /** Epoch ms. Broker events: when recorded. Client events: broker-estimated time of the event. */
  ts: number
  src: "broker" | "client"
  event: string
  /** One id per app launch (`L…`) or chat open (`C…`); shared by the app's and the broker's events. */
  trace?: string
  /** One id per request (a WS subscribe, an HTTP call), to pair a client span with the broker's. */
  req?: string
  [field: string]: unknown
}

/** A [PerfEvent] as recorded: `ts` defaults to now and `src` to "broker". */
export interface PerfInput {
  event: string
  ts?: number
  src?: PerfEvent["src"]
  trace?: string
  req?: string
  [field: string]: unknown
}

export interface PerfLogOptions {
  /** Holds `perf.jsonl` (+ `perf.jsonl.1`) and the `enabled` flag file. */
  dir: string
  envEnabled?: boolean
  maxFileBytes?: number
  ringSize?: number
  /** Buffered writes are appended this often; 0 writes each event at once (tests). */
  flushMs?: number
  /** How long a stat of the flag file is trusted. */
  statCacheMs?: number
  now?: () => number
}

export class PerfLog {
  private readonly ring: PerfEvent[] = []
  private pending: string[] = []
  private flushTimer: ReturnType<typeof setInterval> | undefined
  private flagCheckedAt = -Infinity
  private flagOn = false
  private readonly file: string

  constructor(private readonly opts: PerfLogOptions) {
    this.file = join(opts.dir, "perf.jsonl")
  }

  /** The clock events are stamped with (epoch ms). */
  now(): number {
    return (this.opts.now ?? Date.now)()
  }

  enabled(): boolean {
    if (this.opts.envEnabled) return true
    const now = Date.now()
    if (now - this.flagCheckedAt >= (this.opts.statCacheMs ?? 2_000)) {
      this.flagCheckedAt = now
      this.flagOn = existsSync(join(this.opts.dir, "enabled"))
    }
    return this.flagOn
  }

  setEnabled(on: boolean): void {
    const flag = join(this.opts.dir, "enabled")
    if (on) {
      mkdirSync(this.opts.dir, { recursive: true })
      writeFileSync(flag, `${new Date().toISOString()}\n`)
    } else {
      rmSync(flag, { force: true })
    }
    this.flagCheckedAt = -Infinity
  }

  record(input: PerfInput): void {
    if (!this.enabled()) return
    const { ts, src, event, ...rest } = input
    const e: PerfEvent = { ts: ts ?? this.now(), src: src ?? "broker", event, ...rest }
    this.ring.push(e)
    const cap = this.opts.ringSize ?? 5_000
    if (this.ring.length > cap) this.ring.splice(0, this.ring.length - cap)
    this.pending.push(JSON.stringify(e))
    if ((this.opts.flushMs ?? 1_000) === 0) this.flush()
    else if (!this.flushTimer) {
      this.flushTimer = setInterval(() => this.flush(), this.opts.flushMs ?? 1_000)
      ;(this.flushTimer as { unref?: () => void }).unref?.()
    }
  }

  events(filter: { trace?: string; since?: number; limit?: number } = {}): PerfEvent[] {
    let out = this.ring.filter((e) => (filter.trace === undefined || e.trace === filter.trace) && (filter.since === undefined || e.ts >= filter.since))
    if (filter.limit !== undefined) out = out.slice(-filter.limit)
    return out
  }

  flush(): void {
    if (this.pending.length === 0) return
    const text = this.pending.join("\n") + "\n"
    this.pending = []
    try {
      mkdirSync(this.opts.dir, { recursive: true })
      let size = 0
      try { size = statSync(this.file).size } catch { /* new file */ }
      if (size > 0 && size + text.length > (this.opts.maxFileBytes ?? 20_000_000)) renameSync(this.file, `${this.file}.1`)
      appendFileSync(this.file, text)
    } catch { /* tracing must never break the broker */ }
  }

  close(): void {
    if (this.flushTimer) clearInterval(this.flushTimer)
    this.flushTimer = undefined
    this.flush()
  }
}

/** Frames, bytes and socket sends per frame type since the last [take]. */
export class FrameStats {
  private byType = new Map<string, { frames: number; bytes: number; sends: number }>()

  add(type: string, bytes: number, recipients: number): void {
    const s = this.byType.get(type) ?? { frames: 0, bytes: 0, sends: 0 }
    s.frames++
    s.bytes += bytes
    s.sends += recipients
    this.byType.set(type, s)
  }

  take(): Record<string, { frames: number; bytes: number; sends: number }> {
    const out = Object.fromEntries(this.byType)
    this.byType = new Map()
    return out
  }
}

/** A request path with its ids folded to `:id`, so one route's calls group together. */
export function perfPath(path: string): string {
  return path
    .split("/") // platform-ok: a request URL path, / by definition
    .map((seg) => (/^\d+$/.test(seg) || /^[0-9a-f]{8}-[0-9a-f-]{20,}$/i.test(seg) || (seg.length >= 12 && /\d/.test(seg) && /^[A-Za-z0-9_-]+$/.test(seg)) ? ":id" : seg))
    .join("/")
}

const round1 = (ms: number) => Math.round(ms * 10) / 10

/** Named phases of one piece of work: `lap(name)` books the time since the previous lap. */
export function lapTimer(now: () => number = () => performance.now()) {
  const start = now()
  let last = start
  const laps: Record<string, number> = {}
  return {
    laps,
    lap(name: string): void {
      const t = now()
      laps[name] = round1(t - last)
      last = t
    },
    total: (): number => round1(now() - start),
  }
}

/**
 * How late a timer that should fire every [periodMs] actually fires: the broker's event loop was
 * blocked for that long. [tick] is called by the timer; [take] reports and resets.
 */
export class LoopLag {
  private last: number
  private ticks = 0
  private maxMs = 0
  private over50ms = 0

  constructor(private readonly periodMs: number, private readonly now: () => number = () => performance.now()) {
    this.last = now()
  }

  tick(): void {
    const t = this.now()
    const late = Math.max(0, t - this.last - this.periodMs)
    this.last = t
    this.ticks++
    if (late > this.maxMs) this.maxMs = late
    if (late > 50) this.over50ms++
  }

  take(): { ticks: number; maxMs: number; over50ms: number } {
    const out = { ticks: this.ticks, maxMs: round1(this.maxMs), over50ms: this.over50ms }
    this.ticks = 0
    this.maxMs = 0
    this.over50ms = 0
    return out
  }
}
