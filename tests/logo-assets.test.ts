import { describe, expect, test } from "bun:test"
import { readFileSync, statSync } from "node:fs"
import { join } from "node:path"

const ROOT = join(import.meta.dir, "..")

function pngSize(path: string): { w: number; h: number } {
  const buf = readFileSync(path)
  // PNG magic: 89 50 4E 47 0D 0A 1A 0A then IHDR chunk
  // IHDR starts at byte 8; length=4 + type=4, then width=4 + height=4 (big-endian)
  if (buf.readUInt32BE(0) !== 0x89504e47) throw new Error(`not a PNG: ${path}`)
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20) }
}

const persistentPngs: Array<[string, number]> = [
  ["src/web-app/public/icons/icon-192.png", 192],
  ["src/web-app/public/icons/icon-512.png", 512],
  ["src/web-app/public/icons/icon-mask.png", 512],
  ["src/web-app/public/icons/apple-touch-icon.png", 180],
  ["assets/logo/telegram-avatar.png", 640],
]

describe("logo assets", () => {
  test("master SVG exists and is non-empty", () => {
    const p = join(ROOT, "assets/logo/supermux.svg")
    expect(statSync(p).size).toBeGreaterThan(200)
  })

  test("favicon.svg exists and is non-empty", () => {
    const p = join(ROOT, "src/web-app/public/favicon.svg")
    expect(statSync(p).size).toBeGreaterThan(200)
  })

  test("favicon.ico exists with correct header and >=3 entries", () => {
    const p = join(ROOT, "src/web-app/public/favicon.ico")
    const buf = readFileSync(p)
    expect(buf.length).toBeGreaterThan(500)
    // ICONDIR: reserved(2)=0, type(2)=1, count(2)
    expect(buf.readUInt16LE(0)).toBe(0)
    expect(buf.readUInt16LE(2)).toBe(1)
    expect(buf.readUInt16LE(4)).toBeGreaterThanOrEqual(3)
  })

  for (const [rel, size] of persistentPngs) {
    test(`${rel} is ${size}×${size}`, () => {
      const p = join(ROOT, rel)
      const { w, h } = pngSize(p)
      expect(w).toBe(size)
      expect(h).toBe(size)
    })
  }
})
