import { test, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"

// Isolate memory side-effects: buildMemoryPreamble reads MUX_HOME at call
// time, so pointing it at a temp dir keeps the real ~/.mux untouched.
process.env.MUX_HOME = mkdtempSync(join(tmpdir(), "mux-home-"))

const { buildCodexSpawnCommand, buildCursorSpawnCommand, buildOpenCodeSpawnCommand } = await import("./spawn-command")

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
