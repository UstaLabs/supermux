import { test, expect, afterAll } from "bun:test"
import { existsSync, mkdtempSync, mkdirSync, writeFileSync, readFileSync, rmSync, rmdirSync } from "fs"
import { tmpdir } from "os"
import { dirname, join } from "path"
import { buildClaudeSpawnCommand } from "../src/core/session-manager/spawn-command"
import { writeClaudeHooksSettings, readPersistedHookSecret, CLAUDE_HOOKS_SETTINGS_PATH } from "../src/core/agents/claude/hooks-settings"

const HOOKS_WEB_PORT = Number(process.env.MUX_WEB_PORT ?? 9898)
const persistedHookSecret = readPersistedHookSecret()

afterAll(() => {
  // Tests write claude-hooks.json; restore production hooks when a broker secret exists.
  if (persistedHookSecret) writeClaudeHooksSettings(HOOKS_WEB_PORT, persistedHookSecret)
})

// Hermetic plugins fixture: a temp registry with mux-core present as a
// claude-compatible local plugin. Avoids reading the live ~/.mux/plugins.json
// (and avoids creating ~/.mux, which would block the one-time migration).
function pluginsFixture(): { pluginsFile: string; pluginsDir: string } {
  const root = mkdtempSync(join(tmpdir(), "mux-plugins-"))
  const pluginsDir = join(root, "plugins")
  const coreDir = join(pluginsDir, "mux-core")
  mkdirSync(join(coreDir, ".claude-plugin"), { recursive: true })
  writeFileSync(join(coreDir, ".claude-plugin", "plugin.json"), JSON.stringify({ name: "mux-core", version: "0.1.0" }))
  const pluginsFile = join(root, "plugins.json")
  writeFileSync(
    pluginsFile,
    JSON.stringify({
      version: 1,
      plugins: [{ name: "mux-core", source: { type: "local", path: coreDir }, enabled: true, scopes: ["claude"] }],
    }),
  )
  return { pluginsFile, pluginsDir }
}

test("includes session id and requested name env vars", () => {
  const cmd = buildClaudeSpawnCommand({ name: "ana" })
  expect(cmd).toContain("MUX_SESSION_ID=ana")
  expect(cmd).toContain("MUX_DISPLAY_NAME=ana")
})

test("disables claude native auto-memory so ~/.mux is the sole memory", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).toContain("CLAUDE_CODE_DISABLE_AUTO_MEMORY=1")
})

test("includes the dangerous flags agentmux needs", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).toContain("--dangerously-skip-permissions")
  expect(cmd).toContain("--dangerously-load-development-channels server:mux-channel")
})

test("wraps in bash -lc so the env vars and flags are honoured", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd.startsWith("bash -lc '")).toBe(true)
  expect(cmd.endsWith("'")).toBe(true)
})

test("different names produce different commands", () => {
  const a = buildClaudeSpawnCommand({ name: "a" })
  const b = buildClaudeSpawnCommand({ name: "b" })
  expect(a).not.toBe(b)
})

test("appends the shared environment.md", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).toContain("--append-system-prompt-file")
  expect(cmd).toContain("prompts/environment.md")
})

test("no longer injects the retired reply-rules / claude-skills preambles", () => {
  // Phase 4: reply conventions moved to the mux-core SessionStart hook;
  // claude-skills.md was retired in favor of the plugin host.
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).not.toContain("prompts/CLAUDE.md")
  expect(cmd).not.toContain("claude-skills.md")
})

test("includes --add-dir pointing at the prompts directory", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).toContain("--add-dir")
  expect(cmd).toContain("prompts")
})

test("includes the mux-core plugin via --plugin-dir (reply hook source)", () => {
  // With mux-core enabled in the registry, the spawn loads it as a plugin.
  const { pluginsFile, pluginsDir } = pluginsFixture()
  const cmd = buildClaudeSpawnCommand({ name: "x", pluginsFile, pluginsDir })
  expect(cmd).toContain("--plugin-dir")
  expect(cmd).toContain("mux-core")
})

test("omits the static reply fallback when mux-core is present", () => {
  const { pluginsFile, pluginsDir } = pluginsFixture()
  const cmd = buildClaudeSpawnCommand({ name: "x", pluginsFile, pluginsDir })
  // The hook carries the reply rules, so the fallback file is not appended.
  expect(cmd).not.toContain("reply-fallback.md")
})

test("appends the static reply fallback when no plugins registry is present", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x", pluginsFile: "/no/such/plugins.json" })
  expect(cmd).toContain("reply-fallback.md")
})

// Regression test: an earlier version of this file used `../../prompts`
// which resolved one level too short after the broker→core refactor moved
// spawn-command from src/broker/ to src/core/session-manager/. The path
// in the resulting command pointed at a non-existent src/prompts/ and
// claude crashed on startup. We now verify the LAST --append-system-prompt-file
// path (the dynamic memory-preamble) actually exists on disk.
test("the resolved prompts path actually exists on disk", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  const matches = [...cmd.matchAll(/--append-system-prompt-file\s+(\S+)/g)]
  expect(matches.length).toBeGreaterThan(0)
  const preamblePath = matches[matches.length - 1]![1]!
  expect(existsSync(preamblePath)).toBe(true)
})

test("the resolved --add-dir path actually exists on disk", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  const m = cmd.match(/--add-dir\s+(\S+)/)
  expect(m).not.toBeNull()
  const path = m![1]!
  expect(existsSync(path)).toBe(true)
})

