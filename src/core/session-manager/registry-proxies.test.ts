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

test("per-session proxy limit of 5 is enforced", () => {
  const { registry } = reg()
  const s = addSession(registry, "alpha")
  for (let i = 0; i < 5; i++) registry.addProxy({ domain: `d${i}`, sessionId: s.id, port: 3000 + i })
  expect(() => registry.addProxy({ domain: "d5", sessionId: s.id, port: 4000 })).toThrow(/proxy limit of 5/)
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
