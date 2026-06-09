import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, existsSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { resolveCursorAuth } from "../../src/core/agents/cursor/auth"

describe("resolveCursorAuth", () => {
  let userHome: string
  let sessionHome: string
  beforeEach(() => {
    userHome = mkdtempSync(join(tmpdir(), "cursor-user-"))
    sessionHome = mkdtempSync(join(tmpdir(), "cursor-session-"))
  })
  afterEach(() => {
    rmSync(userHome, { recursive: true, force: true })
    rmSync(sessionHome, { recursive: true, force: true })
  })

  test("uses CURSOR_API_KEY env when set", async () => {
    const r = await resolveCursorAuth({ apiKey: "key_xx", userCursorDir: join(userHome, ".cursor"), userConfigDir: join(userHome, ".config"), sessionHome })
    expect(r.mode).toBe("api_key")
    expect(r.env.CURSOR_API_KEY).toBe("key_xx")
  })

  test("copies auth files into session HOME/.cursor + .config/cursor/", async () => {
    const userCursor = join(userHome, ".cursor")
    mkdirSync(userCursor, { recursive: true })
    writeFileSync(join(userCursor, "cli-config.json"), '{"authInfo":{"email":"x"}}')
    writeFileSync(join(userCursor, "agent-cli-state.json"), '{"state":"y"}')
    // XDG auth token
    const userConfig = join(userHome, ".config")
    mkdirSync(join(userConfig, "cursor"), { recursive: true })
    writeFileSync(join(userConfig, "cursor", "auth.json"), '{"accessToken":"tok"}')
    const r = await resolveCursorAuth({ apiKey: undefined, userCursorDir: userCursor, userConfigDir: userConfig, sessionHome })
    expect(r.mode).toBe("oauth_copy")
    expect(readFileSync(join(sessionHome, ".cursor", "cli-config.json"), "utf8")).toBe('{"authInfo":{"email":"x"}}')
    expect(readFileSync(join(sessionHome, ".cursor", "agent-cli-state.json"), "utf8")).toBe('{"state":"y"}')
    expect(readFileSync(join(sessionHome, ".config", "cursor", "auth.json"), "utf8")).toBe('{"accessToken":"tok"}')
  })

  test("throws when no auth available", async () => {
    await expect(
      resolveCursorAuth({ apiKey: undefined, userCursorDir: join(userHome, ".cursor"), userConfigDir: join(userHome, ".config"), sessionHome })
    ).rejects.toThrow(/cursor-agent login/)
  })
})
