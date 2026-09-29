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

test("blob-sha match keeps the original line", async () => {
  const dir = tmpRepo()
  writeFileSync(join(dir, "a.ts"), "l1\nl2\nl3\n")
  const sha = execFileSync("git", ["-C", dir, "hash-object", "a.ts"], { encoding: "utf-8" }).trim()
  expect(await reanchor(dir, { path: "a.ts", anchorLine: 2, anchorContext: "l2", headBlobSha: sha })).toEqual({ currentLine: 2, outdated: false })
})

test("text search finds a moved line", async () => {
  const dir = tmpRepo()
  writeFileSync(join(dir, "a.ts"), "new0\nl1\nl2\nTARGET\n") // TARGET moved from line 2 to line 4
  expect(await reanchor(dir, { path: "a.ts", anchorLine: 2, anchorContext: "TARGET", headBlobSha: "stale" })).toEqual({ currentLine: 4, outdated: false })
})

test("deleted line → outdated", async () => {
  const dir = tmpRepo()
  writeFileSync(join(dir, "a.ts"), "l1\nl3\n")
  expect(await reanchor(dir, { path: "a.ts", anchorLine: 2, anchorContext: "GONE", headBlobSha: "stale" })).toEqual({ currentLine: null, outdated: true })
})

test("missing file → outdated", async () => {
  const dir = tmpRepo()
  expect(await reanchor(dir, { path: "nope.ts", anchorLine: 1, anchorContext: "x", headBlobSha: "s" })).toEqual({ currentLine: null, outdated: true })
})

test("text search matches a CRLF file against a \\n-normalised context", async () => {
  const dir = tmpRepo()
  writeFileSync(join(dir, "a.ts"), "new0\r\nl1\r\nTARGET\r\n")
  expect(await reanchor(dir, { path: "a.ts", anchorLine: 2, anchorContext: "TARGET", headBlobSha: "stale" })).toEqual({ currentLine: 3, outdated: false })
})
