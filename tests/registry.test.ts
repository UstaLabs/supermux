import { test, expect, describe, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { openDb, runMigrations } from "../src/core/storage/db"
import { Registry } from "../src/core/session-manager/registry"
import type { Session, ProxyEntry } from "../src/core/session-manager/registry"

let tmpDir: string
let r: Registry

function makeRegistry(): Registry {
  const db = openDb(join(tmpDir, `test-${Math.random()}.sqlite3`))
  runMigrations(db, join(import.meta.dir, "../src/core/storage/migrations"))
  return new Registry(db)
}

beforeEach(() => {
  tmpDir = mkdtempSync(join(tmpdir(), "agentmux-reg-"))
  r = makeRegistry()
})
afterEach(() => { rmSync(tmpDir, { recursive: true, force: true }) })

describe("session lifecycle", () => {
  test("register a session", () => {
    const s = r.register({ name: "foo", workdir: "/tmp/foo", tmux_target: "mux:foo", pid: 1234 })
    expect(s.name).toBe("foo")
    expect(r.list()).toHaveLength(1)
    expect(r.get(s.id)?.id).toBe(s.id)
    expect(r.get(s.id)?.name).toBe("foo")
    expect(r.resolveName("foo")?.id).toBe(s.id)
    expect(r.resolveName("foo")?.name).toBe("foo")
  })

  test("unregister", () => {
    const s = r.register({ name: "foo", workdir: "/tmp/foo", tmux_target: "mux:foo", pid: 1 })
    r.unregister(s.id)
    expect(r.get(s.id)).toBeUndefined()
    expect(r.resolveName("foo")).toBeUndefined()
  })

  test("rename", () => {
    const s = r.register({ name: "foo", workdir: "/tmp/foo", tmux_target: "mux:foo", pid: 1 })
    r.rename(s.id, "bar")
    expect(r.get(s.id)?.name).toBe("bar")
    expect(r.resolveName("bar")?.name).toBe("bar")
    expect(r.resolveName("foo")).toBeUndefined()
  })

  test("rename collision throws", () => {
    const s1 = r.register({ name: "foo", workdir: "/tmp/foo", tmux_target: "mux:foo", pid: 1 })
    r.register({ name: "bar", workdir: "/tmp/bar", tmux_target: "mux:bar", pid: 2 })
    expect(() => r.rename(s1.id, "bar")).toThrow(/already in use/)
  })

  test("set mute", () => {
    const s = r.register({ name: "foo", workdir: "/tmp/foo", tmux_target: "mux:foo", pid: 1 })
    r.setMuted(s.id, true)
    expect(r.get(s.id)?.mute).toBe(true)
  })

  test("setConnectionStatus flips connected flag and updates last_pong_at", () => {
    const reg = makeRegistry()
    const s = reg.register({ name: "x", workdir: "/", tmux_target: "t", pid: 1 })
    expect(reg.get(s.id)!.connected).toBe(true)
    reg.setConnectionStatus(s.id, false, 1234567890)
    expect(reg.get(s.id)!.connected).toBe(false)
    expect(reg.get(s.id)!.last_pong_at).toBe(1234567890)
  })
})

describe("active session per chat", () => {
  let sAna: Session
  let sZoom: Session
  let sX: Session

  beforeEach(() => {
    sAna = r.register({ name: "ana", workdir: "/home/x/assistant", tmux_target: "mux:ana", pid: 1, role: "personal_assistant", is_default: true })
    sZoom = r.register({ name: "zoom",   workdir: "/home/x/myproject",  tmux_target: "mux:zoom",   pid: 2 })
    sX =    r.register({ name: "x",      workdir: "/home/x/projects/x",  tmux_target: "mux:x",      pid: 3 })
  })

  test("setActive + getActive", () => {
    r.setActive("chat-1", sZoom.id)
    expect(r.getActive("chat-1")).toBe(sZoom.id)
  })

  test("getActive defaults to ana when unset and ana exists", () => {
    expect(r.getActive("chat-1")).toBe(sAna.id)
  })

  test("kill-active fallback: last-used other, else default PA", () => {
    r.setActive("chat-1", sZoom.id)
    r.setActive("chat-1", sX.id)               // x is now active, zoom in history
    r.unregister(sX.id)                         // kill the active one
    expect(r.activeFallback("chat-1")).toBe(sZoom.id)
  })

  test("kill-active fallback: default PA when no other history", () => {
    r.setActive("chat-1", sZoom.id)
    r.unregister(sZoom.id)
    expect(r.activeFallback("chat-1")).toBe(sAna.id)
  })

  test("kill-active fallback: no candidate if only the killed one existed", () => {
    const empty = makeRegistry()
    const solo = empty.register({ name: "solo", workdir: "/t", tmux_target: "t", pid: 1 })
    empty.setActive("chat-1", solo.id)
    empty.unregister(solo.id)
    expect(empty.activeFallback("chat-1")).toBeUndefined()
  })
})

describe("Registry agent fields", () => {
  test("register defaults agent to 'claude' when omitted", () => {
    const reg = makeRegistry()
    const s = reg.register({ name: "s1", workdir: "/w", tmux_target: "t:s1", pid: 1 })
    expect(reg.get(s.id)?.agent).toBe("claude")
  })

  test("register accepts explicit agent kind", () => {
    const reg = makeRegistry()
    const s = reg.register({ name: "s2", workdir: "/w", tmux_target: "t:s2", pid: 2, agent: "codex", agent_session_id: "thr_x", agent_home: "/h" })
    expect(reg.get(s.id)!.agent).toBe("codex")
    expect(reg.get(s.id)!.agent_session_id).toBe("thr_x")
    expect(reg.get(s.id)!.agent_home).toBe("/h")
  })
})

test("model field is undefined by default", () => {
  const reg = makeRegistry()
  const s = reg.register({ name: "s1", workdir: "/w", tmux_target: "t:s1", pid: 1 })
  expect(reg.get(s.id)?.model).toBeUndefined()
})

test("setModel updates session model", () => {
  const reg = makeRegistry()
  const s = reg.register({ name: "s1", workdir: "/w", tmux_target: "t:s1", pid: 1 })
  reg.setModel(s.id, "sonnet")
  expect(reg.get(s.id)?.model).toBe("sonnet")
})

test("setModel throws for unknown session", () => {
  const reg = makeRegistry()
  expect(() => reg.setModel("nope", "x")).toThrow(/no such session/)
})

describe("proxy CRUD", () => {
  let pr: Registry
  let sAlice: Session
  let sBob: Session

  beforeEach(() => {
    pr = makeRegistry()
    sAlice = pr.register({ name: "alice", workdir: "/tmp/alice", tmux_target: "t:alice", pid: 1 })
    sBob =   pr.register({ name: "bob",   workdir: "/tmp/bob",   tmux_target: "t:bob",   pid: 2 })
  })

  test("addProxy registers an entry", () => {
    const entry = pr.addProxy({ domain: "myapp", sessionId: sAlice.id, port: 3000 })
    expect(entry.domain).toBe("myapp")
    expect(entry.sessionName).toBe("alice")
    expect(entry.port).toBe(3000)
    expect(entry.createdAt).toBeTruthy()
    expect(pr.getProxy("myapp")).toEqual(entry)
  })

  test("addProxy rejects duplicate domain from different session", () => {
    pr.addProxy({ domain: "myapp", sessionId: sAlice.id, port: 3000 })
    expect(() => pr.addProxy({ domain: "myapp", sessionId: sBob.id, port: 4000 })).toThrow(/already registered/)
  })

  test("addProxy upserts when same session re-registers", () => {
    const first = pr.addProxy({ domain: "myapp", sessionId: sAlice.id, port: 3000 })
    const second = pr.addProxy({ domain: "myapp", sessionId: sAlice.id, port: 4000 })
    expect(second.port).toBe(4000)
    expect(second.createdAt).toBe(first.createdAt)
    expect(pr.listProxies()).toHaveLength(1)
  })

  test("addProxy enforces per-session limit of 5", () => {
    for (let i = 0; i < 5; i++) {
      pr.addProxy({ domain: `app${i}`, sessionId: sAlice.id, port: 3000 + i })
    }
    expect(() => pr.addProxy({ domain: "app5", sessionId: sAlice.id, port: 3005 })).toThrow(/proxy limit/)
  })

  test("removeProxy deletes entry", () => {
    pr.addProxy({ domain: "myapp", sessionId: sAlice.id, port: 3000 })
    pr.removeProxy("myapp")
    expect(pr.getProxy("myapp")).toBeUndefined()
  })

  test("removeProxy is no-op for unknown domain", () => {
    expect(() => pr.removeProxy("nonexistent")).not.toThrow()
  })

  test("removeProxiesForSession removes all proxies for a session and returns their domains", () => {
    pr.addProxy({ domain: "app1", sessionId: sAlice.id, port: 3001 })
    pr.addProxy({ domain: "app2", sessionId: sAlice.id, port: 3002 })
    pr.addProxy({ domain: "app3", sessionId: sBob.id,   port: 4001 })
    const removed = pr.removeProxiesForSession(sAlice.id)
    expect(removed.sort()).toEqual(["app1", "app2"])
    expect(pr.listProxies()).toHaveLength(1)
    expect(pr.getProxy("app3")).toBeDefined()
  })

  test("renameSession updates proxy sessionName", () => {
    pr.addProxy({ domain: "myapp", sessionId: sAlice.id, port: 3000 })
    pr.rename(sAlice.id, "alice2")
    expect(pr.getProxy("myapp")?.sessionName).toBe("alice2")
  })

  test("listProxies returns all entries", () => {
    pr.addProxy({ domain: "a", sessionId: sAlice.id, port: 3001 })
    pr.addProxy({ domain: "b", sessionId: sBob.id,   port: 3002 })
    const list = pr.listProxies()
    expect(list).toHaveLength(2)
    expect(list.map(e => e.domain).sort()).toEqual(["a", "b"])
  })

  test("domain validation rejects dots", () => {
    expect(() => pr.addProxy({ domain: "my.app", sessionId: sAlice.id, port: 3000 })).toThrow(/invalid domain/)
  })

  test("domain validation rejects empty string", () => {
    expect(() => pr.addProxy({ domain: "", sessionId: sAlice.id, port: 3000 })).toThrow(/invalid domain/)
  })

  test("domain validation rejects strings longer than 63 chars", () => {
    const long = "a".repeat(64)
    expect(() => pr.addProxy({ domain: long, sessionId: sAlice.id, port: 3000 })).toThrow(/invalid domain/)
  })

  test("domain validation rejects strings with uppercase", () => {
    expect(() => pr.addProxy({ domain: "MyApp", sessionId: sAlice.id, port: 3000 })).toThrow(/invalid domain/)
  })

  test("domain validation rejects strings starting or ending with hyphen", () => {
    expect(() => pr.addProxy({ domain: "-myapp", sessionId: sAlice.id, port: 3000 })).toThrow(/invalid domain/)
    expect(() => pr.addProxy({ domain: "myapp-", sessionId: sAlice.id, port: 3000 })).toThrow(/invalid domain/)
  })
})

test("register defaults role to worker and is_default to false", () => {
  const reg = makeRegistry()
  const s = reg.register({ name: "w1", workdir: "/w", tmux_target: "t", pid: 1 })
  expect(reg.get(s.id)?.role).toBe("worker")
  expect(reg.get(s.id)?.is_default).toBe(false)
})

test("register accepts personal_assistant role and persists it", () => {
  const reg = makeRegistry()
  const s = reg.register({ name: "pa", workdir: "/w", tmux_target: "t", pid: 1, role: "personal_assistant", is_default: true })
  expect(reg.get(s.id)?.role).toBe("personal_assistant")
  expect(reg.get(s.id)?.is_default).toBe(true)
})

describe("personal assistants", () => {
  test("defaultPA returns the is_default PA", () => {
    const reg = makeRegistry()
    const sAna = reg.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1, role: "personal_assistant", is_default: true })
    reg.register({ name: "w",   workdir: "/w", tmux_target: "t2", pid: 2 })
    expect(reg.defaultPA()?.name).toBe("ana")
  })
  test("listPAs returns only personal_assistant sessions", () => {
    const reg = makeRegistry()
    reg.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1, role: "personal_assistant", is_default: true })
    reg.register({ name: "w",   workdir: "/w", tmux_target: "t2", pid: 2 })
    expect(reg.listPAs().map(s => s.name)).toEqual(["ana"])
  })
  test("reassignDefault promotes the oldest remaining PA", () => {
    const reg = makeRegistry()
    const sAna = reg.register({ name: "ana", workdir: "/h",  tmux_target: "t",  pid: 1, role: "personal_assistant", is_default: true })
    const sBob = reg.register({ name: "bob", workdir: "/h2", tmux_target: "t2", pid: 2, role: "personal_assistant" })
    reg.reassignDefault(sAna.id)
    expect(reg.defaultPA()?.name).toBe("bob")
  })
})

