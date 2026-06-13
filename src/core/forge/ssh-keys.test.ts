// src/core/forge/ssh-keys.test.ts
import { test, expect, afterAll } from "bun:test"
import { execFileSync } from "child_process"
import { mkdtempSync, rmSync, existsSync, statSync, readFileSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { ensureKeypair, seedKnownHosts, bindSshCommand, sshCommandFor } from "./ssh-keys"

const work = mkdtempSync(join(tmpdir(), "forge-ssh-"))
afterAll(() => rmSync(work, { recursive: true, force: true }))

test("ensureKeypair generates a 0600 ed25519 key + public key + fingerprint, idempotently", () => {
  const a = ensureKeypair(work, "github:github.com:ahmet")
  expect(existsSync(a.privatePath)).toBe(true)
  expect(a.publicKey).toContain("ssh-ed25519")
  expect(a.fingerprint).toMatch(/^SHA256:/)
  expect(statSync(a.privatePath).mode & 0o777).toBe(0o600)
  const b = ensureKeypair(work, "github:github.com:ahmet") // reuse, don't regenerate
  expect(b.publicKey).toBe(a.publicKey)
})

test("seedKnownHosts writes the provided host-key lines", () => {
  const kh = seedKnownHosts(work, ["github.com ssh-ed25519 AAAAC3Nz..."])
  expect(readFileSync(kh, "utf8")).toContain("github.com ssh-ed25519")
})

test("bindSshCommand sets core.sshCommand pointing at the key + known_hosts", () => {
  const repo = join(work, "repo"); execFileSync("git", ["init", "-q", repo])
  bindSshCommand(repo, sshCommandFor(join(work, "k"), join(work, "known_hosts")))
  const cfg = readFileSync(join(repo, ".git", "config"), "utf8")
  expect(cfg).toContain("sshCommand")
  expect(cfg).toContain("IdentitiesOnly=yes")
})
