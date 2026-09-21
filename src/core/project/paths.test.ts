import { test, expect } from "bun:test"
import { normalizeLocationPath, effectiveLocation, pathLabel } from "./paths"

test("normalize removes redundant separators, dot segments and trailing slash, keeps case", () => {
  expect(normalizeLocationPath("/home/u//Proj/./a/../b/")).toBe("/home/u/Proj/b")
  expect(normalizeLocationPath("/")).toBe("/")
})
test("normalize rejects a relative path", () => {
  expect(normalizeLocationPath("rel/x")).toBeUndefined()
  expect(normalizeLocationPath("")).toBeUndefined()
})
test("effectiveLocation prefers repo_root", () => {
  expect(effectiveLocation({ workdir: "/w/tree", repo_root: "/r" })).toBe("/r")
  expect(effectiveLocation({ workdir: "/w/", repo_root: null })).toBe("/w")
})
test("effectiveLocation leaves a bare managed worktree unresolved", () => {
  expect(effectiveLocation({ workdir: "/h/.mux/worktrees/x/y" }, "/h/.mux/worktrees")).toBeUndefined()
  expect(effectiveLocation({ workdir: "/h/.mux/worktrees/x/y", repo_root: "/r" }, "/h/.mux/worktrees")).toBe("/r")
})
test("effectiveLocation treats a root '/' managed worktrees root as covering every path", () => {
  expect(effectiveLocation({ workdir: "/a/b" }, "/")).toBeUndefined()
  expect(effectiveLocation({ workdir: "/" }, "/")).toBeUndefined()
  expect(effectiveLocation({ workdir: "/a/b", repo_root: "/r" }, "/")).toBe("/r")
})
test("effectiveLocation expands a legacy literal '~' / '~/' against home before normalizing", () => {
  expect(effectiveLocation({ workdir: "~/projects/claudemux" }, undefined, "/home/u")).toBe("/home/u/projects/claudemux")
  expect(effectiveLocation({ workdir: "~" }, undefined, "/home/u")).toBe("/home/u")
  // repo_root takes the same treatment.
  expect(effectiveLocation({ workdir: "/ignored", repo_root: "~/app" }, undefined, "/home/u")).toBe("/home/u/app")
})
test("effectiveLocation leaves other odd spellings of '~' alone", () => {
  expect(effectiveLocation({ workdir: "/home/u/~/x" }, undefined, "/home/u")).toBe("/home/u/~/x")
})
test("effectiveLocation without home leaves a literal '~' untouched (still rejected as non-absolute)", () => {
  expect(effectiveLocation({ workdir: "~/app" })).toBeUndefined()
})
test("pathLabel matches the Kotlin formatWorkdir convention", () => {
  expect(pathLabel("/home/u", "/home/u")).toBe("~")
  expect(pathLabel("/home/u/app", "/home/u")).toBe("~/app")
  expect(pathLabel("/home/u/projects/app", "/home/u")).toBe("…/projects/app")
  expect(pathLabel("/srv/app", "/home/u")).toBe("srv/app")
  expect(pathLabel("/app", "/home/u")).toBe("/app")
})
