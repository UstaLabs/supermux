import { test, expect } from "bun:test"
import { workspaceScope, parseScope } from "./scope"

test("workspaceScope prefixes the id", () => {
  expect(workspaceScope("abc-123")).toBe("w:abc-123")
})

test("parseScope reads a workspace scope", () => {
  expect(parseScope("w:abc-123")).toEqual({ kind: "workspace", id: "abc-123" })
})

test("parseScope treats anything else as a session scope", () => {
  expect(parseScope("my-session")).toEqual({ kind: "session", id: "my-session" })
})

test("parseScope keeps a session name that merely contains a colon", () => {
  // Session names are free-form human titles. Only a leading "w:" is a workspace.
  expect(parseScope("fix: the thing")).toEqual({ kind: "session", id: "fix: the thing" })
})

test("parseScope round-trips workspaceScope", () => {
  const id = "6b1f0e2a-0000-4000-8000-abcdefabcdef"
  expect(parseScope(workspaceScope(id))).toEqual({ kind: "workspace", id })
})
