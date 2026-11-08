import { test, expect } from "bun:test"
import { join, dirname } from "path"
import { fileURLToPath } from "url"
import { openDb, runMigrations } from "../storage/db"
import { Registry } from "./registry"

const _dirname = dirname(fileURLToPath(import.meta.url))
const MIGRATIONS = join(_dirname, "../storage/migrations")

function freshDb() {
  const db = openDb(":memory:")
  runMigrations(db, MIGRATIONS)
  return db
}

function reg(db = freshDb()) {
  return { registry: new Registry(db), db }
}

function addSession(r: Registry, name: string) {
  return r.register({ name, workdir: "/tmp", tmux_target: `t:${name}`, pid: 1 })
}

test("addProxy writes a row, getProxy/listProxies read it back", () => {
  const { registry } = reg()
  const s = addSession(registry, "alpha")
  const entry = registry.addProxy({ domain: "app", sessionId: s.id, port: 3000 })
  expect(entry).toMatchObject({ domain: "app", sessionId: s.id, sessionName: "alpha", port: 3000, isPublic: false })
  expect(registry.getProxy("app")?.port).toBe(3000)
  expect(registry.listProxies().map((p) => p.domain)).toEqual(["app"])
})

test("removeProxy deletes; removeProxiesForSession deletes by session id", () => {
  const { registry } = reg()
  const a = addSession(registry, "alpha")
  const b = addSession(registry, "beta")
  registry.addProxy({ domain: "a1", sessionId: a.id, port: 3001 })
  registry.addProxy({ domain: "a2", sessionId: a.id, port: 3002 })
  registry.addProxy({ domain: "b1", sessionId: b.id, port: 3003 })

  registry.removeProxy("a1")
  expect(registry.getProxy("a1")).toBeUndefined()

  const removed = registry.removeProxiesForSession(a.id)
  expect(removed.sort()).toEqual(["a2"])
  expect(registry.listProxies().map((p) => p.domain)).toEqual(["b1"])
})

test("domain already owned by another session throws", () => {
  const { registry } = reg()
  const a = addSession(registry, "alpha")
  const b = addSession(registry, "beta")
  registry.addProxy({ domain: "shared", sessionId: a.id, port: 3000 })
  expect(() => registry.addProxy({ domain: "shared", sessionId: b.id, port: 3000 })).toThrow(
    /already registered by session: alpha/,
  )
})

test("re-exposing the same domain by the same session is idempotent and keeps createdAt", () => {
  const { registry } = reg()
  const s = addSession(registry, "alpha")
  const first = registry.addProxy({ domain: "app", sessionId: s.id, port: 3000 })
  const second = registry.addProxy({ domain: "app", sessionId: s.id, port: 4000 })
  expect(registry.listProxies()).toHaveLength(1)
  expect(second.port).toBe(4000)
  expect(second.createdAt).toBe(first.createdAt)
})

test("exposing for a non-existent session throws", () => {
  const { registry } = reg()
  expect(() => registry.addProxy({ domain: "app", sessionId: "no-such-uuid", port: 3000 })).toThrow(/no such session/)
})

test("addProxy with isPublic true and setProxyPublic toggle", () => {
  const { registry } = reg()
  const s = addSession(registry, "alpha")
  const pub = registry.addProxy({ domain: "open", sessionId: s.id, port: 3000, isPublic: true })
  expect(pub.isPublic).toBe(true)
  const priv = registry.setProxyPublic("open", false)
  expect(priv.isPublic).toBe(false)
  const again = registry.setProxyPublic("open", true)
  expect(again.isPublic).toBe(true)
})

test("re-expose preserves isPublic when isPublic arg omitted", () => {
  const { registry } = reg()
  const s = addSession(registry, "alpha")
  registry.addProxy({ domain: "app", sessionId: s.id, port: 3000, isPublic: true })
  const second = registry.addProxy({ domain: "app", sessionId: s.id, port: 4000 })
  expect(second.port).toBe(4000)
  expect(second.isPublic).toBe(true)
})

test("setProxyPublic on missing domain throws", () => {
  const { registry } = reg()
  expect(() => registry.setProxyPublic("nope", true)).toThrow(/no proxy registered/)
})

test("rename: proxy survives and listProxies reflects the new name (owned by UUID)", () => {
  const { registry } = reg()
  const s = addSession(registry, "alpha")
  registry.addProxy({ domain: "app", sessionId: s.id, port: 3000 })
  registry.rename(s.id, "alpha2")
  expect(registry.getProxy("app")?.sessionName).toBe("alpha2")
})

