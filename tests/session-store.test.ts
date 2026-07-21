import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { SessionStore } from "../src/core/session-manager/session-store"

let tmpDir: string
let db: any
let store: SessionStore

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-ss-"))
  db = openDb(join(tmpDir, "test.sqlite3"))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  store = new SessionStore(db)
})

afterEach(() => {
  rmSync(tmpDir, { recursive: true, force: true })
})

const BASE_INPUT = {
  name: "my-session",
  agent: "claude" as const,
  workdir: "/tmp/work",
  tmux_target: "cmux:my-session",
  pid: 1234,
}

describe("register", () => {
  test("creates session with UUID, active status, correct pid, connected=false", () => {
    const session = store.register(BASE_INPUT)
    expect(session.id).toMatch(/^[0-9a-f-]{36}$/)
    expect(session.status).toBe("active")
    expect(session.pid).toBe(1234)
    expect(session.connected).toBe(false)
    expect(session.name).toBe("my-session")
    expect(session.agent).toBe("claude")
    expect(session.workdir).toBe("/tmp/work")
    expect(session.tmux_target).toBe("cmux:my-session")
    expect(session.mute).toBe(false)
    expect(session.can_orchestrate).toBe(false)
  })

  test("persists to SQLite", () => {
    const session = store.register(BASE_INPUT)
    const row = db.query("SELECT * FROM sessions WHERE id = ?").get(session.id)
    expect(row).not.toBeNull()
    expect(row.name).toBe("my-session")
    expect(row.status).toBe("active")
    expect(row.mute).toBe(0)
    expect(row.can_orchestrate).toBe(0)
  })

  test("populates cache so getById works immediately", () => {
    const session = store.register(BASE_INPUT)
    const found = store.getById(session.id)
    expect(found).toBeDefined()
    expect(found!.id).toBe(session.id)
  })

  test("registers optional fields (model, can_orchestrate, agent_session_id, agent_home)", () => {
    const session = store.register({
      ...BASE_INPUT,
      model: "claude-opus-4-5",
      can_orchestrate: true,
      agent_session_id: "agent-abc",
      agent_home: "/home/agent",
    })
    expect(session.model).toBe("claude-opus-4-5")
    expect(session.can_orchestrate).toBe(true)
    expect(session.agent_session_id).toBe("agent-abc")
    expect(session.agent_home).toBe("/home/agent")

    const row = db.query("SELECT * FROM sessions WHERE id = ?").get(session.id)
    expect(row.can_orchestrate).toBe(1)
    expect(row.model).toBe("claude-opus-4-5")
  })
})

describe("getById / getByName", () => {
  test("getById returns session from cache", () => {
    const session = store.register(BASE_INPUT)
    const found = store.getById(session.id)
    expect(found).toBeDefined()
    expect(found!.id).toBe(session.id)
  })

  test("getById returns undefined for unknown id", () => {
    expect(store.getById("nonexistent-id")).toBeUndefined()
  })

  test("getByName returns session by name", () => {
    const session = store.register(BASE_INPUT)
    const found = store.getByName("my-session")
    expect(found).toBeDefined()
    expect(found!.id).toBe(session.id)
  })

  test("getByName returns undefined for unknown name", () => {
    expect(store.getByName("ghost")).toBeUndefined()
  })

  test("getByName skips archived sessions", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    expect(store.getByName("my-session")).toBeUndefined()
  })

  test("getById falls back to DB for non-cached session", () => {
    const session = store.register(BASE_INPUT)
    // archive removes from cache; then getById should still find via DB
    store.archive(session.id)
    const found = store.getById(session.id)
    expect(found).toBeDefined()
    expect(found!.status).toBe("archived")
  })
})

