import { describe, expect, test } from "bun:test"
import { CardSendGuard, MAX_CARD_TEXT, fromCardText } from "./card-send"

const guard = (now: { t: number }) =>
  new CardSendGuard((session, id) => (session === "s1" && id === "c1" ? { title: "Pick" } : undefined), () => now.t)

describe("CardSendGuard", () => {
  test("an existing card in that session passes, and names its title", () => {
    expect(guard({ t: 0 }).check("s1", "c1", "Use B")).toEqual({ ok: true, title: "Pick" })
  })
  test("an unknown card id, or a card of another session, is refused", () => {
    const g = guard({ t: 0 })
    expect(g.check("s1", "nope", "x").ok).toBe(false)
    expect(g.check("s2", "c1", "x").ok).toBe(false)
    expect(g.check("s1", "", "x").ok).toBe(false)
  })
  test("one message per card per second", () => {
    const now = { t: 1000 }
    const g = guard(now)
    expect(g.check("s1", "c1", "a").ok).toBe(true)
    now.t = 1999
    const r = g.check("s1", "c1", "b")
    expect(r.ok).toBe(false)
    now.t = 2000
    expect(g.check("s1", "c1", "c").ok).toBe(true)
  })
  test("a refused message does not restart the clock", () => {
    const now = { t: 0 }
    const g = guard(now)
    g.check("s1", "c1", "a")
    now.t = 900; g.check("s1", "c1", "b")
    now.t = 1000
    expect(g.check("s1", "c1", "c").ok).toBe(true)
  })
  test("the text is 1-2000 characters, a string; a refusal costs no rate-limit slot", () => {
    const g = guard({ t: 0 })
    expect(g.check("s1", "c1", "x".repeat(MAX_CARD_TEXT)).ok).toBe(true)
    const g2 = guard({ t: 0 })
    expect(g2.check("s1", "c1", "x".repeat(MAX_CARD_TEXT + 1)).ok).toBe(false)
    expect(g2.check("s1", "c1", "").ok).toBe(false)
    expect(g2.check("s1", "c1", 5 as any).ok).toBe(false)
    expect(g2.check("s1", "c1", "ok").ok).toBe(true)
  })
})

test("the agent receives the text after the card's line", () => {
  expect(fromCardText({ id: "c1", title: "Pick" }, "Use B")).toBe('(from the card "Pick")\nUse B')
})
