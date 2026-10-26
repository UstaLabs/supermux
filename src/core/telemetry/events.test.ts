import { describe, expect, test } from "bun:test"
import { validateEvent, EVENT_NAMES, FEATURES } from "./events"

const common = {
  uuid: "6f1c2e0a-8b3d-4a57-9e21-0c4d5f7a9b13",
  ts: "2026-10-23T14:02:11.482Z",
  installId: "b7e4a1d2-3c5f-4e8a-9b0c-1d2e3f4a5b6c",
  version: "0.14.0",
  commit: "2e3ca970",
  installMode: "binary",
  env: "release",
  os: "linux",
  arch: "x64",
}

describe("validateEvent", () => {
  test("accepts a turn.completed from TELEMETRY.md", () => {
    const r = validateEvent({ ...common, event: "turn.completed", props: {
      agent: "claude", model: "claude-opus-5-5", effort: "high", fast: false, status: "completed", durationMs: 48210,
    } })
    expect(r.ok).toBe(true)
  })

  test("durationMs is optional", () => {
    const r = validateEvent({ ...common, event: "turn.completed", props: {
      agent: "claude", model: "default", effort: "default", fast: false, status: "error",
    } })
    expect(r.ok).toBe(true)
  })

  test("rejects an unknown event", () => {
    expect(validateEvent({ ...common, event: "secret.leaked", props: {} }).ok).toBe(false)
  })

  test("rejects an unknown property", () => {
    const r = validateEvent({ ...common, event: "turn.requested", props: { agent: "codex", surface: "ios", prompt: "hi" } })
    expect(r).toEqual({ ok: false, error: "turn.requested: unknown property prompt" })
  })

  test("rejects an unknown common field", () => {
    expect(validateEvent({ ...common, hostname: "box", event: "broker.started", props: {} }).ok).toBe(false)
  })

  test("rejects a bad surface value", () => {
    expect(validateEvent({ ...common, event: "turn.requested", props: { agent: "codex", surface: "fridge" } }).ok).toBe(false)
  })

  test("rejects an over-long model string", () => {
    const r = validateEvent({ ...common, event: "session.created", props: { agent: "claude", model: "x".repeat(81), effort: "high", fast: false } })
    expect(r.ok).toBe(false)
  })

  test("feature.used only accepts listed features", () => {
    expect(validateEvent({ ...common, event: "feature.used", props: { feature: "worktree.create", surface: "desktop" } }).ok).toBe(true)
    expect(validateEvent({ ...common, event: "feature.used", props: { feature: "rm.rf", surface: "desktop" } }).ok).toBe(false)
  })

  test("feature.used variant must fit the feature", () => {
    expect(validateEvent({ ...common, event: "feature.used", props: { feature: "forge.clone", variant: "github", surface: "android" } }).ok).toBe(true)
    expect(validateEvent({ ...common, event: "feature.used", props: { feature: "forge.clone", variant: "bitbucket", surface: "android" } }).ok).toBe(false)
    expect(validateEvent({ ...common, event: "feature.used", props: { feature: "upload", variant: "x", surface: "ios" } }).ok).toBe(false)
  })

  test("lists match TELEMETRY.md", () => {
    expect(EVENT_NAMES).toEqual([
      "broker.started", "broker.heartbeat", "client.connected", "session.created",
      "turn.requested", "turn.completed", "model.switched", "feature.used",
    ])
    expect(FEATURES.length).toBe(19)
  })
})
