import { describe, expect, test } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, existsSync, chmodSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { sweepRuntimeAssets } from "./runtime-assets-gc"

function makeTmpDir(): string {
  return mkdtempSync(join(tmpdir(), "mux-gc-"))
}

describe("sweepRuntimeAssets", () => {
  test("missing runtime-assets dir → returns []", () => {
    const dir = makeTmpDir()
    try {
      // No runtime-assets dir inside dir
      expect(sweepRuntimeAssets(dir, "0.2.0")).toEqual([])
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("removes old version dirs, keeps keepVersion, returns removed names sorted", () => {
    const dir = makeTmpDir()
    try {
      // Create runtime-assets/{0.1.0,0.2.0,dev} each with a file inside
      const ra = join(dir, "runtime-assets")
      mkdirSync(ra)
      for (const v of ["0.1.0", "0.2.0", "dev"]) {
        const vDir = join(ra, v)
        mkdirSync(vDir)
        writeFileSync(join(vDir, "asset.txt"), `content-${v}`)
      }

      const removed = sweepRuntimeAssets(dir, "0.2.0")
      expect(removed.sort()).toEqual(["0.1.0", "dev"])

      // keepVersion still exists
      expect(existsSync(join(ra, "0.2.0"))).toBe(true)
      expect(existsSync(join(ra, "0.2.0", "asset.txt"))).toBe(true)

      // old dirs are gone
      expect(existsSync(join(ra, "0.1.0"))).toBe(false)
      expect(existsSync(join(ra, "dev"))).toBe(false)
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("root files (non-dirs) inside runtime-assets are left alone", () => {
    const dir = makeTmpDir()
    try {
      const ra = join(dir, "runtime-assets")
      mkdirSync(ra)
      // A file directly inside runtime-assets (not a version dir)
      writeFileSync(join(ra, "stray-file.txt"), "do not delete")
      // An old version dir
      const old = join(ra, "0.1.0")
      mkdirSync(old)
      writeFileSync(join(old, "x"), "old")
      // keepVersion
      const keep = join(ra, "0.2.0")
      mkdirSync(keep)
      writeFileSync(join(keep, "y"), "new")

      const removed = sweepRuntimeAssets(dir, "0.2.0")
      expect(removed).toEqual(["0.1.0"])

      // stray file survives
      expect(existsSync(join(ra, "stray-file.txt"))).toBe(true)
      // old dir removed
      expect(existsSync(old)).toBe(false)
      // kept version intact
      expect(existsSync(keep)).toBe(true)
    } finally {
      rmSync(dir, { recursive: true })
    }
  })

  test("an unremovable dir is skipped; the rest still sweeps", () => {
    const stateDir = mkdtempSync(join(tmpdir(), "mux-gc-"))
    const ra = join(stateDir, "runtime-assets")
    mkdirSync(join(ra, "keep-me"), { recursive: true })
    mkdirSync(join(ra, "stuck"), { recursive: true })
    writeFileSync(join(ra, "stuck", "f"), "x")
    mkdirSync(join(ra, "old-gone"), { recursive: true })
    chmodSync(join(ra, "stuck"), 0o555) // children can't be unlinked
    try {
      const removed = sweepRuntimeAssets(stateDir, "keep-me")
      expect(removed).toContain("old-gone")
      expect(existsSync(join(ra, "stuck"))).toBe(true)
      expect(existsSync(join(ra, "old-gone"))).toBe(false)
    } finally {
      chmodSync(join(ra, "stuck"), 0o755) // so tmp cleanup works
      rmSync(stateDir, { recursive: true })
    }
  })

  test("nothing to remove (only keepVersion present) → returns []", () => {
    const dir = makeTmpDir()
    try {
      const ra = join(dir, "runtime-assets")
      mkdirSync(ra)
      const keep = join(ra, "0.2.0")
      mkdirSync(keep)
      writeFileSync(join(keep, "f"), "data")

      expect(sweepRuntimeAssets(dir, "0.2.0")).toEqual([])
      expect(existsSync(keep)).toBe(true)
    } finally {
      rmSync(dir, { recursive: true })
    }
  })
})
