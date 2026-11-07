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
test("normalize accepts a Windows broker's paths, with one separator and an upper-case drive", () => {
  expect(normalizeLocationPath("D:\\Projects\\TunnelWho")).toBe("D:\\Projects\\TunnelWho")
  // git prints C:/…; a shell may hand over a lower-case drive. Both name the same folder.
  expect(normalizeLocationPath("C:/Users/hatic/app/")).toBe("C:\\Users\\hatic\\app")
  expect(normalizeLocationPath("d:\\a\\\\.\\b\\..\\c\\")).toBe("D:\\a\\c")
  expect(normalizeLocationPath("C:\\")).toBe("C:\\")
  expect(normalizeLocationPath("\\\\nas\\share\\proj\\")).toBe("\\\\nas\\share\\proj")
})
test("normalize rejects a Windows path that depends on the current drive or folder", () => {
  expect(normalizeLocationPath("C:proj")).toBeUndefined()
  expect(normalizeLocationPath("\\proj")).toBeUndefined()
  expect(normalizeLocationPath("proj\\x")).toBeUndefined()
})
test("effectiveLocation prefers repo_root", () => {
  expect(effectiveLocation({ workdir: "/w/tree", repo_root: "/r" })).toBe("/r")
  expect(effectiveLocation({ workdir: "/w/", repo_root: null })).toBe("/w")
})
test("effectiveLocation leaves a bare managed worktree unresolved", () => {
  expect(effectiveLocation({ workdir: "/h/.mux/worktrees/x/y" }, "/h/.mux/worktrees")).toBeUndefined()
  expect(effectiveLocation({ workdir: "/h/.mux/worktrees/x/y", repo_root: "/r" }, "/h/.mux/worktrees")).toBe("/r")
})
test("effectiveLocation leaves a bare managed worktree unresolved on Windows", () => {
  const root = "C:\\Users\\h\\.mux\\worktrees"
  expect(effectiveLocation({ workdir: "C:\\Users\\h\\.mux\\worktrees\\x\\y" }, root)).toBeUndefined()
  expect(effectiveLocation({ workdir: root }, root)).toBeUndefined()
  expect(effectiveLocation({ workdir: "C:\\Users\\h\\.mux\\worktrees\\x\\y", repo_root: "D:\\Projects\\TunnelWho" }, root))
    .toBe("D:\\Projects\\TunnelWho")
  // A sibling that only shares the prefix is not inside the root.
  expect(effectiveLocation({ workdir: "C:\\Users\\h\\.mux\\worktrees-old\\x" }, root)).toBe("C:\\Users\\h\\.mux\\worktrees-old\\x")
})
test("effectiveLocation treats a root '/' managed worktrees root as covering every path", () => {
  expect(effectiveLocation({ workdir: "/a/b" }, "/")).toBeUndefined()
  expect(effectiveLocation({ workdir: "/" }, "/")).toBeUndefined()
  // repo_root gets the same treatment: a root '/' managed root covers it too.
  expect(effectiveLocation({ workdir: "/a/b", repo_root: "/r" }, "/")).toBeUndefined()
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
test("effectiveLocation excludes a repo_root that is itself under the managed worktrees root", () => {
  // A session spawned inside a managed worktree can record that same worktree dir
  // as repo_root (rather than the real repo checkout). The managed-root exclusion
  // must apply to repo_root the same way it applies to a bare workdir.
  expect(effectiveLocation(
    { workdir: "/h/.mux/worktrees/x/y", repo_root: "/h/.mux/worktrees/x/y" },
    "/h/.mux/worktrees",
  )).toBeUndefined()
  expect(effectiveLocation(
    { workdir: "/irrelevant", repo_root: "/h/.mux/worktrees/x" },
    "/h/.mux/worktrees",
  )).toBeUndefined()
  // The root itself, not just a child under it.
  expect(effectiveLocation(
    { workdir: "/irrelevant", repo_root: "/h/.mux/worktrees" },
    "/h/.mux/worktrees",
  )).toBeUndefined()
  // A repo_root outside the managed root still resolves normally.
  expect(effectiveLocation(
    { workdir: "/h/.mux/worktrees/x/y", repo_root: "/h/projects/app" },
    "/h/.mux/worktrees",
  )).toBe("/h/projects/app")
})
test("pathLabel is the last path segment", () => {
  expect(pathLabel("/home/u", "/home/u")).toBe("~")
  expect(pathLabel("/home/u/app", "/home/u")).toBe("app")
  expect(pathLabel("/home/u/projects/app", "/home/u")).toBe("app")
  expect(pathLabel("/srv/app", "/home/u")).toBe("app")
  expect(pathLabel("/app", "/home/u")).toBe("app")
  expect(pathLabel("/", "/home/u")).toBe("/")
})
test("pathLabel names a Windows path by its folder", () => {
  expect(pathLabel("D:\\Projects\\TunnelWho", "C:\\Users\\hatic")).toBe("TunnelWho")
  expect(pathLabel("C:\\Users\\hatic\\.mux\\state\\projects\\github.com\\yasinemre\\erydor", "C:\\Users\\hatic")).toBe("erydor")
  expect(pathLabel("C:\\Users\\hatic", "C:\\Users\\hatic")).toBe("~")
  expect(pathLabel("D:\\", "C:\\Users\\hatic")).toBe("D:\\")
})
