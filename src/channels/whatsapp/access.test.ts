import { describe, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { loadWhatsAppAccess, isWhatsAppAllowed } from "./access"

function withFile(json: string): string {
  const dir = mkdtempSync(join(tmpdir(), "wa-access-"))
  const p = join(dir, "access.json")
  writeFileSync(p, json)
  return p
}

describe("whatsapp access", () => {
  test("allows a listed number, matching despite JID suffix", () => {
    const p = withFile(JSON.stringify({ whatsapp: { allowFrom: ["628123456789"] } }))
    const acc = loadWhatsAppAccess(p)
    expect(isWhatsAppAllowed(acc, "628123456789@s.whatsapp.net")).toBe(true)
    expect(isWhatsAppAllowed(acc, "628123456789:12@s.whatsapp.net")).toBe(true)
    rmSync(p, { force: true })
  })
  test("denies unlisted numbers", () => {
    const p = withFile(JSON.stringify({ whatsapp: { allowFrom: ["628123456789"] } }))
    expect(isWhatsAppAllowed(loadWhatsAppAccess(p), "447700900000@s.whatsapp.net")).toBe(false)
    rmSync(p, { force: true })
  })
  test("deny-by-default on missing/empty file", () => {
    expect(loadWhatsAppAccess("/no/such/file.json")).toEqual({ allowFrom: [] })
    expect(isWhatsAppAllowed({ allowFrom: [] }, "628123456789@s.whatsapp.net")).toBe(false)
  })
})
