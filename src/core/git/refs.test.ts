import { test, expect } from "bun:test"
import { branchOfRef, stripRemote } from "./refs"

test("a remote-tracking ref's branch keeps its own slashes", () => {
  expect(stripRemote("origin/main")).toBe("main")
  expect(stripRemote("upstream/feature/search-box")).toBe("feature/search-box")
  expect(stripRemote("main")).toBe("main")
})

test("a full branch ref's name", () => {
  expect(branchOfRef("refs/heads/feature/x")).toBe("feature/x")
  expect(branchOfRef("refs/tags/v1")).toBe("refs/tags/v1")
})
