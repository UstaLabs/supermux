// #123: the broker's own words, English and Turkish. Every language has exactly the English keys and placeholders.
import { expect, test } from "bun:test"
import { brokerLang, t, MESSAGES, type BrokerLang } from "./messages"

const placeholders = (s: string) => [...s.matchAll(/\{(\w+)\}/g)].map((m) => m[1]).sort()

test("every language has exactly the English keys, with the same placeholders", () => {
  const en = MESSAGES.en
  for (const lang of Object.keys(MESSAGES) as BrokerLang[]) {
    const table = MESSAGES[lang] as Record<string, string>
    expect(Object.keys(table).sort()).toEqual(Object.keys(en).sort())
    for (const [key, text] of Object.entries(en)) {
      expect({ lang, key, vars: placeholders(table[key]!) }).toEqual({ lang, key, vars: placeholders(text) })
      expect(table[key]!.trim().length).toBeGreaterThan(0)
    }
  }
})

test("brokerLang takes a Turkish tag in any shape and gives English for anything else", () => {
  expect(brokerLang("tr")).toBe("tr")
  expect(brokerLang("tr-TR")).toBe("tr")
  expect(brokerLang("TR")).toBe("tr")
  expect(brokerLang("tr_TR")).toBe("tr")
  expect(brokerLang("en-US")).toBe("en")
  expect(brokerLang("de")).toBe("en")
  expect(brokerLang("")).toBe("en")
  expect(brokerLang(null)).toBe("en")
  expect(brokerLang(undefined)).toBe("en")
  expect(brokerLang("trx")).toBe("en")
})

test("t fills placeholders and keeps an unknown one as written", () => {
  expect(t("en", "cmd.no_such_session", { name: "zoom" })).toBe("no such session: zoom")
  expect(t("tr", "cmd.no_such_session", { name: "zoom" })).toBe("Böyle bir oturum yok: zoom")
  expect(t("en", "cmd.no_such_session")).toBe("no such session: {name}")
})

test("a placeholder value is never re-read as a placeholder", () => {
  expect(t("en", "cmd.no_such_session", { name: "{name}" })).toBe("no such session: {name}")
})
