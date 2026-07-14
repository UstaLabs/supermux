import { test, expect } from "bun:test"
import { relativizePath } from "./path-relativize"

const WD = "/h/u/proj"

test("absolute path under workdir -> workdir-relative with forward slashes", () => {
  expect(relativizePath("/h/u/proj/src/main.ts", WD)).toBe("src/main.ts")
})

test("absolute path with line:col ref -> ref is preserved", () => {
  expect(relativizePath("/h/u/proj/src/main.ts:105", WD)).toBe("src/main.ts:105")
})

test("absolute path outside workdir -> unchanged", () => {
  expect(relativizePath("/etc/hosts", WD)).toBe("/etc/hosts")
})

test("sibling directory -> unchanged (starts with ../)", () => {
  expect(relativizePath("/h/u/other/file.ts", WD)).toBe("/h/u/other/file.ts")
})

test("already-relative path -> unchanged", () => {
  expect(relativizePath("src/main.ts", WD)).toBe("src/main.ts")
})

test("empty string -> empty string", () => {
  expect(relativizePath("", WD)).toBe("")
})

test("workdir is the input -> unchanged (rel would be empty/'.')", () => {
  expect(relativizePath(WD, WD)).toBe(WD)
})

test("undefined workdir -> no-op", () => {
  expect(relativizePath("/h/u/proj/src/main.ts", undefined)).toBe("/h/u/proj/src/main.ts")
})

test("null workdir -> no-op", () => {
  expect(relativizePath("/h/u/proj/src/main.ts", null)).toBe("/h/u/proj/src/main.ts")
})

test("nested path -> nested relative", () => {
  expect(relativizePath("/h/u/proj/a/b/c/d.ts", WD)).toBe("a/b/c/d.ts")
})
