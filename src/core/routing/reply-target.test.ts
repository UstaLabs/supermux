import { describe, expect, test } from "bun:test"
import { ReplyTargets, lastInboundChatId } from "./reply-target"

const inbound = (chat_id: string) => ({ direction: "inbound" as const, chat_id })
const outbound = (chat_id: string) => ({ direction: "outbound" as const, chat_id })

describe("ReplyTargets", () => {
  test("remembers the chat a session's last inbound turn came from", () => {
    const t = new ReplyTargets()
    t.note("s1", "telegram:123")
    expect(t.get("s1")).toBe("telegram:123")
  })

  test("keeps sessions independent", () => {
    const t = new ReplyTargets()
    t.note("s1", "web")
    t.note("s2", "telegram:123")
    expect(t.get("s1")).toBe("web")
    expect(t.get("s2")).toBe("telegram:123")
  })

  test("a later turn moves the target", () => {
    const t = new ReplyTargets()
    t.note("s1", "telegram:123")
    t.note("s1", "web")
    expect(t.get("s1")).toBe("web")
  })

  // System-generated turns (agent-rpc, curator wakes) carry no chat_id. They
  // must not erase the destination — that was the codex/opencode adapter bug:
  // `lastChatId = meta?.chat_id` wiped the target and the next reply had
  // nowhere to go.
  test("a turn without a chat_id does not erase the target", () => {
    const t = new ReplyTargets()
    t.note("s1", "web")
    t.note("s1", undefined)
    t.note("s1", "")
    expect(t.get("s1")).toBe("web")
  })

  test("unknown session has no target", () => {
    expect(new ReplyTargets().get("nope")).toBeUndefined()
  })

  test("forget drops the session", () => {
    const t = new ReplyTargets()
    t.note("s1", "web")
    t.forget("s1")
    expect(t.get("s1")).toBeUndefined()
  })
})

describe("lastInboundChatId", () => {
  test("returns the newest inbound chat_id", () => {
    expect(lastInboundChatId([inbound("telegram:1"), outbound("telegram:1"), inbound("web")])).toBe("web")
  })

  test("ignores outbound rows", () => {
    expect(lastInboundChatId([inbound("web"), outbound("telegram:1")])).toBe("web")
  })

  test("returns undefined when the session never received a message", () => {
    expect(lastInboundChatId([])).toBeUndefined()
    expect(lastInboundChatId([outbound("web")])).toBeUndefined()
  })
})
