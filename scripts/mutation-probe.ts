#!/usr/bin/env bun
/**
 * Which tests actually assert anything?
 *
 *   bun scripts/mutation-probe.ts [limit] [--all]
 *
 * Break a source file in a small, deliberate way, run its sibling test file, and
 * see whether the suite notices. A mutant that SURVIVES means those tests say
 * nothing about that behaviour — the objective version of "this test is
 * decoration", instead of judging by how a test looks.
 *
 * Counting tests, or eyeballing them for shape, both mislead: a one-line
 * assertion on a parser is worth more than twenty on getters. The first run of
 * this probe killed 11 of 20 mutants and found that `sameOriginOk` — the CSRF
 * guard — was entirely unasserted, which no amount of reading had surfaced.
 *
 * A survivor is usually a reason to STRENGTHEN a test, not delete one. Deletion
 * is for tests that restate a one-line getter AND whose behaviour a journey
 * already covers (see tests/journeys/).
 *
 * Deliberately crude: one mutant per file, regex not AST, sibling tests only.
 * It is a smoke detector, not a coverage tool — cheap enough to actually run.
 */
import { readFileSync, writeFileSync } from "fs"
import { existsSync } from "fs"
import { Glob } from "bun"

/** Small semantic flips. Each changes behaviour a real test should care about. */
const MUTATIONS: Array<[RegExp, string, string]> = [
  [/\breturn true\b/, "return false", "return true → false"],
  [/\breturn false\b/, "return true", "return false → true"],
  [/(?<![<>=!])>=(?!=)/, ">", ">= → >"],
  [/(?<![<>=!])<=(?!=)/, "<", "<= → <"],
  [/\s&&\s/, " || ", "&& → ||"],
]

function testsPass(file: string): boolean {
  const p = Bun.spawnSync(["bun", "test", file], { stdout: "pipe", stderr: "pipe" })
  return p.exitCode === 0
}

const limit = Number(Bun.argv[2] ?? 25) || 25
const pairs: Array<[string, string]> = []
for (const t of new Glob("src/**/*.test.ts").scanSync(".")) {
  if (t.includes("node_modules")) continue
  const src = t.replace(".test.ts", ".ts")
  if (existsSync(src)) pairs.push([src, t])
}
pairs.sort()

let killed = 0
const survived: string[] = []
const skipped: string[] = []

for (const [src, test] of pairs.slice(0, limit)) {
  const original = readFileSync(src, "utf8")
  // Already-red tests can't attribute a survivor to anything meaningful.
  if (!testsPass(test)) { skipped.push(test); continue }

  for (const [pattern, replacement, label] of MUTATIONS) {
    if (!pattern.test(original)) continue
    const mutated = original.replace(pattern, replacement)
    if (mutated === original) continue

    writeFileSync(src, mutated)
    let stillGreen: boolean
    try {
      stillGreen = testsPass(test)
    } finally {
      writeFileSync(src, original) // always restore, even on throw
    }

    if (stillGreen) survived.push(`${src}  [${label}]  → ${test}`)
    else killed++
    break // one mutant per file keeps a full pass affordable
  }
}

const total = killed + survived.length
console.log(`\nkilled ${killed}/${total}  survived ${survived.length}  skipped ${skipped.length}`)
if (survived.length) {
  console.log("\nSURVIVORS — these tests do not assert the mutated behaviour:")
  for (const s of survived) console.log(`  ${s}`)
}
process.exit(0)
