// Owner-only files and folders (links-perms rule, spec step 7): POSIX modes where they exist. On Windows no mode is
// set (chmod there only toggles read-only); the user's profile ACL keeps them private, so a folder outside it is said.
import { expect, test } from "bun:test"
import { mkdtempSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { homedir, tmpdir } from "node:os"
import { join } from "node:path"
import { files } from "../src/index.js"

const posix = process.platform !== "win32"
const mode = (p: string) => statSync(p).mode & 0o777
const scratch = () => mkdtempSync(join(tmpdir(), "mux-private-"))

test.if(posix)("a private folder is created 0o700, its parents too", () => {
  const dir = join(scratch(), "a", "b")
  files.mkdirPrivate(dir)
  expect(mode(dir)).toBe(0o700)
})

test.if(posix)("a private file is written 0o600, with the write's own options kept", () => {
  const f = join(scratch(), "secret")
  files.writePrivate(f, "s1")
  expect(mode(f)).toBe(0o600)
  expect(readFileSync(f, "utf8")).toBe("s1")
  expect(() => files.writePrivate(f, "s2", { flag: "wx" })).toThrow()
})

test.if(posix)("makePrivate narrows an existing file or folder", () => {
  const d = scratch()
  const f = join(d, "f")
  writeFileSync(f, "x", { mode: 0o644 })
  files.makePrivate(f)
  files.makePrivate(d)
  expect(mode(f)).toBe(0o600)
  expect(mode(d)).toBe(0o700)
})

test.if(posix)("an append creates a private file and adds to it", () => {
  const f = join(scratch(), "log")
  files.appendPrivate(f, "a")
  files.appendPrivate(f, "b")
  expect(mode(f)).toBe(0o600)
  expect(readFileSync(f, "utf8")).toBe("ab")
})

test.if(posix)("a private executable (a launcher, an askpass script) is 0o700", () => {
  const d = scratch()
  const f = join(d, "askpass")
  writeFileSync(f, "#!/bin/sh\n")
  files.makePrivate(f, { executable: true })
  expect(mode(f)).toBe(0o700)
  const g = join(d, "opener")
  files.writeExecutable(g, "#!/bin/sh\n", { private: true })
  expect(mode(g)).toBe(0o700)
})

test.if(posix)("an executable is 0o755", () => {
  const f = join(scratch(), "run")
  files.writeExecutable(f, "#!/bin/sh\n")
  expect(mode(f)).toBe(0o755)
})

test("on Windows nothing is chmod-ed; a path in the profile is private by its ACL, one outside it is not", () => {
  const calls: string[] = []
  const chmod = (p: string) => { calls.push(p) }
  expect(files.makePrivate(join(homedir(), "x"), { platform: "win32", chmod, home: homedir() })).toBe(true)
  expect(files.makePrivate("D:\\shared\\x", { platform: "win32", chmod, home: "C:\\Users\\a" })).toBe(false)
  expect(calls).toEqual([])
})
