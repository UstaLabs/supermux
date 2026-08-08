import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { resolveCursorAuth, cursorCredentialFreshness } from "../../src/core/agents/cursor/auth"

/** A JWT whose payload carries the given `exp` claim (seconds since the epoch). */
function jwt(expSeconds: number, marker: string): string {
  return ["e30", Buffer.from(JSON.stringify({ exp: expSeconds, sub: marker })).toString("base64url"), "sig"].join(".")
}

/** A cursor auth.json in the real shape: two JWTs, no other field. */
function cursorAuth(expSeconds: number, marker: string): string {
  return JSON.stringify({ accessToken: jwt(expSeconds, marker), refreshToken: jwt(expSeconds, marker) })
}

describe("resolveCursorAuth", () => {
  let userHome: string
  let sessionHome: string
  let userCursor: string
  let userConfig: string
  /** Where the session's copy of the credential lands on posix. */
  let sessionAuth: string
  /** The user's canonical credential. */
  let canonical: string

  beforeEach(() => {
    userHome = mkdtempSync(join(tmpdir(), "cursor-user-"))
    sessionHome = mkdtempSync(join(tmpdir(), "cursor-session-"))
    userCursor = join(userHome, ".cursor")
    userConfig = join(userHome, ".config")
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    canonical = join(userConfig, "cursor", "auth.json")
    sessionAuth = join(sessionHome, ".config", "cursor", "auth.json")
  })
  afterEach(() => {
    rmSync(userHome, { recursive: true, force: true })
    rmSync(sessionHome, { recursive: true, force: true })
  })

  test("uses CURSOR_API_KEY env when set", async () => {
    const r = await resolveCursorAuth({ apiKey: "key_xx", userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })
    expect(r.mode).toBe("api_key")
    expect(r.env.CURSOR_API_KEY).toBe("key_xx")
  })

  test("isolates APPDATA and USERPROFILE for Windows API-key sessions", async () => {
    const session = "C:\\Mux\\cursor-session"
    const r = await resolveCursorAuth({
      apiKey: "key_xx", userCursorDir: "C:\\Users\\u\\.cursor", sessionHome: session,
      platform: "win32", env: { USERPROFILE: "C:\\Users\\u", APPDATA: "C:\\Users\\u\\AppData\\Roaming" },
    })
    expect(r.env).toEqual({
      CURSOR_API_KEY: "key_xx", APPDATA: "C:\\Mux\\cursor-session\\AppData\\Roaming", USERPROFILE: session,
    })
  })

  test("copies auth files into session HOME/.cursor + .config/cursor/", async () => {
    mkdirSync(userCursor, { recursive: true })
    writeFileSync(join(userCursor, "cli-config.json"), '{"authInfo":{"email":"x"}}')
    writeFileSync(join(userCursor, "agent-cli-state.json"), '{"state":"y"}')
    writeFileSync(canonical, '{"accessToken":"tok"}')
    const r = await resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })
    expect(r.mode).toBe("oauth_copy")
    expect(readFileSync(join(sessionHome, ".cursor", "cli-config.json"), "utf8")).toBe('{"authInfo":{"email":"x"}}')
    expect(readFileSync(join(sessionHome, ".cursor", "agent-cli-state.json"), "utf8")).toBe('{"state":"y"}')
    expect(readFileSync(sessionAuth, "utf8")).toBe('{"accessToken":"tok"}')
  })

  // --- normalized contract ---

  test("the resolver is async", () => {
    const pending = resolveCursorAuth({ apiKey: "key_xx", userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })
    expect(pending).toBeInstanceOf(Promise)
    return pending
  })

  test("FAILS CLOSED: throws when no auth is available", async () => {
    await expect(
      resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })
    ).rejects.toThrow(/cursor-agent login/)
  })

  // --- refresh-drift healing ---

  test("cursorCredentialFreshness reads the access token expiry", () => {
    const path = join(sessionHome, "probe.json")
    writeFileSync(path, cursorAuth(2_000_000, "a"))
    expect(cursorCredentialFreshness(path)).toBe(2_000_000_000)
    writeFileSync(path, '{"accessToken":"opaque"}')
    expect(cursorCredentialFreshness(path)).toBe(Number.NEGATIVE_INFINITY)
  })

  test("a session copy that refreshed its token is promoted before the re-copy", async () => {
    writeFileSync(canonical, cursorAuth(1_000_000, "stale"))
    mkdirSync(join(sessionHome, ".config", "cursor"), { recursive: true })
    writeFileSync(sessionAuth, cursorAuth(2_000_000, "refreshed"))

    const r = await resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })

    expect(r.mode).toBe("oauth_copy")
    expect(readFileSync(canonical, "utf8")).toBe(cursorAuth(2_000_000, "refreshed"))
    expect(readFileSync(sessionAuth, "utf8")).toBe(cursorAuth(2_000_000, "refreshed"))
  })

  test("a stale session copy never overwrites a newer canonical credential", async () => {
    writeFileSync(canonical, cursorAuth(2_000_000, "canonical"))
    mkdirSync(join(sessionHome, ".config", "cursor"), { recursive: true })
    writeFileSync(sessionAuth, cursorAuth(1_000_000, "stale"))

    await resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })

    expect(readFileSync(canonical, "utf8")).toBe(cursorAuth(2_000_000, "canonical"))
    expect(readFileSync(sessionAuth, "utf8")).toBe(cursorAuth(2_000_000, "canonical"))
  })

  test("a corrupt session copy never clobbers the canonical credential", async () => {
    writeFileSync(canonical, cursorAuth(1_000_000, "canonical"))
    mkdirSync(join(sessionHome, ".config", "cursor"), { recursive: true })
    writeFileSync(sessionAuth, "{ truncated by a crash")

    await resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })

    expect(readFileSync(canonical, "utf8")).toBe(cursorAuth(1_000_000, "canonical"))
  })

  test("a logout on the host is not undone by a leftover session copy", async () => {
    mkdirSync(join(sessionHome, ".config", "cursor"), { recursive: true })
    writeFileSync(sessionAuth, cursorAuth(2_000_000, "leftover"))

    await expect(
      resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })
    ).rejects.toThrow(/cursor-agent login/)
    expect(existsSync(canonical)).toBe(false)
  })

  test("identity and CLI state never travel back to the user's home", async () => {
    mkdirSync(userCursor, { recursive: true })
    writeFileSync(join(userCursor, "cli-config.json"), '{"authInfo":{"email":"canonical"}}')
    writeFileSync(canonical, cursorAuth(1_000_000, "canonical"))
    mkdirSync(join(sessionHome, ".cursor"), { recursive: true })
    writeFileSync(join(sessionHome, ".cursor", "cli-config.json"), '{"authInfo":{"email":"session"}}')

    await resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })

    expect(readFileSync(join(userCursor, "cli-config.json"), "utf8")).toBe('{"authInfo":{"email":"canonical"}}')
  })
})
