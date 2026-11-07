import { expect, test } from "bun:test"
import { claudeStoredLoginService, readClaudeStoredLogin } from "./stored-login"

// The layout below is Claude Code 2.1.311's own (read from its Windows build, #101): Bun.secrets with service
// "Claude Code-credentials" (+ "-<8 hex of sha256(config dir)>" under CLAUDE_CONFIG_DIR) and name "claude-code-user";
// a login over 2400 bytes is base64 split across "claude-code-user#0", "#1", … with a manifest at "#m".

/** A Bun.secrets store holding [entries] ("service/name" → value), recording every read. */
function store(entries: Record<string, string>) {
  const asked: string[] = []
  const read = async (service: string, name: string) => {
    asked.push(`${service}/${name}`)
    return entries[`${service}/${name}`] ?? null
  }
  return { read, asked }
}

const LOGIN = JSON.stringify({ claudeAiOauth: { accessToken: "sk-ant-oat01-abc", expiresAt: 1 } })

test("the system login is service Claude Code-credentials, name claude-code-user", async () => {
  const s = store({ "Claude Code-credentials/claude-code-user": LOGIN })
  expect(await readClaudeStoredLogin(undefined, s.read)).toBe(LOGIN)
  expect(s.asked).toEqual(["Claude Code-credentials/claude-code-user#m", "Claude Code-credentials/claude-code-user"])
})

test("a login kept under CLAUDE_CONFIG_DIR is named after the first 8 hex of the folder's sha256, NFC first", () => {
  expect(claudeStoredLoginService()).toBe("Claude Code-credentials")
  expect(claudeStoredLoginService("")).toBe("Claude Code-credentials")
  expect(claudeStoredLoginService("C:\\Users\\Yasin\\.mux\\state\\accounts\\homes\\claude\\claude-1a2b3c4d"))
    .toBe("Claude Code-credentials-7fc565c6")
  // "Hüseyin" spelled with a combining diaeresis hashes as the composed ü.
  expect(claudeStoredLoginService("C:\\Users\\Hu\u0308seyin\\.claude-work")).toBe("Claude Code-credentials-e1f4a182")
  expect(claudeStoredLoginService("C:\\Users\\H\u00fcseyin\\.claude-work")).toBe("Claude Code-credentials-e1f4a182")
})

test("a long login comes back whole from its base64 chunks", async () => {
  const long = JSON.stringify({ claudeAiOauth: { accessToken: "t".repeat(3000) } })
  const b64 = Buffer.from(long, "utf8").toString("base64")
  const chunks = [b64.slice(0, 2400), b64.slice(2400, 4800)]
  expect(b64.length).toBeGreaterThan(2400)
  expect(b64.length).toBeLessThanOrEqual(4800)
  const s = store({
    "Claude Code-credentials/claude-code-user#m": JSON.stringify({ n: 2, l: b64.length }),
    "Claude Code-credentials/claude-code-user#0": chunks[0]!,
    "Claude Code-credentials/claude-code-user#1": chunks[1]!,
  })
  expect(await readClaudeStoredLogin(undefined, s.read)).toBe(long)
})

test("a chunk missing, or chunks that do not add up to the manifest's length, is an error, not half a login", async () => {
  const missing = store({
    "Claude Code-credentials/claude-code-user#m": JSON.stringify({ n: 2, l: 10 }),
    "Claude Code-credentials/claude-code-user#0": "aGVsbG8=",
  })
  await expect(readClaudeStoredLogin(undefined, missing.read)).rejects.toThrow()
  const short = store({
    "Claude Code-credentials/claude-code-user#m": JSON.stringify({ n: 1, l: 12 }),
    "Claude Code-credentials/claude-code-user#0": "aGVsbG8=",
  })
  await expect(readClaudeStoredLogin(undefined, short.read)).rejects.toThrow()
})

test("a manifest Claude itself would not take is ignored: the login is read whole", async () => {
  for (const manifest of ["{\"n\":0,\"l\":5}", "{\"n\":257,\"l\":5}", "{\"n\":1,\"l\":2401}", "not json"]) {
    const s = store({ "Claude Code-credentials/claude-code-user#m": manifest, "Claude Code-credentials/claude-code-user": LOGIN })
    expect(await readClaudeStoredLogin(undefined, s.read)).toBe(LOGIN)
  }
})

test("no login in the store is null", async () => {
  expect(await readClaudeStoredLogin(undefined, store({}).read)).toBeNull()
})

test("off Windows (no store this layer reads) the answer is null, never an error", async () => {
  expect(await readClaudeStoredLogin(undefined, undefined, "linux")).toBeNull()
  expect(await readClaudeStoredLogin(undefined, undefined, "darwin")).toBeNull()
})
