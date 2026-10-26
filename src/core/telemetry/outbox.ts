// SQLite queue for telemetry events. Caps from TELEMETRY.md: ≤5000 unsent, nothing older than 7 days,
// a batch dropped after 5 failed sends. Also keeps the last 50 events for "View recent events".
import type { Database } from "bun:sqlite"
import type { TelemetryEvent } from "./events"

const RECENT_KEEP = 50

export type OutboxOptions = { now?: () => number; maxRows?: number; maxAgeMs?: number }
export type RecentEvent = TelemetryEvent & { sent: boolean }

export class TelemetryOutbox {
  private readonly now: () => number
  private readonly maxRows: number
  private readonly maxAgeMs: number

  constructor(private readonly db: Database, opts: OutboxOptions = {}) {
    this.now = opts.now ?? Date.now
    this.maxRows = opts.maxRows ?? 5000
    this.maxAgeMs = opts.maxAgeMs ?? 7 * 24 * 3600 * 1000
  }

  add(event: TelemetryEvent): void {
    const json = JSON.stringify(event)
    const at = this.now()
    this.db.run("INSERT OR IGNORE INTO telemetry_outbox (uuid, event_json, created_at) VALUES (?, ?, ?)", [event.uuid, json, at])
    this.db.run("INSERT OR IGNORE INTO telemetry_recent (uuid, event_json, created_at) VALUES (?, ?, ?)", [event.uuid, json, at])
    this.db.run(
      "DELETE FROM telemetry_recent WHERE uuid NOT IN (SELECT uuid FROM telemetry_recent ORDER BY created_at DESC, rowid DESC LIMIT ?)",
      [RECENT_KEEP],
    )
    this.prune()
  }

  take(limit: number): TelemetryEvent[] {
    this.prune()
    const rows = this.db
      .query("SELECT event_json FROM telemetry_outbox ORDER BY created_at, rowid LIMIT ?")
      .all(limit) as { event_json: string }[]
    return rows.map((r) => JSON.parse(r.event_json) as TelemetryEvent)
  }

  markSent(uuids: string[]): void {
    const at = this.now()
    for (const u of uuids) {
      this.db.run("DELETE FROM telemetry_outbox WHERE uuid = ?", [u])
      this.db.run("UPDATE telemetry_recent SET sent_at = ? WHERE uuid = ?", [at, u])
    }
  }

  /** Count one failed attempt; rows at `maxAttempts` are deleted. */
  markFailed(uuids: string[], maxAttempts: number): void {
    for (const u of uuids) {
      this.db.run("UPDATE telemetry_outbox SET attempts = attempts + 1 WHERE uuid = ?", [u])
      this.db.run("DELETE FROM telemetry_outbox WHERE uuid = ? AND attempts >= ?", [u, maxAttempts])
    }
  }

  drop(uuids: string[]): void {
    for (const u of uuids) this.db.run("DELETE FROM telemetry_outbox WHERE uuid = ?", [u])
  }

  size(): number {
    return (this.db.query("SELECT COUNT(*) AS n FROM telemetry_outbox").get() as { n: number }).n
  }

  clear(): void {
    this.db.run("DELETE FROM telemetry_outbox")
    this.db.run("DELETE FROM telemetry_recent")
  }

  recent(): RecentEvent[] {
    const rows = this.db
      .query("SELECT event_json, sent_at FROM telemetry_recent ORDER BY created_at DESC, rowid DESC LIMIT ?")
      .all(RECENT_KEEP) as { event_json: string; sent_at: number | null }[]
    return rows.map((r) => ({ ...(JSON.parse(r.event_json) as TelemetryEvent), sent: r.sent_at !== null }))
  }

  private prune(): void {
    this.db.run("DELETE FROM telemetry_outbox WHERE created_at < ?", [this.now() - this.maxAgeMs])
    this.db.run(
      "DELETE FROM telemetry_outbox WHERE uuid NOT IN (SELECT uuid FROM telemetry_outbox ORDER BY created_at DESC, rowid DESC LIMIT ?)",
      [this.maxRows],
    )
  }
}
