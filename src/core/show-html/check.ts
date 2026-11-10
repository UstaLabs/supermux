// The render check for #104's show_html: it loads an agent's page the way the web app will (the strictest of the six
// platforms: a sandboxed blob: frame, an opaque origin), at phone and desktop width, and reports what stops a card.
// The spec (docs/superpowers/specs/2026-11-08-show-html-design.md, "The check") is the contract. The evals use this
// same module (evals/show-html/check.ts). Playwright is imported lazily, so a host without it answers "not checked".
import type { Browser, BrowserContext, Frame, Page } from "playwright"
import { inflateSync } from "node:zlib"
import { randomUUID } from "node:crypto"
import { existsSync } from "node:fs"
import { pagePrelude } from "./page-api"
import { startProxy } from "./check-proxy"

// The functions handed to page.evaluate run in the browser; the broker's tsconfig has no DOM lib, so name what they use.
/* eslint-disable @typescript-eslint/no-explicit-any */
declare const document: any
declare const requestAnimationFrame: (cb: (t?: any) => void) => void
declare const window: any
declare function getComputedStyle(el: any): any
type Element = any

/** What stops a card: Ahmet's three. A page that grows with its frame is only a note: the card caps its height. */
export type Problem =
  | { kind: "script-error"; message: string }
  | { kind: "blank" }
  | { kind: "overflow"; width: number; widest?: string }

export interface CheckResult {
  checked: boolean
  reason?: string
  problems: Problem[]
  notes: string[]
  heights: { phone: number; desktop: number }
  /** supermux.send calls the page made with no click: the guard refuses them; the evals count them. */
  unpromptedSends: string[]
  /** The colour the page paints behind itself (html or body), if it isn't transparent. */
  background?: string
  endlessHeight?: boolean
  screenshots?: { phone: Buffer; desktop: Buffer }
}

export const PHONE = { width: 390, height: 844 }
export const DESKTOP = { width: 720, height: 900 }
const SETTLE_QUIET_MS = 1000
const SETTLE_CAP_MS = 4000
// The spec's cap is 6 s; on a loaded host (the evals' box sits at load 30-60) slowness must not pass for "not checked".
const defaultCapMs = () => Number(process.env.SHOW_HTML_CHECK_CAP_MS) || 6000
const MAX_CHECKS = 2
const IDLE_CLOSE_MS = 5 * 60_000
const HOST = "https://host.check.invalid/"

// System Chrome, then Edge, then Chromium (Brave last); MUX_CHECK_BROWSER overrides all.
const CHROMES = [
  "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
  "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
  "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
  "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
  "/usr/bin/microsoft-edge", "/usr/bin/microsoft-edge-stable",
  "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
  "C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe",
  "C:\\Program Files\\Microsoft\\Edge\\Application\\msedge.exe",
  "/usr/bin/chromium", "/usr/bin/chromium-browser", "/snap/bin/chromium",
  "/Applications/Chromium.app/Contents/MacOS/Chromium",
  "/usr/bin/brave-browser",
]

export function findBrowser(): string | undefined {
  const override = process.env.MUX_CHECK_BROWSER
  if (override) return existsSync(override) ? override : undefined
  return CHROMES.find((p) => existsSync(p))
}

/** Chrome set up for one SOCKS5 proxy per check (startProxy), which refuses private addresses after resolving. */
export async function launchBrowser(): Promise<Browser | undefined> {
  const executablePath = findBrowser()
  if (!executablePath) return undefined
  const { chromium } = await import("playwright")
  return chromium.launch({
    executablePath,
    headless: true,
    env: { ...process.env, DBUS_SESSION_BUS_ADDRESS: "disabled:" },
    proxy: { server: "per-context" },
    args: ["--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--webrtc-ip-handling-policy=disable_non_proxied_udp"],
  })
}

/** The card as the app frames it: the page API first, then the agent's document or fragment. */
export function cardDocument(html: string, theme: "dark" | "light" = "dark", nonce = randomUUID()): string {
  const prelude = pagePrelude(theme, nonce)
  const doctype = html.match(/^\s*<!doctype[^>]*>/i)
  if (!doctype && !/<html[\s>]/i.test(html)) {
    return `<!doctype html><html data-theme="${theme}"><head><meta charset="utf-8">` +
      `<meta name="viewport" content="width=device-width, initial-scale=1">${prelude}</head><body>${html}</body></html>`
  }
  const rest = doctype ? html.slice(doctype[0].length) : html
  const head = rest.match(/<head(?=[\s>])[^>]*>/i)
  const body = head
    ? rest.slice(0, head.index! + head[0].length) + prelude + rest.slice(head.index! + head[0].length)
    : prelude + rest
  return `${doctype ? doctype[0] : "<!doctype html>"}${body}`
}

