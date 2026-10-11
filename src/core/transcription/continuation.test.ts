import { expect, test } from "bun:test"
import { joinContinuation } from "./continuation"

test("plain continuation", () => {
  expect(joinContinuation("Hello there.", "How are you?")).toBe("Hello there. How are you?")
  expect(joinContinuation("", "x")).toBe("x")
  expect(joinContinuation("x", "")).toBe("x")
})

test("words heard twice in the overlap are dropped; the earlier text stays verbatim", () => {
  expect(joinContinuation("We ship the broker", "broker side today.")).toBe("We ship the broker side today.")
  expect(joinContinuation("Meet on Tuesday,", "on tuesday at three")).toBe("Meet on Tuesday, at three")
  expect(joinContinuation("Toplantıyı yarın", "yarın sabah yapalım.")).toBe("Toplantıyı yarın sabah yapalım.")
})

test("a short word repeated once is kept — it is usually real", () => {
  expect(joinContinuation("we saw it", "it was big")).toBe("we saw it it was big")
})
