// The render check for #104's show_html: it loads an agent's page the way the web app will (the strictest of the six
// platforms: a sandboxed blob: frame, an opaque origin), at phone and desktop width, and reports what stops a card.
// The spec (docs/superpowers/specs/2026-11-08-show-html-design.md, "The check") is the contract. The evals use this
// same module (evals/show-html/check.ts). The browser is driven over the DevTools protocol directly (./cdp.ts): no
// automation library ships with the broker.
import { inflateSync } from "node:zlib"
import { randomUUID } from "node:crypto"
import { existsSync } from "node:fs"
import { pagePrelude } from "./page-api"
import { startProxy } from "./check-proxy"
import { CdpClient, CdpSession, launchChrome } from "./cdp"
import { makeLogger } from "../../shared/log"

const log = makeLogger("show-html")

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

/** A launched Chrome and its DevTools connection. One per broker (checkPage) or per harness run. */
export interface CheckBrowser {
  client: CdpClient
  close(): Promise<void>
  /** Resolves when the browser is gone, however it went. */
  disconnected: Promise<void>
  /** False when the host refused Chrome's own sandbox and the browser runs without it (launchSandboxFirst). */
  sandboxed: boolean
}

export const UNSANDBOXED_NOTE = "checked without Chrome's own sandbox: this host refused it"

/**
 * Chrome's own sandbox first; where the host refuses it (Ubuntu's user-namespace restriction, a container), without it,
 * logged and said in every result (Tally, 2026-11-10). No host is changed to make the sandbox start.
 */
export async function launchSandboxFirst<T>(launch: (sandbox: boolean) => Promise<T>, warn: (msg: string) => void = (m) => log.warn(m)): Promise<{ chrome: T; sandboxed: boolean }> {
  try {
    return { chrome: await launch(true), sandboxed: true }
  } catch (e) {
    warn(`the HTML check's Chrome would not start with its sandbox (${(e as Error).message}); running it without its sandbox`)
    return { chrome: await launch(false), sandboxed: false }
  }
}

/** Chrome set up for one SOCKS5 proxy per check (startProxy), which refuses private addresses after resolving. */
export async function launchBrowser(): Promise<CheckBrowser | undefined> {
  const executable = findBrowser()
  if (!executable) return undefined
  const { chrome, sandboxed } = await launchSandboxFirst((sandbox) => launchChrome(executable, {
    sandbox,
    env: { ...process.env, DBUS_SESSION_BUS_ADDRESS: "disabled:" },
    args: ["--force-webrtc-ip-handling-policy=disable_non_proxied_udp", "--webrtc-ip-handling-policy=disable_non_proxied_udp",
      "--hide-scrollbars", "--mute-audio", "--force-color-profile=srgb", "--disable-extensions", "--disable-sync",
      "--disable-background-networking", "--disable-component-update", "--disable-default-apps"],
  }))
  return { client: chrome.client, close: () => chrome.close(), disconnected: chrome.client.closed, sandboxed }
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

// ---- Talking to the page: the page's target, and the card's, which Chrome puts in its own process (a sandboxed frame).

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/** Run a function in a target's page (the card's frame when `contextId` names it) and return its value. */
async function evalIn<T>(s: CdpSession, fn: (...a: any[]) => any, args: unknown[] = [], contextId?: number): Promise<T> {
  const r = await s.send("Runtime.evaluate", {
    expression: `(${fn.toString()})(...${JSON.stringify(args)})`, awaitPromise: true, returnByValue: true,
    ...(contextId ? { contextId } : {}),
  })
  if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description ?? r.exceptionDetails.text ?? "evaluation failed")
  return r.result.value as T
}

/** Poll until `cond` holds; false at the deadline. */
async function until(cond: () => boolean | Promise<boolean>, ms: number): Promise<boolean> {
  const deadline = Date.now() + ms
  for (;;) {
    if (await Promise.resolve().then(cond).catch(() => false)) return true
    if (Date.now() > deadline) return false
    await sleep(25)
  }
}

/** The card frame: its own session when Chrome isolates it, else an execution context in the page's. */
interface Card { session: CdpSession; contextId?: number }

interface Tab {
  page: CdpSession
  card(): Card | undefined
  inCard<T>(fn: (...a: any[]) => any, ...args: unknown[]): Promise<T>
  /** Requests in flight, across the page and its frames. */
  inflight: Set<string>
}

