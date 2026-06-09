import { afterEach, beforeEach, expect, test } from "bun:test"
import { mkdirSync, rmSync, symlinkSync, writeFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { mkdtempSync } from "fs"
import { normalizeExistingWorkdir, normalizeWorkdirInput, uniqueKnownWorkdirs } from "../src/core/session-manager/workdir-paths"

let root: string

beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "mux-workdir-"))
})

afterEach(() => {
  rmSync(root, { recursive: true, force: true })
})

test("normalizeWorkdirInput expands tilde and strips trailing slashes", () => {
  expect(normalizeWorkdirInput("~/projects/app///", root)).toBe(join(root, "projects", "app"))
})

test("normalizeWorkdirInput repairs home-prefixed tilde paths", () => {
  expect(normalizeWorkdirInput(`${root}/~/projects/app///`, root)).toBe(join(root, "projects", "app"))
})

test("uniqueKnownWorkdirs deduplicates equivalent tilde paths", () => {
  expect(uniqueKnownWorkdirs([
    `${root}/~/acme`,
    "~/acme",
    join(root, "acme"),
  ], root)).toEqual([join(root, "acme")])
})

test("normalizeExistingWorkdir resolves symlinks to the actual directory", () => {
  const target = join(root, "target")
  const link = join(root, "link")
  mkdirSync(target)
  symlinkSync(target, link)

  expect(normalizeExistingWorkdir(link)).toBe(target)
})

test("normalizeExistingWorkdir rejects missing paths and files", () => {
  const file = join(root, "file.txt")
  writeFileSync(file, "not a directory")

  expect(() => normalizeExistingWorkdir(join(root, "missing"))).toThrow(/does not exist/)
  expect(() => normalizeExistingWorkdir(file)).toThrow(/not a directory/)
})
