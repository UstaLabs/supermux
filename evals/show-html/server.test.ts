// The stub server over real MCP: what an agent sees for each arm, and what a call records.
import { afterEach, expect, test } from "bun:test"
import { Client } from "@modelcontextprotocol/sdk/client/index.js"
import { StdioClientTransport } from "@modelcontextprotocol/sdk/client/stdio.js"
import { mkdtempSync, readFileSync, writeFileSync } from "node:fs"
import { join } from "node:path"
import { tmpdir } from "node:os"
import { findBrowser } from "./check"
import { SERVER } from "./agents"

let client: Client | undefined
afterEach(async () => { await client?.close(); client = undefined })
// Real Chrome, on demand: SHOW_HTML_EVALS=1 bun test evals/show-html
const run = findBrowser() && process.env.SHOW_HTML_EVALS === "1" ? test : test.skip

async function connect(arm: string, extra: Record<string, string> = {}) {
  const dir = mkdtempSync(join(process.env.TMPDIR ?? tmpdir(), "show-html-server-"))
  client = new Client({ name: "eval-test", version: "0" })
  await client.connect(new StdioClientTransport({ command: process.execPath, args: [SERVER], env: { ...process.env as Record<string, string>, EVAL_ARM: arm, EVAL_DIR: dir, ...extra } }))
  return dir
}
const text = (r: any) => r.content.filter((c: any) => c.type === "text").map((c: any) => c.text).join("\n")
const calls = (dir: string) => readFileSync(join(dir, "calls.jsonl"), "utf8").split("\n").filter(Boolean).map((l) => JSON.parse(l))

test("each arm serves its tools", async () => {
  await connect("A1")
  expect((await client!.listTools()).tools.map((t) => t.name)).toEqual(["show_html", "attach"])
  await client!.close()
  await connect("B2")
  const b = (await client!.listTools()).tools
  expect(b.map((t) => t.name)).toEqual(["preview_html", "show_html", "attach"])
  expect(b.find((t) => t.name === "show_html")!.description).toContain("preview_html first")
}, 30_000)

run("a clean page is shown, a broken one is not, and both are recorded", async () => {
  const dir = await connect("A1")
  const ok = await client!.callTool({ name: "show_html", arguments: { title: "Signups", html: "<p style='margin:0'>120, 135, 128</p>" } })
  expect(text(ok)).toMatch(/^shown \(id: h_1\): \d+ px tall on a desktop, \d+ px on a phone$/)
  const bad = await client!.callTool({ name: "show_html", arguments: { title: "Wide", html: "<div style='width:900px'>x</div>" } })
  expect(text(bad)).toContain("not shown — fix the page and call show_html again:")
  expect(text(bad)).toContain("overflow at phone width")
  const c = calls(dir)
  expect(c.map((x) => [x.tool, x.shown])).toEqual([["show_html", true], ["show_html", false]])
  expect(readFileSync(join(dir, c[0].page), "utf8")).toContain("120, 135, 128")
}, 60_000)

run("a forced problem answers the first check only", async () => {
  const dir = await connect("A1", { EVAL_FORCE_PROBLEM: "script-error" })
  const first = await client!.callTool({ name: "show_html", arguments: { title: "Calc", html: "<p>ok</p>" } })
  expect(text(first)).toContain("script error: TypeError")
  const second = await client!.callTool({ name: "show_html", arguments: { title: "Calc", html: "<p>ok again</p>" } })
  expect(text(second)).toStartWith("shown (id: h_2)")
  expect(calls(dir).map((x) => x.forced)).toEqual([true, false])
}, 60_000)

run("preview_html returns the problems and two screenshots, and shows nothing", async () => {
  const dir = await connect("B1")
  const r: any = await client!.callTool({ name: "preview_html", arguments: { html: "<p>hello</p>" } })
  expect(text(r)).toStartWith("no problems:")
  expect(r.content.filter((c: any) => c.type === "image").length).toBe(2)
  expect(calls(dir)[0]).toMatchObject({ tool: "preview_html", shown: false })
}, 60_000)

test("bad arguments are errors, not cards", async () => {
  const dir = await connect("A1")
  writeFileSync(join(dir, "page.html"), "<p>from a file</p>")
  expect((await client!.callTool({ name: "show_html", arguments: { title: "", html: "<p>x</p>" } })).isError).toBe(true)
  expect((await client!.callTool({ name: "show_html", arguments: { title: "Both", html: "<p>x</p>", file: "page.html" } })).isError).toBe(true)
  expect(text(await client!.callTool({ name: "attach", arguments: { files: ["shot.png"] } }))).toBe("sent (id: m_3)")
}, 30_000)
