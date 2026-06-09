// Renders the supermux logo to all target raster variants from the
// master SVG. Idempotent — rerun whenever the master changes.
//
// Outputs:
//   src/web-app/public/icons/icon-192.png        192² transparent  · light mark
//   src/web-app/public/icons/icon-512.png        512² transparent  · light mark
//   src/web-app/public/icons/icon-mask.png       512² dark tile    · light mark · 64px safe zone
//   src/web-app/public/icons/apple-touch-icon.png 180² dark tile   · light mark
//   src/web-app/public/favicon.ico               16/32/48 ICO     · dark mark on transparent
//   assets/logo/telegram-avatar.png              640² dark tile    · light mark

import { mkdir, readFile, writeFile, rm } from "node:fs/promises"
import { join, dirname } from "node:path"
import { Resvg } from "@resvg/resvg-js"
// @ts-ignore — png-to-ico ships no types
import pngToIco from "png-to-ico"

const ROOT = join(import.meta.dir, "..")
const MASTER_SVG = join(ROOT, "assets/logo/supermux.svg")
const TMP_DIR = "/tmp/supermux-logo-build"
const MASTER_VIEWBOX = 1015  // assets/logo/supermux.svg viewBox edge — all variants scale from this

type Variant = {
  out: string                        // path relative to repo root
  size: number                       // square output size, px
  tile: "transparent" | string       // hex bg or "transparent"
  color: string                      // mark colour (hex)
  safeZonePadding?: number           // px padding inside the canvas (for maskable)
}

const variants: Variant[] = [
  { out: "src/web-app/public/icons/icon-192.png",         size: 192, tile: "transparent", color: "#fafafa" },
  { out: "src/web-app/public/icons/icon-512.png",         size: 512, tile: "transparent", color: "#fafafa" },
  { out: "src/web-app/public/icons/icon-mask.png",        size: 512, tile: "#0b0b0b",     color: "#fafafa", safeZonePadding: 64 },
  { out: "src/web-app/public/icons/apple-touch-icon.png", size: 180, tile: "#0a0a0a",     color: "#fafafa" },
  { out: "assets/logo/telegram-avatar.png",               size: 640, tile: "#0a0a0a",     color: "#fafafa" },
]

// Transient — used to assemble favicon.ico only
const faviconSizes = [16, 32, 48] as const

function extractMasterInner(masterSvg: string): string {
  // Pull whatever is between the outer <svg ...> and </svg>
  const m = masterSvg.match(/<svg[^>]*>([\s\S]*)<\/svg>/)
  if (!m) throw new Error("master SVG: cannot find <svg>...</svg>")
  return m[1].trim()
}

function wrapSvg(inner: string, v: Variant): string {
  const pad = v.safeZonePadding ?? 0
  const innerPx = v.size - pad * 2
  const scale = innerPx / MASTER_VIEWBOX
  const tileRect = v.tile === "transparent"
    ? ""
    : `<rect width="${v.size}" height="${v.size}" fill="${v.tile}"/>`
  return `<svg xmlns="http://www.w3.org/2000/svg" width="${v.size}" height="${v.size}" viewBox="0 0 ${v.size} ${v.size}">${tileRect}<g fill="${v.color}" transform="translate(${pad},${pad}) scale(${scale})">${inner}</g></svg>`
}

async function renderPng(inner: string, v: Variant): Promise<Buffer> {
  const svg = wrapSvg(inner, v)
  const resvg = new Resvg(svg, {
    fitTo: { mode: "width", value: v.size },
    background: v.tile === "transparent" ? undefined : v.tile,
  })
  return resvg.render().asPng()
}

async function writeOut(rel: string, buf: Buffer): Promise<void> {
  const abs = join(ROOT, rel)
  await mkdir(dirname(abs), { recursive: true })
  await writeFile(abs, buf)
  console.log(`✓ ${rel} (${buf.length.toLocaleString()} bytes)`)
}

async function main(): Promise<void> {
  const master = await readFile(MASTER_SVG, "utf8")
  const inner = extractMasterInner(master)

  // 1) Persistent PNG variants
  for (const v of variants) {
    const buf = await renderPng(inner, v)
    await writeOut(v.out, buf)
  }

  // 2) Transient favicon PNGs → favicon.ico
  await mkdir(TMP_DIR, { recursive: true })
  const tmpPngs: string[] = []
  for (const size of faviconSizes) {
    const v: Variant = { out: "", size, tile: "transparent", color: "#0a0a0a" }
    const buf = await renderPng(inner, v)
    const path = join(TMP_DIR, `favicon-${size}.png`)
    await writeFile(path, buf)
    tmpPngs.push(path)
  }
  const icoBuf = await pngToIco(tmpPngs)
  await writeOut("src/web-app/public/favicon.ico", icoBuf)
  // Clean up transient PNGs
  for (const p of tmpPngs) await rm(p).catch(() => {})
}

await main()
