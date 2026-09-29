// The FsService class this file used to cover was replaced by the host FileSystemService
// (src/core/fs, tests alongside) and its legacy adapter WorkdirFs (src/channels/web/workspace-fs.test.ts).
import { test, expect, describe } from "bun:test"
import { parseDiff } from "../src/core/editor/fs-service"

describe("parseDiff flags", () => {
  test("flags binary files", () => {
    const raw = `diff --git a/img.png b/img.png
index 1234..5678 100644
Binary files a/img.png and b/img.png differ
`
    const entries = parseDiff(raw)
    expect(entries.length).toBe(1)
    expect(entries[0]!.binary).toBe(true)
  })

  test("flags mode-only changes", () => {
    const raw = `diff --git a/script.sh b/script.sh
old mode 100644
new mode 100755
`
    const entries = parseDiff(raw)
    expect(entries.length).toBe(1)
    expect(entries[0]!.modeChange).toBe(true)
  })
})
