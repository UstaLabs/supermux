// The click round trip on the web, as the app will do it (spec, "Click-to-send"). The card's shim already refuses a
// send with no real input in the card (page-api.ts); the host then accepts it only if the browser says the user just
// activated the page and the card was the last thing the user touched. Real clicks come through the DevTools protocol.
import type { Browser, Page } from "playwright"
import { cardDocument } from "./check"

export interface Delivery { card: string; text: string; ok: boolean; reason?: string }

/** A host page with one or more cards; window.__deliveries lists every send and refusal, in order. */
export function clickHost(cards: Record<string, string>): string {
  const frames = Object.keys(cards).map((id) => `<iframe id="${id}" sandbox="allow-scripts" title="${id}"></iframe>`).join("")
  return `<!doctype html><html><head><meta charset="utf-8"><style>
html{color-scheme:dark} html,body{margin:0;background:#16181a} iframe{display:block;border:0;width:100%;height:320px}
</style></head><body><button id="composer">app</button>${frames}<script>
window.__deliveries = [];
window.__forged = 0;
let lastHostInput = 0;
for (const t of ["pointerdown", "keydown"]) addEventListener(t, (e) => { if (e.isTrusted) lastHostInput = Date.now(); }, { capture: true });
const cards = ${JSON.stringify(cards).replace(/</g, "\\u003c")};
for (const [id, html] of Object.entries(cards)) {
  const frame = document.getElementById(id);
  frame.addEventListener("load", () => {
    // The card's private channel: only the shim holds the other end, so a page can't post a send of its own.
    const ch = new MessageChannel();
    ch.port1.onmessage = (e) => {
      const m = e.data;
      if (!m || (m.sm !== "send" && m.sm !== "refused")) return;
      if (m.sm === "refused") { window.__deliveries.push({ card: id, text: String(m.text), ok: false, reason: String(m.reason) }); return; }
      const reason = lastHostInput > m.clickAt ? "the user touched the app since" : "";
      window.__deliveries.push({ card: id, text: String(m.text).slice(0, 2000), ok: !reason, ...(reason ? { reason } : {}) });
      ch.port1.postMessage({ sm: "sent", id: m.id, ok: !reason, reason });
    };
    frame.contentWindow.postMessage({ sm: "port" }, "*", [ch.port2]);
  });
  frame.src = URL.createObjectURL(new Blob([html], { type: "text/html" }));
}
// A page posting to the app's window directly is not the shim: never a send.
addEventListener("message", (e) => { if (e.data && e.data.sm === "send") window.__forged++; });
</script></body></html>`
}

export async function openCards(browser: Browser, pages: Record<string, string>): Promise<Page> {
  const cards = Object.fromEntries(Object.entries(pages).map(([id, html]) => [id, cardDocument(html)]))
  const page = await browser.newPage({ viewport: { width: 720, height: 900 }, colorScheme: "dark" })
  await page.route("https://host.click.invalid/", (r) => r.fulfill({ contentType: "text/html", body: clickHost(cards) }))
  await page.goto("https://host.click.invalid/", { waitUntil: "domcontentloaded" })
  await page.waitForFunction((n) => [...document.querySelectorAll("iframe")].every((f) => f.src.startsWith("blob:")) &&
    document.querySelectorAll("iframe").length === n, Object.keys(pages).length)
  await page.waitForTimeout(400)
  return page
}

export const deliveries = (page: Page) => page.evaluate(() => (window as unknown as { __deliveries: Delivery[] }).__deliveries)
export const forged = (page: Page) => page.evaluate(() => (window as unknown as { __forged: number }).__forged)

/** Click each control a person would press, one at a time, and collect what the page sends. */
export async function clickRoundTrip(browser: Browser, html: string): Promise<{ clicked: number; sent: string[]; refused: Delivery[] }> {
  const page = await openCards(browser, { card: html })
  try {
    const frame = page.frames().find((f) => f.url().startsWith("blob:"))!
    // What a person would press: buttons, and anything the page styles as clickable (swatches with click listeners).
    await frame.evaluate(() => {
      for (const el of Array.from(document.querySelectorAll("*"))) {
        if (el.matches("button, [role=button], input[type=button], input[type=submit], [onclick]") ||
          (getComputedStyle(el).cursor === "pointer" && !el.parentElement?.closest("[data-sm-press]"))) el.setAttribute("data-sm-press", "")
      }
    })
    const controls = frame.locator("[data-sm-press]")
    const n = Math.min(await controls.count(), 8)
    let clicked = 0
    for (let i = 0; i < n; i++) {
      const c = controls.nth(i)
      if (!(await c.isVisible().catch(() => false))) continue
      await c.click({ timeout: 2000 }).then(() => clicked++).catch(() => {})
      await page.waitForTimeout(250)
    }
    const all = await deliveries(page)
    return { clicked, sent: all.filter((d) => d.ok).map((d) => d.text), refused: all.filter((d) => !d.ok) }
  } finally {
    await page.close()
  }
}
