import { test, expect } from "bun:test"
import { mkdtempSync, writeFileSync, mkdirSync, chmodSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { resolveVerifyCommand, runVerify } from "./verify"

test("resolveVerifyCommand: .mux/verify.sh present → bash command", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-vfy-"))
  mkdirSync(join(dir, ".mux"))
  writeFileSync(join(dir, ".mux", "verify.sh"), "#!/usr/bin/env bash\nexit 0\n"); chmodSync(join(dir, ".mux", "verify.sh"), 0o755)
  expect(resolveVerifyCommand(dir)).toBe("bash .mux/verify.sh")
})

test("resolveVerifyCommand: no .mux/verify.sh → null (no runtime auto-detect)", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-vfy-"))
  writeFileSync(join(dir, "go.mod"), "module x\n") // a manifest must NOT trigger detection anymore
  expect(resolveVerifyCommand(dir)).toBeNull()
})

test("runVerify reports pass/fail with output", () => {
  const dir = mkdtempSync(join(tmpdir(), "mux-vfy-"))
  expect(runVerify(dir, "true").ok).toBe(true)
  const fail = runVerify(dir, "echo boom; false")
  expect(fail.ok).toBe(false); expect(fail.output).toContain("boom")
})
