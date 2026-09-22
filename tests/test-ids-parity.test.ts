// The test-id files are hand-mirrored across languages, so the only thing
// stopping them from drifting is this test. A journey that looks for
// "composer-input" on web and finds "chat_composer" on Android is not a portable
// journey, and the drift is invisible until a device lane goes red for a reason
// that has nothing to do with the change under test.
import { test, expect } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"
import { TEST_IDS, chatMessageId, sessionRowId } from "../src/shared/test-ids"

const ROOT = join(import.meta.dir, "..")
// Kotlin is the only remaining mirror: the SwiftUI app (and its TestIds.swift)
// was retired in f185d64a, leaving Compose as the sole iOS/macOS client.
const KOTLIN = join(ROOT, "apps/shared/src/commonMain/kotlin/dev/supermux/ui/TestIds.kt")

/** Every double-quoted kebab-case literal in a mirror file. */
function literalsIn(path: string): Set<string> {
  const src = readFileSync(path, "utf8")
  const out = new Set<string>()
  for (const m of src.matchAll(/"([a-z][a-z0-9]*(?:-[a-z0-9]+)+)"/g)) out.add(m[1]!)
  return out
}

const canonical = new Set<string>(Object.values(TEST_IDS))

test("the canonical vocabulary is non-empty and kebab-case", () => {
  expect(canonical.size).toBeGreaterThan(0)
  for (const id of canonical) expect(id).toMatch(/^[a-z][a-z0-9]*(-[a-z0-9]+)+$/)
})

test("TestIds.kt mirrors src/shared/test-ids.ts exactly", () => {
  expect([...literalsIn(KOTLIN)].sort()).toEqual([...canonical].sort())
})

test("the per-row id keeps the shared prefix so one selector matches every client", () => {
  expect(sessionRowId("abc123")).toBe("session-row:abc123")
  expect(sessionRowId("abc123").startsWith(`${TEST_IDS.sessionRow}:`)).toBe(true)
})

// The vocabulary is only worth anything if the SCREENS use it. These pin the
// four journey-critical call sites in `:ui` to the shared constants rather than
// to hand-typed literals — the failure this catches is someone "fixing" a tag by
// editing the screen and leaving the mirrors (and every other client) behind.
// Compose is the only renderer left, so these are Kotlin paths; the Vue PWA that
// used to be asserted here is retired.
test("the Compose screens actually use the canonical constants", () => {
  const sites: Array<[string, string[]]> = [
    ["apps/ui/src/commonMain/kotlin/dev/supermux/ui/session/SessionListScreen.kt", ["TestIds.SESSION_LIST"]],
    ["apps/ui/src/commonMain/kotlin/dev/supermux/ui/session/SessionRow.kt", ["TestIds.sessionRow("]],
    ["apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Timeline.kt", ["TestIds.chatMessage("]],
    ["apps/ui/src/commonMain/kotlin/dev/supermux/ui/chat/Composer.kt", ["TestIds.COMPOSER_SEND", "TestIds.COMPOSER_INPUT"]],
  ]
  for (const [path, needles] of sites) {
    const src = readFileSync(join(ROOT, path), "utf8")
    for (const needle of needles) expect(`${path} :: ${src.includes(needle)}`).toBe(`${path} :: true`)
  }
})

// `chat-message` is a PREFIX, not an id: the web journeys select the agent's
// reply with `[id^="chat-message:outbound:"]`, so the shape of the suffix is part
// of the contract and not an implementation detail of the Kotlin helper.
test("the per-message id keeps the direction between the prefix and the message id", () => {
  expect(chatMessageId("outbound", "m17")).toBe("chat-message:outbound:m17")
  expect(chatMessageId("outbound", "m17").startsWith(`${TEST_IDS.chatMessage}:`)).toBe(true)
})
