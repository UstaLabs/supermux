import { test, expect } from "bun:test"
import { writeFileSync, readFileSync, unlinkSync, existsSync } from "fs"
import { readJsonOr, writeJsonAtomic } from "../src/shared/atomic-json"

test("readJsonOr returns fallback when file missing", () => {
  const p = `/tmp/aj-${process.pid}-a.json`
  if (existsSync(p)) unlinkSync(p)
  expect(readJsonOr(p, { fallback: true })).toEqual({ fallback: true })
})

test("writeJsonAtomic + readJsonOr roundtrip", () => {
  const p = `/tmp/aj-${process.pid}-b.json`
  writeJsonAtomic(p, { hello: "world" })
  expect(readJsonOr(p, {})).toEqual({ hello: "world" })
  unlinkSync(p)
})

test("writeJsonAtomic uses 0o600 perms", () => {
  const p = `/tmp/aj-${process.pid}-c.json`
  writeJsonAtomic(p, { ok: true })
  const stat = require("fs").statSync(p)
  expect(stat.mode & 0o777).toBe(0o600)
  unlinkSync(p)
})
