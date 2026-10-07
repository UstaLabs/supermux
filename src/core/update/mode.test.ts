import { afterEach, expect, test } from "bun:test"
import { detectInstallMode, detectUpdateMode } from "./mode"

const saved = process.env.MUX_MANAGED_BY
afterEach(() => {
  if (saved === undefined) delete process.env.MUX_MANAGED_BY
  else process.env.MUX_MANAGED_BY = saved
})

test("MUX_MANAGED_BY=desktop makes the update mode 'managed'", () => {
  process.env.MUX_MANAGED_BY = "desktop"
  expect(detectUpdateMode()).toBe("managed")
})

test("without a manager the update mode is the install mode", () => {
  delete process.env.MUX_MANAGED_BY
  expect(detectUpdateMode()).toBe(detectInstallMode())
})

test("the install mode never reports 'managed'", () => {
  process.env.MUX_MANAGED_BY = "desktop"
  expect(["binary", "source", "docker"]).toContain(detectInstallMode())
})

test("detectInstallMode is 'source' under bun test (not compiled, no /.dockerenv)", () => {
  delete process.env.MUX_MANAGED_BY
  expect(detectInstallMode()).toBe("source")
})
