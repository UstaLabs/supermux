// src/core/forge/launcher.test.ts
import { test, expect, afterAll } from "bun:test"
import { mkdtempSync, rmSync, readFileSync, statSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { installCredentialLauncher } from "./launcher"

const work = mkdtempSync(join(tmpdir(), "forge-launch-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

test("installCredentialLauncher writes an executable launcher pointing at the cli (source mode)", () => {
  const p = installCredentialLauncher(join(work, "bin"), "/opt/mux/repo", {
    execPath: "/opt/bun/bin/bun",
    compiled: false,
  })
  expect(p).toBe(join(work, "bin", "mux-credential"))
  const body = readFileSync(p, "utf8")
  expect(body).toContain("credential-cli.ts")
  expect(body).toContain("/opt/mux/repo")
  expect(body).toContain("/opt/bun/bin/bun")
  expect(body).not.toMatch(/\scredential\s/)
  expect(statSync(p).mode & 0o777).toBe(0o700)
})

test("compiled launcher invokes the supermux credential subcommand (not a /$bunfs .ts path)", () => {
  const p = installCredentialLauncher(join(work, "bin-compiled"), "/opt/mux/repo", {
    execPath: "/usr/local/bin/supermux",
    compiled: true,
  })
  const body = readFileSync(p, "utf8")
  expect(body).toBe('#!/bin/sh\nexec "/usr/local/bin/supermux" credential "$@"\n')
  expect(body).not.toContain("credential-cli.ts")
  expect(body).not.toContain("/$bunfs")
})

test("launcher defaults embed the current process.execPath in source mode", () => {
  const p = installCredentialLauncher(join(work, "bin2"), "/opt/mux/repo", { compiled: false })
  expect(readFileSync(p, "utf8")).toContain(process.execPath)
})
