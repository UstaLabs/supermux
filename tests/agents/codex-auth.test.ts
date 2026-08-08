import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync, existsSync, readFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { resolveCodexAuth, codexCredentialFreshness } from "../../src/core/agents/codex/auth"

/** A JWT whose payload carries the given `exp` claim (seconds since the epoch). */
function jwt(expSeconds: number): string {
  return ["e30", Buffer.from(JSON.stringify({ exp: expSeconds })).toString("base64url"), "sig"].join(".")
}

/** A codex auth.json in the real ChatGPT-subscription shape. */
function codexAuth(expSeconds: number, marker: string): string {
  return JSON.stringify({
    auth_mode: "chatgpt",
    OPENAI_API_KEY: null,
    tokens: { access_token: jwt(expSeconds), refresh_token: marker, id_token: jwt(expSeconds) },
    last_refresh: new Date(expSeconds * 1000).toISOString(),
  })
}

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

  // --- failure policy (see tests/agents/auth-contract.test.ts) ---

  test("FAILS CLOSED: throws a clear error when no auth is available", async () => {
    await expect(
      resolveCodexAuth({ apiKey: undefined, userCodexHome: userHome, sessionCodexHome: sessionHome })
    ).rejects.toThrow(/codex login/)
  })

  // --- refresh-drift healing ---

  test("codexCredentialFreshness reads the access token expiry", () => {
    const path = join(sessionHome, "auth.json")
    writeFileSync(path, codexAuth(2_000_000, "r"))
    expect(codexCredentialFreshness(path)).toBe(2_000_000_000)
    writeFileSync(path, '{"tokens":{}}')
    expect(codexCredentialFreshness(path)).toBe(Number.NEGATIVE_INFINITY)
  })

  test("a session copy that refreshed its token is promoted before the re-copy", async () => {
    const canonical = join(userHome, "auth.json")
    const copy = join(sessionHome, "auth.json")
    writeFileSync(canonical, codexAuth(1_000_000, "stale"))
    writeFileSync(copy, codexAuth(2_000_000, "refreshed"))

    const r = await resolveCodexAuth({ apiKey: undefined, userCodexHome: userHome, sessionCodexHome: sessionHome })

    expect(r.mode).toBe("oauth_copy")
    // The canonical file learned the refresh, so a sibling session gets it too.
    expect(readFileSync(canonical, "utf8")).toBe(codexAuth(2_000_000, "refreshed"))
    expect(readFileSync(copy, "utf8")).toBe(codexAuth(2_000_000, "refreshed"))
  })

  test("a stale session copy never overwrites a newer canonical credential", async () => {
    const canonical = join(userHome, "auth.json")
    const copy = join(sessionHome, "auth.json")
    writeFileSync(canonical, codexAuth(2_000_000, "canonical"))
    writeFileSync(copy, codexAuth(1_000_000, "stale"))

    await resolveCodexAuth({ apiKey: undefined, userCodexHome: userHome, sessionCodexHome: sessionHome })

    expect(readFileSync(canonical, "utf8")).toBe(codexAuth(2_000_000, "canonical"))
    expect(readFileSync(copy, "utf8")).toBe(codexAuth(2_000_000, "canonical"))
  })

  test("a corrupt session copy never clobbers the canonical credential", async () => {
    const canonical = join(userHome, "auth.json")
    const copy = join(sessionHome, "auth.json")
    writeFileSync(canonical, codexAuth(1_000_000, "canonical"))
    writeFileSync(copy, "{ truncated by a crash")

    await resolveCodexAuth({ apiKey: undefined, userCodexHome: userHome, sessionCodexHome: sessionHome })

    expect(readFileSync(canonical, "utf8")).toBe(codexAuth(1_000_000, "canonical"))
  })

  test("a logout on the host is not undone by a leftover session copy", async () => {
    const canonical = join(userHome, "auth.json")
    writeFileSync(join(sessionHome, "auth.json"), codexAuth(2_000_000, "leftover"))

    await expect(
      resolveCodexAuth({ apiKey: undefined, userCodexHome: userHome, sessionCodexHome: sessionHome })
    ).rejects.toThrow(/codex login/)
    expect(existsSync(canonical)).toBe(false)
  })

  test("an API-key session never promotes and never copies", async () => {
    const canonical = join(userHome, "auth.json")
    writeFileSync(canonical, codexAuth(1_000_000, "canonical"))
    writeFileSync(join(sessionHome, "auth.json"), codexAuth(2_000_000, "refreshed"))

    await resolveCodexAuth({ apiKey: "sk-test", userCodexHome: userHome, sessionCodexHome: sessionHome })

    expect(readFileSync(canonical, "utf8")).toBe(codexAuth(1_000_000, "canonical"))
  })
})
