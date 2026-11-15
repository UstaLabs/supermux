// The Anthropic org of a Claude account (#146 compact advice, #159 same-org auto-switch).
import { afterEach, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { tmpdir } from "node:os"
import { claudeAccountOrg, claudeJsonPath, readClaudeOrg } from "../src/accounts/org.js"

const dirsMade: string[] = []
afterEach(() => { for (const d of dirsMade.splice(0)) rmSync(d, { recursive: true, force: true }) })
const tmp = () => { const d = mkdtempSync(join(tmpdir(), "org-")); dirsMade.push(d); return d }

test("a supermux-home account's org comes from its own .claude.json", () => {
  const homes = tmp()
  mkdirSync(join(homes, "claude", "claude-abc"), { recursive: true })
  writeFileSync(join(homes, "claude", "claude-abc", ".claude.json"), JSON.stringify({ oauthAccount: { organizationUuid: "org-1", emailAddress: "x" } }))
  expect(claudeAccountOrg(homes, { id: "claude-abc", agent: "claude" })).toBe("org-1")
})

test("the system login's .claude.json is CLAUDE_CONFIG_DIR's, else the home directory's", () => {
  const cfg = tmp()
  writeFileSync(join(cfg, ".claude.json"), JSON.stringify({ oauthAccount: { organizationUuid: "org-sys" } }))
  expect(claudeJsonPath("/unused", { id: "claude:system", agent: "claude" }, { CLAUDE_CONFIG_DIR: cfg })).toBe(join(cfg, ".claude.json"))
  expect(claudeAccountOrg("/unused", { id: "claude:system", agent: "claude" }, { CLAUDE_CONFIG_DIR: cfg })).toBe("org-sys")
})

test("unknown is undefined, never an error: no file, no login, bad JSON, another agent", () => {
  const d = tmp()
  expect(readClaudeOrg(join(d, "missing.json"))).toBeUndefined()
  writeFileSync(join(d, "a.json"), "{not json")
  expect(readClaudeOrg(join(d, "a.json"))).toBeUndefined()
  writeFileSync(join(d, "b.json"), JSON.stringify({ oauthAccount: {} }))
  expect(readClaudeOrg(join(d, "b.json"))).toBeUndefined()
  expect(claudeAccountOrg(d, { id: "codex-1", agent: "codex" })).toBeUndefined()
})
