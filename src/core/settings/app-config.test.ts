import { describe, expect, test } from "bun:test"
import { redactAppConfig, defaultAppConfig, resolveAppConfig, sanitizeAppConfigPatch, credentialEnvVars, SECRET_FIELDS } from "./app-config"

describe("redactAppConfig WhatsApp secrets", () => {
  test("never exposes the raw secret keys", () => {
    const out = redactAppConfig({ ...defaultAppConfig, whatsappGowaBasicAuth: "user:pass", whatsappWebhookSecret: "s3cr3t" })
    expect(out).not.toHaveProperty("whatsappGowaBasicAuth")
    expect(out).not.toHaveProperty("whatsappWebhookSecret")
  })

  test("emits *Configured booleans reflecting whether each secret is set", () => {
    const set = redactAppConfig({ ...defaultAppConfig, whatsappGowaBasicAuth: "user:pass", whatsappWebhookSecret: "s3cr3t" })
    expect(set.whatsappGowaBasicAuthConfigured).toBe(true)
    expect(set.whatsappWebhookSecretConfigured).toBe(true)

    const unset = redactAppConfig({ ...defaultAppConfig })
    expect(unset.whatsappGowaBasicAuthConfigured).toBe(false)
    expect(unset.whatsappWebhookSecretConfigured).toBe(false)
  })
})

describe("geminiApiKey (antigravity)", () => {
  test("is a secret: redacted to geminiConfigured", () => {
    expect(SECRET_FIELDS).toContain("geminiApiKey")
    const set = redactAppConfig({ ...defaultAppConfig, geminiApiKey: "AIza-x" })
    expect(set).not.toHaveProperty("geminiApiKey")
    expect(set.geminiConfigured).toBe(true)
    expect(redactAppConfig({ ...defaultAppConfig }).geminiConfigured).toBe(false)
  })

  test("defaults empty, survives sanitize/resolve, and is injected as GEMINI_API_KEY", () => {
    expect(defaultAppConfig.geminiApiKey).toBe("")
    expect(sanitizeAppConfigPatch({ geminiApiKey: "AIza-x" }).geminiApiKey).toBe("AIza-x")
    expect(resolveAppConfig({ geminiApiKey: "AIza-x" }, {}).geminiApiKey).toBe("AIza-x")
    expect(credentialEnvVars({ ...defaultAppConfig, geminiApiKey: "AIza-x" })).toEqual({ GEMINI_API_KEY: "AIza-x" })
    expect(credentialEnvVars({ ...defaultAppConfig })).toEqual({})
  })
})

describe("voiceLanguages", () => {
  test("sanitize normalizes codes, drops junk, dedupes", () => {
    expect(sanitizeAppConfigPatch({ voiceLanguages: ["TR", "en-US", "en", "not a lang", 3] }).voiceLanguages).toEqual(["tr", "en"])
  })

  test("an explicit empty list is kept (clears back to auto-detect)", () => {
    expect(sanitizeAppConfigPatch({ voiceLanguages: [] }).voiceLanguages).toEqual([])
  })

  test("resolve surfaces the stored list; absent stays absent", () => {
    expect(resolveAppConfig({ voiceLanguages: ["tr", "en"] }, {}).voiceLanguages).toEqual(["tr", "en"])
    expect(resolveAppConfig({}, {}).voiceLanguages).toBeUndefined()
  })
})

// #72: Source Control's Commit ▾ pick and its "no staged changes" answer, kept on the broker (the owner's, until users exist).
describe("git commit preferences", () => {
  test("sanitize keeps only the known values", () => {
    expect(sanitizeAppConfigPatch({ gitPostCommit: "push", gitSmartCommit: "always" })).toMatchObject({ gitPostCommit: "push", gitSmartCommit: "always" })
    expect(sanitizeAppConfigPatch({ gitPrPush: "always" })).toMatchObject({ gitPrPush: "always" })
    expect(sanitizeAppConfigPatch({ gitPrPush: "never" }).gitPrPush).toBeUndefined()
    const junk = sanitizeAppConfigPatch({ gitPostCommit: "deploy", gitSmartCommit: 1 })
    expect(junk.gitPostCommit).toBeUndefined()
    expect(junk.gitSmartCommit).toBeUndefined()
  })

  test("resolve surfaces what is stored; absent stays absent", () => {
    expect(resolveAppConfig({ gitPostCommit: "sync", gitSmartCommit: "never" }, {})).toMatchObject({ gitPostCommit: "sync", gitSmartCommit: "never" })
    expect(resolveAppConfig({}, {}).gitPostCommit).toBeUndefined()
  })
})
