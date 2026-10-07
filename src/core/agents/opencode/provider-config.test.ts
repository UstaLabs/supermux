import { test, expect, afterEach } from "bun:test"
import { mkdtempSync, rmSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { readGlobalProviderConfig } from "./provider-config"
import { resolveOpenCodeAuth } from "./auth"

const dirs: string[] = []
afterEach(() => {
  for (const d of dirs) rmSync(d, { recursive: true, force: true })
  dirs.length = 0
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
  expect(Object.keys((provider as { "alibaba-token-plan": { models: Record<string, unknown> } })["alibaba-token-plan"].models)).toEqual(["qwen3.8-max-preview"])
})

test("preserves URLs inside strings when stripping comments", () => {
  const dir = mkdtempSync(join(tmpdir(), "oc-global-"))
  dirs.push(dir)
  writeFileSync(join(dir, "opencode.json"), `{
  "provider": { "p": { "options": { "baseURL": "https://example.com/v1" } } }
}`)
  const provider = readGlobalProviderConfig({ configDir: dir })
  expect((provider as { p: { options: { baseURL: string } } }).p.options.baseURL).toBe("https://example.com/v1")
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

test("uses LOCALAPPDATA for native Windows opencode auth without changing POSIX XDG", async () => {
  expect((await resolveOpenCodeAuth({
    home: "C:\\Users\\u", platform: "win32", localAppData: "D:\\Data", fileExists: () => false,
  })).authPath).toBe("D:\\Data\\opencode\\auth.json")
  expect((await resolveOpenCodeAuth({
    home: "/home/u", platform: "linux", xdgDataHome: "/xdg", fileExists: () => false,
  })).authPath).toBe("/xdg/opencode/auth.json")
})
