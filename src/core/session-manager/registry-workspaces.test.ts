import { test, expect } from "bun:test"
import { Registry } from "./registry"

test("registry exposes a workspace store", () => {
  const r = new Registry()
  expect(r.workspaces).toBeDefined()
  const w = r.workspaces.create({ name: "a", workdir: "/w" })
  expect(r.workspaces.getById(w.id)?.name).toBe("a")
})

test("registry heals a session that has no workspace", () => {
  const r = new Registry()
  // register() does not create a workspace yet — Phase 1b adds that to the
  // spawn path. Until then every registered session is an orphan, and the heal
  // is what keeps the invariant true.
  const s = r.register({ name: "n", workdir: "/w", pid: 1 })
  const healed = r.healWorkspaces()
  expect(healed).toContain(s.id)
  expect(r.workspaces.findByPrimarySession(s.id)).toBeDefined()
})
