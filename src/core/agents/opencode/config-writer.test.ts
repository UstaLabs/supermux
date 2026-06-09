import { test, expect, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { writeOpenCodeConfig } from "./config-writer"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true })
  dirs.length = 0
})

test("registers the mux-shim MCP server with the session id as MUX_SESSION_ID", () => {
  const home = mkdtempSync(join(tmpdir(), "oc-cfg-"))
  dirs.push(home)
  const path = writeOpenCodeConfig({
    configHome: home,
    shimCommand: "bun",
    shimArgs: ["run", "/shim/index.ts"],
    sessionName: "demo",
    socketsDir: "/sockets",
    sessionId: "uuid-123",
    instructionsPath: "/home/AGENTS.md",
  })
  const cfg = JSON.parse(readFileSync(path, "utf8"))
  expect(cfg.mcp.mux.type).toBe("local")
  expect(cfg.mcp.mux.command).toEqual(["bun", "run", "/shim/index.ts"])
  expect(cfg.mcp.mux.environment.MUX_SESSION_ID).toBe("uuid-123")
  expect(cfg.mcp.mux.environment.MUX_AGENT_KIND).toBe("opencode")
  expect(cfg.mcp.mux.environment.MUX_DISPLAY_NAME).toBe("demo")
  expect(cfg.mcp.mux.environment.MUX_SOCKETS_DIR).toBe("/sockets")
  expect(cfg.instructions).toEqual(["/home/AGENTS.md"])
})

test("merges plugin and skills paths into opencode.json", () => {
  const home = mkdtempSync(join(tmpdir(), "oc-cfg-"))
  dirs.push(home)
  const path = writeOpenCodeConfig({
    configHome: home,
    shimCommand: "bun",
    shimArgs: [],
    sessionName: "d",
    socketsDir: "/s",
    sessionId: "id",
    pluginPaths: ["/plugins/superpowers"],
    skillsPaths: ["/plugins/extra/skills"],
  })
  const cfg = JSON.parse(readFileSync(path, "utf8"))
  expect(cfg.plugin).toEqual(["/plugins/superpowers"])
  expect(cfg.skills).toEqual({ paths: ["/plugins/extra/skills"] })
})

test("omits instructions when no preamble path is given", () => {
  const home = mkdtempSync(join(tmpdir(), "oc-cfg-"))
  dirs.push(home)
  const path = writeOpenCodeConfig({
    configHome: home, shimCommand: "bun", shimArgs: [], sessionName: "d", socketsDir: "/s", sessionId: "id",
  })
  const cfg = JSON.parse(readFileSync(path, "utf8"))
  expect(cfg.instructions).toBeUndefined()
})
