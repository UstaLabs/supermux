import { test, expect, afterEach } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { writeOpenCodeConfig, readGlobalProviderConfig } from "./config-writer"
import { resolveOpenCodeAuth } from "./auth"

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

// Models absent from the models.dev registry (e.g. qwen3.8-max-preview) exist
// only as a declaration in the user's global config. Without this bridge the
// session dies with ProviderModelNotFoundError on the first prompt.
test("bridges the global provider block into the session config", () => {
  const home = mkdtempSync(join(tmpdir(), "oc-cfg-"))
  dirs.push(home)
  const path = writeOpenCodeConfig({
    configHome: home, shimCommand: "bun", shimArgs: [], sessionName: "d", socketsDir: "/s", sessionId: "id",
    providerConfig: { "alibaba-token-plan": { models: { "qwen3.8-max-preview": { name: "Qwen3.8 Max Preview" } } } },
  })
  const cfg = JSON.parse(readFileSync(path, "utf8"))
  expect(cfg.provider["alibaba-token-plan"].models["qwen3.8-max-preview"].name).toBe("Qwen3.8 Max Preview")
})

test("reads the provider block from a commented opencode.jsonc", () => {
  const dir = mkdtempSync(join(tmpdir(), "oc-global-"))
  dirs.push(dir)
  writeFileSync(join(dir, "opencode.jsonc"), `{
  // a line comment mentioning "quotes" and a // marker
  "$schema": "https://opencode.ai/config.json",
  /* block comment */
  "provider": { "alibaba-token-plan": { "models": { "qwen3.8-max-preview": {} } } }
}`)
  const provider = readGlobalProviderConfig({ configDir: dir })
  expect(Object.keys((provider as any)["alibaba-token-plan"].models)).toEqual(["qwen3.8-max-preview"])
})

test("preserves URLs inside strings when stripping comments", () => {
  const dir = mkdtempSync(join(tmpdir(), "oc-global-"))
  dirs.push(dir)
  writeFileSync(join(dir, "opencode.json"), `{
  "provider": { "p": { "options": { "baseURL": "https://example.com/v1" } } }
}`)
  const provider = readGlobalProviderConfig({ configDir: dir })
  expect((provider as any).p.options.baseURL).toBe("https://example.com/v1")
})

test("returns undefined when the global config is missing or has no provider", () => {
  const dir = mkdtempSync(join(tmpdir(), "oc-global-"))
  dirs.push(dir)
  expect(readGlobalProviderConfig({ configDir: dir })).toBeUndefined()
  writeFileSync(join(dir, "opencode.json"), `{ "$schema": "x" }`)
  expect(readGlobalProviderConfig({ configDir: dir })).toBeUndefined()
})

test("never throws on malformed global config", () => {
  const dir = mkdtempSync(join(tmpdir(), "oc-global-"))
  dirs.push(dir)
  writeFileSync(join(dir, "opencode.json"), `{ this is not json`)
  expect(readGlobalProviderConfig({ configDir: dir })).toBeUndefined()
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

test("uses LOCALAPPDATA for native Windows opencode auth without changing POSIX XDG", () => {
  expect(resolveOpenCodeAuth({
    home: "C:\\Users\\u", platform: "win32", localAppData: "D:\\Data", fileExists: () => false,
  }).authPath).toBe("D:\\Data\\opencode\\auth.json")
  expect(resolveOpenCodeAuth({
    home: "/home/u", platform: "linux", xdgDataHome: "/xdg", fileExists: () => false,
  }).authPath).toBe("/xdg/opencode/auth.json")
})