async function setFrameHeight(tab: Tab, px: number): Promise<void> {
  await evalIn(tab.page, (h) => { document.getElementById("card")!.style.height = `${h}px` }, [px])
  await sleep(60)
}

/** The root element's layout height: the content's, unless the page ties it to the frame (vh, height:100%). */
const rootHeight = (tab: Tab) => tab.inCard<number>(() => Math.ceil(document.documentElement.getBoundingClientRect().height))

async function contentHeight(tab: Tab): Promise<number> {
  await setFrameHeight(tab, 80)
  return Math.max(await rootHeight(tab), await tab.inCard<number>(() => document.body?.scrollHeight ?? 0))
}

function overflow(tab: Tab, width: number): Promise<{ width: number; widest?: string } | undefined> {
  return tab.inCard((w) => {
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

/** The card's pixels, as Chrome paints them (the frame may be taller than the window: captured beyond it). */
async function screenshotCard(page: CdpSession): Promise<Buffer> {
  const r = await evalIn<{ x: number; y: number; width: number; height: number }>(page, () => {
    const b = document.getElementById("card")!.getBoundingClientRect()
    return { x: b.x + window.scrollX, y: b.y + window.scrollY, width: b.width, height: b.height }
  })
  await evalIn(page, () => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r))))
  const { data } = await page.send("Page.captureScreenshot", { format: "png", clip: { ...r, scale: 1 }, captureBeyondViewport: true })
  return Buffer.from(data, "base64")
}

const viewport = (page: CdpSession, v: { width: number; height: number }) =>
  page.send("Emulation.setDeviceMetricsOverride", { ...v, deviceScaleFactor: 1, mobile: false })

const colorAndMotion = (s: CdpSession) => s.send("Emulation.setEmulatedMedia", {
  features: [{ name: "prefers-color-scheme", value: "dark" }, { name: "prefers-reduced-motion", value: "reduce" }],
})

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
export function checkWithBrowser(html: string, browser: CheckBrowser | undefined,
  o: { screenshots?: boolean; blockedHosts?: string[]; capMs?: number } = {}): Promise<CheckResult> {
  const capMs = o.capMs ?? defaultCapMs()
  const empty = { problems: [], notes: [], heights: { phone: 0, desktop: 0 }, unpromptedSends: [] }
  if (!browser) return Promise.resolve({ checked: false, reason: "no Chrome, Edge or Chromium on this machine", ...empty })
  return slot(async () => {
    const proxy = await startProxy(o.blockedHosts)
    let contextId: string | undefined
    let timer: ReturnType<typeof setTimeout> | undefined
    // A page that hasn't rendered by the cap is a problem, not a pass: a stuck script would otherwise be shown unchecked.
    const capped = new Promise<CheckResult>((resolve) => {
      timer = setTimeout(() => resolve({ checked: true, ...empty,
        problems: [{ kind: "script-error", message: `the page didn't finish rendering within ${capMs / 1000} s (a script may be stuck)` }] }), capMs)
    })
    try {
      // The proxy is per context: every connection of this check's page goes through it, loopback included.
      const created = await browser.client.send<{ browserContextId: string }>("Target.createBrowserContext",
        { proxyServer: `socks5://127.0.0.1:${proxy.port}`, proxyBypassList: "<-loopback>" })
      contextId = created.browserContextId
      return await Promise.race([measure(browser.client, contextId, html, proxy.refused, o), capped])
    } catch (e) {
      return { checked: false, reason: `the check failed: ${(e as Error).message.split("\n")[0]}`, ...empty }
    } finally {
      clearTimeout(timer)
      if (contextId) await browser.client.send("Target.disposeBrowserContext", { browserContextId: contextId }).catch(() => {})
      proxy.stop()
    }
  })
}

