import type { Db } from "../storage/db"

export interface PushSubscriptionRecord {
  device: string
  endpoint: string
  keys: { p256dh: string; auth: string }
  userAgent?: string
  createdAt: string
  lastUsedAt?: string
}

export class PushSubscriptionStore {
  constructor(private readonly db: Db) {}

  upsert(rec: { device: string; endpoint: string; keys: { p256dh: string; auth: string }; userAgent?: string }): void {
    this.db.prepare(`
      INSERT INTO push_subscriptions (device, endpoint, p256dh, auth, user_agent, created_at, last_used_at)
      VALUES (?, ?, ?, ?, ?, datetime('now'), NULL)
      ON CONFLICT(device) DO UPDATE SET
        endpoint = excluded.endpoint,
        p256dh = excluded.p256dh,
        auth = excluded.auth,
        user_agent = excluded.user_agent,
        last_used_at = NULL
    `).run(rec.device, rec.endpoint, rec.keys.p256dh, rec.keys.auth, rec.userAgent ?? null)
  }

  remove(device: string): boolean {
    const info = this.db.prepare("DELETE FROM push_subscriptions WHERE device = ?").run(device)
    return info.changes > 0
  }

  get(device: string): PushSubscriptionRecord | null {
    const row = this.db.prepare(`
      SELECT device, endpoint, p256dh, auth, user_agent, created_at, last_used_at
      FROM push_subscriptions WHERE device = ?
    `).get(device) as any
    if (!row) return null
    return {
      device: row.device,
      endpoint: row.endpoint,
      keys: { p256dh: row.p256dh, auth: row.auth },
      userAgent: row.user_agent ?? undefined,
      createdAt: row.created_at,
      lastUsedAt: row.last_used_at ?? undefined,
    }
  }

  forChatId(chatId: string): PushSubscriptionRecord | null {
    if (!chatId.startsWith("web:")) return null
    const device = chatId.slice("web:".length)
    return this.get(device)
  }

  markUsed(device: string): void {
    this.db.prepare("UPDATE push_subscriptions SET last_used_at = datetime('now') WHERE device = ?").run(device)
  }

  /** Every subscription — used to fan a web notification out to all devices. */
  all(): PushSubscriptionRecord[] {
    const rows = this.db.prepare(`
      SELECT device, endpoint, p256dh, auth, user_agent, created_at, last_used_at
      FROM push_subscriptions
    `).all() as any[]
    return rows.map((row) => ({
      device: row.device,
      endpoint: row.endpoint,
      keys: { p256dh: row.p256dh, auth: row.auth },
      userAgent: row.user_agent ?? undefined,
      createdAt: row.created_at,
      lastUsedAt: row.last_used_at ?? undefined,
    }))
  }
}
