import { test, expect } from "bun:test"
import { parseAppConfig, defaultAppConfig, resolveAppConfig, sanitizeAppConfigPatch } from "../src/core/settings/app-config"

test("parseAppConfig fills defaults from empty input", () => {
  const c = parseAppConfig(undefined)
  expect(c.paName).toBe(defaultAppConfig.paName)
  expect(c.telegramBotToken).toBe("")
  expect(c.exposureMode).toBe("local")
  expect(c.onboarded).toBe(false)
  expect(parseAppConfig(null).paName).toBe("assistant")
})

test("parseAppConfig coerces and clamps known fields, ignores junk", () => {
  const c = parseAppConfig({ paName: "ana", exposureMode: "weird", onboarded: 1, bogus: true })
  expect(c.paName).toBe("ana")
  expect(c.exposureMode).toBe("local") // invalid enum → safe default
  expect(c.onboarded).toBe(true)
  expect((c as any).bogus).toBeUndefined()
})

test("parseAppConfig uses base for unspecified fields", () => {
  const base = { ...defaultAppConfig, paName: "kept", onboarded: true }
  const c = parseAppConfig({ paWorkdir: "/w" }, base)
  expect(c.paName).toBe("kept")
  expect(c.onboarded).toBe(true)
  expect(c.paWorkdir).toBe("/w")
})

const ENV = {
  MUX_PA_NAME: "envpa",
  MUX_PA_WORKDIR: "/env/wd",
  MUX_TELEGRAM_BOT_TOKEN: "111:env",
  MUX_WEB_PUBLIC_URL: "http://localhost:8787",
  MUX_WEB_PORT: "8787",
}

test("resolve: env seeds defaults when store empty", () => {
  const c = resolveAppConfig({}, ENV)
  expect(c.paName).toBe("envpa")
  expect(c.paWorkdir).toBe("/env/wd")
  expect(c.telegramBotToken).toBe("111:env")
  expect(c.webPublicUrl).toBe("http://localhost:8787")
})

test("resolve: stored non-empty value wins over env", () => {
  const c = resolveAppConfig({ paName: "storepa", telegramBotToken: "222:store" }, ENV)
  expect(c.paName).toBe("storepa")
  expect(c.telegramBotToken).toBe("222:store")
  expect(c.paWorkdir).toBe("/env/wd") // not stored → env
})

test("resolve: empty string in store does NOT override env (treated as unset)", () => {
  const c = resolveAppConfig({ telegramBotToken: "" }, ENV)
  expect(c.telegramBotToken).toBe("111:env")
})

test("resolve: falls back to built-in default when neither store nor env set", () => {
  const c = resolveAppConfig({}, {})
  expect(c.paName).toBe("assistant")
  expect(c.exposureMode).toBe("local")
})

test("resolve: onboarded comes only from store (no env), default false", () => {
  expect(resolveAppConfig({}, ENV).onboarded).toBe(false)
  expect(resolveAppConfig({ onboarded: true }, ENV).onboarded).toBe(true)
})

test("sanitizeAppConfigPatch keeps only present known keys, no defaults", () => {
  const p = sanitizeAppConfigPatch({ paName: "x", bogus: 1, exposureMode: "weird" })
  expect(p).toEqual({ paName: "x" }) // unknown dropped, invalid enum dropped, nothing defaulted
  expect("onboarded" in p).toBe(false)
})

test("sanitizeAppConfigPatch coerces onboarded + validates enum", () => {
  expect(sanitizeAppConfigPatch({ onboarded: 1, exposureMode: "public" })).toEqual({ onboarded: true, exposureMode: "public" })
})

import { redactAppConfig, credentialEnvVars, hydrateCredentialEnv, applyCredentialEnv, SECRET_FIELDS } from "../src/core/settings/app-config"

test("credential fields default to empty and round-trip through parse/sanitize", () => {
  expect(defaultAppConfig.claudeOauthToken).toBe("")
  expect(parseAppConfig({ codexApiKey: "ck" }).codexApiKey).toBe("ck")
  expect(sanitizeAppConfigPatch({ cursorApiKey: "xk", bogus: 1 })).toEqual({ cursorApiKey: "xk" })
  expect(resolveAppConfig({ anthropicApiKey: "ak" }, {}).anthropicApiKey).toBe("ak")
})

test("redactAppConfig strips every secret and adds *Configured booleans", () => {
  const cfg = { ...defaultAppConfig, telegramBotToken: "t", claudeOauthToken: "c", anthropicApiKey: "", codexApiKey: "k", cursorApiKey: "", paName: "ana" }
  const r = redactAppConfig(cfg) as Record<string, unknown>
  for (const f of SECRET_FIELDS) expect(r[f]).toBeUndefined()
  expect(r.paName).toBe("ana")
  expect(r.telegramConfigured).toBe(true)
  expect(r.claudeConfigured).toBe(true)
  expect(r.anthropicConfigured).toBe(false)
  expect(r.codexConfigured).toBe(true)
  expect(r.cursorConfigured).toBe(false)
})

test("credentialEnvVars maps only non-empty creds to the CLI env-var names", () => {
  const cfg = { ...defaultAppConfig, claudeOauthToken: "c", codexApiKey: "k" }
  expect(credentialEnvVars(cfg)).toEqual({ CLAUDE_CODE_OAUTH_TOKEN: "c", OPENAI_API_KEY: "k" })
})

test("hydrateCredentialEnv sets unset vars but never clobbers an existing one", () => {
  const cfg = { ...defaultAppConfig, codexApiKey: "store", cursorApiKey: "xk" }
  const env: Record<string, string | undefined> = { OPENAI_API_KEY: "already" }
  const applied = hydrateCredentialEnv(cfg, env)
  expect(env.OPENAI_API_KEY).toBe("already")
  expect(env.CURSOR_API_KEY).toBe("xk")
  expect(applied).toEqual(["CURSOR_API_KEY"])
})

test("applyCredentialEnv overwrites existing vars (store is authoritative on PUT)", () => {
  const cfg = { ...defaultAppConfig, codexApiKey: "new", cursorApiKey: "" }
  const env: Record<string, string | undefined> = { OPENAI_API_KEY: "old", CURSOR_API_KEY: "shellset" }
  const applied = applyCredentialEnv(cfg, env)
  expect(env.OPENAI_API_KEY).toBe("new")        // clobbered — store wins on explicit PUT
  expect(env.CURSOR_API_KEY).toBe("shellset")    // unset in store ⇒ not applied ⇒ shell var untouched
  expect(applied).toEqual(["OPENAI_API_KEY"])
})
