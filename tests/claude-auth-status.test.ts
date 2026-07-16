import { expect, test } from "bun:test"
import { claudeCliIsAuthenticated } from "../src/core/agents/claude-auth-status"

test("macOS Claude auth uses the CLI status command so Keychain credentials are detected", () => {
  let seen: { command: string; args: string[] } | undefined
  const authed = claudeCliIsAuthenticated("darwin", (command, args) => {
    seen = { command, args }
    return true
  })

  expect(authed).toBe(true)
  expect(seen).toEqual({ command: "claude", args: ["auth", "status"] })
})

test("non-macOS Claude auth keeps using the credential-file path", () => {
  let called = false
  const authed = claudeCliIsAuthenticated("linux", () => {
    called = true
    return true
  })

  expect(authed).toBe(false)
  expect(called).toBe(false)
})

test("Claude auth probe fails closed when the CLI status command errors", () => {
  expect(claudeCliIsAuthenticated("darwin", () => { throw new Error("boom") })).toBe(false)
})
