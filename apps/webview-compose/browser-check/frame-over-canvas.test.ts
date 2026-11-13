// The web engine's frame over Compose's canvas, in real Chrome, read from the screen (#104). The frame lies above the
// WebGL canvas; while it was opaque, Chrome culled the canvas at the frame's place mirrored top to bottom, and a band
// of the host page's black showed over the chat under a card. A shown frame is at WebFrame.kt's SHOWN_OPACITY, which
// must stay under what Chrome rounds to opaque.
//   WEBVIEW_PIXELS=1 bun test apps/webview-compose/browser-check
import { afterAll, beforeAll, expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { inflateSync } from "node:zlib"
import { findBrowser, launchBrowser, type CheckBrowser } from "../../../src/core/show-html/check"

const FRAME_KT = join(import.meta.dir, "../src/wasmJsMain/kotlin/dev/supermux/webview/engine/web/WebFrame.kt")

/** WebFrame.kt's SHOWN_OPACITY, read from the source so this check follows it. */
function shownOpacity(): string {
  const m = readFileSync(FRAME_KT, "utf8").match(/const val SHOWN_OPACITY = "([0-9.]+)"/)
  if (!m) throw new Error("no SHOWN_OPACITY in WebFrame.kt")
  return m[1]
}

test("a shown frame's opacity is under what Chrome rounds to opaque", () => {
  // Chrome keeps a layer's opacity in 8 bits: 0.999 rounds to 255 and is opaque (measured), 0.998 to 254 is not.
  const op = Number(shownOpacity())
  expect(Math.round(op * 255)).toBeLessThan(255)
  // And it changes no colour by more than a level.
  expect(Math.round(op * 255)).toBeGreaterThanOrEqual(254)
})

const W = 440
const H = 956
const CHAT = [243, 244, 239]
const BODY = [11, 11, 11]

/** The app's shape: the host page's black body, a WebGL canvas filling the window in the chat's colour, a frame above it. */
function page(opacity: string): string {
  return `<!doctype html><html><head><style>html,body{margin:0;height:100%;background:rgb(${BODY});overflow:hidden}
canvas{position:absolute;left:0;top:0}</style></head><body><canvas id="c" width="${W}" height="${H}"></canvas>
<div style="position:absolute;left:0;top:0;width:0;height:0">
<div style="position:absolute;left:12px;top:217px;width:416px;height:300px;clip-path:inset(0px);opacity:${opacity}">
<div style="position:absolute;left:0;top:0;width:100%;height:100%;overflow:hidden">
<iframe sandbox="allow-scripts" style="position:absolute;left:0;top:0;width:100%;height:100%;border:0"
 srcdoc="<html style='background:#fff'><body>a page with its own background</body></html>"></iframe></div></div></div>
<script>const gl = document.getElementById("c").getContext("webgl2", { antialias: false });
gl.clearColor(${CHAT.map((v) => v / 255)}, 1); gl.clear(gl.COLOR_BUFFER_BIT);</script></body></html>`
}

/** An 8-bit RGB or RGBA PNG, as Chrome's screenshots write them. */
function decodePng(buf: Buffer): { width: number; bpp: number; pixels: Buffer } {
  let pos = 8, width = 0, height = 0, colorType = 0
  const idat: Buffer[] = []
  while (pos < buf.length) {
    const len = buf.readUInt32BE(pos), type = buf.toString("ascii", pos + 4, pos + 8), data = buf.subarray(pos + 8, pos + 8 + len)
    if (type === "IHDR") { width = data.readUInt32BE(0); height = data.readUInt32BE(4); colorType = data[9] }
    else if (type === "IDAT") idat.push(data)
    else if (type === "IEND") break
    pos += 12 + len
  }
  const bpp = colorType === 6 ? 4 : 3
  const raw = inflateSync(Buffer.concat(idat)), stride = width * bpp, pixels = Buffer.alloc(height * stride)
  let prev = Buffer.alloc(stride)
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)], line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1)), cur = pixels.subarray(y * stride, (y + 1) * stride)
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp] : 0, b = prev[x], c = x >= bpp ? prev[x - bpp] : 0
      let v = line[x]
      if (filter === 1) v += a
      else if (filter === 2) v += b
      else if (filter === 3) v += (a + b) >> 1
      else if (filter === 4) { const p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c); v += pa <= pb && pa <= pc ? a : pb <= pc ? b : c }
      cur[x] = v & 255
    }
    prev = cur
  }
  return { width, bpp, pixels }
}

const pixels = !!findBrowser() && process.env.WEBVIEW_PIXELS === "1"
const run = pixels ? test : test.skip
let chrome: CheckBrowser | undefined
// The broker's own Chrome: its sandbox first, without it where the host refuses it.
beforeAll(async () => { if (pixels) chrome = await launchBrowser() }, 45_000)
afterAll(async () => { await chrome?.close() })

/** The rows down the middle column showing the host page's black, after [opacity]'s page has drawn. */
async function blackRows(opacity: string): Promise<number[]> {
  const client = chrome!.client
  const { targetId } = await client.send<{ targetId: string }>("Target.createTarget", { url: "about:blank" })
  const tab = await client.attach(targetId)
  try {
    await tab.send("Emulation.setDeviceMetricsOverride", { width: W, height: H, deviceScaleFactor: 1, mobile: false })
    await tab.send("Page.enable")
    const { frameId } = await tab.send<{ frameId: string }>("Page.getFrameTree").then((t: any) => ({ frameId: t.frameTree.frame.id }))
    await tab.send("Page.setDocumentContent", { frameId, html: page(opacity) })
    await new Promise((r) => setTimeout(r, 1200))
    const { data } = await tab.send<{ data: string }>("Page.captureScreenshot", { format: "png" })
    const png = decodePng(Buffer.from(data, "base64"))
    const rows: number[] = []
    for (let y = 0; y < H; y++) {
      const i = (y * png.width + W / 2) * png.bpp
      if (BODY.every((v, c) => Math.abs(png.pixels[i + c] - v) <= 2)) rows.push(y)
    }
    return rows
  } finally {
    await client.send("Target.closeTarget", { targetId }).catch(() => {})
  }
}

run("an opaque frame blacks out the canvas at its mirrored place: the check sees Chrome's culling", async () => {
  const rows = await blackRows("1")
  // The frame is y 217–517 of 956: mirrored, 439–739; what the frame doesn't cover of that, 517–739.
  expect(rows.length).toBeGreaterThan(150)
  expect(rows[0]).toBeGreaterThanOrEqual(515)
  expect(rows[rows.length - 1]).toBeLessThanOrEqual(741)
}, 30_000)

run("a shown frame leaves the canvas whole", async () => {
  expect(await blackRows(shownOpacity())).toEqual([])
}, 30_000)
