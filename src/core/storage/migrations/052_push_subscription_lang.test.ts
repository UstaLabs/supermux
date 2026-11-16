import { expect, test } from "bun:test"
import { openDb, runMigrations } from "../db"
import { PushSubscriptionStore } from "../../push/subscriptions"
import { MIGRATIONS } from "./index"

test("052: a web push subscription keeps its device's language", () => {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  const cols = (db.query("PRAGMA table_info(push_subscriptions)").all() as { name: string }[]).map((c) => c.name)
  expect(cols).toContain("lang")
  const store = new PushSubscriptionStore(db)
  const keys = { p256dh: "p", auth: "a" }
  store.upsert({ device: "laptop", endpoint: "e1", keys, lang: "tr" })
  store.upsert({ device: "old", endpoint: "e2", keys })
  expect(store.get("laptop")?.lang).toBe("tr")
  expect(store.get("old")?.lang).toBeUndefined()
  expect(store.all().find((s) => s.device === "laptop")?.lang).toBe("tr")
  // A client that says nothing keeps the language it said before; a new one replaces it.
  store.upsert({ device: "laptop", endpoint: "e1", keys })
  expect(store.get("laptop")?.lang).toBe("tr")
  store.upsert({ device: "laptop", endpoint: "e1", keys, lang: "en" })
  expect(store.get("laptop")?.lang).toBe("en")
})
