import { describe, expect, test } from "bun:test"
import { telemetryModel, telemetryEffort, parseClientInfo, featureForRequest, featureForUpgrade, featureForFrame, featureNeedsBody } from "./sanitize"

const catalog = (ids: string[]) => () => ids.map((id) => ({ id, displayName: id }))

describe("telemetryModel", () => {
  test("no model picked → default", () => {
    expect(telemetryModel("claude", undefined, catalog([]))).toBe("default")
  })
  test("catalog hit passes through", () => {
    expect(telemetryModel("claude", "claude-opus-5-5", catalog(["claude-opus-5-5"]))).toBe("claude-opus-5-5")
  })
  test("unknown id → custom", () => {
    expect(telemetryModel("codex", "my-finetune", catalog(["gpt-5.5"]))).toBe("custom")
  })
  test("opencode hosted provider passes, even if not in the catalog", () => {
    expect(telemetryModel("opencode", "anthropic/claude-sonnet-5-5", catalog([]))).toBe("anthropic/claude-sonnet-5-5")
  })
  test("opencode local provider → custom, even if in the catalog", () => {
    expect(telemetryModel("opencode", "ollama/qwen3:32b", catalog(["ollama/qwen3:32b"]))).toBe("custom")
  })
  test("a catalog id with odd characters → custom", () => {
    expect(telemetryModel("claude", "weird model!", catalog(["weird model!"]))).toBe("custom")
  })
})

describe("telemetryEffort", () => {
  test("undefined → default; slug passes; junk → default", () => {
    expect(telemetryEffort(undefined)).toBe("default")
    expect(telemetryEffort("high")).toBe("high")
    expect(telemetryEffort("not valid!")).toBe("default")
  })
})

describe("parseClientInfo", () => {
  test("reads valid params", () => {
    const u = new URL("https://h/ws?clientSurface=android&clientVersion=0.14.0&clientOs=android&clientOsMajor=16")
    expect(parseClientInfo(u.searchParams)).toEqual({ surface: "android", clientVersion: "0.14.0", clientOs: "android", clientOsMajor: 16 })
  })
  test("drops invalid values, keeps absence absent", () => {
    const u = new URL("https://h/ws?clientSurface=fridge&clientVersion=" + "x".repeat(40) + "&clientOsMajor=abc")
    expect(parseClientInfo(u.searchParams)).toEqual({})
  })
  test("telegram/whatsapp can't be claimed by a /ws client", () => {
    expect(parseClientInfo(new URL("https://h/ws?clientSurface=telegram").searchParams)).toEqual({})
  })
})

describe("feature mapping", () => {
  test("HTTP routes", () => {
    expect(featureForRequest("POST", "/sessions", { worktree: true })).toEqual({ feature: "worktree.create" })
    expect(featureForRequest("POST", "/sessions", {})).toBeUndefined()
    expect(featureForRequest("POST", "/forge/clone", { forgeKind: "github" })).toEqual({ feature: "forge.clone", variant: "github" })
    expect(featureForRequest("POST", "/forge/clone", {})).toEqual({ feature: "forge.clone" })
    expect(featureForRequest("POST", "/forge/create-local")).toEqual({ feature: "forge.create", variant: "local" })
    expect(featureForRequest("DELETE", "/sessions/abc")).toEqual({ feature: "session.archive" })
    expect(featureForRequest("POST", "/sessions/abc/mute", { muted: true })).toEqual({ feature: "session.mute" })
    expect(featureForRequest("POST", "/sessions/abc/mute", { muted: false })).toBeUndefined()
    expect(featureForRequest("PUT", "/fs/write")).toEqual({ feature: "file.write" })
    expect(featureForRequest("POST", "/sessions/abc/transcribe")).toEqual({ feature: "voice.transcribe" })
    expect(featureForRequest("GET", "/sessions")).toBeUndefined()
  })
  test("featureNeedsBody", () => {
    expect(featureNeedsBody("POST", "/sessions")).toBe(true)
    expect(featureNeedsBody("POST", "/sessions/a/mute")).toBe(true)
    expect(featureNeedsBody("PUT", "/fs/write")).toBe(false)
  })
  test("WS upgrades", () => {
    expect(featureForUpgrade("/ws/term")).toEqual({ feature: "terminal.open" })
    expect(featureForUpgrade("/ws/display")).toEqual({ feature: "display.open" })
    expect(featureForUpgrade("/ws/scrcpy")).toEqual({ feature: "emulator.open" })
    expect(featureForUpgrade("/ws")).toBeUndefined()
  })
  test("WS frames", () => {
    expect(featureForFrame({ type: "editor_open" })).toEqual({ feature: "editor.open" })
    expect(featureForFrame({ type: "send" })).toBeUndefined()
  })
})
