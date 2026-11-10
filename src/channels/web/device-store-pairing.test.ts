// #119: a pairing link mints a pending device that the first /pair or /pair.json claims, within 10 minutes.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { DeviceStore, PAIRING_TTL_MS } from "./device-store"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function store(now: { t: number }): { s: DeviceStore; file: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-pairing-"))
  dirs.push(dir)
  const file = join(dir, "devices.json")
  return { s: new DeviceStore(file, { clock: () => now.t }), file }
}

test("a pairing link works once: the second claim is refused, the paired device keeps working", () => {
  const now = { t: 1_000_000 }
  const { s } = store(now)
  const { token, name } = s.mintPairing("phone")
  expect(s.claimPairing(token)).toEqual({ ok: true, name })
  expect(s.claimPairing(token)).toEqual({ ok: false, reason: "used" })
  expect(s.verify(token)?.name).toBe(name)
  now.t += PAIRING_TTL_MS * 10
  expect(s.verify(token)?.name).toBe(name)
})

test("an unclaimed link expires after 10 minutes: refused, and its device is gone", () => {
  const now = { t: 1_000_000 }
  const { s } = store(now)
  const { token, name } = s.mintPairing("phone")
  // Still pending inside the window: it authenticates (the apps' /me fallback).
  now.t += PAIRING_TTL_MS - 1
  expect(s.verify(token)?.name).toBe(name)
  now.t += 2
  expect(s.verify(token)).toBeUndefined()
  expect(s.claimPairing(token)).toEqual({ ok: false, reason: "expired" })
  expect(s.list().map((d) => d.name)).not.toContain(name)
})

test("a new code from the same device revokes its previous unclaimed one; others' codes are untouched", () => {
  const now = { t: 1_000_000 }
  const { s } = store(now)
  const first = s.mintPairing("phone", { by: "laptop" })
  const other = s.mintPairing("tablet", { by: "desk" })
  const second = s.mintPairing("phone", { by: "laptop" })
  expect(s.claimPairing(first.token)).toEqual({ ok: false, reason: "unknown" })
  expect(s.verify(first.token)).toBeUndefined()
  expect(s.claimPairing(second.token).ok).toBe(true)
  expect(s.claimPairing(other.token).ok).toBe(true)
})

test("a claimed code is not revoked by the next new code", () => {
  const now = { t: 1_000_000 }
  const { s } = store(now)
  const first = s.mintPairing("phone", { by: "laptop" })
  expect(s.claimPairing(first.token).ok).toBe(true)
  s.mintPairing("phone", { by: "laptop" })
  expect(s.verify(first.token)).toBeDefined()
})

test("existing devices are unaffected; their old links cannot pair again", () => {
  const now = { t: 1_000_000 }
  const { s, file } = store(now)
  const { token } = s.mint("old-phone")
  expect(s.verify(token)?.name).toBe("old-phone")
  expect(s.claimPairing(token)).toEqual({ ok: false, reason: "used" })
  expect(s.claimPairing("nope")).toEqual({ ok: false, reason: "unknown" })
  // The pending state is on disk: another store (a restarted broker) sees it.
  const pending = s.mintPairing("phone")
  const again = new DeviceStore(file, { clock: () => now.t })
  expect(again.claimPairing(pending.token).ok).toBe(true)
  expect(s.claimPairing(pending.token)).toEqual({ ok: false, reason: "used" })
})

test("using a pending token to sign in counts as its one use: it stays, and the link no longer pairs", () => {
  const now = { t: 1_000_000 }
  const { s } = store(now)
  const { token, name } = s.mintPairing("phone")
  s.touch(token)
  expect(s.claimPairing(token)).toEqual({ ok: false, reason: "used" })
  now.t += PAIRING_TTL_MS * 2
  expect(s.verify(token)?.name).toBe(name)
})
