// The render check for #104's show_html evals: it loads an agent's page the way the web app will (the strictest of
// the six platforms: a sandboxed blob: frame, an opaque origin), at phone and desktop width, and reports what stops a
// card. The spec (docs/superpowers/specs/2026-11-08-show-html-design.md, "The check") is the contract; the broker's
// module will implement the same rules over raw CDP.
import { chromium, type Browser, type Frame, type Page } from "playwright"
import { inflateSync } from "node:zlib"
import { existsSync } from "node:fs"
import { pagePrelude } from "./page-api"

export type Problem =
  | { kind: "script-error"; message: string }
  | { kind: "blank" }
  | { kind: "overflow"; width: number; widest?: string }
  | { kind: "endless-height" }

export interface CheckResult {
  checked: boolean
  reason?: string
  problems: Problem[]
  notes: string[]
  heights: { phone: number; desktop: number }
  /** supermux.send calls the page made with no click: the guard refuses them; the evals count them. */
  unpromptedSends: string[]
  screenshots?: { phone: Buffer; desktop: Buffer }
}

export const PHONE = { width: 390, height: 844 }
export const DESKTOP = { width: 720, height: 900 }
const SETTLE_QUIET_MS = 1000
const SETTLE_CAP_MS = 4000
const HOST = "https://host.check.invalid/"

const CHROMES = [
  process.env.MUX_CHECK_BROWSER,
  "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable", "/usr/bin/chromium", "/usr/bin/chromium-browser",
  "/usr/bin/microsoft-edge", "/usr/bin/brave-browser",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
]

export function findBrowser(): string | undefined {
  return CHROMES.find((p): p is string => !!p && existsSync(p))
}

export async function launchBrowser(): Promise<Browser | undefined> {
  const executablePath = findBrowser()
  if (!executablePath) return undefined
  return chromium.launch({
    executablePath,
    headless: true,
    args: ["--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--webrtc-ip-handling-policy=disable_non_proxied_udp"],
  })
}

/** Loopback, private, link-local and CGNAT (Tailscale) addresses: what the host can reach and the page must not. */
export function isPrivateHost(host: string): boolean {
  const h = host.replace(/^\[|\]$/g, "").toLowerCase()
  if (h === "localhost" || h.endsWith(".localhost") || h.endsWith(".local") || h.endsWith(".internal") || h === "0.0.0.0") return true
  const v4 = h.match(/^(\d+)\.(\d+)\.(\d+)\.(\d+)$/)
  if (v4) {
    const [a, b] = [Number(v4[1]), Number(v4[2])]
    return a === 127 || a === 10 || a === 0 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168) ||
      (a === 169 && b === 254) || (a === 100 && b >= 64 && b <= 127)
  }
  if (h.includes(":")) return h === "::" || h === "::1" || /^f[cd]/.test(h) || /^fe[89ab]/.test(h) || h.startsWith("::ffff:")
  return false
}

/** The card as the app frames it: the page API first, then the agent's document or fragment. */
export function cardDocument(html: string, theme: "dark" | "light" = "dark"): string {
  const prelude = pagePrelude(theme)
  const doctype = html.match(/^\s*<!doctype[^>]*>/i)
  if (!doctype && !/<html[\s>]/i.test(html)) {
    return `<!doctype html><html data-theme="${theme}"><head><meta charset="utf-8">` +
      `<meta name="viewport" content="width=device-width, initial-scale=1">${prelude}</head><body>${html}</body></html>`
  }
  const rest = doctype ? html.slice(doctype[0].length) : html
  const head = rest.match(/<head[^>]*>/i)
  const body = head
    ? rest.slice(0, head.index! + head[0].length) + prelude + rest.slice(head.index! + head[0].length)
    : prelude + rest
  return `${doctype ? doctype[0] : "<!doctype html>"}${body}`
}