describe("registerPA", () => {
  test("registerPA creates a personal assistant session", () => {
    const reg = makeRegistry()
    const s = reg.registerPA({
      name: "devops",
      agent: "claude",
      workdir: "/home/user/.mux/workspace/devops",
      model: "claude-sonnet-4",
      reasoningLevel: "high",
      pid: 1234,
    })

    expect(s.role).toBe("personal_assistant")
    expect(s.is_default).toBe(false)
    expect(s.agent).toBe("claude")
    expect(s.model).toBe("claude-sonnet-4")
    expect(s.reasoningLevel).toBe("high")
    expect(s.workdir).toBe("/home/user/.mux/workspace/devops")
  })

  test("registerPA rejects duplicate names", () => {
    const reg = makeRegistry()
    reg.registerPA({
      name: "devops",
      agent: "claude",
      workdir: "/home/user/.mux/workspace/devops",
      pid: 1234,
    })

    expect(() =>
      reg.registerPA({
        name: "devops",
        agent: "codex",
        workdir: "/home/user/.mux/workspace/devops-2",
        pid: 1234,
      })
    ).toThrow(/session name already in use: devops/)
  })

  test("registerPA passes through is_default: true", () => {
    const reg = makeRegistry()
    const s = reg.registerPA({
      name: "devops",
      agent: "claude",
      workdir: "/home/user/.mux/workspace/devops",
      pid: 1234,
      is_default: true,
    })
    expect(s.is_default).toBe(true)
  })

  test("register rejects duplicate names", () => {
    const reg = makeRegistry()
    reg.register({ name: "foo", workdir: "/tmp/foo", tmux_target: "mux:foo", pid: 1234 })
    expect(() =>
      reg.register({ name: "foo", workdir: "/tmp/foo2", tmux_target: "mux:foo2", pid: 5678 })
    ).toThrow(/session name already in use: foo/)
  })
})

