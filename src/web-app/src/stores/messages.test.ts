import { beforeEach, expect, test } from "bun:test"
import { createPinia, setActivePinia } from "pinia"
import { useMessages, type MessageEntry } from "./messages"

beforeEach(() => setActivePinia(createPinia()))

function msg(id: string, text: string): MessageEntry {
  return {
    id,
    ts: "2026-06-05T00:00:00.000Z",
    direction: "inbound",
    channel: "web",
    text,
  }
}

test("append replaces an existing message with the same id", () => {
  const messages = useMessages()

  messages.append("s1", msg("in:web:web-1", "pending"))
  messages.append("s1", msg("in:web:web-1", "authoritative"))

  expect(messages.bySession["s1"]).toHaveLength(1)
  expect(messages.bySession["s1"][0]?.text).toBe("authoritative")
})
