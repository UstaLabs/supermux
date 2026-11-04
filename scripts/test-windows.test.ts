import { describe, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { dirname, join } from "node:path"
import {
  chunk,
  discoverTestFiles,
  failed,
  normalizeTestPath,
  parseJunit,
  parseQuarantine,
  quarantineFromReport,
  resultsForChunk,
  verdict,
  type FileResult,
} from "./test-windows"

// What bun 1.3.14 writes with --reporter=junit (trimmed): one <testsuite> per file that LOADED. A file that threw
// while loading gets no suite at all, which is why resultsForChunk calls it "unreported". The second suite is
// spelled the Windows way.
const JUNIT = `<?xml version="1.0" encoding="UTF-8"?>
<testsuites name="bun test" tests="3" assertions="2" failures="1" skipped="0" time="0.5">
  <testsuite name="a/ok.test.ts" file="a/ok.test.ts" tests="1" assertions="1" failures="0" skipped="0" time="0.009">
    <testcase name="passes" classname="" time="0.009" file="a/ok.test.ts" line="2" assertions="1" />
  </testsuite>
  <testsuite name="a\\bad &amp; worse.test.ts" file="a\\bad &amp; worse.test.ts" tests="2" assertions="1" failures="1" skipped="0" time="0.009">
    <testcase name="fails" classname="" time="0.009" file="a\\bad &amp; worse.test.ts" line="2" assertions="1"><failure type="AssertionError" /></testcase>
  </testsuite>
</testsuites>`

describe("normalizeTestPath", () => {
  test("one spelling: forward slashes, no leading ./", () => {
    expect(normalizeTestPath(".\\src\\core\\a.test.ts")).toBe("src/core/a.test.ts")
    expect(normalizeTestPath("./tests/b.test.ts")).toBe("tests/b.test.ts")
  })
})

describe("parseQuarantine", () => {
  test("reads file, reason, card and the optional flaky mark; skips comments and blanks", () => {
    const text = "# header\n\nsrc\\a.test.ts | uses /bin/sh | #29\r\nsrc/b.test.ts | timing | #29 | flaky\n"
    expect(parseQuarantine(text)).toEqual([
      { file: "src/a.test.ts", reason: "uses /bin/sh", card: "#29", flaky: false },
      { file: "src/b.test.ts", reason: "timing", card: "#29", flaky: true },
    ])
  })

  test("a line without a reason and a card is an error, with its line number", () => {
    expect(() => parseQuarantine("# c\nsrc/a.test.ts | no card")).toThrow(/line 2/)
  })

  test("the only fourth field is 'flaky'", () => {
    expect(() => parseQuarantine("a.test.ts | r | #29 | sometimes")).toThrow(/line 1/)
  })

  test("a file listed twice is an error", () => {
    expect(() => parseQuarantine("a.test.ts | r | #29\n./a.test.ts | r | #29")).toThrow(/twice/)
  })
})

describe("parseJunit", () => {
  test("counts per file, in one spelling, with XML entities decoded", () => {
    const suites = parseJunit(JUNIT)
    expect(suites.size).toBe(2)
    expect(suites.get("a/ok.test.ts")).toEqual({ tests: 1, failures: 0, errors: 0 })
    expect(suites.get("a/bad & worse.test.ts")).toEqual({ tests: 2, failures: 1, errors: 0 })
  })
})

describe("resultsForChunk", () => {
  test("passed, failed, and unreported for a file the report leaves out", () => {
    const files = ["a/ok.test.ts", "a/bad & worse.test.ts", "a/boom.test.ts"]
    expect(resultsForChunk(files, parseJunit(JUNIT))).toEqual([
      { file: "a/ok.test.ts", status: "passed" },
      { file: "a/bad & worse.test.ts", status: "failed" },
      { file: "a/boom.test.ts", status: "unreported" },
    ])
  })
})

describe("verdict", () => {
  const quarantine = parseQuarantine(
    "a/known.test.ts | r | #29\na/fixed.test.ts | r | #29\na/flaky.test.ts | r | #29 | flaky\na/gone.test.ts | r | #29",
  )
  const results: FileResult[] = [
    { file: "a/new.test.ts", status: "failed" },
    { file: "a/known.test.ts", status: "failed" },
    { file: "a/fixed.test.ts", status: "passed" },
    { file: "a/flaky.test.ts", status: "passed" },
    { file: "a/ok.test.ts", status: "passed" },
  ]

  test("a new failure, a quarantined file that now passes, and a stale entry all fail the run", () => {
    const v = verdict(results, quarantine, results.map(r => r.file))
    expect(v).toEqual({
      newFailures: ["a/new.test.ts"],
      nowPassing: ["a/fixed.test.ts"],
      staleEntries: ["a/gone.test.ts"],
      quarantinedFailing: ["a/known.test.ts"],
    })
    expect(failed(v)).toBe(true)
  })

  test("only quarantined failures: the run passes", () => {
    const only = parseQuarantine("a/known.test.ts | r | #29")
    expect(failed(verdict([{ file: "a/known.test.ts", status: "failed" }], only, ["a/known.test.ts"]))).toBe(false)
  })
})

describe("chunk", () => {
  test("splits in order and rejects a size below 1", () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]])
    expect(() => chunk([1], 0)).toThrow()
  })
})

describe("quarantineFromReport", () => {
  test("keeps known reasons, adds new failures, drops fixed files, keeps flaky ones; round-trips", () => {
    const existing = parseQuarantine("a/known.test.ts | uses sh | #29\na/fixed.test.ts | r | #29\na/flaky.test.ts | r | #29 | flaky")
    const text = quarantineFromReport(
      [
        { file: "a/known.test.ts", status: "failed" },
        { file: "a/new.test.ts", status: "failed" },
        { file: "a/fixed.test.ts", status: "passed" },
        { file: "a/flaky.test.ts", status: "passed" },
      ],
      existing,
      "2026-11-04",
    )
    expect(text.startsWith("#")).toBe(true)
    expect(parseQuarantine(text)).toEqual([
      { file: "a/flaky.test.ts", reason: "r", card: "#29", flaky: true },
      { file: "a/known.test.ts", reason: "uses sh", card: "#29", flaky: false },
      { file: "a/new.test.ts", reason: "fails on Windows (first seen 2026-11-04)", card: "#29", flaky: false },
    ])
  })
})

describe("discoverTestFiles", () => {
  test("bun test's names under the four roots, never inside node_modules, sorted", () => {
    const root = mkdtempSync(join(tmpdir(), "mux-discover-"))
    try {
      for (const file of [
        "src/a.test.ts", "src/b.ts", "src/node_modules/x/c.test.ts", "tests/d.spec.ts",
        "tests/e_test.ts", "scripts/f.test.mjs", "apps/g.test.ts",
      ]) {
        mkdirSync(join(root, dirname(file)), { recursive: true })
        writeFileSync(join(root, file), "")
      }
      expect(discoverTestFiles(root)).toEqual(["src/a.test.ts", "tests/d.spec.ts", "tests/e_test.ts"])
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  })
})
