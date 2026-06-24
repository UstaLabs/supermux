import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"

// Isolate memory side-effects: buildMemoryPreamble + STATE_DIR resolve MUX_HOME
// at import time, so point it at a temp dir BEFORE importing anything that reads
// it. Every import that transitively pulls in shared/paths must therefore be
// dynamic + below this line (a static import would hoist above it and freeze the
// real ~/.mux/state).
process.env.MUX_HOME = mkdtempSync(join(tmpdir(), "mux-home-"))

const { buildClaudeSpawnCommand, buildCodexSpawnCommand, buildCursorSpawnCommand, buildOpenCodeSpawnCommand } = await import("./spawn-command")
const { ensureMuxCoreRegistered, ensureMuxCoreSkills } = await import("../plugins/mux-core")

test("codex spawn command invokes codex app-server with env vars", () => {
  const cmd = buildCodexSpawnCommand({ name: "test-pa", sessionId: "sess-1" })
  expect(cmd).toContain("codex app-server")
  expect(cmd).toContain("MUX_SESSION_ID=sess-1")
  expect(cmd).toContain("MUX_DISPLAY_NAME=test-pa")
  expect(cmd).toContain('approval_policy="never"')
  expect(cmd).toContain('sandbox_mode="danger-full-access"')
})

test("codex spawn command includes model flag when provided", () => {
  const cmd = buildCodexSpawnCommand({ name: "test-pa", sessionId: "sess-1", model: "gpt-4o" })
  expect(cmd).toContain('model="gpt-4o"')
})

test("codex spawn command includes effort flag when provided", () => {
  const cmd = buildCodexSpawnCommand({ name: "test-pa", sessionId: "sess-1", effort: "high" })
  expect(cmd).toContain('model_reasoning_effort="high"')
})

test("cursor spawn command invokes cursor-agent with env vars", () => {
  const cmd = buildCursorSpawnCommand({ name: "test-pa", sessionId: "sess-1" })
  expect(cmd).toContain("cursor-agent")
  expect(cmd).toContain("MUX_SESSION_ID=sess-1")
  expect(cmd).toContain("MUX_DISPLAY_NAME=test-pa")
})

test("cursor spawn command includes model flag when provided", () => {
  const cmd = buildCursorSpawnCommand({ name: "test-pa", sessionId: "sess-1", model: "cursor-fast" })
  expect(cmd).toContain("cursor-fast")
})

test("opencode spawn command invokes opencode serve with env vars", () => {
  const cmd = buildOpenCodeSpawnCommand({ name: "test-pa", sessionId: "sess-1", port: 8080 })
  expect(cmd).toContain("opencode serve")
  expect(cmd).toContain("MUX_SESSION_ID=sess-1")
  expect(cmd).toContain("MUX_DISPLAY_NAME=test-pa")
  expect(cmd).toContain("--port 8080")
})

test("opencode spawn command includes model flag when provided", () => {
  const cmd = buildOpenCodeSpawnCommand({ name: "test-pa", sessionId: "sess-1", port: 8080, model: "kimi-k2.6" })
  expect(cmd).toContain("kimi-k2.6")
})

// ── reply-fallback gate: the static fallback must be appended UNLESS mux-core's
//    SessionStart hook is actually on disk. Keying on the file (not just the
//    registry entry) is what stops a registered-but-half-installed plugin from
//    suppressing the fallback while delivering no reply rules at all. ─────────

test("claude spawn appends the reply fallback when mux-core is registered but its hook is absent", () => {
  const root = mkdtempSync(join(tmpdir(), "spawn-nohook-"))
  try {
    const pluginsDir = join(root, "plugins")
    const file = join(root, "plugins.json")
    // Registered + a valid claude manifest (so --plugin-dir is emitted)…
    ensureMuxCoreRegistered({ file, pluginsDir })
    mkdirSync(join(pluginsDir, "mux-core", ".claude-plugin"), { recursive: true })
    writeFileSync(join(pluginsDir, "mux-core", ".claude-plugin", "plugin.json"), JSON.stringify({ name: "mux" }))
    // …but NO hooks/session-start on disk (the fresh-install bug state).
    const cmd = buildClaudeSpawnCommand({ name: "worker-nohook", pluginsFile: file, pluginsDir })
    expect(cmd).toContain("--plugin-dir")
    expect(cmd).toContain("reply-fallback.md")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})

test("claude spawn suppresses the reply fallback when mux-core ships its SessionStart hook", () => {
  const root = mkdtempSync(join(tmpdir(), "spawn-hook-"))
  try {
    const pluginsDir = join(root, "plugins")
    const file = join(root, "plugins.json")
    ensureMuxCoreRegistered({ file, pluginsDir })
    ensureMuxCoreSkills({ pluginDir: join(pluginsDir, "mux-core") }) // writes manifest + hook
    const cmd = buildClaudeSpawnCommand({ name: "worker-hook", pluginsFile: file, pluginsDir })
    expect(cmd).toContain("--plugin-dir")
    expect(cmd).not.toContain("reply-fallback.md")
  } finally {
    rmSync(root, { recursive: true, force: true })
  }
})
