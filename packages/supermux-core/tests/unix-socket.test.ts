import { afterEach, expect, test } from "bun:test"
import { chmodSync, mkdirSync, mkdtempSync, rmSync, statSync, symlinkSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { privateDirectory } from "../src/unix-socket.js"

const dirs: string[] = []
afterEach(() => { for (const dir of dirs.splice(0)) rmSync(dir, { recursive: true, force: true }) })

function scratch() {
  const dir = mkdtempSync(join(tmpdir(), "private-dir-"))
  dirs.push(dir)
  return dir
}

test("privateDirectory makes a missing directory 0700", () => {
  const path = join(scratch(), "a", "b")
  privateDirectory(path)
  expect(statSync(path).isDirectory()).toBe(true)
  expect(statSync(path).mode & 0o777).toBe(0o700)
})

test("privateDirectory tightens a loose mode of this user's directory to 0700", () => {
  const path = join(scratch(), "loose")
  mkdirSync(path, { mode: 0o755 })
  chmodSync(path, 0o755)
  privateDirectory(path)
  expect(statSync(path).mode & 0o777).toBe(0o700)
})

test("privateDirectory refuses a symlink, even to a private directory", () => {
  const dir = scratch()
  const target = join(dir, "target")
  mkdirSync(target, { mode: 0o700 })
  const link = join(dir, "link")
  symlinkSync(target, link)
  expect(() => privateDirectory(link)).toThrow(expect.objectContaining({ code: "invalid_options", message: `${link} must be a directory` }))
})

// "/" belongs to root: another user's directory, without needing root to make one.
test.skipIf(typeof process.getuid !== "function" || process.getuid() === 0)("privateDirectory refuses a directory another user owns, and leaves it as it is", () => {
  const before = statSync("/").mode
  expect(() => privateDirectory("/")).toThrow(expect.objectContaining({ code: "invalid_options", message: "/ is owned by another user" }))
  expect(statSync("/").mode).toBe(before)
})
