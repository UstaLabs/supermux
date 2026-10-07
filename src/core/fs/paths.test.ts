import { test, expect } from "bun:test"
import { mkdtempSync, mkdirSync, symlinkSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { normalizeAbsPath, realKey } from "./paths"
import { FsError } from "./errors"
import { mapLimit } from "./pool"

test("normalizeAbsPath resolves dot segments and strips the trailing slash", () => {
  expect(normalizeAbsPath("/a/b/../c/./d/")).toBe("/a/c/d")
  expect(normalizeAbsPath("/")).toBe("/")
})

test("normalizeAbsPath rejects relative, empty and NUL paths with EINVAL", () => {
  for (const bad of ["", "a/b", "./x", "/a\0b"]) {
    let err: unknown
    try { normalizeAbsPath(bad) } catch (e) { err = e }
    expect(err).toBeInstanceOf(FsError)
    expect((err as FsError).code).toBe("EINVAL")
  }
})

test("realKey follows symlinks", async () => {
  const root = mkdtempSync(join(tmpdir(), "fs-paths-"))
  mkdirSync(join(root, "real"))
  symlinkSync(join(root, "real"), join(root, "link"))
  expect(await realKey(join(root, "link"))).toBe(await realKey(join(root, "real")))
})

test("realKey maps a missing path to FsError ENOENT", async () => {
  await expect(realKey("/definitely/not/here")).rejects.toMatchObject({ code: "ENOENT" })
})

test("mapLimit keeps order and never exceeds the limit", async () => {
  let active = 0
  let peak = 0
  const out = await mapLimit([1, 2, 3, 4, 5, 6, 7, 8], 3, async (n) => {
    active++; peak = Math.max(peak, active)
    await new Promise((r) => setTimeout(r, 5))
    active--
    return n * 2
  })
  expect(out).toEqual([2, 4, 6, 8, 10, 12, 14, 16])
  expect(peak).toBeLessThanOrEqual(3)
})
