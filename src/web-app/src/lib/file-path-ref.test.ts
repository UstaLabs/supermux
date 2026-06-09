import { describe, expect, it } from "bun:test"
import {
  formatFilePathRef,
  parseFilePathRef,
  stripFilePathRefSuffix,
} from "./file-path-ref"

describe("parseFilePathRef", () => {
  it("parses single-line relative paths", () => {
    expect(parseFilePathRef("src/main.ts:105")).toEqual({
      path: "src/main.ts",
      line: 105,
    })
  })

  it("parses line ranges", () => {
    expect(parseFilePathRef("src/utils.ts:10-20")).toEqual({
      path: "src/utils.ts",
      line: 10,
      endLine: 20,
    })
  })

  it("parses bare paths without line suffix", () => {
    expect(parseFilePathRef("src/main.ts")).toEqual({ path: "src/main.ts" })
  })

  it("parses absolute paths with line suffix", () => {
    expect(parseFilePathRef("/home/user/projects/app/src/foo.ts:42")).toEqual({
      path: "/home/user/projects/app/src/foo.ts",
      line: 42,
    })
  })

  it("parses home-relative paths with line range", () => {
    expect(parseFilePathRef("~/projects/app/src/foo.ts:5-15")).toEqual({
      path: "~/projects/app/src/foo.ts",
      line: 5,
      endLine: 15,
    })
  })

  it("rejects non-numeric suffix", () => {
    expect(parseFilePathRef("file.ts:abc")).toBeNull()
  })

  it("rejects inverted ranges", () => {
    expect(parseFilePathRef("file.ts:20-10")).toBeNull()
  })
})

describe("stripFilePathRefSuffix", () => {
  it("removes single-line suffix", () => {
    expect(stripFilePathRefSuffix("src/a.ts:10")).toBe("src/a.ts")
  })

  it("removes range suffix", () => {
    expect(stripFilePathRefSuffix("/abs/path/a.ts:10-20")).toBe("/abs/path/a.ts")
  })

  it("returns bare paths unchanged", () => {
    expect(stripFilePathRefSuffix("src/a.ts")).toBe("src/a.ts")
  })
})

describe("formatFilePathRef", () => {
  it("formats bare paths", () => {
    expect(formatFilePathRef({ path: "src/main.ts" })).toBe("src/main.ts")
  })

  it("formats single-line references", () => {
    expect(formatFilePathRef({ path: "src/main.ts", line: 105 })).toBe("src/main.ts:105")
  })

  it("formats line ranges", () => {
    expect(
      formatFilePathRef({ path: "src/utils.ts", line: 10, endLine: 20 }),
    ).toBe("src/utils.ts:10-20")
  })
})
