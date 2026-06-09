import { test, expect } from "bun:test"
import { classifyInbound, RouteDecision } from "../src/core/routing"
import { Registry } from "../src/core/session-manager/registry"

type RegResult = { reg: Registry; ids: { ana: string; zoom: string; x: string } }

function makeReg(): RegResult {
  const r = new Registry()
  const ana = r.register({ name: "ana",    workdir: "/h",       tmux_target: "mux:ana",    pid: 1 })
  const zoom = r.register({ name: "zoom",   workdir: "/zoom",    tmux_target: "mux:zoom",   pid: 2 })
  const x = r.register({ name: "x",      workdir: "/proj/x",  tmux_target: "mux:x",      pid: 3 })
  r.setActive("chat-1", zoom.id)
  return { reg: r, ids: { ana: ana.id, zoom: zoom.id, x: x.id } }
}

// Reply-to map: which sessions have posted message_id N. Broker tracks this when
// it relays an outbound; lookup is "given chat_id + message_id, who posted it?"
type ReplyToFn = (chat_id: string, message_id: string) => string | undefined

const noReplies: ReplyToFn = () => undefined

test("slash command goes to broker handler", () => {
  const { reg } = makeReg()
  const d = classifyInbound({ chat_id: "chat-1", text: "/sessions", reply_to: undefined }, reg, noReplies)
  expect(d).toEqual({ kind: "slash", command: "sessions", rest: "" })
})

test("slash command with args", () => {
  const { reg } = makeReg()
  const d = classifyInbound({ chat_id: "chat-1", text: "/switch zoom", reply_to: undefined }, reg, noReplies)
  expect(d).toEqual({ kind: "slash", command: "switch", rest: "zoom" })
})

test("/switch_to_<name> autocomplete form maps to switch", () => {
  const { reg } = makeReg()
  const d = classifyInbound({ chat_id: "chat-1", text: "/switch_to_x", reply_to: undefined }, reg, noReplies)
  expect(d).toEqual({ kind: "slash", command: "switch", rest: "x" })
})

test("quote-reply to a session's message routes to that session", () => {
  const { reg, ids } = makeReg()
  const xSession = reg.get(ids.x)!
  const replyTo: ReplyToFn = (cid, mid) => (cid === "chat-1" && mid === "42") ? ids.x : undefined
  const d = classifyInbound({ chat_id: "chat-1", text: "got it", reply_to: "42" }, reg, replyTo)
  expect(d).toEqual({ kind: "session", name: "x", id: xSession.id, text: "got it", change_active: false, suspended: false })
})

test("@name prefix routes one-shot, strips prefix, no active change", () => {
  const { reg, ids } = makeReg()
  const xSession = reg.get(ids.x)!
  const d = classifyInbound({ chat_id: "chat-1", text: "@x do this thing", reply_to: undefined }, reg, noReplies)
  expect(d).toEqual({ kind: "session", name: "x", id: xSession.id, text: "do this thing", change_active: false, suspended: false })
})

test("@unknown name falls through to active (no silent misroute)", () => {
  const { reg, ids } = makeReg()
  const zoomSession = reg.get(ids.zoom)!
  const d = classifyInbound({ chat_id: "chat-1", text: "@nope hello", reply_to: undefined }, reg, noReplies)
  expect(d).toEqual({ kind: "session", name: "zoom", id: zoomSession.id, text: "@nope hello", change_active: false, suspended: false })
})

test("plain message routes to active session", () => {
  const { reg, ids } = makeReg()
  const zoomSession = reg.get(ids.zoom)!
  const d = classifyInbound({ chat_id: "chat-1", text: "hello", reply_to: undefined }, reg, noReplies)
  expect(d).toEqual({ kind: "session", name: "zoom", id: zoomSession.id, text: "hello", change_active: false, suspended: false })
})

test("no active and no default PA → error decision", () => {
  const r = new Registry()
  const d = classifyInbound({ chat_id: "chat-1", text: "hello", reply_to: undefined }, r, noReplies)
  expect(d).toEqual({ kind: "error", reason: "no_active_session" })
})
