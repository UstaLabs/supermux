import { expect, test } from "bun:test"
import { mkdtempSync, existsSync, statSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { loadOrCreateHostKey, hostIdFromPublicKey } from "./keypair"

function freshKeyPath(): string {
  return join(mkdtempSync(join(tmpdir(), "mux-hostkey-")), "host-key")
}

test("hostId is 26 lowercase base32 chars, deterministic from the public key", () => {
  const p = freshKeyPath()
  const a = loadOrCreateHostKey(p)
  expect(a.hostId).toMatch(/^[a-z2-7]{26}$/)
  expect(hostIdFromPublicKey(a.publicKeyRaw)).toBe(a.hostId)
})

test("second load returns the SAME identity (persisted, not regenerated)", () => {
  const p = freshKeyPath()
  const a = loadOrCreateHostKey(p)
  const b = loadOrCreateHostKey(p)
  expect(b.hostId).toBe(a.hostId)
  expect(b.publicKeyRaw.equals(a.publicKeyRaw)).toBe(true)
})

test("key file is created 0600", () => {
  const p = freshKeyPath()
  loadOrCreateHostKey(p)
  expect(existsSync(p)).toBe(true)
  expect(statSync(p).mode & 0o777).toBe(0o600)
})

test("sign/verify round-trips; a tampered message fails", () => {
  const p = freshKeyPath()
  const id = loadOrCreateHostKey(p)
  const msg = Buffer.from("challenge-nonce-123")
  const sig = id.sign(msg)
  expect(id.verify(msg, sig)).toBe(true)
  expect(id.verify(Buffer.from("challenge-nonce-124"), sig)).toBe(false)
})
