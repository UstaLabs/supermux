import { expect, test } from "bun:test"
import { Registry } from "./registry"
import { SessionStore } from "./session-store"

test("setParent links a session to its parent, and the link survives a reload", () => {
  const r = new Registry()
  const parent = r.register({ name: "p", workdir: "/tmp", pid: 0 })
  const child = r.register({ name: "c", workdir: "/tmp", pid: 0, agent: "codex" })
  r.sessions.setParent(child.id, parent.id)
  expect(r.get(child.id)?.parent_session_id).toBe(parent.id)
  expect(new SessionStore(r.db).getById(child.id)?.parent_session_id).toBe(parent.id)
  expect(r.get(parent.id)?.parent_session_id).toBeUndefined()
})

test("childrenOf lists the live sessions whose parent is the given one", () => {
  const r = new Registry()
  const p = r.register({ name: "p", workdir: "/tmp", pid: 0 })
  const a = r.register({ name: "a", workdir: "/tmp", pid: 0 })
  const b = r.register({ name: "b", workdir: "/tmp", pid: 0 })
  r.register({ name: "x", workdir: "/tmp", pid: 0 })
  r.sessions.setParent(a.id, p.id)
  r.sessions.setParent(b.id, p.id)
  expect(r.childrenOf(p.id).map((s) => s.id)).toEqual([a.id, b.id])
  r.unregister(b.id)
  expect(r.childrenOf(p.id).map((s) => s.id)).toEqual([a.id])
  expect(r.childrenOf(a.id)).toEqual([])
})

test("listVisible leaves children out; list keeps them", () => {
  const r = new Registry()
  const p = r.register({ name: "p", workdir: "/tmp", pid: 0 })
  const c = r.register({ name: "c", workdir: "/tmp", pid: 0 })
  r.sessions.setParent(c.id, p.id)
  expect(r.listVisible().map((s) => s.id)).toEqual([p.id])
  expect(r.list().map((s) => s.id).sort()).toEqual([p.id, c.id].sort())
})
