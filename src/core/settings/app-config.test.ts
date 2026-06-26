import { describe, expect, test } from "bun:test"
import { redactAppConfig, defaultAppConfig } from "./app-config"

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
