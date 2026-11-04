import { expect, test } from "bun:test"
import { lstatSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { Unsupported, files } from "@supermux/platform"

const scratch = () => mkdtempSync(join(tmpdir(), "mux-files-"))
const eperm = () => { throw Object.assign(new Error("EPERM: operation not permitted, symlink"), { code: "EPERM" }) }

test("a folder link is a junction on Windows and a symlink elsewhere; it needs no privilege", () => {
  const dir = scratch()
  mkdirSync(join(dir, "target"))
  writeFileSync(join(dir, "target", "a.txt"), "a")
  files.linkDirectory(join(dir, "target"), join(dir, "link"))
  expect(lstatSync(join(dir, "link")).isSymbolicLink()).toBe(true)
  expect(readFileSync(join(dir, "link", "a.txt"), "utf8")).toBe("a")
  const types: string[] = []
  files.linkDirectory("t", "p", { symlink: (_t, _p, type) => { types.push(type) } })
  expect(types).toEqual(["junction"])
})

test("a file link works where the OS allows it", () => {
  const dir = scratch()
  writeFileSync(join(dir, "target.txt"), "hello")
  files.linkFile(join(dir, "target.txt"), join(dir, "link.txt"))
  expect(lstatSync(join(dir, "link.txt")).isSymbolicLink()).toBe(true)
  expect(readFileSync(join(dir, "link.txt"), "utf8")).toBe("hello")
})

// #37: on Windows a file symlink needs Developer Mode or elevation; a standard user gets EPERM. Typed, so the caller
// falls back on purpose (Q5: copy a small file, or make the account isolated) instead of failing after sign-in.
test("a file link on Windows without the privilege is Unsupported; elsewhere EPERM stays an error", () => {
  let thrown: unknown
  try { files.linkFile("t", "p", { platform: "win32", symlink: eperm }) } catch (e) { thrown = e }
  expect(thrown).toBeInstanceOf(Unsupported)
  expect((thrown as Unsupported).code).toBe("unsupported")
  expect(() => files.linkFile("t", "p", { platform: "linux", symlink: eperm })).toThrow("EPERM")
})

test("whether this process can link files is probed once, in a scratch folder of its own", () => {
  expect(files.canLinkFiles({ scratch: scratch() })).toBe(true)
  let probes = 0
  const failing = { platform: "win32" as const, scratch: scratch(), symlink: () => { probes++; eperm() }, cache: new Map<string, boolean>() }
  expect(files.canLinkFiles(failing)).toBe(false)
  expect(files.canLinkFiles(failing)).toBe(false)
  expect(probes).toBe(1)
})