describe("archive", () => {
  test("sets status to archived and sets killed_at", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    const row = db.query("SELECT * FROM sessions WHERE id = ?").get(session.id)
    expect(row.status).toBe("archived")
    expect(row.killed_at).not.toBeNull()
  })

  test("removes session from active cache", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    const active = store.listActive()
    expect(active.find(s => s.id === session.id)).toBeUndefined()
  })

  test("frees the name in nameIndex", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    expect(store.getByName("my-session")).toBeUndefined()
    // The name should no longer be in takenNames
    expect(store.takenNames().has("my-session")).toBe(false)
  })

  test("no-op for unknown id", () => {
    // Should not throw
    store.archive("nonexistent-id")
  })
})

describe("suspend / activate", () => {
  test("suspend marks session as suspended", () => {
    const session = store.register(BASE_INPUT)
    store.suspend(session.id)
    const row = db.query("SELECT * FROM sessions WHERE id = ?").get(session.id)
    expect(row.status).toBe("suspended")
  })

  test("suspend resets pid and connected", () => {
    const session = store.register(BASE_INPUT)
    store.setConnectionStatus(session.id, true)
    store.suspend(session.id)
    const cached = store.getById(session.id)
    expect(cached!.pid).toBe(0)
    expect(cached!.connected).toBe(false)
  })

  test("suspended session appears in listSuspended", () => {
    const session = store.register(BASE_INPUT)
    store.suspend(session.id)
    const suspended = store.listSuspended()
    expect(suspended.find(s => s.id === session.id)).toBeDefined()
  })

  test("activate moves session back to active with new pid", () => {
    const session = store.register(BASE_INPUT)
    store.suspend(session.id)
    store.activate(session.id, 9999)
    const row = db.query("SELECT * FROM sessions WHERE id = ?").get(session.id)
    expect(row.status).toBe("active")
    const cached = store.getById(session.id)
    expect(cached!.status).toBe("active")
    expect(cached!.pid).toBe(9999)
  })

  test("activated session appears in listActive", () => {
    const session = store.register(BASE_INPUT)
    store.suspend(session.id)
    store.activate(session.id, 9999)
    const active = store.listActive()
    expect(active.find(s => s.id === session.id)).toBeDefined()
  })
})

describe("resume (from archive)", () => {
  test("moves archived session to active and clears killed_at", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    store.resume(session.id, "my-session-resumed", 5678)
    const row = db.query("SELECT * FROM sessions WHERE id = ?").get(session.id)
    expect(row.status).toBe("active")
    expect(row.killed_at).toBeNull()
    expect(row.name).toBe("my-session-resumed")
  })

  test("resumed session is in cache with correct pid", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    store.resume(session.id, "my-session-resumed", 5678)
    const cached = store.getById(session.id)
    expect(cached).toBeDefined()
    expect(cached!.status).toBe("active")
    expect(cached!.pid).toBe(5678)
  })

  test("resumed session name is in nameIndex", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    store.resume(session.id, "new-name", 5678)
    expect(store.getByName("new-name")).toBeDefined()
    expect(store.getByName("new-name")!.id).toBe(session.id)
  })
})

describe("listActive / listSuspended / listArchived", () => {
  test("listActive returns only active sessions", () => {
    const s1 = store.register({ ...BASE_INPUT, name: "s1" })
    const s2 = store.register({ ...BASE_INPUT, name: "s2" })
    const s3 = store.register({ ...BASE_INPUT, name: "s3" })
    store.suspend(s2.id)
    store.archive(s3.id)

    const active = store.listActive()
    expect(active.length).toBe(1)
    expect(active[0]!.id).toBe(s1.id)
  })

  test("listSuspended returns only suspended sessions", () => {
    const s1 = store.register({ ...BASE_INPUT, name: "s1" })
    const s2 = store.register({ ...BASE_INPUT, name: "s2" })
    store.suspend(s1.id)
    store.archive(s2.id)

    const suspended = store.listSuspended()
    expect(suspended.length).toBe(1)
    expect(suspended[0]!.id).toBe(s1.id)
  })

  test("listArchived returns archived sessions ordered by killed_at DESC", () => {
    const s1 = store.register({ ...BASE_INPUT, name: "s1" })
    const s2 = store.register({ ...BASE_INPUT, name: "s2" })
    store.archive(s1.id)
    store.archive(s2.id)

    const archived = store.listArchived()
    expect(archived.length).toBe(2)
    expect(archived.every(s => s.status === "archived")).toBe(true)
  })

  test("listArchived returns SessionRecord (no pid/connected)", () => {
    const s1 = store.register({ ...BASE_INPUT, name: "s1" })
    store.archive(s1.id)
    const archived = store.listArchived()
    expect((archived[0] as any).pid).toBeUndefined()
    expect((archived[0] as any).connected).toBeUndefined()
  })
})

