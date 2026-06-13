import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useUnread } from "./unread"
import { useMessages } from "./messages"

beforeEach(() => setActivePinia(createPinia()))

function appendMsg(session: string, id: string, ts: string) {
  useMessages().append(session, { id, ts, direction: "outbound", channel: "web" } as any)
}

test("a session is unread when its newest message is newer than last read", () => {
  const unread = useUnread()
  appendMsg("s1", "m1", "2026-06-13T10:00:00.000Z")
  expect(unread.isUnread("s1")).toBe(true) // never read
  unread.setLastRead("s1", "2026-06-13T10:00:00.000Z")
  expect(unread.isUnread("s1")).toBe(false) // read up to newest
  appendMsg("s1", "m2", "2026-06-13T10:05:00.000Z")
  expect(unread.isUnread("s1")).toBe(true) // a newer message arrived
})

test("setLastRead is monotonic — never moves the pointer backwards", () => {
  const unread = useUnread()
  appendMsg("s1", "m1", "2026-06-13T10:05:00.000Z")
  unread.setLastRead("s1", "2026-06-13T10:05:00.000Z")
  unread.setLastRead("s1", "2026-06-13T10:00:00.000Z") // older — ignored
  expect(unread.isUnread("s1")).toBe(false)
})

test("seed hydrates read state from the server snapshot", () => {
  const unread = useUnread()
  appendMsg("s1", "m1", "2026-06-13T10:00:00.000Z")
  unread.seed({ s1: "2026-06-13T10:00:00.000Z" })
  expect(unread.isUnread("s1")).toBe(false)
})

test("markRead optimistically clears unread for an opened session", () => {
  const unread = useUnread()
  appendMsg("s1", "m1", "2026-06-13T10:00:00.000Z") // in the past relative to now
  unread.markRead("s1")
  expect(unread.isUnread("s1")).toBe(false)
})
