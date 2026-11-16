// #157: a missing path must throw before anything is written. An untyped caller once passed undefined, and the temp
// file landed in the working directory as `undefined.tmp.<pid>` (and was committed) while the rename failed.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, readdirSync, readFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { writeJsonAtomic } from "./atomic-json"
import { DeviceStore } from "../channels/web/device-store"

const dirs: string[] = []
const cwd = process.cwd()
afterEach(() => {
  process.chdir(cwd)
  for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true })
})

function scratch(): string {
  const dir = mkdtempSync(join(tmpdir(), "mux-atomic-"))
  dirs.push(dir)
  return dir
}

test("a missing path throws, and nothing lands in the working directory", () => {
  const dir = scratch()
  process.chdir(dir)
  for (const path of [undefined, null, ""]) {
    expect(() => writeJsonAtomic(path as unknown as string, [{ a: 1 }])).toThrow(/file path is required/)
  }
  expect(readdirSync(dir)).toEqual([])
})

test("a path writes the value, with no temp file left beside it", () => {
  const dir = scratch()
  const file = join(dir, "x.json")
  writeJsonAtomic(file, { a: 1 })
  expect(JSON.parse(readFileSync(file, "utf8"))).toEqual({ a: 1 })
  expect(readdirSync(dir)).toEqual(["x.json"])
})

test("a DeviceStore without a file refuses at construction, before it can mint", () => {
  const dir = scratch()
  process.chdir(dir)
  expect(() => new (DeviceStore as unknown as new () => DeviceStore)()).toThrow(/DeviceStore: a file path is required/)
  expect(readdirSync(dir)).toEqual([])
})
