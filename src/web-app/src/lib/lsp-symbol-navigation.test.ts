import { describe, expect, test } from "bun:test"
import {
  isDeclarationLocation,
  lspPositionToOffset,
  locationsAction,
  uriToWorkdirPath,
  type SymbolLocation,
} from "./lsp-symbol-navigation"
import { Text } from "@codemirror/state"

const uri = "file:///work/src/example.ts"

function location(
  targetUri = uri,
  start = { line: 3, character: 4 },
  end = { line: 3, character: 10 },
): SymbolLocation {
  return { uri: targetUri, range: { start, end } }
}

describe("isDeclarationLocation", () => {
  test("matches a click inside a same-document definition range", () => {
    expect(isDeclarationLocation(uri, { line: 3, character: 7 }, [location()])).toBe(true)
  })

  test("rejects definitions in another document or outside the range", () => {
    expect(isDeclarationLocation(uri, { line: 3, character: 11 }, [location()])).toBe(false)
    expect(isDeclarationLocation(uri, { line: 3, character: 7 }, [location("file:///work/src/other.ts")])).toBe(false)
  })
})

describe("locationsAction", () => {
  test("navigates directly for one location", () => {
    expect(locationsAction("implementation", [location()])).toEqual({
      kind: "navigate",
      location: location(),
    })
  })

  test("shows a selectable list for multiple locations", () => {
    const locations = [location(), location("file:///work/src/other.ts")]
    expect(locationsAction("implementation", locations)).toEqual({
      kind: "list",
      title: "2 implementations",
      locations,
    })
  })

  test("shows usages as a list even when there is one", () => {
    expect(locationsAction("usage", [location()], true)).toEqual({
      kind: "list",
      title: "1 usage",
      locations: [location()],
    })
  })

  test("returns none for no locations", () => {
    expect(locationsAction("definition", [])).toEqual({ kind: "none" })
  })
})

describe("uriToWorkdirPath", () => {
  test("decodes a file URI under the workdir", () => {
    expect(uriToWorkdirPath("file:///work/my%20project/src/a.ts", "/work/my project")).toBe("src/a.ts")
  })

  test("rejects locations outside the workdir", () => {
    expect(uriToWorkdirPath("file:///other/a.ts", "/work/my project")).toBeNull()
  })
})

describe("lspPositionToOffset", () => {
  const doc = Text.of(["first", "second", "third"])

  test("maps a zero-based LSP position to a document offset", () => {
    expect(lspPositionToOffset(doc, { line: 1, character: 3 })).toBe(9)
  })

  test("clamps positions past the document or line end", () => {
    expect(lspPositionToOffset(doc, { line: 99, character: 99 })).toBe(doc.length)
  })
})