test("reload across restart: active and suspended sessions keep their proxies", () => {
  const db = freshDb()
  const a = new Registry(db)
  const active = addSession(a, "active-sess")
  const susp = addSession(a, "susp-sess")
  a.addProxy({ domain: "ap", sessionId: active.id, port: 3000 })
  a.addProxy({ domain: "sp", sessionId: susp.id, port: 3001 })
  a.sessions.suspend(susp.id)

  // Simulate a broker restart: a new Registry over the same DB.
  const b = new Registry(db)
  const domains = b.listProxies().map((p) => p.domain).sort()
  expect(domains).toEqual(["ap", "sp"])
  expect(b.getProxy("ap")?.sessionId).toBe(active.id)
  expect(b.getProxy("sp")?.sessionName).toBe("susp-sess")
})

// A digits-only slug means a PORT: /p/5173/ opens localhost:5173 with no registration. So a named proxy must never be
// one, and addProxy is where every way of naming a proxy (expose_port, Settings > Proxies / POST /proxies, /proxy) lands.
test("addProxy refuses a digits-only name: /p/<digits>/ means a port", () => {
  const { registry, db } = reg()
  const s = addSession(registry, "alpha")
  for (const name of ["5173", "0", "007", "65535", "70000", "1234567890"]) {
    expect(() => registry.addProxy({ domain: name, sessionId: s.id, port: 3000 })).toThrow(
      `proxy names can't be only digits: /p/${name}/ means a port`,
    )
  }
  // Refused means nothing was written: not in the working set, not in the table.
  expect(registry.listProxies()).toEqual([])
  expect(db.query("SELECT domain FROM proxies").all()).toEqual([])
})

test("addProxy still accepts a name with digits plus anything else", () => {
  const { registry } = reg()
  const s = addSession(registry, "alpha")
  // "px-12345678" is the random default whose eight hex characters all happen to be digits.
  const names = ["px-1234", "app5173", "5173a", "a5173", "1-2", "5173-app", "px-12345678"]
  for (const name of names) {
    expect(registry.addProxy({ domain: name, sessionId: s.id, port: 3000 }).domain).toBe(name)
  }
  expect(registry.listProxies().map((p) => p.domain).sort()).toEqual([...names].sort())
})

test("addProxy refuses a name that is not a string, so a number can't become a digits-only one", () => {
  // POST /proxies casts the JSON body's domain with `as string`, so a number or a boolean can arrive. The name check is
  // a regex, and a regex coerces: 5173 reads as "5173" and true as "true". SQLite then stores `true` as the name "1".
  const { registry, db } = reg()
  const s = addSession(registry, "alpha")
  for (const name of [5173, true, 1, ["5173"]]) {
    expect(() => registry.addProxy({ domain: name as unknown as string, sessionId: s.id, port: 3000 })).toThrow(/invalid domain/)
  }
  expect(registry.listProxies()).toEqual([])
  expect(db.query("SELECT domain FROM proxies").all()).toEqual([])
})

test("a digits-only proxy stored before the rule still loads, can be made public and removed; only creating one is refused", () => {
  const db = freshDb()
  const s = addSession(new Registry(db), "alpha")
  // What an older broker could have written: the table itself has no rule about names.
  db.run("INSERT INTO proxies (domain, session_id, port, created_at, is_public) VALUES (?, ?, ?, ?, 0)", [
    "5173", s.id, 3000, "2026-01-01T00:00:00.000Z",
  ])

  const registry = new Registry(db) // a broker restart
  expect(registry.getProxy("5173")).toMatchObject({ domain: "5173", sessionName: "alpha", port: 3000, isPublic: false })
  expect(registry.setProxyPublic("5173", true).isPublic).toBe(true)
  // Exposing is an upsert, and the rule is about the name, so it can't be re-exposed either.
  expect(() => registry.addProxy({ domain: "5173", sessionId: s.id, port: 4000 })).toThrow(/only digits/)
  expect(registry.getProxy("5173")?.port).toBe(3000)
  registry.removeProxy("5173")
  expect(registry.getProxy("5173")).toBeUndefined()
})

test("reload across restart: archived (killed) session's proxy is dropped and the row deleted", () => {
  const db = freshDb()
  const a = new Registry(db)
  const live = addSession(a, "live")
  const dead = addSession(a, "dead")
  a.addProxy({ domain: "live-d", sessionId: live.id, port: 3000 })
  a.addProxy({ domain: "dead-d", sessionId: dead.id, port: 3001 })
  a.sessions.archive(dead.id)

  const b = new Registry(db)
  expect(b.listProxies().map((p) => p.domain)).toEqual(["live-d"])
  // The orphan row was pruned from the DB, not just the in-memory set.
  const rows = db.query("SELECT domain FROM proxies").all() as { domain: string }[]
  expect(rows.map((r) => r.domain)).toEqual(["live-d"])
  expect(live.id).toBeTruthy()
})
