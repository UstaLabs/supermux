import { expect, test } from "bun:test"
import { parseDiffLines, diffStats } from "./diff-lines"

test("parseDiffLines colors unified hunks", () => {
  const lines = parseDiffLines("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n context")
  expect(lines.map((l) => l.type)).toEqual(["meta", "meta", "hunk", "del", "add", "ctx"])
  expect(lines[3]!.content).toBe("old")
  expect(lines[4]!.content).toBe("new")
})

test("diffStats counts adds and deletes", () => {
  expect(diffStats("@@\n-a\n-b\n+c\n")).toEqual({ added: 1, deleted: 2 })
})
