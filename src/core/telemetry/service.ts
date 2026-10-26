// TelemetryService: records the events in TELEMETRY.md and sends them in batches. Shaped like
// UpdateChecker: injected fetch and clock, idempotent start()/stop(), errors logged and never
// thrown, so an unreachable telemetry host can't affect the broker.
import type { Database } from "bun:sqlite"
import { randomUUID } from "crypto"
import type { FetchLike } from "../update/checker"
import type { SettingsStore } from "../settings/store"
import { validateEvent, type EventName, type TelemetryEvent } from "./events"
import { TelemetryOutbox, type RecentEvent } from "./outbox"
import { makeLogger } from "../../shared/log"

const log = makeLogger("telemetry")

const SETTINGS_KEY_INSTALL_ID = "telemetry.installId"
const FLUSH_INTERVAL_MS = 60_000
const FLUSH_AT = 50
const BATCH_MAX = 100
const MAX_ATTEMPTS = 5
const BACKOFF_BASE_MS = 30_000
const BACKOFF_MAX_MS = 60 * 60 * 1000
const HEARTBEAT_MS = 24 * 60 * 60 * 1000
const FETCH_TIMEOUT_MS = 10_000
const STOP_FLUSH_MS = 2_000

export type TelemetryCommon = { version: string; commit: string; installMode: string; env: "dev" | "release"; os: string; arch: string }

export type TelemetryServiceOptions = {
  db: Database
  settings: SettingsStore
  url: string
  isEnabled: () => boolean
  common: TelemetryCommon
  heartbeat: () => Record<string, unknown>
  fetchImpl?: FetchLike
  now?: () => number
}

export class TelemetryService {
  private readonly outbox: TelemetryOutbox
  private readonly fetchImpl: FetchLike
  private readonly now: () => number
  private flushTimer: ReturnType<typeof setInterval> | null = null
  private heartbeatTimer: ReturnType<typeof setInterval> | null = null
  private failures = 0
  private retryAt = 0
  private flushing: Promise<void> | null = null

  constructor(private readonly opts: TelemetryServiceOptions) {
    this.now = opts.now ?? Date.now
    this.fetchImpl = opts.fetchImpl ?? fetch
    this.outbox = new TelemetryOutbox(opts.db, { now: this.now })
  }

  installId(): string {
    const existing = this.opts.settings.get<string>(SETTINGS_KEY_INSTALL_ID)
    if (typeof existing === "string" && /^[0-9a-f-]{36}$/.test(existing)) return existing
    const id = randomUUID()
    this.opts.settings.set(SETTINGS_KEY_INSTALL_ID, id)
    return id
  }

  /** Queue one event. Never throws; an event that fails validation is logged and dropped. */
  record(event: EventName, props: Record<string, unknown>): void {
    try {
      if (!this.opts.isEnabled()) return
      const candidate = {
        uuid: randomUUID(),
        event,
        ts: new Date(this.now()).toISOString(),
        installId: this.installId(),
        ...this.opts.common,
        props,
      }
      const v = validateEvent(candidate)
      if (!v.ok) { log.warn("telemetry_event_invalid", { event, error: v.error }); return }
      this.outbox.add(v.event)
      if (this.outbox.size() >= FLUSH_AT) void this.flushWhenDue()
    } catch (err) {
      log.warn("telemetry_record_failed", { event, err: String(err) })
    }
  }

  recordHeartbeat(): void {
    this.record("broker.heartbeat", this.opts.heartbeat())
  }

  pending(): number { return this.outbox.size() }
  recent(): RecentEvent[] { return this.outbox.recent() }

  /** Call when the user (or env) turns telemetry off: unsent events are deleted right away. */
  onDisabled(): void { this.outbox.clear() }

  /** Flush unless we're inside a backoff window. */
  async flushWhenDue(): Promise<void> {
    if (this.now() < this.retryAt) return
    await this.flush()
  }

  /** Send one batch now. Concurrent calls share the same in-flight send. */
  flush(): Promise<void> {
    if (!this.flushing) this.flushing = this.sendOne().finally(() => { this.flushing = null })
    return this.flushing
  }

  private async sendOne(): Promise<void> {
    if (!this.opts.isEnabled()) return
    const batch: TelemetryEvent[] = this.outbox.take(BATCH_MAX)
    if (batch.length === 0) return
    const uuids = batch.map((e) => e.uuid)
    try {
      const res = await this.fetchImpl(this.opts.url, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ events: batch }),
        signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
      })
      if (res.ok) {
        this.outbox.markSent(uuids)
        this.failures = 0
        this.retryAt = 0
        return
      }
      if (res.status >= 400 && res.status < 500 && res.status !== 429) {
        log.warn("telemetry_batch_rejected", { status: res.status, count: batch.length })
        this.outbox.drop(uuids)
        this.backoff()
        return
      }
      throw new Error(`HTTP ${res.status}`)
    } catch (err) {
      log.debug("telemetry_send_failed", { err: String(err), count: batch.length })
      this.outbox.markFailed(uuids, MAX_ATTEMPTS)
      this.backoff()
    }
  }

  private backoff(): void {
    this.failures += 1
    this.retryAt = this.now() + Math.min(BACKOFF_MAX_MS, BACKOFF_BASE_MS * 2 ** (this.failures - 1))
  }

  start(): void {
    if (this.flushTimer) return
    this.flushTimer = setInterval(() => { void this.flushWhenDue() }, FLUSH_INTERVAL_MS)
    this.heartbeatTimer = setInterval(() => this.recordHeartbeat(), HEARTBEAT_MS)
    this.flushTimer.unref?.()
    this.heartbeatTimer.unref?.()
  }

  /** Stop timers and make one best-effort send, capped at 2 s so shutdown never hangs. */
  async stop(): Promise<void> {
    if (this.flushTimer) clearInterval(this.flushTimer)
    if (this.heartbeatTimer) clearInterval(this.heartbeatTimer)
    this.flushTimer = null
    this.heartbeatTimer = null
    await Promise.race([this.flush(), new Promise((r) => setTimeout(r, STOP_FLUSH_MS))])
  }
}
