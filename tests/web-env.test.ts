import { test, expect } from "bun:test"
import { validateWebEnv } from "../src/shared/web-env"

test("neither set: disabled, no error", () => {
  const r = validateWebEnv(undefined, undefined)
  expect(r.enabled).toBe(false)
  expect(r.error).toBeUndefined()
})

test("only one of the pair set: error", () => {
  expect(validateWebEnv("8787", undefined).error).toBeDefined()
  expect(validateWebEnv(undefined, "http://localhost:8787").error).toBeDefined()
})

test("invalid port: error", () => {
  expect(validateWebEnv("notaport", "http://localhost:8787").error).toBeDefined()
  expect(validateWebEnv("70000", "http://localhost:8787").error).toBeDefined()
})

test("invalid url: error", () => {
  expect(validateWebEnv("8787", "not a url").error).toBeDefined()
})

test("both valid: enabled, no error", () => {
  const r = validateWebEnv("8787", "http://localhost:8787")
  expect(r.enabled).toBe(true)
  expect(r.error).toBeUndefined()
})