async function measure(client: CdpClient, browserContextId: string, html: string, refused: string[], o: { screenshots?: boolean }): Promise<CheckResult> {
  const problems: Problem[] = []
  const notes: string[] = []
  const nonce = randomUUID()
  try {
    const { targetId } = await client.send<{ targetId: string }>("Target.createTarget", { url: "about:blank", browserContextId })
    const page = await client.attach(targetId)
    const inflight = new Set<string>()
    let card: Card | undefined
    const contexts = new Map<string, number>()

    // What a session reports: script errors, console errors, failed loads. Frames Chrome isolates attach as child sessions.
    const watch = (s: CdpSession) => {
      const urls = new Map<string, string>()
      s.on("Runtime.exceptionThrown", (p) => problems.push({ kind: "script-error", message: exceptionMessage(p.exceptionDetails) }))
      s.on("Runtime.consoleAPICalled", (p) => {
        if (p.type !== "error" || notes.length >= 5) return
        const text = (p.args ?? []).map((a: any) => a.value ?? a.description ?? "").join(" ")
        if (!text.startsWith("Failed to load resource")) notes.push(`console.error: ${text.slice(0, 200)}`)
      })
      s.on("Network.requestWillBeSent", (p) => { urls.set(p.requestId, p.request.url); inflight.add(`${s.id}:${p.requestId}`) })
      s.on("Network.loadingFinished", (p) => { inflight.delete(`${s.id}:${p.requestId}`) })
      s.on("Network.loadingFailed", (p) => {
        inflight.delete(`${s.id}:${p.requestId}`)
        const url = urls.get(p.requestId) ?? ""
        if (!url || url.startsWith("blob:") || url.startsWith("data:")) return
        if (p.type === "Script") problems.push({ kind: "script-error", message: `a script failed to load: ${url.slice(0, 200)}` })
        else notes.push(`failed to load: ${url.slice(0, 200)}`)
      })
      s.on("Network.responseReceived", (p) => {
        const { status, url } = p.response
        if (status < 400 || url === HOST) return
        if (p.type === "Script") problems.push({ kind: "script-error", message: `a script failed to load (${status}): ${String(url).slice(0, 200)}` })
        else notes.push(`failed to load (${status}): ${String(url).slice(0, 200)}`)
      })
    }
    const prepare = async (s: CdpSession) => {
      watch(s)
      await Promise.all([s.send("Runtime.enable"), s.send("Network.enable"), colorAndMotion(s)])
      await s.send("Target.setAutoAttach", { autoAttach: true, waitForDebuggerOnStart: true, flatten: true })
    }
    const follow = (parent: CdpSession, top: boolean) => parent.on("Target.attachedToTarget", (p) => {
      const child = new CdpSession(client, p.sessionId)
      follow(child, false)
      void (async () => {
        try {
          await prepare(child)
          // The first frame the page itself embeds is the card.
          if (top && p.targetInfo.type === "iframe" && !card) card = { session: child }
        } finally {
          if (p.waitingForDebugger) await child.send("Runtime.runIfWaitingForDebugger").catch(() => {})
        }
      })()
    })
    follow(page, true)
    page.on("Runtime.executionContextCreated", (p) => {
      const c = p.context
      if (c.auxData?.isDefault && c.auxData.frameId) contexts.set(c.auxData.frameId, c.id)
    })
    page.on("Runtime.executionContextsCleared", () => contexts.clear())
    page.on("Fetch.requestPaused", (p) => {
      const body = Buffer.from(hostPage(cardDocument(html, "dark", nonce), nonce)).toString("base64")
      void page.send("Fetch.fulfillRequest", { requestId: p.requestId, responseCode: 200, responseHeaders: [{ name: "Content-Type", value: "text/html" }], body }).catch(() => {})
    })
    await Promise.all([
      prepare(page), page.send("Page.enable"), page.send("Emulation.setFocusEmulationEnabled", { enabled: true }),
      viewport(page, PHONE), page.send("Fetch.enable", { patterns: [{ urlPattern: HOST, requestStage: "Request" }] }),
    ])

    const tab: Tab = {
      page, inflight,
      card: () => card,
      inCard: (fn, ...args) => {
        if (!card) throw new Error("the card frame never loaded")
        return evalIn(card.session, fn, args, card.contextId)
      },
    }

    const started = Date.now()
    const nav = await page.send("Page.navigate", { url: HOST })
    if (nav.errorText) throw new Error(`could not open the check page: ${nav.errorText}`)
    await until(() => evalIn<boolean>(page, () => document.readyState !== "loading"), SETTLE_CAP_MS)
    // The card is a child session; without site isolation it is a frame of this page, found by its blob: URL.
    const found = await until(async () => {
      if (card) return true
      const { frameTree } = await page.send("Page.getFrameTree")
      const f = (frameTree.childFrames ?? []).map((c: any) => c.frame).find((fr: any) => String(fr.url).startsWith("blob:"))
      const contextId = f && contexts.get(f.id)
      if (contextId) card = { session: page, contextId }
      return !!card
    }, 3000)
    if (!found) throw new Error("the card frame never loaded")
    const loaded = await until(async () => (await tab.inCard<string>(() => document.readyState)) === "complete", Math.max(0, SETTLE_CAP_MS - (Date.now() - started)))
    if (!loaded) notes.push("the page was still loading after 4 s")
    const left = SETTLE_CAP_MS - (Date.now() - started)
    if (left > 0) {
      // Network idle: nothing in flight for 500 ms, waiting at most 2 s.
      let quietSince = Date.now()
      await until(() => { if (inflight.size) quietSince = Date.now(); return Date.now() - quietSince >= 500 }, Math.min(left, SETTLE_QUIET_MS * 2))
    }
    await tab.inCard(() => Promise.race([
      document.fonts.ready.then(() => new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))),
      new Promise((r) => setTimeout(r, 500)),
    ])).catch(() => {})

    // A page that grows with its frame (vh units, height:100%, a 100vh child): the card would grow to its cap.
    const bottom = () => tab.inCard<number>(() => Math.max(document.documentElement.getBoundingClientRect().height,
      ...(Array.from(document.body?.querySelectorAll("*") ?? []) as Element[]).map((e) => e.getBoundingClientRect().bottom)))
    await setFrameHeight(tab, 600)
    const at600 = await bottom()
    await setFrameHeight(tab, 1200)
    const at1200 = await bottom()
    const endless = at600 >= 480 && at1200 >= at600 + 400
    if (endless) notes.push("the page's height follows its frame (vh units or height:100%): the card stops it at its cap")

    const phoneHeight = endless ? PHONE.height : await contentHeight(tab)
    const over = await overflow(tab, PHONE.width)
    if (over) problems.push({ kind: "overflow", ...over })
    await setFrameHeight(tab, Math.max(80, Math.min(phoneHeight, 1600)))
    let phoneShot = await screenshotCard(page)
    // A fresh browser's first frame can lag the layout: look again before calling a page blank.
    if (isBlank(phoneShot)) { await sleep(250); phoneShot = await screenshotCard(page) }
    if (isBlank(phoneShot)) problems.push({ kind: "blank" })
    const background = await tab.inCard<string | null>(() => {
      const paint = (el: Element | null) => (el ? getComputedStyle(el).backgroundColor : "")
      const clear = (c: string) => !c || c === "transparent" || /^rgba\(.*,\s*0\)$/.test(c)
      const b = [paint(document.documentElement), paint(document.body)].find((c) => !clear(c))
      return b ?? null
    })

    await viewport(page, DESKTOP)
    await sleep(60)
    const desktopHeight = endless ? DESKTOP.height : await contentHeight(tab)
    await setFrameHeight(tab, Math.max(80, Math.min(desktopHeight, 1600)))
    const desktopShot = o.screenshots ? await screenshotCard(page) : undefined

    for (const r of refused) notes.push(`refused a local address: ${r}`)
    const unpromptedSends = await evalIn<string[]>(page, () => window.__sends)
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

/** The first line of an uncaught error, without its class name ("x is not defined", as the page's own message reads). */
function exceptionMessage(d: any): string {
  const ex = d?.exception
  const raw = String(ex?.description ?? (ex?.value !== undefined ? ex.value : d?.text) ?? "uncaught error").split("\n")[0]!
  return raw.replace(/^[A-Za-z]*Error: /, "").slice(0, 300)
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

let shared: Promise<CheckBrowser | undefined> | undefined
let idleTimer: ReturnType<typeof setTimeout> | undefined
let active = 0

function sharedBrowser(): Promise<CheckBrowser | undefined> {
  if (!shared) {
    const p: Promise<CheckBrowser | undefined> = launchBrowser().then((b) => {
      void b?.disconnected.then(() => { if (shared === p) shared = undefined })
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
    const r = toPageCheck(await checkWithBrowser(html, browser, { capMs: opts.capMs }))
    return browser.sandboxed ? r : { ...r, notes: [UNSANDBOXED_NOTE, ...r.notes] }
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