describe("takenNames", () => {
  test("includes active session names", () => {
    store.register(BASE_INPUT)
    expect(store.takenNames().has("my-session")).toBe(true)
  })

  test("includes suspended session names", () => {
    const session = store.register(BASE_INPUT)
    store.suspend(session.id)
    expect(store.takenNames().has("my-session")).toBe(true)
  })

  test("excludes archived session names", () => {
    const session = store.register(BASE_INPUT)
    store.archive(session.id)
    expect(store.takenNames().has("my-session")).toBe(false)
  })
})

describe("setters", () => {
  test("setConnectionStatus updates connected and last_pong_at", () => {
    const session = store.register(BASE_INPUT)
    store.setConnectionStatus(session.id, true, 999999)
    const cached = store.getById(session.id)
    expect(cached!.connected).toBe(true)
    expect(cached!.last_pong_at).toBe(999999)
  })

  test("setConnectionStatus updates connected without last_pong_at", () => {
    const session = store.register(BASE_INPUT)
    store.setConnectionStatus(session.id, true)
    const cached = store.getById(session.id)
    expect(cached!.connected).toBe(true)
    expect(cached!.last_pong_at).toBeUndefined()
  })

  test("setMuted updates mute in cache and DB", () => {
    const session = store.register(BASE_INPUT)
    store.setMuted(session.id, true)
    expect(store.getById(session.id)!.mute).toBe(true)
    const row = db.query("SELECT mute FROM sessions WHERE id = ?").get(session.id)
    expect(row.mute).toBe(1)

    store.setMuted(session.id, false)
    expect(store.getById(session.id)!.mute).toBe(false)
    const row2 = db.query("SELECT mute FROM sessions WHERE id = ?").get(session.id)
    expect(row2.mute).toBe(0)
  })

  test("setModel updates model in cache and DB", () => {
    const session = store.register(BASE_INPUT)
    store.setModel(session.id, "claude-haiku-4-5")
    expect(store.getById(session.id)!.model).toBe("claude-haiku-4-5")
    const row = db.query("SELECT model FROM sessions WHERE id = ?").get(session.id)
    expect(row.model).toBe("claude-haiku-4-5")
  })

  test("setModel clears model when undefined", () => {
    const session = store.register({ ...BASE_INPUT, model: "some-model" })
    store.setModel(session.id, undefined)
    expect(store.getById(session.id)!.model).toBeUndefined()
    const row = db.query("SELECT model FROM sessions WHERE id = ?").get(session.id)
    expect(row.model).toBeNull()
  })

  test("setAgentSessionId updates in cache and DB", () => {
    const session = store.register(BASE_INPUT)
    store.setAgentSessionId(session.id, "agent-xyz-789")
    expect(store.getById(session.id)!.agent_session_id).toBe("agent-xyz-789")
    const row = db.query("SELECT agent_session_id FROM sessions WHERE id = ?").get(session.id)
    expect(row.agent_session_id).toBe("agent-xyz-789")
  })

  test("grantOrchestrate sets can_orchestrate", () => {
    const session = store.register(BASE_INPUT)
    store.grantOrchestrate(session.id, true)
    expect(store.getById(session.id)!.can_orchestrate).toBe(true)
    const row = db.query("SELECT can_orchestrate FROM sessions WHERE id = ?").get(session.id)
    expect(row.can_orchestrate).toBe(1)

    store.grantOrchestrate(session.id, false)
    expect(store.getById(session.id)!.can_orchestrate).toBe(false)
  })
})

