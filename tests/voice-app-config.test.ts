import { test, expect } from "bun:test"
import { sanitizeAppConfigPatch, resolveAppConfig } from "../src/core/settings/app-config"

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
