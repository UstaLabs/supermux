import { afterEach, beforeEach, expect, test } from "bun:test"
import { createHash } from "crypto"
import { mkdirSync, mkdtempSync, readdirSync, readlinkSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { discoverAntigravityModels, parseAgyModels } from "./model-discovery"

test("parseAgyModels reads id<TAB>label lines, skips noise and dedupes ids", () => {
  const raw = "Fetching available models...\ngemini-3.8-flash-high\tGemini 3.8 Flash (High)\nclaude-opus-5-5-medium\tClaude Opus 5.5 (Medium)\n\nnoise line\ngemini-3.8-flash-high\tdup\n"
  expect(parseAgyModels(raw)).toEqual([
    { id: "gemini-3.8-flash-high", displayName: "Gemini 3.8 Flash (High)", agent: "antigravity" },
    { id: "claude-opus-5-5-medium", displayName: "Claude Opus 5.5 (Medium)", agent: "antigravity" },
  ])
})

test("parseAgyModels falls back to the id when the label is empty and tolerates CRLF", () => {
  expect(parseAgyModels("gpt-oss-120b-medium\t\r\n")).toEqual([
    { id: "gpt-oss-120b-medium", displayName: "gpt-oss-120b-medium", agent: "antigravity" },
  ])
})

let tmp: string
let stateDir: string
let fakeUser: string
let userCli: string

beforeEach(() => {
  tmp = mkdtempSync(join(tmpdir(), "agy-discovery-"))
  stateDir = join(tmp, "state")
  fakeUser = join(tmp, "user")
  userCli = join(fakeUser, ".gemini", "antigravity-cli")
  mkdirSync(userCli, { recursive: true })
  writeFileSync(join(userCli, "antigravity-oauth-token"), "{}")
})

afterEach(() => rmSync(tmp, { recursive: true, force: true }))

const RAW = "gemini-3.8-flash-high\tGemini 3.8 Flash (High)\n"

test("system discovery runs under a shadow HOME linked to the user's cli dir", async () => {
  let seen: Record<string, string> | undefined
  const models = await discoverAntigravityModels({
    stateDir,
    userHome: fakeUser,
    run: async (env) => { seen = env; return RAW },
  })
  const home = join(stateDir, "agents", "antigravity", "_discovery", "system", "home")
  expect(seen?.HOME).toBe(home)
  expect(readlinkSync(join(home, ".gemini", "antigravity-cli"))).toBe(userCli)
  expect(models).toEqual([{ id: "gemini-3.8-flash-high", displayName: "Gemini 3.8 Flash (High)", agent: "antigravity" }])
  // The real ~/.gemini is never written.
  expect(readdirSync(join(fakeUser, ".gemini")).sort()).toEqual(["antigravity-cli"])
  expect(readdirSync(userCli)).toEqual(["antigravity-oauth-token"])
})

test("account discovery links the account cli dir under a per-account key", async () => {
  const acct = join(tmp, "accounts", "a1", "antigravity-cli")
  mkdirSync(acct, { recursive: true })
  let seen: Record<string, string> | undefined
  await discoverAntigravityModels({
    stateDir,
    userHome: fakeUser,
    launch: { env: { SUPERMUX_ANTIGRAVITY_CLI_DIR: acct, FOO: "bar" } },
    run: async (env) => { seen = env; return RAW },
  })
  const key = createHash("sha256").update(acct).digest("hex").slice(0, 12)
  expect(key).not.toBe("system")
  const home = join(stateDir, "agents", "antigravity", "_discovery", key, "home")
  expect(seen?.HOME).toBe(home)
  expect(seen?.FOO).toBe("bar")
  expect(readlinkSync(join(home, ".gemini", "antigravity-cli"))).toBe(acct)
  expect(readdirSync(join(fakeUser, ".gemini")).sort()).toEqual(["antigravity-cli"])
})

test("a failing run yields no models", async () => {
  const models = await discoverAntigravityModels({
    stateDir,
    userHome: fakeUser,
    run: async () => { throw new Error("agy not found") },
  })
  expect(models).toEqual([])
})
