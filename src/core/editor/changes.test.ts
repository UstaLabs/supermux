import { describe, test, expect } from "bun:test"
import { parseRawNumstat } from "./changes"

const Z = "0000000000000000000000000000000000000000"
const A = "a".repeat(40)
const B = "b".repeat(40)

describe("parseRawNumstat", () => {
  test("modify, add, delete, rename, binary, typechange", () => {
    const raw = [
      `:100644 100644 ${A} ${Z} M`, "src/a.ts",
      `:000000 100644 ${Z} ${Z} A`, "src/new.ts",
      `:100644 000000 ${B} ${Z} D`, "gone.txt",
      `:100644 100644 ${A} ${Z} R087`, "old name.ts", "new name.ts",
      `:100644 100644 ${B} ${Z} M`, "img/logo.png",
      `:100644 120000 ${A} ${Z} T`, "link",
    ].join("\0") + "\0"
    const numstat = [
      "12\t3\tsrc/a.ts",
      "40\t0\tsrc/new.ts",
      "0\t7\tgone.txt",
      "1\t1\t", "old name.ts", "new name.ts",
      "-\t-\timg/logo.png",
      "1\t1\tlink",
    ].join("\0") + "\0"

    expect(parseRawNumstat(raw, numstat)).toEqual([
      { path: "src/a.ts", status: "modified", added: 12, removed: 3, binary: false, oldPath: null, baseBlob: A },
      { path: "src/new.ts", status: "added", added: 40, removed: 0, binary: false, oldPath: null, baseBlob: null },
      { path: "gone.txt", status: "deleted", added: 0, removed: 7, binary: false, oldPath: null, baseBlob: B },
      { path: "new name.ts", status: "renamed", added: 1, removed: 1, binary: false, oldPath: "old name.ts", baseBlob: A },
      { path: "img/logo.png", status: "modified", added: null, removed: null, binary: true, oldPath: null, baseBlob: B },
      { path: "link", status: "typechange", added: 1, removed: 1, binary: false, oldPath: null, baseBlob: A },
    ])
  })

  test("empty output is an empty list", () => {
    expect(parseRawNumstat("", "")).toEqual([])
  })

  test("a path missing from numstat keeps null counts", () => {
    const raw = `:100644 100644 ${A} ${Z} M\0x.ts\0`
    expect(parseRawNumstat(raw, "")[0]).toMatchObject({ path: "x.ts", added: null, removed: null, binary: false })
  })
})
