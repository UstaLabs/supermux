import { test, expect } from "bun:test"
import { sanitizeAppConfigPatch, resolveAppConfig, DEFAULT_VOICE_CLEANUP_GLOSSARY } from "../src/core/settings/app-config"

test("voice/whisper fields survive sanitize + resolve", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupModel: "haiku", whisperModel: "/m/ggml-base.bin", whisperLang: "tr" })
  expect(patch.voiceCleanupModel).toBe("haiku")
  expect(patch.whisperModel).toBe("/m/ggml-base.bin")
  expect(patch.whisperLang).toBe("tr")
  const resolved = resolveAppConfig(patch, {} as any)
  expect(resolved.voiceCleanupModel).toBe("haiku")
  expect(resolved.whisperModel).toBe("/m/ggml-base.bin")
  expect(resolved.whisperLang).toBe("tr")
})

test("voice/whisper fields absent when not provided", () => {
  const patch = sanitizeAppConfigPatch({ paName: "test" })
  expect(patch.voiceCleanupModel).toBeUndefined()
  expect(patch.whisperModel).toBeUndefined()
  expect(patch.whisperLang).toBeUndefined()
  const resolved = resolveAppConfig(patch, {} as any)
  expect(resolved.voiceCleanupModel).toBeUndefined()
  expect(resolved.whisperModel).toBeUndefined()
  expect(resolved.whisperLang).toBeUndefined()
})

test("sanitize drops non-string voice fields", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupModel: 42, whisperModel: null, whisperLang: true })
  expect(patch.voiceCleanupModel).toBeUndefined()
  expect(patch.whisperModel).toBeUndefined()
  expect(patch.whisperLang).toBeUndefined()
})

test("voiceCleanupEngine: valid engine survives sanitize + resolve", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupEngine: "opencode-zen" })
  expect(patch.voiceCleanupEngine).toBe("opencode-zen")
  const resolved = resolveAppConfig(patch, {} as any)
  expect(resolved.voiceCleanupEngine).toBe("opencode-zen")
})

test("voiceCleanupEngine: non-string input is dropped", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupEngine: 42 })
  expect(patch.voiceCleanupEngine).toBeUndefined()
})

test("voiceCleanupEngine: unknown string is rejected by the allowlist", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupEngine: "not-a-real-engine" })
  expect(patch.voiceCleanupEngine).toBeUndefined()
  const resolved = resolveAppConfig(patch, {} as any)
  expect(resolved.voiceCleanupEngine).toBeUndefined()
})

test("voiceCleanupGlossary: defaults to the built-in seed when not configured", () => {
  const resolved = resolveAppConfig({}, {} as any)
  expect(resolved.voiceCleanupGlossary).toEqual(DEFAULT_VOICE_CLEANUP_GLOSSARY)
  // sanity: a known seed term is present
  expect(resolved.voiceCleanupGlossary).toContain("Supermux")
})

test("voiceCleanupGlossary: a stored array survives sanitize + resolve", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupGlossary: ["Foo", "Bar"] })
  expect(patch.voiceCleanupGlossary).toEqual(["Foo", "Bar"])
  const resolved = resolveAppConfig(patch, {} as any)
  expect(resolved.voiceCleanupGlossary).toEqual(["Foo", "Bar"])
})

test("voiceCleanupGlossary: a comma-separated string is coerced to an array", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupGlossary: "Foo, Bar ,Baz" })
  expect(patch.voiceCleanupGlossary).toEqual(["Foo", "Bar", "Baz"])
})

test("voiceCleanupGlossary: array entries are trimmed and empties dropped; non-strings ignored", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupGlossary: [" Foo ", "", "Bar", 42, null] })
  expect(patch.voiceCleanupGlossary).toEqual(["Foo", "Bar"])
})

test("voiceCleanupGlossary: a stored empty array is preserved (user cleared it)", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupGlossary: [] })
  expect(patch.voiceCleanupGlossary).toEqual([])
  const resolved = resolveAppConfig(patch, {} as any)
  expect(resolved.voiceCleanupGlossary).toEqual([])
})

test("voiceCleanupGlossary: invalid (non-array/non-string) input is dropped → reveals default", () => {
  const patch = sanitizeAppConfigPatch({ voiceCleanupGlossary: 42 })
  expect(patch.voiceCleanupGlossary).toBeUndefined()
  const resolved = resolveAppConfig(patch, {} as any)
  expect(resolved.voiceCleanupGlossary).toEqual(DEFAULT_VOICE_CLEANUP_GLOSSARY)
})
