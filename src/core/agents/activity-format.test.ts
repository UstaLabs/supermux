import { test, expect } from "bun:test"
import { clip, firstLine, pickString } from "./activity-format"

test("clip caps length and flags truncation", () => {
  expect(clip("hello", 10)).toEqual({ text: "hello", truncated: false })
  const r = clip("x".repeat(20), 10)
  expect(r.text.length).toBe(10)
  expect(r.truncated).toBe(true)
})
test("firstLine returns first non-empty line", () => {
  expect(firstLine("\n  \nhello\nworld")).toBe("hello")
  expect(firstLine("solo")).toBe("solo")
})
test("pickString returns the first matching string field, else first string value", () => {
  expect(pickString({ command: "npm test", x: 1 }, ["command"])).toBe("npm test")
  expect(pickString({ a: 2, path: "/x" }, ["command", "path"])).toBe("/x")
  expect(pickString({ a: 2, note: "hi" }, ["command"])).toBe("hi")
  expect(pickString({ a: 2 }, ["command"])).toBe("")
  expect(pickString(null, ["command"])).toBe("")
})