// The host declares the card's colour scheme: a frame whose scheme differs from its embedder's gets an opaque
// canvas, which would hide a page painted in the background's own colour (and lose the card's transparency).
function hostPage(card: string, nonce: string): string {
  return `<!doctype html><html><head><meta charset="utf-8"><style>
html{color-scheme:dark} html,body{margin:0;background:#16181a} iframe{display:block;border:0;width:100%;height:80px}
</style></head><body><iframe id="card" sandbox="allow-scripts" title="card"></iframe><script>
window.__sends = [];
const card = document.getElementById("card");
let opened = false;
addEventListener("message", (e) => {
  if (opened || e.source !== card.contentWindow || !e.data || e.data.sm !== "hello" || e.data.nonce !== ${JSON.stringify(nonce)} || !e.ports[0]) return;
  opened = true;
  e.ports[0].onmessage = (m) => { if (m.data && (m.data.sm === "send" || m.data.sm === "refused")) window.__sends.push(String(m.data.text).slice(0, 2000)); };
});
card.src = URL.createObjectURL(new Blob([${JSON.stringify(card).replace(/</g, "\\u003c")}], { type: "text/html" }));
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
    for (const el of (Array.from(document.body?.querySelectorAll("*") ?? []) as Element[])) {
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

/** At most `max` tasks at once; the rest wait in order. */
export function createLimiter(max: number): <T>(fn: () => Promise<T>) => Promise<T> {
  let running = 0
  const queue: (() => void)[] = []
  return async (fn) => {
    // A finishing task hands its slot straight to the next waiter, so a newcomer can't jump the queue.
    if (running >= max) await new Promise<void>((r) => queue.push(r))
    else running++
    try { return await fn() } finally { const next = queue.shift(); if (next) next(); else running-- }
  }
}
const slot = createLimiter(MAX_CHECKS)

/** The harness's entry: its own browser, screenshots, an extra blocked-host list, and the full result. */
export function checkWithBrowser(html: string, browser: Browser | undefined,
  o: { screenshots?: boolean; blockedHosts?: string[]; capMs?: number } = {}): Promise<CheckResult> {
  const capMs = o.capMs ?? defaultCapMs()
  const empty = { problems: [], notes: [], heights: { phone: 0, desktop: 0 }, unpromptedSends: [] }
  if (!browser) return Promise.resolve({ checked: false, reason: "no Chrome, Edge or Chromium on this machine", ...empty })
  return slot(async () => {
    const proxy = await startProxy(o.blockedHosts)
    const context = await browser.newContext({
      viewport: PHONE, colorScheme: "dark", reducedMotion: "reduce", deviceScaleFactor: 1,
      proxy: { server: `socks5://127.0.0.1:${proxy.port}`, bypass: "<-loopback>" },
    })
    let timer: ReturnType<typeof setTimeout> | undefined
    // A page that hasn't rendered by the cap is a problem, not a pass: a stuck script would otherwise be shown unchecked.
    const capped = new Promise<CheckResult>((resolve) => {
      timer = setTimeout(() => resolve({ checked: true, ...empty,
        problems: [{ kind: "script-error", message: `the page didn't finish rendering within ${capMs / 1000} s (a script may be stuck)` }] }), capMs)
    })
    try {
      return await Promise.race([measure(context, html, proxy.refused, o), capped])
    } finally {
      clearTimeout(timer)
      await context.close().catch(() => {})
      proxy.stop()
    }
  })
}

