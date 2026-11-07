import { expect, test } from "bun:test"
import { Registry } from "./registry"

test("listAllAgents includes archived Agents (with their stored is_default) and no workers", () => {
  const r = new Registry()
  const live = r.registerPA({ name: "live", agent: "claude", workdir: "/a", pid: 1 })
  const gone = r.registerPA({ name: "gone", agent: "claude", workdir: "/b", pid: 2, is_default: true })
  r.register({ name: "worker", workdir: "/c", pid: 3 })
  // Older installs archived the default without clearing the flag: model that stored row.
  r.sessions.archive(gone.id)
  const all = r.listAllAgents()
  expect(all.map(s => s.id).sort()).toEqual([live.id, gone.id].sort())
  expect(all.find(s => s.id === gone.id)?.is_default).toBe(true)
  expect(all.find(s => s.id === gone.id)?.status).toBe("archived")
})