describe("getActive PA fallback", () => {
  test("getActive falls back to the default PA UUID when nothing is active", () => {
    const reg = makeRegistry()
    const sAna = reg.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1, role: "personal_assistant", is_default: true })
    expect(reg.getActive("chat-1")).toBe(sAna.id)
  })
})

describe("orchestrate guard", () => {
  test("revoking orchestrate from a PA throws", () => {
    const reg = makeRegistry()
    const sAna = reg.register({ name: "ana", workdir: "/h", tmux_target: "t", pid: 1, role: "personal_assistant", is_default: true })
    expect(() => reg.grantOrchestrate(sAna.id, false)).toThrow(/cannot revoke orchestrate from a personal assistant/)
  })
  test("revoking orchestrate from a worker is allowed", () => {
    const reg = makeRegistry()
    const sW1 = reg.register({ name: "w1", workdir: "/w", tmux_target: "t2", pid: 2 })
    reg.grantOrchestrate(sW1.id, true)
    expect(() => reg.grantOrchestrate(sW1.id, false)).not.toThrow()
  })
})

// ── Regression guard: the registry public API is keyed by UUID, not display name. ──
// The 2026-06 session-display-name refactor (Task 3) made get()/setModel()/setMuted()/
// rename()/setActive() resolve ONLY by UUID; name lookups must go through resolveName().
// Several callers kept passing display names → get() silently returned undefined and the
// surrounding code fell back to `?? "claude"` (model-switch / resume / new-session all
// showed "claude"). These tests lock the contract so the bug class can't quietly return —
// e.g. nobody re-adds a name fallback inside get(), which would mask the misuse again.
describe("UUID-only public API (name/UUID contract)", () => {
  test("get() resolves by UUID, never by display name", () => {
    const s = r.register({ name: "alpha", workdir: "/a", tmux_target: "t", pid: 1 })
    expect(r.get(s.id)?.id).toBe(s.id)
    expect(r.get("alpha")).toBeUndefined()        // a display name is not a valid id
  })

  test("resolveName() resolves by display name, never by UUID", () => {
    const s = r.register({ name: "beta", workdir: "/b", tmux_target: "t", pid: 1 })
    expect(r.resolveName("beta")?.id).toBe(s.id)
    expect(r.resolveName(s.id)).toBeUndefined()   // a UUID is not a display name
  })

  test("mutators reject a display name and require the UUID", () => {
    const s = r.register({ name: "gamma", workdir: "/g", tmux_target: "t", pid: 1 })
    expect(() => r.setMuted("gamma", true)).toThrow(/no such session/)
    expect(() => r.setModel("gamma", "opus")).toThrow(/no such session/)
    expect(() => r.setReasoningLevel("gamma", "high")).toThrow(/no such session/)
    expect(() => r.rename("gamma", "delta")).toThrow(/no such session/)
    // ...and succeed when given the UUID
    r.setMuted(s.id, true)
    expect(r.get(s.id)?.mute).toBe(true)
  })

  test("setActive rejects a display name; getActive returns the UUID", () => {
    const s = r.register({ name: "epsilon", workdir: "/e", tmux_target: "t", pid: 1 })
    expect(() => r.setActive("chat-x", "epsilon")).toThrow(/no such session/)
    r.setActive("chat-x", s.id)
    expect(r.getActive("chat-x")).toBe(s.id)
  })
})