// The host declares the card's colour scheme: a frame whose scheme differs from its embedder's gets an opaque
// canvas, which would hide a page painted in the background's own colour (and lose the card's transparency).
function hostPage(card: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
html{color-scheme:dark} html,body{margin:0;background:#16181a} iframe{display:block;border:0;width:100%;height:80px}
</style></head><body><iframe id="card" sandbox="allow-scripts" title="card"></iframe><script>
window.__sends = [];
addEventListener("message", (e) => {
  const card = document.getElementById("card");
  if (e.source !== card.contentWindow || !e.data || (e.data.sm !== "send" && e.data.sm !== "refused")) return;
  window.__sends.push(String(e.data.text).slice(0, 2000));
});
document.getElementById("card").src = URL.createObjectURL(new Blob([${JSON.stringify(card).replace(/</g, "\\u003c")}], { type: "text/html" }));
</script></body></html>`
}

async function cardFrame(page: Page): Promise<Frame> {
  const deadline = Date.now() + 3000
  for (;;) {
    const f = page.frames().find((fr) => fr !== page.mainFrame())
    if (f && f.url().startsWith("blob:")) return f
    if (Date.now() > deadline) throw new Error("the card frame never loaded")
    await page.waitForTimeout(25)
  }
}

async function setFrameHeight(page: Page, px: number): Promise<void> {
  await page.evaluate((h) => { document.getElementById("card")!.style.height = `${h}px` }, px)
  await page.waitForTimeout(60)
}

/** The root element's layout height: the content's, unless the page ties it to the frame (vh, height:100%). */
async function rootHeight(frame: Frame): Promise<number> {
  return frame.evaluate(() => Math.ceil(document.documentElement.getBoundingClientRect().height))
}

async function contentHeight(page: Page, frame: Frame): Promise<number> {
  await setFrameHeight(page, 80)
  return Math.max(await rootHeight(frame), await frame.evaluate(() => document.body?.scrollHeight ?? 0))
}

async function overflow(frame: Frame, width: number): Promise<{ width: number; widest?: string } | undefined> {
  return frame.evaluate((w) => {
    const sw = document.documentElement.scrollWidth
    if (sw <= w + 1) return undefined
    let widest: Element | undefined
    let right = w
    for (const el of Array.from(document.body?.querySelectorAll("*") ?? [])) {
      const r = el.getBoundingClientRect().right
      if (r > right + 0.5) { right = r; widest = el }
    }
    const name = widest
      ? widest.tagName.toLowerCase() + (widest.id ? `#${widest.id}` : "") +
        (typeof widest.className === "string" && widest.className.trim() ? `.${widest.className.trim().split(/\s+/).join(".")}` : "")
      : undefined
    return { width: sw, ...(name ? { widest: name } : {}) }
  }, width)
}

/** Nothing painted: all but a few pixels are one colour (theme mismatches, an unrendered app root, …). */
export function isBlank(png: Buffer): boolean {
  const { width, height, rgba } = decodePng(png)
  if (width * height === 0) return true
  const counts = new Map<number, number>()
  for (let i = 0; i < rgba.length; i += 4) {
    const key = (rgba[i]! << 16) | (rgba[i + 1]! << 8) | rgba[i + 2]!
    counts.set(key, (counts.get(key) ?? 0) + 1)
  }
  let dominant = 0, most = 0
  for (const [k, n] of counts) if (n > most) { most = n; dominant = k }
  const [r, g, b] = [dominant >> 16, (dominant >> 8) & 255, dominant & 255]
  let visible = 0
  for (let i = 0; i < rgba.length; i += 4) {
    if (Math.max(Math.abs(rgba[i]! - r), Math.abs(rgba[i + 1]! - g), Math.abs(rgba[i + 2]! - b)) > 8 && ++visible >= 40) return false
  }
  return true
}

export async function checkPage(html: string, browser: Browser | undefined, o: { screenshots?: boolean } = {}): Promise<CheckResult> {
  const empty = { problems: [], notes: [], heights: { phone: 0, desktop: 0 }, unpromptedSends: [] }
  if (!browser) return { checked: false, reason: "no Chrome, Edge or Chromium on this machine", ...empty }
  const context = await browser.newContext({ viewport: PHONE, colorScheme: "dark", deviceScaleFactor: 1 })
  const problems: Problem[] = []
  const notes: string[] = []
  try {
    await context.route("**/*", (route) => {
      const url = new URL(route.request().url())
      if (url.href === HOST) return route.fulfill({ contentType: "text/html", body: hostPage(cardDocument(html)) })
      if (url.protocol === "blob:" || url.protocol === "data:") return route.continue()
      if (url.protocol === "file:" || isPrivateHost(url.hostname)) return route.abort("blockedbyclient")
      return route.continue()
    })
    const page = await context.newPage()
    page.on("pageerror", (e) => problems.push({ kind: "script-error", message: e.message.split("\n")[0]!.slice(0, 300) }))
    page.on("console", (m) => { if (m.type() === "error" && notes.length < 5) notes.push(`console.error: ${m.text().slice(0, 200)}`) })
    page.on("requestfailed", (r) => {
      const url = r.url()
      if (url.startsWith("blob:")) return
      if (r.resourceType() === "script") problems.push({ kind: "script-error", message: `a script failed to load: ${url.slice(0, 200)}` })
      else notes.push(`failed to load: ${url.slice(0, 200)}`)
    })
    page.on("response", (r) => {
      if (r.status() < 400 || r.url() === HOST) return
      if (r.request().resourceType() === "script") problems.push({ kind: "script-error", message: `a script failed to load (${r.status()}): ${r.url().slice(0, 200)}` })
      else notes.push(`failed to load (${r.status()}): ${r.url().slice(0, 200)}`)
    })

    const started = Date.now()
    await page.goto(HOST, { waitUntil: "domcontentloaded", timeout: SETTLE_CAP_MS })
    const frame = await cardFrame(page)
    await frame.waitForLoadState("load", { timeout: SETTLE_CAP_MS }).catch(() => notes.push("the page was still loading after 4 s"))
    const left = SETTLE_CAP_MS - (Date.now() - started)
    if (left > 0) await page.waitForLoadState("networkidle", { timeout: Math.min(left, SETTLE_QUIET_MS * 2) }).catch(() => {})

    // Endless height: the page grows with its frame (vh units, height:100% on html/body).
    await setFrameHeight(page, 600)
    const at600 = await rootHeight(frame)
    await setFrameHeight(page, 1200)
    const at1200 = await rootHeight(frame)
    const endless = at600 >= 590 && at1200 >= at600 + 400
    if (endless) problems.push({ kind: "endless-height" })

    const phoneHeight = endless ? PHONE.height : await contentHeight(page, frame)
    const over = await overflow(frame, PHONE.width)
    if (over) problems.push({ kind: "overflow", ...over })
    await setFrameHeight(page, Math.max(80, Math.min(phoneHeight, 1600)))
    const phoneShot = await page.locator("#card").screenshot()
    if (isBlank(phoneShot)) problems.push({ kind: "blank" })

    await page.setViewportSize(DESKTOP)
    await page.waitForTimeout(60)
    const desktopHeight = endless ? DESKTOP.height : await contentHeight(page, frame)
    await setFrameHeight(page, Math.max(80, Math.min(desktopHeight, 1600)))
    const desktopShot = o.screenshots ? await page.locator("#card").screenshot() : undefined

    const unpromptedSends = await page.evaluate(() => (window as unknown as { __sends: string[] }).__sends)
    return {
      checked: true,
      problems: dedupe(problems),
      notes: [...new Set(notes)].slice(0, 8),
      heights: { phone: phoneHeight, desktop: desktopHeight },
      unpromptedSends,
      ...(o.screenshots && desktopShot ? { screenshots: { phone: phoneShot, desktop: desktopShot } } : {}),
    }
  } catch (e) {
    return { checked: false, reason: `the check failed: ${(e as Error).message.split("\n")[0]}`, ...empty, problems, notes }
  } finally {
    await context.close().catch(() => {})
  }
}

