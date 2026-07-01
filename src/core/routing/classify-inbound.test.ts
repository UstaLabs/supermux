import { test, expect } from "bun:test"
import { classifyInbound } from "./classify-inbound"

// Minimal duck-typed registry — classifyInbound only calls get/fuzzyResolve/getActive.
const sess = (id: string, name: string, status = "running") => ({ id, name, status })
function mockRegistry(sessions: Record<string, any>, active?: string) {
  return {
    get: (id: string) => sessions[id], // ID-ONLY (mirrors Registry.get → getById)
    fuzzyResolve: (q: string) => Object.values(sessions).find((s: any) => s.name.includes(q)),
    getActive: (_chat: string) => active,
  } as any
}

test("slash command is classified before anything else", () => {
  const d = classifyInbound({ chat_id: "whatsapp:x", text: "/switch foo", reply_to: undefined }, mockRegistry({}), () => undefined)
  expect(d).toEqual({ kind: "slash", command: "switch", rest: "foo" })
})

// The regression guard: quote-reply must route to the OWNER session, resolved via
// registry.get() — which is ID-ONLY. The dispatcher therefore must store a session
// ID (not a name) in replyOwner. Storing a name made get() return undefined and the
// reply silently fell through to the active session.
test("quote-reply routes to the owning session when the lookup returns its ID", () => {
  const reg = mockRegistry({ "id-owner": sess("id-owner", "owner"), "id-active": sess("id-active", "active") }, "id-active")
  const d = classifyInbound(
    { chat_id: "whatsapp:x", text: "hi", reply_to: "WAMID-123" },
    reg,
    (_cid, mid) => (mid === "WAMID-123" ? "id-owner" : undefined),
  )
  expect(d).toEqual({ kind: "session", name: "owner", id: "id-owner", text: "hi", change_active: false, suspended: false })
})

test("quote-reply whose stored owner is NOT a resolvable id falls through to the active session (the old bug)", () => {
  const reg = mockRegistry({ "id-active": sess("id-active", "active") }, "id-active")
  const d = classifyInbound(
    { chat_id: "whatsapp:x", text: "hi", reply_to: "WAMID-123" },
    reg,
    () => "owner", // a NAME, not an id → registry.get() can't resolve it
  )
  // Must NOT throw and must fall through — but it reaches the WRONG (active) session,
  // which is exactly the user-visible bug. Guards that owners are stored as ids.
  expect(d).toEqual({ kind: "session", name: "active", id: "id-active", text: "hi", change_active: false, suspended: false })
})

test("no reply_to, no @mention, no active session → error", () => {
  const d = classifyInbound({ chat_id: "whatsapp:x", text: "hi", reply_to: undefined }, mockRegistry({}), () => undefined)
  expect(d).toEqual({ kind: "error", reason: "no_active_session" })
})
