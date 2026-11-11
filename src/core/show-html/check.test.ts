import { afterAll, expect, test } from "bun:test"
import { createServer } from "node:http"
import {
  checkPage, checkWithBrowser, closeCheckBrowser, createLimiter, describeProblem, findBrowser, NO_BROWSER_NOTE, toPageCheck,
  type CheckResult,
} from "./check"

// Real Chrome, on demand (the harness's flag): SHOW_HTML_EVALS=1 bun test src/core/show-html
const run = findBrowser() && process.env.SHOW_HTML_EVALS === "1" ? test : test.skip
afterAll(async () => { await closeCheckBrowser() })

const clean = `<!doctype html><html><head><title>t</title></head><body>
<h3 style="margin:0 0 8px">p95 latency</h3>
<div style="display:grid;gap:6px">${["upload", "search", "files"].map((n, i) =>
  `<div style="display:flex;gap:8px;align-items:center"><span style="width:70px">${n}</span>` +
  `<div style="height:14px;width:${30 + i * 20}%;background:var(--sm-chart-1);border-radius:4px"></div></div>`).join("")}
</div></body></html>`

// ---- always run

test("problems read as sentences the agent can act on", () => {
  expect(describeProblem({ kind: "script-error", message: "x is not defined" })).toBe("script error: x is not defined")
  expect(describeProblem({ kind: "blank" })).toContain("nothing is visible")
  expect(describeProblem({ kind: "overflow", width: 600 })).toBe("overflow at phone width: the page is 600 px wide in a 390 px frame")
  expect(describeProblem({ kind: "overflow", width: 600, widest: "table#t" })).toContain("(widest: table#t)")
})

test("no browser: shown, and said in the result", async () => {
  const saved = process.env.MUX_CHECK_BROWSER
  process.env.MUX_CHECK_BROWSER = "/nonexistent/chrome"
  try {
    expect(findBrowser()).toBeUndefined()
    expect(await checkPage(clean)).toEqual({ problems: [], notes: ["not checked: no Chrome, Edge or Chromium on the host"] })
    expect(NO_BROWSER_NOTE).toContain("no Chrome, Edge or Chromium")
  } finally {
    if (saved === undefined) delete process.env.MUX_CHECK_BROWSER
    else process.env.MUX_CHECK_BROWSER = saved
  }
})

test("MUX_CHECK_BROWSER overrides discovery", () => {
  const saved = process.env.MUX_CHECK_BROWSER
  process.env.MUX_CHECK_BROWSER = process.execPath
  try { expect(findBrowser()).toBe(process.execPath) } finally {
    if (saved === undefined) delete process.env.MUX_CHECK_BROWSER
    else process.env.MUX_CHECK_BROWSER = saved
  }
})

test("a check that did not run is a note, not a pass", () => {
  const r: CheckResult = { checked: false, reason: "the check failed: boom", problems: [], notes: ["a"], heights: { phone: 0, desktop: 0 }, unpromptedSends: [] }
  expect(toPageCheck(r)).toEqual({ problems: [], notes: ["not checked: the check failed: boom", "a"] })
  const ok: CheckResult = { checked: true, problems: [{ kind: "blank" }], notes: [], heights: { phone: 120, desktop: 100 }, unpromptedSends: [], background: "rgb(0, 0, 0)" }
  expect(toPageCheck(ok)).toEqual({ problems: [{ kind: "blank" }], notes: [], height: 120, desktopHeight: 100, background: "rgb(0, 0, 0)" })
})

test("the queue runs at most N at once and keeps order", async () => {
  const limit = createLimiter(2)
  let live = 0, peak = 0
  const started: number[] = []
  const gates: (() => void)[] = []
  const tasks = [0, 1, 2, 3, 4].map((i) => limit(async () => {
    started.push(i)
    peak = Math.max(peak, ++live)
    await new Promise<void>((r) => gates.push(r))
    live--
  }))
  await Bun.sleep(10)
  expect(started).toEqual([0, 1])
  gates.shift()!()
  await Bun.sleep(10)
  expect(started).toEqual([0, 1, 2])
  while (gates.length || started.length < 5) { gates.shift()?.(); await Bun.sleep(5) }
  await Promise.all(tasks)
  expect(started).toEqual([0, 1, 2, 3, 4])
  expect(peak).toBe(2)
})

test("a failing task frees its slot", async () => {
  const limit = createLimiter(1)
  await expect(limit(async () => { throw new Error("x") })).rejects.toThrow("x")
  expect(await limit(async () => 7)).toBe(7)
})

// ---- real Chrome

run("a clean page has no problems and a height", async () => {
  const r = await checkPage(clean)
  expect(r.problems).toEqual([])
  expect(r.notes.filter((n) => n.startsWith("not checked"))).toEqual([])
  expect(r.height).toBeGreaterThan(40)
  expect(r.height).toBeLessThan(400)
  expect(r.background).toBeUndefined()
}, 30_000)

run("a throwing script is a script error", async () => {
  const r = await checkPage(`<p>hi</p><script>notDefinedAnywhere()</script>`)
  expect(r.problems.map((p) => p.kind)).toEqual(["script-error"])
  expect(r.problems[0]).toMatchObject({ message: expect.stringContaining("notDefinedAnywhere") })
}, 30_000)

run("a blank page is blank", async () => {
  const r = await checkPage(`<div id="root"></div>`)
  expect(r.problems).toEqual([{ kind: "blank" }])
}, 30_000)

run("a 600 px table at 390 overflows", async () => {
  const r = await checkPage(`<table id="t" style="width:600px"><tr><td>wide</td></tr></table>`)
  expect(r.problems.map((p) => p.kind)).toEqual(["overflow"])
  const over = r.problems[0]!
  expect(over.kind === "overflow" ? over.width : 0).toBeGreaterThanOrEqual(600)
}, 30_000)

run("a page stuck in a loop fails closed at the cap", async () => {
  const t = Date.now()
  const r = await checkPage(`<p>hi</p><script>setTimeout(() => { for (;;) {} }, 50)</script>`, { capMs: 2500 })
  expect(r.problems.some((p) => p.kind === "script-error" && p.message.includes("didn't finish rendering within 2.5 s"))).toBe(true)
  expect(Date.now() - t).toBeLessThan(20_000)
}, 60_000)

run("a fetch of a local server is refused, and the server never sees it", async () => {
  let hits = 0
  const server = createServer((_, res) => { hits++; res.setHeader("access-control-allow-origin", "*"); res.end("secret") })
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r))
  const port = (server.address() as { port: number }).port
  try {
    const r = await checkPage(`<p>hi</p><img src="http://127.0.0.1:${port}/x.png" alt=""><script>fetch("http://127.0.0.1:${port}/d").catch(() => {})</script>`)
    expect(hits).toBe(0)
    expect(r.notes.join("\n")).toContain(`refused a local address: 127.0.0.1:${port}`)
  } finally { server.close() }
}, 30_000)

run("two checks at once share one browser and both answer", async () => {
  const [a, b, c] = await Promise.all([checkPage(clean), checkPage(`<div id="root"></div>`), checkPage(clean)])
  expect(a.problems).toEqual([])
  expect(b.problems).toEqual([{ kind: "blank" }])
  expect(c.problems).toEqual([])
}, 60_000)

run("the shared browser closes on request and the next check relaunches it", async () => {
  await checkPage(clean)
  await closeCheckBrowser()
  expect((await checkPage(clean)).problems).toEqual([])
}, 60_000)

run("checkWithBrowser with no browser says why", async () => {
  const r = await checkWithBrowser(clean, undefined)
  expect(r.checked).toBe(false)
}, 5000)