function dedupe(problems: Problem[]): Problem[] {
  const seen = new Set<string>()
  return problems.filter((p) => {
    const key = p.kind === "script-error" ? `${p.kind}:${p.message}` : p.kind
    if (seen.has(key)) return false
    seen.add(key)
    return true
  }).slice(0, 6)
}

export function describeProblem(p: Problem): string {
  switch (p.kind) {
    case "script-error": return `script error: ${p.message}`
    case "blank": return "blank: nothing is visible at phone width"
    case "overflow": return `overflow at phone width: the page is ${p.width} px wide in a ${PHONE.width} px frame${p.widest ? ` (widest: ${p.widest})` : ""}`
    case "endless-height": return "endless height: the page grows with its frame (vh units or height:100% on html/body), so a card would grow forever"
  }
}

/** A minimal PNG reader for Chrome's screenshots: 8-bit RGB/RGBA, not interlaced. */
export function decodePng(png: Buffer): { width: number; height: number; rgba: Uint8Array } {
  let p = 8
  let width = 0, height = 0, colorType = 6
  const idat: Buffer[] = []
  while (p < png.length) {
    const len = png.readUInt32BE(p)
    const type = png.toString("latin1", p + 4, p + 8)
    const data = png.subarray(p + 8, p + 8 + len)
    if (type === "IHDR") {
      width = data.readUInt32BE(0); height = data.readUInt32BE(4); colorType = data[9]!
      if (data[8] !== 8 || data[12] !== 0 || (colorType !== 6 && colorType !== 2)) throw new Error("unsupported PNG")
    } else if (type === "IDAT") idat.push(data)
    else if (type === "IEND") break
    p += 12 + len
  }
  const bpp = colorType === 6 ? 4 : 3
  const raw = inflateSync(Buffer.concat(idat))
  const stride = width * bpp
  const out = new Uint8Array(width * height * 4)
  const prev = new Uint8Array(stride)
  const cur = new Uint8Array(stride)
  for (let y = 0; y < height; y++) {
    const filter = raw[y * (stride + 1)]!
    const line = raw.subarray(y * (stride + 1) + 1, (y + 1) * (stride + 1))
    for (let x = 0; x < stride; x++) {
      const a = x >= bpp ? cur[x - bpp]! : 0
      const b = prev[x]!
      const c = x >= bpp ? prev[x - bpp]! : 0
      const v = line[x]!
      cur[x] = filter === 0 ? v : filter === 1 ? v + a : filter === 2 ? v + b : filter === 3 ? v + ((a + b) >> 1)
        : v + paeth(a, b, c)
    }
    for (let x = 0; x < width; x++) {
      out.set([cur[x * bpp]!, cur[x * bpp + 1]!, cur[x * bpp + 2]!, bpp === 4 ? cur[x * bpp + 3]! : 255], (y * width + x) * 4)
    }
    prev.set(cur)
  }
  return { width, height, rgba: out }
}

function paeth(a: number, b: number, c: number): number {
  const p = a + b - c
  const pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c)
  return pa <= pb && pa <= pc ? a : pb <= pc ? b : c
}
