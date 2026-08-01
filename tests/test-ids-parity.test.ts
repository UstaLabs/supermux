// The three test-id files are hand-mirrored across three languages, so the only
// thing stopping them from drifting is this test. A journey that looks for
// "composer-input" on web and finds "chat_composer" on Android is not a portable
// journey, and the drift is invisible until a device lane goes red for a reason
// that has nothing to do with the change under test.
import { test, expect } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"
import { TEST_IDS, sessionRowId } from "../src/shared/test-ids"

const ROOT = join(import.meta.dir, "..")
const KOTLIN = join(ROOT, "apps/shared/src/commonMain/kotlin/dev/supermux/ui/TestIds.kt")
const SWIFT = join(ROOT, "apps/iosApp/Supermux/DesignSystem/TestIds.swift")

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

test("TestIds.swift mirrors src/shared/test-ids.ts exactly", () => {
  expect([...literalsIn(SWIFT)].sort()).toEqual([...canonical].sort())
})

test("the per-row id keeps the shared prefix so one selector matches every client", () => {
  expect(sessionRowId("abc123")).toBe("session-row:abc123")
  expect(sessionRowId("abc123").startsWith(`${TEST_IDS.sessionRow}:`)).toBe(true)
})

// The web is the one client that can carry a sibling attribute, so it uses a bare
// data-testid plus data-session-id rather than the `:<id>` suffix. Pin that the
// journey spec and the components agree on the bare name.
test("the web components actually use the canonical names", () => {
  const files = [
    "src/web-app/src/views/SessionListView.vue",
    "src/web-app/src/components/SessionRow.vue",
  ].map((p) => readFileSync(join(ROOT, p), "utf8"))
  const all = files.join("\n")
  expect(all).toContain(`data-testid="${TEST_IDS.sessionList}"`)
  expect(all).toContain(`data-testid="${TEST_IDS.sessionRow}"`)
})
