import { describe, expect, test } from "bun:test"
import { usePendingFirstMessage } from "./pendingFirstMessage"

describe("pendingFirstMessage", () => {
  test("set, consume, clear", () => {
    const store = usePendingFirstMessage()
    store.set("s1", { text: "hi", files: [] })
    expect(store.consume("s1")).toEqual({ text: "hi", files: [] })
    expect(store.consume("s1")).toBeNull()
    store.set("s2", { text: "go", files: [] })
    expect(store.consume("s1")).toBeNull()
    store.clear()
    expect(store.consume("s2")).toBeNull()
  })
})
