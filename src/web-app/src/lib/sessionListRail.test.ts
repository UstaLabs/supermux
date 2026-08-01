import { expect, test } from "bun:test"
import {
  sessionListRailIndicator,
  sessionListRailKind,
  sessionListShowsUnread,
} from "./sessionListRail"

test("working wins over unread for the leading icon", () => {
  expect(sessionListRailIndicator({ working: true, unread: true })).toBe("working")
  expect(sessionListRailKind({ working: true, unread: true })).toBe("working")
  expect(sessionListShowsUnread({ working: true, unread: true })).toBe(false)
})

test("idle unread paints the green unread mark", () => {
  expect(sessionListShowsUnread({ working: false, unread: true })).toBe(true)
  expect(sessionListRailKind({ working: false, unread: true })).toBe("unread")
  expect(sessionListRailIndicator({ working: false, unread: true })).toBe("unread")
})

test("idle read falls back to other (gray / settled)", () => {
  expect(sessionListShowsUnread({ working: false, unread: false })).toBe(false)
  expect(sessionListRailKind({ working: false, unread: false })).toBe("other")
})

test("active session never shows unread even if store says unread", () => {
  expect(sessionListShowsUnread({ active: true, working: false, unread: true })).toBe(false)
  expect(sessionListRailKind({ active: true, working: false, unread: true })).toBe("other")
})

test("full matrix matches native SessionListRail", () => {
  const cases: Array<{
    active?: boolean
    working: boolean
    unread: boolean
    kind: "working" | "unread" | "other"
  }> = [
    { working: true, unread: true, kind: "working" },
    { working: true, unread: false, kind: "working" },
    { working: false, unread: true, kind: "unread" },
    { working: false, unread: false, kind: "other" },
    { active: true, working: false, unread: true, kind: "other" },
  ]
  for (const c of cases) {
    expect(sessionListRailKind(c)).toBe(c.kind)
  }
})