test("includes --model flag when model is specified", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x", model: "sonnet" })
  expect(cmd).toContain("--model sonnet")
})

test("omits --model flag when model is undefined", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).not.toContain("--model")
})

test("includes --effort flag when effort is specified", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x", effort: "max" })
  expect(cmd).toContain("--effort max")
})

test("omits --effort flag when effort is undefined", () => {
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).not.toContain("--effort")
})

test("personal_assistant sessionRole yields the main-agent memory preamble", () => {
  const cmd = buildClaudeSpawnCommand({ name: "ana", sessionRole: "personal_assistant" })
  const worker = buildClaudeSpawnCommand({ name: "w1", sessionRole: "worker" })
  expect(cmd).not.toBe(worker) // PA gets the main preamble, worker does not
})

test("includes --settings pointing at the hooks file when it exists", () => {
  writeClaudeHooksSettings(HOOKS_WEB_PORT, "test-hook-secret")
  const cmd = buildClaudeSpawnCommand({ name: "alpha" })
  expect(cmd).toContain(`--settings ${CLAUDE_HOOKS_SETTINGS_PATH}`)
})

// Regression: every mux-spawned claude inherits the user's real ~/.claude home,
// whose settings.json has enabledPlugins."telegram@claude-plugins-official"=true.
// Without an override, each session auto-loads that plugin, whose bundled
// .mcp.json boots a `bun server.ts` Telegram long-poller. N sessions → N pollers
// all contending for the single getUpdates consumer slot on one bot token →
// busy-retry on 409 → CPU saturation (observed: load ~20 from 15 pollers), plus
// they orphan to systemd on session exit and pile up. mux already has its own
// native telegram channel + reply shim, so the plugin is pure redundant load.
// The session settings mux passes via --settings must disable it.
const TELEGRAM_PLUGIN_ID = "telegram@claude-plugins-official"

test("disables the redundant telegram plugin in the session settings", () => {
  writeClaudeHooksSettings(HOOKS_WEB_PORT, "test-hook-secret")
  const settings = JSON.parse(readFileSync(CLAUDE_HOOKS_SETTINGS_PATH, "utf8"))
  expect(settings.enabledPlugins?.[TELEGRAM_PLUGIN_ID]).toBe(false)
})

test("the spawn command's --settings file disables the telegram plugin (no server.ts child)", () => {
  writeClaudeHooksSettings(HOOKS_WEB_PORT, "test-hook-secret")
  const cmd = buildClaudeSpawnCommand({ name: "x" })
  expect(cmd).toContain(`--settings ${CLAUDE_HOOKS_SETTINGS_PATH}`)
  const settings = JSON.parse(readFileSync(CLAUDE_HOOKS_SETTINGS_PATH, "utf8"))
  expect(settings.enabledPlugins?.[TELEGRAM_PLUGIN_ID]).toBe(false)
})

test("still emits the agent-hook commands (disabling telegram must not drop hooks)", () => {
  writeClaudeHooksSettings(HOOKS_WEB_PORT, "test-hook-secret")
  const settings = JSON.parse(readFileSync(CLAUDE_HOOKS_SETTINGS_PATH, "utf8"))
  // The plugin-disable lives alongside hooks; both must survive.
  expect(settings.hooks?.Stop).toBeDefined()
  expect(settings.hooks?.UserPromptSubmit).toBeDefined()
})

// agent-rpc spawn flags — rpcMcpConfig wires the worker to a strict mcp config
test("includes MUX_RPC_ONLY=1 and --strict-mcp-config when rpcMcpConfig is set", () => {
  const cmd = buildClaudeSpawnCommand({ name: "rpc-worker", rpcMcpConfig: "/tmp/rpc.json" })
  expect(cmd).toContain("MUX_RPC_ONLY=1")
  expect(cmd).toContain("--strict-mcp-config")
  expect(cmd).toContain("--mcp-config /tmp/rpc.json")
})

test("omits MUX_RPC_ONLY and --strict-mcp-config when rpcMcpConfig is not set", () => {
  const cmd = buildClaudeSpawnCommand({ name: "regular-worker" })
  expect(cmd).not.toContain("MUX_RPC_ONLY")
  expect(cmd).not.toContain("--strict-mcp-config")
})

test("workdir soul.md and focus.md are injected into the memory preamble", () => {
  const workdir = mkdtempSync(join(tmpdir(), "mux-workdir-"))
  mkdirSync(workdir, { recursive: true })
  writeFileSync(join(workdir, "soul.md"), "## Project Soul\nBe helpful.")
  writeFileSync(join(workdir, "focus.md"), "## Focus\nFix the bug.")
  const cmd = buildClaudeSpawnCommand({ name: "test-pa", sessionRole: "personal_assistant", workdir })
  const matches = [...cmd.matchAll(/--append-system-prompt-file\s+(\S+)/g)]
  expect(matches.length).toBeGreaterThan(0)
  const preamblePath = matches[matches.length - 1]![1]!
  const preamble = readFileSync(preamblePath, "utf8")
  expect(preamble).toContain("Be helpful.")
  expect(preamble).toContain("Fix the bug.")
  // Clean up the temp preamble so it doesn't leak across tests.
  try { rmSync(preamblePath, { force: true }) } catch {}
  try { rmdirSync(dirname(preamblePath)) } catch {}
})
