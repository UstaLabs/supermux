import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync, existsSync, readFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { resolveCodexAuth } from "../../src/core/agents/codex/auth"

describe("resolveCodexAuth", () => {
  let userHome: string
  let sessionHome: string

  beforeEach(() => {
    userHome = mkdtempSync(join(tmpdir(), "codex-user-"))
    sessionHome = mkdtempSync(join(tmpdir(), "codex-session-"))
  })
  afterEach(() => {
    rmSync(userHome, { recursive: true, force: true })
    rmSync(sessionHome, { recursive: true, force: true })
  })

  test("uses OPENAI_API_KEY when set; no auth.json copy", async () => {
    const r = await resolveCodexAuth({ apiKey: "sk-test", userCodexHome: userHome, sessionCodexHome: sessionHome })
    expect(r.mode).toBe("api_key")
    expect(r.env.OPENAI_API_KEY).toBe("sk-test")
    expect(existsSync(join(sessionHome, "auth.json"))).toBe(false)
  })

  test("copies ~/.codex/auth.json into session home when no API key", async () => {
    mkdirSync(userHome, { recursive: true })
    writeFileSync(join(userHome, "auth.json"), '{"token":"x"}', { mode: 0o600 })
    const r = await resolveCodexAuth({ apiKey: undefined, userCodexHome: userHome, sessionCodexHome: sessionHome })
    expect(r.mode).toBe("oauth_copy")
    expect(readFileSync(join(sessionHome, "auth.json"), "utf8")).toBe('{"token":"x"}')
  })

  test("throws clear error when no auth available", async () => {
    await expect(
      resolveCodexAuth({ apiKey: undefined, userCodexHome: userHome, sessionCodexHome: sessionHome })
    ).rejects.toThrow(/codex login/)
  })
})
