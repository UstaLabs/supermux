import { test, expect } from "bun:test"
import { handleSlash } from "../src/core/commands"

function mockCtx(over: { active?: string | undefined; unknown?: boolean; interrupt?: (n: string) => Promise<{ ok: boolean; reason?: string }> } = {}) {
  return {
    registry: {
      getActive: () => ("active" in over ? over.active : "alice"),
      // UUID-keyed: get(uuid) returns the session or undefined
      get: (id: string) => (over.unknown ? undefined : { name: id }),
      // resolveName(displayName) returns { id, name } or undefined
      resolveName: (name: string) => (over.unknown ? undefined : { id: name, name }),
    },
    chat_id: "web",
    interrupt: over.interrupt,
  } as any
}

test("/stop with no arg interrupts the chat's active session", async () => {
  const calls: string[] = []
  const ctx = mockCtx({ interrupt: async (n) => { calls.push(n); return { ok: true } } })
  const r = await handleSlash({ command: "stop", rest: "" }, ctx)
  expect(calls).toEqual(["alice"])
  expect(r.text).toBe("stopped alice")
})

test("/stop <name> targets the named session", async () => {
  const calls: string[] = []
  const ctx = mockCtx({ interrupt: async (n) => { calls.push(n); return { ok: true } } })
  const r = await handleSlash({ command: "stop", rest: "bob" }, ctx)
  expect(calls).toEqual(["bob"])
  expect(r.text).toBe("stopped bob")
})

test("/stop with no active session reports it (and never calls interrupt)", async () => {
  let called = 0
  const ctx = mockCtx({ active: undefined, interrupt: async () => { called++; return { ok: true } } })
  const r = await handleSlash({ command: "stop", rest: "" }, ctx)
  expect(r.text).toBe("no active session")
  expect(called).toBe(0)
})

test("/stop on an unknown session reports it", async () => {
  const ctx = mockCtx({ unknown: true, interrupt: async () => ({ ok: true }) })
  const r = await handleSlash({ command: "stop", rest: "ghost" }, ctx)
  expect(r.text).toBe("no such session: ghost")
})

test("/stop surfaces an interrupt failure reason", async () => {
  const ctx = mockCtx({ interrupt: async () => ({ ok: false, reason: "pane gone" }) })
  const r = await handleSlash({ command: "stop", rest: "alice" }, ctx)
  expect(r.text).toBe("couldn't stop alice: pane gone")
})