describe("rename", () => {
  test("updates name in cache, nameIndex, and SQLite", () => {
    const session = store.register(BASE_INPUT)
    store.rename(session.id, "new-name")

    expect(store.getById(session.id)!.name).toBe("new-name")
    expect(store.getByName("new-name")).toBeDefined()
    expect(store.getByName("my-session")).toBeUndefined()

    const row = db.query("SELECT name FROM sessions WHERE id = ?").get(session.id)
    expect(row.name).toBe("new-name")
  })

  test("old name is removed from takenNames after rename", () => {
    const session = store.register(BASE_INPUT)
    store.rename(session.id, "new-name")
    expect(store.takenNames().has("my-session")).toBe(false)
    expect(store.takenNames().has("new-name")).toBe(true)
  })
})

describe("self_renamed", () => {
  test("register defaults self_renamed to false", () => {
    const session = store.register(BASE_INPUT)
    expect(session.self_renamed).toBe(false)
    const row = db.query("SELECT self_renamed FROM sessions WHERE id = ?").get(session.id)
    expect(row.self_renamed).toBe(0)
  })

  test("markSelfRenamed persists to SQLite and updates the cache", () => {
    const session = store.register(BASE_INPUT)
    store.markSelfRenamed(session.id)

    expect(store.getById(session.id)!.self_renamed).toBe(true)
    const row = db.query("SELECT self_renamed FROM sessions WHERE id = ?").get(session.id)
    expect(row.self_renamed).toBe(1)
  })

  test("self_renamed survives a restart", () => {
    const session = store.register(BASE_INPUT)
    store.markSelfRenamed(session.id)

    const store2 = new SessionStore(db)
    expect(store2.getById(session.id)!.self_renamed).toBe(true)
  })
})

describe("loadFromDb (simulates restart)", () => {
  test("new SessionStore loads active and suspended sessions from DB", () => {
    const s1 = store.register({ ...BASE_INPUT, name: "s1" })
    const s2 = store.register({ ...BASE_INPUT, name: "s2" })
    const s3 = store.register({ ...BASE_INPUT, name: "s3" })
    store.suspend(s2.id)
    store.archive(s3.id)

    // Simulate restart by creating a new store with the same DB
    const store2 = new SessionStore(db)

    // Should have s1 (active) and s2 (suspended), not s3 (archived)
    expect(store2.getById(s1.id)).toBeDefined()
    expect(store2.getById(s1.id)!.status).toBe("active")
    expect(store2.getById(s2.id)).toBeDefined()
    expect(store2.getById(s2.id)!.status).toBe("suspended")

    // s3 is archived: not in cache, but getById falls back to DB
    const s3found = store2.getById(s3.id)
    expect(s3found?.status).toBe("archived")
  })

  test("new store has correct nameIndex for active and suspended", () => {
    const s1 = store.register({ ...BASE_INPUT, name: "s1" })
    const s2 = store.register({ ...BASE_INPUT, name: "s2" })
    store.suspend(s2.id)

    const store2 = new SessionStore(db)
    expect(store2.getByName("s1")).toBeDefined()
    expect(store2.getByName("s2")).toBeDefined()
    expect(store2.takenNames().has("s1")).toBe(true)
    expect(store2.takenNames().has("s2")).toBe(true)
  })

  test("new store listActive and listSuspended reflect correct state", () => {
    const s1 = store.register({ ...BASE_INPUT, name: "s1" })
    const s2 = store.register({ ...BASE_INPUT, name: "s2" })
    const s3 = store.register({ ...BASE_INPUT, name: "s3" })
    store.suspend(s2.id)
    store.archive(s3.id)

    const store2 = new SessionStore(db)
    const active = store2.listActive()
    const suspended = store2.listSuspended()
    expect(active.length).toBe(1)
    expect(active[0]!.id).toBe(s1.id)
    expect(suspended.length).toBe(1)
    expect(suspended[0]!.id).toBe(s2.id)
  })
})
