// #143 (team rule): who answered a prompt is recorded with its close, not only what was chosen.
import { expect, test } from "bun:test"
import { AnsweredBy } from "./answered-by"

test("the answerer noted at respond time is taken once, at the close", () => {
  const by = new AnsweredBy()
  by.note("s1", "r1", "phone")
  expect(by.take("s1", "r1")).toBe("phone")
  expect(by.take("s1", "r1")).toBeUndefined()
  expect(by.take("s1", "r2")).toBeUndefined()
})

test("a note nobody takes expires, so the map can't grow forever", () => {
  let now = 0
  const by = new AnsweredBy({ now: () => now, ttlMs: 1000 })
  by.note("s1", "r1", "phone")
  now = 1001
  by.note("s1", "r2", "laptop")
  expect(by.take("s1", "r1")).toBeUndefined()
  expect(by.take("s1", "r2")).toBe("laptop")
})
