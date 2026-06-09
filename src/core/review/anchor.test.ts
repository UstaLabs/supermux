// src/core/review/anchor.test.ts
import { test, expect } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { reanchor } from "./anchor"

function tmpRepo(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-anch-"))
  execFileSync("git", ["init", dir]); execFileSync("git", ["-C", dir, "config", "user.email", "t@t.t"]); execFileSync("git", ["-C", dir, "config", "user.name", "t"])
  return dir
}

test("blob-sha match keeps the original line", () => {
  const dir = tmpRepo()
  writeFileSync(join(dir, "a.ts"), "l1\nl2\nl3\n")
  const sha = execFileSync("git", ["-C", dir, "hash-object", "a.ts"], { encoding: "utf-8" }).trim()
  expect(reanchor(dir, { path: "a.ts", anchorLine: 2, anchorContext: "l2", headBlobSha: sha })).toEqual({ currentLine: 2, outdated: false })
})

test("text search finds a moved line", () => {
  const dir = tmpRepo()
  writeFileSync(join(dir, "a.ts"), "new0\nl1\nl2\nTARGET\n") // TARGET moved from line 2 to line 4
  expect(reanchor(dir, { path: "a.ts", anchorLine: 2, anchorContext: "TARGET", headBlobSha: "stale" })).toEqual({ currentLine: 4, outdated: false })
})

test("deleted line → outdated", () => {
  const dir = tmpRepo()
  writeFileSync(join(dir, "a.ts"), "l1\nl3\n")
  expect(reanchor(dir, { path: "a.ts", anchorLine: 2, anchorContext: "GONE", headBlobSha: "stale" })).toEqual({ currentLine: null, outdated: true })
})

test("missing file → outdated", () => {
  const dir = tmpRepo()
  expect(reanchor(dir, { path: "nope.ts", anchorLine: 1, anchorContext: "x", headBlobSha: "s" })).toEqual({ currentLine: null, outdated: true })
})
