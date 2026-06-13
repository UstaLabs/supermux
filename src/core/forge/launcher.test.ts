// src/core/forge/launcher.test.ts
import { test, expect, afterAll } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, statSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { installCredentialLauncher } from "./launcher"

const work = mkdtempSync(join(tmpdir(), "forge-launch-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

test("installCredentialLauncher writes an executable launcher pointing at the cli", () => {
  const p = installCredentialLauncher(join(work, "bin"), "/opt/mux/repo")
  expect(p).toBe(join(work, "bin", "mux-credential"))
  const body = readFileSync(p, "utf8")
  expect(body).toContain("credential-cli.ts")
  expect(body).toContain("/opt/mux/repo")
  expect(statSync(p).mode & 0o777).toBe(0o700)
})
