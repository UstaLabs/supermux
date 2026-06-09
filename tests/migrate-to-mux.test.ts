import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, existsSync, readFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { migrate, renameEnvKey } from "../scripts/migrate-to-mux"

function fixture(): string {
  const h = mkdtempSync(join(tmpdir(), "muxmig-"))
  mkdirSync(join(h, ".agentmux", "state"), { recursive: true })
  writeFileSync(
    join(h, ".agentmux", "state", ".env"),
    "AGENTMUX_HOME=/x\nWEB_PORT=8787\nTELEGRAM_BOT_TOKEN=abc\nCURATOR_ENABLED=1\nOPENAI_API_KEY=k\n",
  )
  return h
}

test("renameEnvKey: prefixes ours, leaves provider keys + values alone", () => {
  expect(renameEnvKey("AGENTMUX_HOME=/x")).toBe("MUX_HOME=/x")
  expect(renameEnvKey("WEB_PORT=8787")).toBe("MUX_WEB_PORT=8787")
  expect(renameEnvKey("TELEGRAM_BOT_TOKEN=abc")).toBe("MUX_TELEGRAM_BOT_TOKEN=abc")
  expect(renameEnvKey("CURATOR_ENABLED=1")).toBe("MUX_CURATOR_ENABLED=1")
  expect(renameEnvKey("OPENAI_API_KEY=k")).toBe("OPENAI_API_KEY=k")
  expect(renameEnvKey("MUX_WEB_PORT=1")).toBe("MUX_WEB_PORT=1") // idempotent
  expect(renameEnvKey("# a comment")).toBe("# a comment")
})

test("dry-run moves nothing and reports a plan", () => {
  const h = fixture()
  const plan = migrate({ home: h, dryRun: true })
  expect(existsSync(join(h, ".agentmux"))).toBe(true)
  expect(existsSync(join(h, ".mux"))).toBe(false)
  expect(plan.steps.length).toBeGreaterThan(0)
  rmSync(h, { recursive: true, force: true })
})

test("real run moves the dir and rewrites .env keys (backup kept)", () => {
  const h = fixture()
  migrate({ home: h, dryRun: false })
  expect(existsSync(join(h, ".mux"))).toBe(true)
  expect(existsSync(join(h, ".agentmux"))).toBe(false)
  const env = readFileSync(join(h, ".mux", "state", ".env"), "utf8")
  expect(env).toContain("MUX_HOME=/x")
  expect(env).toContain("MUX_WEB_PORT=8787")
  expect(env).toContain("MUX_TELEGRAM_BOT_TOKEN=abc")
  expect(env).toContain("MUX_CURATOR_ENABLED=1")
  expect(env).toContain("OPENAI_API_KEY=k") // unchanged
  expect(env).not.toContain("AGENTMUX_HOME")
  expect(existsSync(join(h, ".mux", "state", ".env.bak"))).toBe(true)
  rmSync(h, { recursive: true, force: true })
})

test("renames the plugin dir + patches its manifest name", () => {
  const h = fixture()
  const mfDir = join(h, ".agentmux", "plugins", "agentmux-core", ".claude-plugin")
  mkdirSync(mfDir, { recursive: true })
  writeFileSync(join(mfDir, "plugin.json"), JSON.stringify({ name: "agentmux", version: "0.1.0" }))
  writeFileSync(
    join(h, ".agentmux", "plugins.json"),
    JSON.stringify({ version: 1, plugins: [{ name: "agentmux-core", source: { type: "local", path: "~/.agentmux/plugins/agentmux-core" }, enabled: true, scopes: ["claude"] }] }),
  )
  migrate({ home: h, dryRun: false })
  expect(existsSync(join(h, ".mux", "plugins", "mux-core"))).toBe(true)
  const mf = JSON.parse(readFileSync(join(h, ".mux", "plugins", "mux-core", ".claude-plugin", "plugin.json"), "utf8"))
  expect(mf.name).toBe("mux")
  const reg = readFileSync(join(h, ".mux", "plugins.json"), "utf8")
  expect(reg).toContain("mux-core")
  expect(reg).toContain(".mux/plugins")
  expect(reg).not.toContain("agentmux")
  rmSync(h, { recursive: true, force: true })
})

test("refuses when ~/.mux already exists", () => {
  const h = fixture()
  mkdirSync(join(h, ".mux"))
  expect(() => migrate({ home: h, dryRun: false })).toThrow(/already exists/)
  rmSync(h, { recursive: true, force: true })
})
