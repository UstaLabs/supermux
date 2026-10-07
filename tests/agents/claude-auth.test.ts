import { expect, test } from "bun:test"
import {
  claudeAuthMode,
  claudeCliIsAuthenticated,
  claudeCredentialsPath,
  claudeIsAuthed,
  resolveClaudeAuth,
} from "../../src/core/agents/claude/auth"

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

// --- consolidated detection: environment, stored settings, file, Keychain ---

const NEVER = () => false
const base = { home: "/home/u", platform: "linux" as const, fileExists: NEVER, runner: NEVER }

test("claudeCredentialsPath points at the file `claude login` writes", () => {
  expect(claudeCredentialsPath({ home: "/home/u", platform: "linux" })).toBe("/home/u/.claude/.credentials.json")
  expect(claudeCredentialsPath({ home: "C:\\Users\\u", platform: "win32" }))
    .toBe("C:\\Users\\u\\.claude\\.credentials.json")
})

test("an OAuth token in the environment authenticates claude", () => {
  expect(claudeAuthMode({ ...base, env: { CLAUDE_CODE_OAUTH_TOKEN: "tok" } })).toBe("oauth_token")
})

test("an API key in the environment authenticates claude", () => {
  expect(claudeAuthMode({ ...base, env: { ANTHROPIC_API_KEY: "sk" } })).toBe("api_key")
})

test("an empty environment variable is not a credential", () => {
  expect(claudeAuthMode({ ...base, env: { ANTHROPIC_API_KEY: "" } })).toBe("none")
})

test("the environment is NOT read unless the caller passes it", () => {
  expect(claudeAuthMode({ ...base })).toBe("none")
})

test("a stored settings credential authenticates claude", () => {
  expect(claudeAuthMode({ ...base, storedCredential: true })).toBe("stored_credential")
})

test("the credential file authenticates claude", () => {
  const seen: string[] = []
  const mode = claudeAuthMode({
    ...base,
    fileExists: (p) => { seen.push(p); return p === "/home/u/.claude/.credentials.json" },
  })
  expect(mode).toBe("credentials_file")
  expect(seen).toEqual(["/home/u/.claude/.credentials.json"])
})

test("on darwin the CLI status command answers when no file exists", () => {
  const calls: string[][] = []
  const mode = claudeAuthMode({
    ...base,
    platform: "darwin",
    runner: (command, args) => { calls.push([command, ...args]); return true },
  })
  expect(mode).toBe("cli_keychain")
  expect(calls).toEqual([["claude", "auth", "status"]])
})

test("the CLI status command never runs off darwin", () => {
  let called = false
  expect(claudeIsAuthed({ ...base, runner: () => { called = true; return true } })).toBe(false)
  expect(called).toBe(false)
})

test("no source answering means claude is not authenticated", () => {
  expect(claudeIsAuthed({ ...base, platform: "darwin" })).toBe(false)
})

test("the resolver reports the mode and adds no environment of its own", async () => {
  const r = await resolveClaudeAuth({ ...base, env: { ANTHROPIC_API_KEY: "sk" } })
  expect(r).toEqual({ mode: "api_key", env: {} })
})
