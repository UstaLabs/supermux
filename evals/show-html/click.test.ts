// Click-to-send on the web, in real Chrome: a send goes through only from a real click or tap in that card, once.
import { afterAll, beforeAll, expect, test } from "bun:test"
import type { Browser } from "playwright"
import { findBrowser, launchBrowser } from "./check"
import { clickRoundTrip, deliveries, openCards } from "./click"

let browser: Browser | undefined
beforeAll(async () => { browser = await launchBrowser() }, 30_000)
afterAll(async () => { await browser?.close() })
// Real Chrome, on demand: SHOW_HTML_EVALS=1 bun test evals/show-html
const run = findBrowser() && process.env.SHOW_HTML_EVALS === "1" ? test : test.skip

const card = (body: string) => `<!doctype html><html><head></head><body>${body}</body></html>`
run("a real click sends, once", async () => {
  const page = await openCards(browser!, { a: card(`<button id="b" onclick="supermux.send('real')">Use</button>`) })
  const f = page.frames().find((fr) => fr.url().startsWith("blob:"))!
  await f.locator("#b").click()
  await page.waitForTimeout(200)
  expect(await deliveries(page)).toEqual([{ card: "a", text: "real", ok: true }])
  await page.close()
}, 30_000)

run("refused: on load, from a timer, from a script click, after the window, a second send from one click", async () => {
  const page = await openCards(browser!, { a: card(`
    <button id="real" onclick="supermux.send('real')">real</button>
    <button id="twice" onclick="supermux.send('first'); supermux.send('second')">twice</button>
    <button id="late" onclick="setTimeout(() => supermux.send('late'), 1300)">late</button>
    <script>
      supermux.send('on load');
      setTimeout(() => supermux.send('timer'), 150);
      setTimeout(() => document.getElementById('real').click(), 250);
      setTimeout(() => document.getElementById('real').dispatchEvent(new MouseEvent('click', { bubbles: true })), 300);
    </script>`) })
  await page.waitForTimeout(500)
  const f = page.frames().find((fr) => fr.url().startsWith("blob:"))!
  await f.locator("#twice").click()
  await page.waitForTimeout(150)
  await f.locator("#late").click()
  await page.waitForTimeout(1600)
  const d = await deliveries(page)
  const byText = (t: string) => d.filter((x) => x.text === t)
  expect(byText("on load")).toEqual([{ card: "a", text: "on load", ok: false, reason: "no click" }])
  expect(byText("timer")[0]!.ok).toBe(false)
  expect(byText("real").every((x) => !x.ok)).toBe(true)
  expect(byText("real").length).toBe(2)
  expect(byText("first")).toEqual([{ card: "a", text: "first", ok: true }])
  expect(byText("second")[0]!.ok).toBe(false)
  expect(byText("late")[0]).toMatchObject({ ok: false, reason: "too late after the click" })
  await page.close()
}, 30_000)

run("refused: a send from a different card than the one clicked", async () => {
  const page = await openCards(browser!, {
    a: card(`<button id="b">just a button</button>`),
    b: card(`<script>addEventListener('message', () => {}); setInterval(() => supermux.send('from b'), 100)</script>`),
  })
  const f = page.frames().filter((fr) => fr.url().startsWith("blob:"))
  const a = f[0]!
  await a.locator("#b").click()
  await page.waitForTimeout(400)
  const d = await deliveries(page)
  expect(d.filter((x) => x.card === "b").length).toBeGreaterThan(0)
  expect(d.filter((x) => x.card === "b").every((x) => !x.ok)).toBe(true)
  await page.close()
}, 30_000)

run("refused: the user typed in the app after touching the card", async () => {
  const page = await openCards(browser!, { a: card(`<button id="b" onclick="setTimeout(() => supermux.send('later'), 400)">x</button>`) })
  const f = page.frames().find((fr) => fr.url().startsWith("blob:"))!
  await f.locator("#b").click()
  await page.locator("#composer").click()
  await page.waitForTimeout(600)
  const d = await deliveries(page)
  expect(d).toEqual([{ card: "a", text: "later", ok: false, reason: "not the card the user touched" }])
  await page.close()
}, 30_000)

run("the round trip on an agent's page: clicking its controls gets the choice back", async () => {
  const r = await clickRoundTrip(browser!, card(`
    <div>Palette A</div><button onclick="supermux.send('Use palette A')">Use A</button>
    <div>Palette B</div><button onclick="supermux.send('Use palette B')">Use B</button>`))
  expect(r.clicked).toBe(2)
  expect(r.sent).toEqual(["Use palette A", "Use palette B"])
}, 30_000)

