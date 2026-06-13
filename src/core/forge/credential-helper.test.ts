import { test, expect, afterAll } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, writeFileSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { bindHttpsCredentials, resolveCredentialFill } from "./credential-helper"

const work = mkdtempSync(join(tmpdir(), "forge-cred-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

function repo(): string {
  const r = join(work, `r-${Math.random().toString(36).slice(2)}`)
  execFileSync("git", ["init", "-q", r]); return r
}

test("bindHttpsCredentials writes a helper ref — not the raw token", () => {
  const r = repo()
  bindHttpsCredentials(r, "github.com", "github:github.com:ahmet")
  const cfg = readFileSync(join(r, ".git", "config"), "utf8")
  expect(cfg).toContain("mux-credential github:github.com:ahmet")
  expect(cfg).not.toContain("ghp_") // no token in config
})

test("resolveCredentialFill emits username+password from a lookup", () => {
  const out = resolveCredentialFill("github:github.com:ahmet", () => ({ user: "x-access-token", token: "ghp_secret" }))
  expect(out).toBe("username=x-access-token\npassword=ghp_secret\n")
})
