// src/core/forge/credential-helper.test.ts
import { test, expect, afterAll } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { bindHttpsCredentials, resolveCredentialFill, helperCommand } from "./credential-helper"

const work = mkdtempSync(join(tmpdir(), "forge-cred-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

function repo(): string {
  const r = join(work, `r-${Math.random().toString(36).slice(2)}`)
  execFileSync("git", ["init", "-q", r]); return r
}

test("bindHttpsCredentials stores the helper ref (round-trips via git config) — not the raw token", () => {
  const r = repo()
  bindHttpsCredentials(r, "github.com", "github:github.com:ahmet")
  const val = execFileSync("git", ["-C", r, "config", "--get", "credential.https://github.com.helper"], { encoding: "utf8" }).trim()
  expect(val).toBe(helperCommand("github:github.com:ahmet"))
  const cfg = readFileSync(join(r, ".git", "config"), "utf8")
  expect(cfg).not.toContain("ghp_") // no token on disk
})

test("helperCommand single-quotes the id so shell metacharacters cannot inject", () => {
  expect(helperCommand("github:github.com:ahmet")).toBe("!mux-credential 'github:github.com:ahmet'")
  // a hostile id with a quote is escaped, not broken out of
  expect(helperCommand("a'b")).toBe("!mux-credential 'a'\\''b'")
})

test("resolveCredentialFill emits username+password from a lookup, empty on miss", () => {
  expect(resolveCredentialFill("id", () => ({ user: "x-access-token", token: "ghp_secret" })))
    .toBe("username=x-access-token\npassword=ghp_secret\n")
  expect(resolveCredentialFill("id", () => undefined)).toBe("")
})