async function measure(context: BrowserContext, html: string, refused: string[], o: { screenshots?: boolean }): Promise<CheckResult> {
  const problems: Problem[] = []
  const notes: string[] = []
  const nonce = randomUUID()
  try {
    await context.route(HOST, (route) => route.fulfill({ contentType: "text/html", body: hostPage(cardDocument(html, "dark", nonce), nonce) }))
    const page = await context.newPage()
    page.on("pageerror", (e) => problems.push({ kind: "script-error", message: e.message.split("\n")[0]!.slice(0, 300) }))
    page.on("console", (m) => { if (m.type() === "error" && notes.length < 5 && !m.text().startsWith("Failed to load resource")) notes.push(`console.error: ${m.text().slice(0, 200)}`) })
    page.on("requestfailed", (r) => {
      const url = r.url()
      if (url.startsWith("blob:") || url.startsWith("data:")) return
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
    await frame.evaluate(() => Promise.race([
      document.fonts.ready.then(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))),
      new Promise((r) => setTimeout(r, 500)),
    ])).catch(() => {})

    // A page that grows with its frame (vh units, height:100%, a 100vh child): the card would grow to its cap.
    const bottom = () => frame.evaluate(() => Math.max(document.documentElement.getBoundingClientRect().height,
      ...(Array.from(document.body?.querySelectorAll("*") ?? []) as Element[]).map((e) => e.getBoundingClientRect().bottom)))
    await setFrameHeight(page, 600)
    const at600 = await bottom()
    await setFrameHeight(page, 1200)
    const at1200 = await bottom()
    const endless = at600 >= 480 && at1200 >= at600 + 400
    if (endless) notes.push("the page's height follows its frame (vh units or height:100%): the card stops it at its cap")

    const phoneHeight = endless ? PHONE.height : await contentHeight(page, frame)
    const over = await overflow(frame, PHONE.width)
    if (over) problems.push({ kind: "overflow", ...over })
    await setFrameHeight(page, Math.max(80, Math.min(phoneHeight, 1600)))
    const phoneShot = await page.locator("#card").screenshot()
    if (isBlank(phoneShot)) problems.push({ kind: "blank" })
    const background = await frame.evaluate(() => {
      const paint = (el: Element | null) => (el ? getComputedStyle(el).backgroundColor : "")
      const clear = (c: string) => !c || c === "transparent" || /^rgba\(.*,\s*0\)$/.test(c)
      const b = [paint(document.documentElement), paint(document.body)].find((c) => !clear(c))
      return b ?? null
    })

    await page.setViewportSize(DESKTOP)
    await page.waitForTimeout(60)
    const desktopHeight = endless ? DESKTOP.height : await contentHeight(page, frame)
    await setFrameHeight(page, Math.max(80, Math.min(desktopHeight, 1600)))
    const desktopShot = o.screenshots ? await page.locator("#card").screenshot() : undefined

    for (const r of refused) notes.push(`refused a local address: ${r}`)
    const unpromptedSends = await page.evaluate(() => (window as unknown as { __sends: string[] }).__sends)
    return {
      checked: true,
      problems: dedupe(problems),
      notes: [...new Set(notes)].slice(0, 8),
      heights: { phone: phoneHeight, desktop: desktopHeight },
      unpromptedSends,
      ...(background ? { background } : {}),
      ...(endless ? { endlessHeight: true } : {}),
      ...(o.screenshots && desktopShot ? { screenshots: { phone: phoneShot, desktop: desktopShot } } : {}),
    }
  } catch (e) {
    return { checked: false, reason: `the check failed: ${(e as Error).message.split("\n")[0]}`, problems, notes, heights: { phone: 0, desktop: 0 }, unpromptedSends: [] }
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

// ---- The product's entry: one shared browser, launched lazily, closed after 5 idle minutes or when the broker stops.

export interface PageCheck {
  problems: Problem[]
  notes: string[]
  /** The content's height at phone width, in px (measured with the frame at 80 px). */
  height?: number
  /** The content's height at the desktop chat column's width. */
  desktopHeight?: number
  /** The colour the page paints behind itself, if it isn't transparent. */
  background?: string
}

export const NO_BROWSER_NOTE = "not checked: no Chrome, Edge or Chromium on the host"

let shared: Promise<Browser | undefined> | undefined
let idleTimer: ReturnType<typeof setTimeout> | undefined
let active = 0

function sharedBrowser(): Promise<Browser | undefined> {
  if (!shared) {
    const p: Promise<Browser | undefined> = launchBrowser().then((b) => {
      b?.on("disconnected", () => { if (shared === p) shared = undefined })
      return b
    })
    shared = p
    // A failed launch isn't remembered: the next check tries again.
    p.catch(() => { if (shared === p) shared = undefined })
  }
  return shared
}

/** Close the shared browser (the broker stopping, or idle). The next check launches a fresh one. */
export async function closeCheckBrowser(): Promise<void> {
  clearTimeout(idleTimer)
  const p = shared
  shared = undefined
  await (await p?.catch(() => undefined))?.close().catch(() => {})
}

function armIdle(): void {
  clearTimeout(idleTimer)
  idleTimer = setTimeout(() => { if (active === 0) void closeCheckBrowser() }, IDLE_CLOSE_MS)
  idleTimer.unref?.()
}

export async function checkPage(html: string, opts: { capMs?: number } = {}): Promise<PageCheck> {
  if (!findBrowser()) return { problems: [], notes: [NO_BROWSER_NOTE] }
  active++
  clearTimeout(idleTimer)
  try {
    const browser = await sharedBrowser()
    if (!browser) return { problems: [], notes: [NO_BROWSER_NOTE] }
    const r = await checkWithBrowser(html, browser, { capMs: opts.capMs })
    return toPageCheck(r)
  } catch (e) {
    return { problems: [], notes: [`not checked: the browser would not start: ${(e as Error).message.split("\n")[0]}`] }
  } finally {
    active--
    armIdle()
  }
}

export function toPageCheck(r: CheckResult): PageCheck {
  if (!r.checked) return { problems: r.problems, notes: [`not checked: ${r.reason ?? "the check did not run"}`, ...r.notes] }
  return {
    problems: r.problems, notes: r.notes,
    ...(r.heights.phone ? { height: r.heights.phone, desktopHeight: r.heights.desktop } : {}),
    ...(r.background ? { background: r.background } : {}),
  }
}
