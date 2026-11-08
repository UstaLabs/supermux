import { afterAll, beforeAll, expect, test } from "bun:test"
import type { Browser } from "playwright"
import { cardDocument, checkPage, findBrowser, isPrivateHost, launchBrowser } from "./check"

let browser: Browser | undefined
beforeAll(async () => { browser = await launchBrowser() }, 30_000)
afterAll(async () => { await browser?.close() })
// Real Chrome, on demand: SHOW_HTML_EVALS=1 bun test evals/show-html
const run = findBrowser() && process.env.SHOW_HTML_EVALS === "1" ? test : test.skip

const clean = `<!doctype html><html><head><title>t</title></head><body>
<h3 style="margin:0 0 8px">p95 latency</h3>
<div style="display:grid;gap:6px">${["upload", "search", "files"].map((n, i) =>
  `<div style="display:flex;gap:8px;align-items:center"><span style="width:70px">${n}</span>` +
  `<div style="height:14px;width:${30 + i * 20}%;background:var(--sm-chart-1);border-radius:4px"></div></div>`).join("")}
</div></body></html>`

run("a clean page has no problems and a height at both widths", async () => {
  const r = await checkPage(clean, browser)
  expect(r.checked).toBe(true)
  expect(r.problems).toEqual([])
  expect(r.heights.phone).toBeGreaterThan(40)
  expect(r.heights.desktop).toBeGreaterThan(40)
  expect(r.heights.phone).toBeLessThan(400)
}, 30_000)

run("an uncaught exception is a script error", async () => {
  const r = await checkPage(`<p>hi</p><script>notDefinedAnywhere()</script>`, browser)
  expect(r.problems.map((p) => p.kind)).toContain("script-error")
}, 30_000)

run("a script that can't load is a script error", async () => {
  // A local address: refused by the check's network rule, and unreachable from the user's phone anyway.
  const r = await checkPage(`<p>hi</p><script src="http://127.0.0.1:4000/lib.js"></script>`, browser)
  expect(r.problems.some((p) => p.kind === "script-error" && p.message.includes("failed to load"))).toBe(true)
}, 30_000)

run("an empty app root is blank", async () => {
  const r = await checkPage(`<div id="root"></div><script>/* the app never mounts */</script>`, browser)
  expect(r.problems.map((p) => p.kind)).toEqual(["blank"])
}, 30_000)

run("text the same colour as the background is blank too", async () => {
  const r = await checkPage(`<p style="color:#16181a;margin:0;padding:20px">invisible</p>`, browser)
  expect(r.problems.map((p) => p.kind)).toContain("blank")
}, 30_000)

run("a page wider than a phone overflows, and the widest element is named", async () => {
  const r = await checkPage(`<table id="t" style="width:800px"><tr><td>wide</td></tr></table>`, browser)
  const over = r.problems.find((p) => p.kind === "overflow")
  expect(over).toBeDefined()
  expect(over && over.kind === "overflow" && over.width).toBeGreaterThanOrEqual(800)
  expect(over && over.kind === "overflow" && over.widest).toContain("table")
}, 30_000)

run("a page that grows with its frame is noted, not stopped: the card caps it", async () => {
  const r = await checkPage(`<div style="height:100vh;background:var(--sm-card)">full</div>`, browser)
  expect(r.problems).toEqual([])
  expect(r.endlessHeight).toBe(true)
  const positioned = await checkPage(`<p>x</p><div style="position:absolute;top:0;left:0;width:10px;height:100vh"></div>`, browser)
  expect(positioned.endlessHeight).toBe(true)
}, 30_000)

run("a public name that resolves to a local address is refused at the socket", async () => {
  // nip.io answers 127.0.0.1 for this name: Chrome sees a public host, only the proxy's own lookup sees the address.
  const r = await checkPage(`<p>hi</p><img src="http://127.0.0.1.nip.io:4000/x.png" alt="">`, browser)
  expect(r.problems).toEqual([])
  expect(r.notes.join("\n")).toContain("refused a local address: 127.0.0.1.nip.io:4000")
}, 30_000)

run("a page that paints its own background says so", async () => {
  expect((await checkPage(`<style>body{background:#000;color:#fff}</style><p>x</p>`, browser)).background).toBe("rgb(0, 0, 0)")
  expect((await checkPage(clean, browser)).background).toBeUndefined()
}, 30_000)

run("a request to a local address is refused and only noted", async () => {
  const r = await checkPage(`<p>hi</p><img src="http://127.0.0.1:4000/x.png" alt="">`, browser)
  expect(r.problems).toEqual([])
  expect(r.notes.join("\n")).toContain("refused a local address: 127.0.0.1:4000")
}, 30_000)

run("a send with no click is recorded as unprompted", async () => {
  const r = await checkPage(`<p>hi</p><script>supermux.send("picked A")</script>`, browser)
  expect(r.unpromptedSends).toEqual(["picked A"])
}, 30_000)

run("variant B gets both screenshots", async () => {
  const r = await checkPage(clean, browser, { screenshots: true })
  expect(r.screenshots?.phone.subarray(1, 4).toString()).toBe("PNG")
  expect(r.screenshots?.desktop.subarray(1, 4).toString()).toBe("PNG")
}, 30_000)

test("no browser: not checked, and said why", async () => {
  const r = await checkPage(clean, undefined)
  expect(r.checked).toBe(false)
  expect(r.reason).toContain("no Chrome")
})

test("private hosts", () => {
  for (const h of ["localhost", "a.localhost", "127.0.0.1", "10.1.2.3", "172.20.0.1", "192.168.1.5", "169.254.1.1",
    "100.100.1.1", "[::1]", "fd00::1", "fe80::1", "printer.local", "0.0.0.0"]) expect(isPrivateHost(h)).toBe(true)
  for (const h of ["cdn.jsdelivr.net", "8.8.8.8", "172.32.0.1", "100.128.0.1", "example.com"]) expect(isPrivateHost(h)).toBe(false)
})

test("a <header> is not a <head>", () => {
  const doc = cardDocument("<!doctype html><html><body><header>top</header></body></html>")
  expect(doc.indexOf("sm-api")).toBeLessThan(doc.indexOf("<body>"))
})

test("the page API goes first: a fragment is wrapped, a document gets it in its head", () => {
  expect(cardDocument("<p>x</p>")).toMatch(/^<!doctype html><html data-theme="dark"><head>.*id="sm-api".*<body><p>x<\/p>/s)
  const doc = cardDocument("<!DOCTYPE html><html><head><title>t</title></head><body>b</body></html>")
  expect(doc.startsWith("<!DOCTYPE html><html><head>")).toBe(true)
  expect(doc.indexOf("sm-api")).toBeLessThan(doc.indexOf("<title>"))
})
