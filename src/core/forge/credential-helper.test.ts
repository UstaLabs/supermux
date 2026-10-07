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

test("bindHttpsCredentials stores the absolute helper ref (round-trips via git config) — not the raw token", () => {
  const r = repo()
  bindHttpsCredentials(r, "github.com", "github:github.com:ahmet", "/opt/mux/bin/mux-credential")
  const val = execFileSync("git", ["-C", r, "config", "--get", "credential.https://github.com.helper"], { encoding: "utf8" }).trim()
  expect(val).toBe(helperCommand("/opt/mux/bin/mux-credential", "github:github.com:ahmet"))
  expect(val).toContain("/opt/mux/bin/mux-credential")
  expect(readFileSync(join(r, ".git", "config"), "utf8")).not.toContain("ghp_")
})

test("bindHttpsCredentials scopes the helper to an http connection's scheme", () => {
  const r = repo()
  bindHttpsCredentials(r, "git.acme.com", "gitlab:git.acme.com:ahmet", "/opt/mux/bin/mux-credential", "http")
  const val = execFileSync("git", ["-C", r, "config", "--get", "credential.http://git.acme.com.helper"], { encoding: "utf8" }).trim()
  expect(val).toBe(helperCommand("/opt/mux/bin/mux-credential", "gitlab:git.acme.com:ahmet"))
})

test("helperCommand quotes both the path and the id", () => {
  expect(helperCommand("/a b/mux-credential", "x'y")).toBe("!'/a b/mux-credential' 'x'\\''y'")
})

test("resolveCredentialFill emits username+password from a lookup, empty on miss", () => {
  expect(resolveCredentialFill("id", () => ({ user: "x-access-token", token: "ghp_secret" })))
    .toBe("username=x-access-token\npassword=ghp_secret\n")
  expect(resolveCredentialFill("id", () => undefined)).toBe("")
})
