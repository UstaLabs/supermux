import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { authorSteps, formatInstantComment, newSideLines, parseLines } from "./author"

test("parseLines accepts a single line and a range", () => {
  expect(parseLines("42")).toEqual({ rangeStart: 42, rangeEnd: 42, anchorLine: 42 })
  expect(parseLines("12-40")).toEqual({ rangeStart: 12, rangeEnd: 40, anchorLine: 12 })
  expect(parseLines(undefined)).toEqual({})
})

test("parseLines rejects garbage", () => {
  expect(() => parseLines("abc")).toThrow(/invalid lines/)
})

test("newSideLines collects context and added lines from a unified hunk", () => {
  const diff = [
    "diff --git a/f.ts b/f.ts",
    "--- a/f.ts",
    "+++ b/f.ts",
    "@@ -10,3 +10,4 @@",
    " keep",
    "-old",
    "+new",
    " after",
  ].join("\n")
  const lines = newSideLines(diff)
  expect([...lines].sort((a, b) => a - b)).toEqual([10, 11, 12])
})

test("authorSteps marks a missing file not_in_diff and fills context for a hit", () => {
  const dir = mkdtempSync(join(tmpdir(), "wt-auth-"))
  try {
    writeFileSync(join(dir, "hit.ts"), "a\nb\nc\n")
    const repos = [{
      repo: "",
      files: [{
        path: "hit.ts",
        status: "modified",
        diff: "@@ -1,3 +1,3 @@\n a\n-b\n+B\n c\n",
      }],
    }]
    const { steps, results } = authorSteps(dir, repos, [
      { title: "ok", body: "m", file: "hit.ts", lines: "2" },
      { title: "miss", body: "m", file: "gone.ts", lines: "1" },
      { title: "text", body: "hello" },
    ])
    expect(results[0]!.status).toBe("ok")
    expect(steps[0]!.anchorContext).toBe("b")
    expect(results[1]!.status).toBe("not_in_diff")
    expect(results[2]!.status).toBe("ok")
    expect(steps[2]!.path).toBeUndefined()
  } finally {
    rmSync(dir, { recursive: true, force: true })
  }
})

test("formatInstantComment matches the spec template", () => {
  const text = formatInstantComment({
    id: "c1", repo: "app", path: "src/a.ts", line: 12, body: "why?", stepN: 3, stepTitle: "Login",
  })
  expect(text).toContain("💬 Walkthrough comment c1 on app/src/a.ts:12 (step 3 \"Login\"):")
  expect(text).toContain("\"why?\"")
  expect(text).toContain("comment_id=c1")
  expect(text).toContain("resolve:true")
})
