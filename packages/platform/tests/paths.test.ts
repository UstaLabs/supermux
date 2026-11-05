import { expect, test } from "bun:test"
import { posix, win32 } from "node:path"
import { paths } from "@supermux/platform"
import windowsNames from "./windows-names.json"

// One containment rule for the broker's 19 hand-written copies (`startsWith(root + "/")`): the runtime's relative(),
// which knows the host's separators, letter case and drives. A path on another drive is NOT inside: win32.relative
// returns it as it is (absolute), which the copies took for "under the root".
test("inside on a Windows host: drives, roots, case and separators", () => {
  expect(paths.isInside("C:\\w\\src\\a.ts", "C:\\w", win32)).toBe(true)
  expect(paths.isInside("C:\\w", "C:\\w", win32)).toBe(true)
  expect(paths.isInside("c:\\W\\src", "C:\\w", win32)).toBe(true)
  expect(paths.isInside("C:\\w/src", "C:\\w\\", win32)).toBe(true)
  expect(paths.isInside("C:\\anything", "C:\\", win32)).toBe(true)
  expect(paths.isInside("D:\\w\\a.ts", "C:\\w", win32)).toBe(false)
  expect(paths.isInside("C:\\work\\a.ts", "C:\\w", win32)).toBe(false)
  expect(paths.isInside("C:\\w\\..\\x", "C:\\w", win32)).toBe(false)
  expect(paths.isInside("\\\\server\\share\\a", "\\\\server\\share", win32)).toBe(true)
})

test("inside on a POSIX host: exact case, and a sibling sharing a prefix is outside", () => {
  expect(paths.isInside("/w/src/a.ts", "/w", posix)).toBe(true)
  expect(paths.isInside("/w", "/w/", posix)).toBe(true)
  expect(paths.isInside("/anything", "/", posix)).toBe(true)
  expect(paths.isInside("/W/src", "/w", posix)).toBe(false)
  expect(paths.isInside("/work/a.ts", "/w", posix)).toBe(false)
  expect(paths.isInside("/w/..x/a", "/w", posix)).toBe(true)
})

test("by default it is this host's rule", () => {
  expect(paths.isInside(process.cwd(), process.cwd())).toBe(true)
})

// "Under the root, never the root itself": mux-owned worktrees, include-file copies, a clone's target folder.
// The relative()-based copies took any name starting with `..` (a folder called `..foo`) for "outside".
test("strictly inside excludes the root itself, and keeps `..foo` inside", () => {
  expect(paths.isStrictlyInside("C:\\w\\a", "C:\\w", win32)).toBe(true)
  expect(paths.isStrictlyInside("C:\\w", "C:\\w", win32)).toBe(false)
  expect(paths.isStrictlyInside("c:\\W\\", "C:\\w", win32)).toBe(false)
  expect(paths.isStrictlyInside("D:\\w\\a", "C:\\w", win32)).toBe(false)
  expect(paths.isStrictlyInside("/w/..foo", "/w", posix)).toBe(true)
  expect(paths.isStrictlyInside("/w", "/w", posix)).toBe(false)
  expect(paths.isStrictlyInside("/x", "/w", posix)).toBe(false)
})

// Code that decides for a platform it is told (tests run Windows' rules on Linux) asks for that platform's flavour here,
// instead of importing path.win32 / path.posix itself.
test("the flavour of a platform: win32's rules for win32, posix's for the rest", () => {
  expect(paths.isInside("c:\\W\\a", "C:\\w", paths.flavor("win32"))).toBe(true)
  expect(paths.isInside("/W/a", "/w", paths.flavor("linux"))).toBe(false)
  expect(paths.flavor("darwin").sep).toBe("/")
  expect(paths.flavor("win32").sep).toBe("\\")
})

// Session and Agent names become folder names (an agent's home, its workspace). A title is free text: on Windows a name
// with < > : " | ? * or a control character, a device name (CON, NUL, COM1...) or a trailing dot or space cannot be a
// folder, so the session never started there. Elsewhere only / and \\ and . / .. are changed, so no home moves.
test("safeFolderName on Windows makes any name a folder Windows can create", () => {
  // The vectors the desktop app's safeFileName is held to as well (its DesktopPlatformTest reads the same file).
  for (const [name, folder] of windowsNames.windows) expect(paths.safeFolderName(name, "win32")).toBe(folder)
  expect(paths.safeFolderName("a/b\\c", "win32")).toBe("a-b-c")
  // Windows drops a trailing dot: "_.." would be the folder "_".
  expect(paths.safeFolderName("..", "win32")).toBe("_")
  expect(paths.safeFolderName("...", "win32")).toBe("_")
})

test("safeFolderName elsewhere changes only what would leave the folder: / and \\, and . and ..", () => {
  expect(paths.safeFolderName("Fix: login? <now>", "linux")).toBe("Fix: login? <now>")
  expect(paths.safeFolderName("con", "darwin")).toBe("con")
  expect(paths.safeFolderName("a/b\\c", "linux")).toBe("a-b-c")
  expect(paths.safeFolderName(".", "linux")).toBe("_.")
  expect(paths.safeFolderName("..", "darwin")).toBe("_..")
})
