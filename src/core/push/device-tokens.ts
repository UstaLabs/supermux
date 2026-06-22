import type { Database } from "bun:sqlite"

export interface DevicePushTokenRecord {
  device: string
  platform: "ios" | "android"
  token: string
  created_at: string
  last_used_at: string | null
  routing_token: string | null
  device_pubkey: string | null
}

export class DevicePushTokenStore {
  constructor(private readonly db: Database) {}

  put(device: string, platform: "ios" | "android", token: string): void {
    this.db
      .prepare(
        `INSERT INTO device_push_tokens (device, platform, token, created_at)
         VALUES (?, ?, ?, ?)
         ON CONFLICT(device) DO UPDATE SET platform = excluded.platform, token = excluded.token`,
      )
      .run(device, platform, token, new Date().toISOString())
  }

  putNative(device: string, platform: "ios" | "android", routingToken: string, pubkey: string): void {
    this.db.prepare(`INSERT INTO device_push_tokens (device, platform, token, routing_token, device_pubkey, created_at)
      VALUES (?, ?, '', ?, ?, ?)
      ON CONFLICT(device) DO UPDATE SET platform=excluded.platform, routing_token=excluded.routing_token, device_pubkey=excluded.device_pubkey`)
      .run(device, platform, routingToken, pubkey, new Date().toISOString())
  }

  get(device: string): DevicePushTokenRecord | null {
    return (this.db.prepare(`SELECT * FROM device_push_tokens WHERE device = ?`).get(device) as any) ?? null
  }

  all(): DevicePushTokenRecord[] {
    return this.db.prepare(`SELECT * FROM device_push_tokens`).all() as any[]
  }

  remove(device: string): void {
    this.db.prepare(`DELETE FROM device_push_tokens WHERE device = ?`).run(device)
  }
}
