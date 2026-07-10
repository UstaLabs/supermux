import { mkdtempSync, writeFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"

process.env.MUX_STATE_DIR = mkdtempSync(join(tmpdir(), "mux-hooks-test-"))

const { test, expect } = await import("bun:test")
const { existsSync, readFileSync } = await import("fs")
const {
  writeClaudeHooksSettings,
  CLAUDE_HOOKS_SETTINGS_PATH,
  hooksFileUsesHookSecret,
  INTERNAL_HOOK_SECRET_FILE,
  readPersistedHookSecret,
  writePersistedHookSecret,
  resolveInternalHookSecret,
} = await import("./hooks-settings")

test("includes an AskUserQuestion deny hook and a StopFailure curl hook", () => {
  const p = writeClaudeHooksSettings(9898, "abc123secret")
  const cfg = JSON.parse(readFileSync(p, "utf8"))
  const askEntry = cfg.hooks.PreToolUse.find((e: any) => e.matcher === "AskUserQuestion")
  expect(askEntry).toBeTruthy()
  expect(askEntry.hooks[0].command).toContain("permissionDecision")
  expect(askEntry.hooks[0].command).toContain("deny")
  const sf = cfg.hooks.StopFailure[0].hooks[0]
  expect(sf.command).toContain("/internal/agent-hook/StopFailure")
  expect(sf.command).toContain("|| true")
})

test("SessionStart hook injects the PA's soul.md (role=main only, never workers)", () => {
  const cmd = JSON.parse(readFileSync(writeClaudeHooksSettings(9898, "sec"), "utf8")).hooks.SessionStart[0].hooks[0].command as string
  expect(cmd).toContain("MUX_SESSION_ROLE")
  expect(cmd).toContain("soul.md")
  expect(cmd).toContain("hookSpecificOutput")
  const { execSync } = require("child_process")
  const { mkdtempSync: mkd, writeFileSync: wfs } = require("fs")
  const home = mkd(join(tmpdir(), "hooks-soul-"))
  wfs(join(home, "soul.md"), "# chewy\nYou are chewy.")
  const pa = execSync(cmd, { env: { ...process.env, MUX_SESSION_ROLE: "main", MUX_HOME: home } }).toString()
  const ctx = JSON.parse(pa).hookSpecificOutput.additionalContext
  expect(ctx).toContain("chewy")
  const w = execSync(cmd, { env: { ...process.env, MUX_SESSION_ROLE: "worker", MUX_HOME: home } }).toString()
  expect(w).toBe("")
})

test("writes lifecycle hooks with broker port and hook auth secret", () => {
  const p = writeClaudeHooksSettings(9898, "deadbeef")
  expect(p).toBe(CLAUDE_HOOKS_SETTINGS_PATH)
  expect(existsSync(p)).toBe(true)
  expect(readPersistedHookSecret()).toBe("deadbeef")
  expect(existsSync(INTERNAL_HOOK_SECRET_FILE)).toBe(true)
  const cfg = JSON.parse(readFileSync(p, "utf8"))
  for (const ev of ["UserPromptSubmit", "PreToolUse", "PostToolUse", "Stop"]) {
    const cmd = cfg.hooks[ev][0].hooks[0]
    expect(cmd.type).toBe("command")
    expect(cmd.command).toContain(`/internal/agent-hook/${ev}`)
    expect(cmd.command).toContain("127.0.0.1:9898")
    expect(cmd.command).toContain("?s=deadbeef")
    expect(cmd.command).toContain("--max-time 2")
    expect(cmd.command).toContain("|| true")
    expect(cmd.command).toContain("--data-binary @-")
  }
  expect(hooksFileUsesHookSecret(p)).toBe(true)
})

test("hooksFileUsesHookSecret is false when curl URLs omit ?s=", () => {
  const { unlinkSync } = require("fs")
  try { unlinkSync(INTERNAL_HOOK_SECRET_FILE) } catch { /* absent */ }
  writeClaudeHooksSettings(9898, "")
  expect(hooksFileUsesHookSecret()).toBe(false)
})

// Claude Code snapshots hook config at CLI startup, so the secret embedded in a
// long-running session's hooks must survive broker restarts — a per-boot secret
// silently 403s every pre-restart session and its status freezes at "idle".
test("resolveInternalHookSecret reuses the persisted secret across boots", () => {
  writePersistedHookSecret("boot-one-secret")
  expect(resolveInternalHookSecret(() => "freshly-generated")).toBe("boot-one-secret")
  expect(readPersistedHookSecret()).toBe("boot-one-secret")
})

test("resolveInternalHookSecret generates and persists only on first boot", () => {
  const { unlinkSync } = require("fs")
  try { unlinkSync(INTERNAL_HOOK_SECRET_FILE) } catch { /* absent */ }
  expect(resolveInternalHookSecret(() => "gen-a")).toBe("gen-a")
  expect(readPersistedHookSecret()).toBe("gen-a")
  // simulated second boot: the generator must not be consulted again
  expect(resolveInternalHookSecret(() => "gen-b")).toBe("gen-a")
})
