// #97: a device can be renamed. The name is the device's key, so the rename moves the record and
// whatever names it (a pending code's maker, the rename listeners' push rows and uploads).
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { DeviceStore, DEVICE_NAME_MAX } from "./device-store"

const dirs: string[] = []
afterEach(() => { for (const d of dirs.splice(0)) rmSync(d, { recursive: true, force: true }) })

function store(now = { t: 1_000_000 }): { s: DeviceStore; file: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-rename-"))
  dirs.push(dir)
  const file = join(dir, "devices.json")
  return { s: new DeviceStore(file, { clock: () => now.t }), file }
}

test("rename moves the device: its token signs in under the new name, the old name is gone", () => {
  const { s, file } = store()
  const { token } = s.mint("phone")
  const before = s.list().find((d) => d.name === "phone")!
  const r = s.rename("phone", "  Pixel  ")
  expect(r).toEqual({ ok: true, device: expect.objectContaining({ name: "Pixel", created_at: before.created_at }) })
  expect(s.verify(token)?.name).toBe("Pixel")
  expect(s.list().map((d) => d.name)).toEqual(["Pixel"])
  // Another store on the same file (another process) reads it too.
  expect(new DeviceStore(file).verify(token)?.name).toBe("Pixel")
})

test("a name that is empty after trimming, or too long, is refused", () => {
  const { s } = store()
  s.mint("phone")
  expect(s.rename("phone", "   ")).toEqual({ ok: false, reason: "invalid" })
  expect(s.rename("phone", "x".repeat(DEVICE_NAME_MAX + 1))).toEqual({ ok: false, reason: "invalid" })
  expect(s.rename("phone", "x".repeat(DEVICE_NAME_MAX)).ok).toBe(true)
})

test("another device's name is refused, and nothing changes", () => {
  const { s } = store()
  s.mint("phone")
  s.mint("laptop")
  expect(s.rename("phone", " laptop ")).toEqual({ ok: false, reason: "taken" })
  expect(s.list().map((d) => d.name).sort()).toEqual(["laptop", "phone"])
})

test("an unknown, revoked, or expired-code device is unknown", () => {
  const now = { t: 1_000_000 }
  const { s } = store(now)
  expect(s.rename("ghost", "x")).toEqual({ ok: false, reason: "unknown" })
  s.mint("old")
  s.revoke("old")
  expect(s.rename("old", "x")).toEqual({ ok: false, reason: "unknown" })
  const { name } = s.mintPairing("tablet")
  now.t += 11 * 60_000
  expect(s.rename(name, "x")).toEqual({ ok: false, reason: "unknown" })
})

test("renaming to the same name changes nothing and tells no listener", () => {
  const { s } = store()
  s.mint("phone")
  const heard: string[][] = []
  s.addRenameListener((from, to) => heard.push([from, to]))
  expect(s.rename("phone", " phone ")).toEqual({ ok: true, device: expect.objectContaining({ name: "phone" }) })
  expect(heard).toEqual([])
})

test("the rename listeners hear the old and the new name; a revoke listener hears nothing", () => {
  const { s } = store()
  s.mint("phone")
  const heard: string[][] = []
  const revoked: string[] = []
  s.addRenameListener((from, to) => heard.push([from, to]))
  s.addRevokeListener((n) => revoked.push(n))
  s.rename("phone", "Pixel")
  expect(heard).toEqual([["phone", "Pixel"]])
  expect(revoked).toEqual([])
})

test("a renamed device's next code still revokes the code it made before the rename", () => {
  const { s } = store()
  s.mint("laptop")
  const first = s.mintPairing("phone", { by: "laptop" })
  s.rename("laptop", "Work laptop")
  s.mintPairing("phone", { by: "Work laptop" })
  expect(s.verify(first.token)).toBeUndefined()
})
