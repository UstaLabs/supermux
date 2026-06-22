import type { Database } from "bun:sqlite"
import { randomBytes } from "node:crypto"

export interface RelayRoute { routing_token: string; platform: "ios" | "android"; push_token: string; created_at: string }

export class RelayStore {
  constructor(private readonly db: Database) {
    db.run(`CREATE TABLE IF NOT EXISTS relay_routes (
      routing_token TEXT PRIMARY KEY, platform TEXT NOT NULL CHECK (platform IN ('ios','android')),
      push_token TEXT NOT NULL, created_at TEXT NOT NULL)`)
  }
  register(platform: "ios" | "android", pushToken: string): string {
    const token = randomBytes(32).toString("base64url")
    this.db.prepare(`INSERT INTO relay_routes (routing_token, platform, push_token, created_at) VALUES (?,?,?,?)`)
      .run(token, platform, pushToken, new Date().toISOString())
    return token
  }
  lookup(routingToken: string): { platform: "ios" | "android"; pushToken: string } | null {
    const row = this.db.prepare(`SELECT platform, push_token FROM relay_routes WHERE routing_token = ?`).get(routingToken) as any
    return row ? { platform: row.platform, pushToken: row.push_token } : null
  }
  unregister(routingToken: string): void {
    this.db.prepare(`DELETE FROM relay_routes WHERE routing_token = ?`).run(routingToken)
  }
}
