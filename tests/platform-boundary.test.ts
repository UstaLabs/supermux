// The platform guard over the whole repository (scripts/platform-boundary.ts has the rules and why). A count
// that rises is a new OS decision made in place; a count that falls means the baseline should be lowered:
//   bun scripts/platform-boundary.ts --write
import { expect, test } from "bun:test"
import { compare, countHits, readBaseline, RULES, scanRepo, scanText, type Change, type Hit } from "../scripts/platform-boundary"

const hits = scanRepo()
const drift = compare(countHits(hits), readBaseline())
const use = new Map(RULES.map(rule => [rule.id, rule.use]))

function explain(change: Change): string {
  const lines = hits.filter((h: Hit) => h.rule === change.rule && h.file === change.file).map(h => `    ${h.file}:${h.line}  ${h.text}`)
  return [`${change.rule}: ${change.file} ${change.was} → ${change.now}. Use instead: ${use.get(change.rule)}`, ...lines].join("\n")
}

test("no new OS decision outside the platform layer", () => {
  expect(drift.rose.map(explain)).toEqual([])
})

test("the baseline only shrinks: lower it when a count falls (bun scripts/platform-boundary.ts --write)", () => {
  expect(drift.fell.map(c => `${c.rule}: ${c.file} ${c.was} → ${c.now}`)).toEqual([])
})

test("every platform-ok marker gives its reason", () => {
  expect(hits.filter(h => h.rule === "platform-ok").map(h => `${h.file}:${h.line}  ${h.text}`)).toEqual([])
})

test("a user agent a library sets is no OS check; reading navigator.userAgent is (#122)", () => {
  const kt = RULES.filter(rule => rule.id === "os-check-kt")
  expect(scanText("apps/x/A.kt", "    public val userAgent: String? = null,", kt)).toEqual([])
  expect(scanText("apps/x/A.kt", "        userAgent = setup.agent(baseUserAgent),", kt)).toEqual([])
  expect(scanText("apps/x/A.kt", '    js("/Mac/.test(navigator.userAgent)")', kt).map(h => h.rule)).toEqual(["os-check-kt"])
})

// #129, Ahmet: the broker never raises a Keychain or other OS security prompt. Reading the macOS Keychain (the
// security tool's find-/dump- commands, Bun.secrets, the Security framework's SecItem/SecKeychain calls) can, whatever
// a given item's ACL says today, so no broker code may do it; only the platform layer's Windows-gated Bun.secrets read.
test("no broker code reads the macOS Keychain (#129: never a security prompt)", () => {
  const keychain = RULES.filter(rule => rule.id === "keychain")
  expect(keychain.length).toBe(1)
  const caught = (text: string) => scanText("src/core/x.ts", text, keychain).map(h => h.rule)
  expect(caught(`spawn("/usr/bin/security", ["find-generic-password", "-s", svc, "-w"])`)).toEqual(["keychain"])
  expect(caught(`await $\`security dump-keychain -a\``)).toEqual(["keychain"])
  expect(caught(`const v = await Bun.secrets.get({ service, name })`)).toEqual(["keychain"])
  expect(caught(`const fn = lib.symbols.SecItemCopyMatching`)).toEqual(["keychain"])
  expect(caught(`// the system's security settings`)).toEqual([])
  expect(hits.filter(h => h.rule === "keychain").map(h => `${h.file}:${h.line}`)).toEqual([])
})
