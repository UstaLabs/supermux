// Renders the desktop app's macOS menu-bar ("template") tray icon from the master logo SVG.
// Idempotent — rerun whenever assets/logo/supermux.svg changes:
//
//   bun scripts/generate-tray-template.ts
//
// Outputs (black + alpha only, which is what macOS needs to tint a template image for a light or
// dark menu bar; the desktop app sets apple.awt.enableTemplateImages=true on macOS):
//   apps/desktop/src/main/resources/supermux-tray-template.png     44×44 (the 2× / Retina variant)
//   apps/desktop/src/main/resources/supermux-tray-template-22.png  22×22 (the 1× variant)
//
// The menu bar's icon slot is 22×22 points; the whole master viewBox maps onto it, which leaves the
// mark about 19 pt tall — the margin is the master's own. Windows and Linux keep the colour
// supermux-tray.png.

import { writeFileSync } from "node:fs"
import { readFile } from "node:fs/promises"
import { join } from "node:path"
import { Resvg } from "@resvg/resvg-js"

const ROOT = join(import.meta.dir, "..")
const MASTER_SVG = join(ROOT, "assets/logo/supermux.svg")
const OUT_DIR = join(ROOT, "apps/desktop/src/main/resources")

const outputs: Array<{ file: string; size: number }> = [
  { file: "supermux-tray-template.png", size: 44 },
  { file: "supermux-tray-template-22.png", size: 22 },
]

// Force the mark black, whatever fill the master carries.
const svg = (await readFile(MASTER_SVG, "utf8"))
  .replace(/fill="#[0-9a-fA-F]{3,8}"/g, 'fill="#000000"')

for (const { file, size } of outputs) {
  const img = new Resvg(svg, { fitTo: { mode: "width", value: size } }).render()
  if (img.width !== size || img.height !== size) throw new Error(`${file}: rendered ${img.width}×${img.height}, want ${size}²`)
  // Template images must be pure black where they are not transparent.
  const px = img.pixels
  for (let i = 0; i < px.length; i += 4) {
    if (px[i + 3] !== 0 && (px[i] !== 0 || px[i + 1] !== 0 || px[i + 2] !== 0)) {
      throw new Error(`${file}: non-black pixel at ${i / 4}`)
    }
  }
  writeFileSync(join(OUT_DIR, file), img.asPng())
  console.log(`wrote ${file} (${size}×${size})`)
}
