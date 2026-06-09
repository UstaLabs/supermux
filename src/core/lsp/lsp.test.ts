import { test, expect } from "bun:test"
import { getCatalogServerById, languageIdForPath, launchCommand, resolveBinPath } from "./catalog"
import { parseEditorConfig, parseCustomLspServerDef } from "../settings/editor-config"
import { getServerById, resolveServerForPath } from "./registry"
import { encodeMessage, MessageReader } from "./framing"

test("resolveServerForPath maps extensions to the right server", () => {
  expect(resolveServerForPath("src/app.ts")?.id).toBe("typescript")
  expect(resolveServerForPath("a/b/comp.tsx")?.id).toBe("typescript")
  expect(resolveServerForPath("main.js")?.id).toBe("typescript")
  expect(resolveServerForPath("script.py")?.id).toBe("pyright")
  expect(resolveServerForPath("main.go")?.id).toBe("gopls")
  expect(resolveServerForPath("lib.rs")?.id).toBe("rust-analyzer")
  expect(resolveServerForPath("run.sh")?.id).toBe("bash")
  expect(resolveServerForPath("config.yaml")?.id).toBe("yaml")
  expect(resolveServerForPath("data.json")?.id).toBe("json")
  expect(resolveServerForPath("lib/main.dart")?.id).toBe("dart")
})

test("resolveServerForPath respects editor config disabled servers", () => {
  const cfg = parseEditorConfig({ lsp: { servers: { typescript: { enabled: false } } } })
  expect(resolveServerForPath("app.ts", cfg)).toBeUndefined()
})

test("resolveServerForPath returns undefined for unknown / extensionless", () => {
  expect(resolveServerForPath("weird.xyz")).toBeUndefined()
  expect(resolveServerForPath("Dockerfile")).toBeUndefined()
  expect(resolveServerForPath("noext")).toBeUndefined()
})

test("languageIdForPath distinguishes ts vs tsx vs js", () => {
  expect(languageIdForPath("a.ts")).toBe("typescript")
  expect(languageIdForPath("a.tsx")).toBe("typescriptreact")
  expect(languageIdForPath("a.js")).toBe("javascript")
  expect(languageIdForPath("a.jsx")).toBe("javascriptreact")
  expect(languageIdForPath("a.py")).toBe("python")
  expect(languageIdForPath("a.dart")).toBe("dart")
  expect(languageIdForPath("a.unknown")).toBe("plaintext")
})

test("custom server resolves by extension", () => {
  const cfg = parseEditorConfig({
    lsp: {
      custom: {
        zig: parseCustomLspServerDef({
          label: "Zig",
          command: "zls",
          extensions: [".zig"],
        }),
      },
    },
  })
  expect(resolveServerForPath("main.zig", cfg)?.id).toBe("zig")
  expect(getServerById("zig", cfg)?.command).toBe("zls")
})

test("getServerById round-trips and carries install metadata", () => {
  const ts = getCatalogServerById("typescript")
  expect(ts?.bin).toBe("typescript-language-server")
  expect(ts?.runtime).toBe("node")
  expect(ts?.install?.requires).toBe("bun") // host has no npm/node — install via bun
  expect(getCatalogServerById("nope")).toBeUndefined()
})

test("launchCommand runs node servers through bun, native servers directly", () => {
  const ts = getCatalogServerById("typescript")!
  const tsCmd = launchCommand(ts)
  expect(tsCmd.command).toBe("bun")
  expect(tsCmd.args[0]).toBe(resolveBinPath(ts)) // absolute path under bun global bin
  expect(tsCmd.args).toContain("--stdio")

  const go = getCatalogServerById("gopls")!
  const goCmd = launchCommand(go)
  expect(goCmd.command).toBe("gopls")
  expect(goCmd.args).toEqual([])

  const dart = getCatalogServerById("dart")!
  expect(dart.install?.requires).toBe("unzip")
  expect(dart.install?.label).toContain("~/.mux/lsp")
  const dartCmd = launchCommand(dart)
  expect(dartCmd.args[0]).toBe("language-server")
  expect(dartCmd.args).toContain("--protocol=lsp")
})

test("framing round-trips a single message", () => {
  const msgs: string[] = []
  const reader = new MessageReader((m) => msgs.push(m))
  const payload = JSON.stringify({ jsonrpc: "2.0", id: 1, method: "initialize" })
  reader.push(encodeMessage(payload))
  expect(msgs).toEqual([payload])
})

test("framing handles a chunk containing two messages", () => {
  const msgs: string[] = []
  const reader = new MessageReader((m) => msgs.push(m))
  const a = JSON.stringify({ a: 1 })
  const b = JSON.stringify({ b: 2 })
  reader.push(Buffer.concat([encodeMessage(a), encodeMessage(b)]))
  expect(msgs).toEqual([a, b])
})

test("framing reassembles a message split across chunks", () => {
  const msgs: string[] = []
  const reader = new MessageReader((m) => msgs.push(m))
  const payload = JSON.stringify({ hello: "wörld" }) // multibyte to exercise byte-length
  const full = encodeMessage(payload)
  reader.push(full.subarray(0, 10))
  reader.push(full.subarray(10, 20))
  reader.push(full.subarray(20))
  expect(msgs).toEqual([payload])
})

test("framing counts body length in bytes, not characters", () => {
  const msgs: string[] = []
  const reader = new MessageReader((m) => msgs.push(m))
  const payload = JSON.stringify({ emoji: "🚀", text: "café" })
  reader.push(encodeMessage(payload))
  expect(msgs).toEqual([payload])
  expect(JSON.parse(msgs[0]!).emoji).toBe("🚀")
})
