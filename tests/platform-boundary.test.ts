// The platform guard over the whole repository (scripts/platform-boundary.ts has the rules and why). A count
// that rises is a new OS decision made in place; a count that falls means the baseline should be lowered:
//   bun scripts/platform-boundary.ts --write
import { expect, test } from "bun:test"
import { compare, countHits, readBaseline, RULES, scanRepo, type Change, type Hit } from "../scripts/platform-boundary"

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
