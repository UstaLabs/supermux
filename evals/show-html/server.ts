// The stub MCP server an agent CLI talks to during an eval run: it serves one arm's tools, runs the real check on
// every page and records every call. Environment:
//   EVAL_ARM            the arm (A1 … C2)
//   EVAL_DIR            the run's folder: relative `file` paths resolve here; calls.jsonl and pages/ are written here
//   EVAL_FORCE_PROBLEM  blank | overflow | script-error: the first check answers with this problem, whatever the page
import { Server } from "@modelcontextprotocol/sdk/server/index.js"
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js"
import { CallToolRequestSchema, ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js"
import { appendFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs"
import { isAbsolute, join, resolve } from "node:path"
import { createHash } from "node:crypto"
import type { Browser } from "playwright"
import { checkPage, describeProblem, launchBrowser, PHONE, type CheckResult, type Problem } from "./check"
import { toolsFor, type Arm } from "./arms"

const MAX_BYTES = 2 * 1024 * 1024
const arm = (process.env.EVAL_ARM ?? "A1") as Arm
const dir = resolve(process.env.EVAL_DIR ?? ".")
const forced = process.env.EVAL_FORCE_PROBLEM as Problem["kind"] | undefined
let forcedUsed = false
let seq = 0
let browser: Promise<Browser | undefined> | undefined

mkdirSync(join(dir, "pages"), { recursive: true })

export function forcedProblem(kind: Problem["kind"]): Problem {
  switch (kind) {
    case "blank": return { kind: "blank" }
    case "overflow": return { kind: "overflow", width: 612, widest: "table" }
    case "script-error": return { kind: "script-error", message: "TypeError: Cannot read properties of undefined (reading 'map')" }
  }
}

function record(entry: Record<string, unknown>): void {
  appendFileSync(join(dir, "calls.jsonl"), JSON.stringify({ at: new Date().toISOString(), ...entry }) + "\n")
}

function readPage(args: Record<string, unknown>): { html: string } | { error: string } {
  const hasHtml = typeof args.html === "string" && args.html.length > 0
  const hasFile = typeof args.file === "string" && args.file.length > 0
  if (hasHtml === hasFile) return { error: "pass exactly one of html or file" }
  if (hasHtml) {
    const html = args.html as string
    return Buffer.byteLength(html) > MAX_BYTES ? { error: "the page is over 2 MB" } : { html }
  }
  const path = isAbsolute(args.file as string) ? (args.file as string) : join(dir, args.file as string)
  if (!existsSync(path)) return { error: `no such file: ${args.file}` }
  if (statSync(path).size > MAX_BYTES) return { error: "the page is over 2 MB" }
  return { html: readFileSync(path, "utf8") }
}

// A forced problem answers the first check, whichever tool asked. A forced preview carries no screenshots, which would
// contradict it; the page that comes after is checked for real.
async function check(html: string, screenshots: boolean): Promise<CheckResult & { forced: boolean }> {
  browser ??= launchBrowser()
  const result = await checkPage(html, await browser, { screenshots })
  if (forced && !forcedUsed && result.checked) {
    forcedUsed = true
    const { screenshots: _drop, ...rest } = result
    return { ...rest, problems: [forcedProblem(forced)], forced: true }
  }
  return { ...result, forced: false }
}

function notesText(r: CheckResult): string {
  const lines = [...r.notes]
  if (r.unpromptedSends.length) lines.push(`supermux.send was called with no click (refused): ${r.unpromptedSends.length}×`)
  return lines.length ? `\nNotes:\n${lines.map((l) => `- ${l}`).join("\n")}` : ""
}

const server = new Server({ name: "supermux", version: "0.0.0-eval" }, { capabilities: { tools: {} } })

server.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: toolsFor(arm) }))

server.setRequestHandler(CallToolRequestSchema, async (req) => {
  const name = req.params.name
  const args = (req.params.arguments ?? {}) as Record<string, unknown>
  const n = ++seq
  const started = Date.now()

  if (name === "attach") {
    const files = Array.isArray(args.files) ? args.files.map(String) : []
    record({ seq: n, tool: name, files, text: args.text })
    return { content: [{ type: "text", text: `sent (id: m_${n})` }] }
  }
  if (name !== "show_html" && name !== "preview_html") {
    return { isError: true, content: [{ type: "text", text: `unknown tool ${name}` }] }
  }

  const title = typeof args.title === "string" ? args.title.trim() : ""
  if (name === "show_html" && (title.length < 1 || title.length > 80)) {
    record({ seq: n, tool: name, error: "title" })
    return { isError: true, content: [{ type: "text", text: "title must be 1–80 characters" }] }
  }
  const page = readPage(args)
  if ("error" in page) {
    record({ seq: n, tool: name, error: page.error })
    return { isError: true, content: [{ type: "text", text: page.error }] }
  }
  const pageFile = join("pages", `${n}-${name}.html`)
  writeFileSync(join(dir, pageFile), page.html)

  const r = await check(page.html, name === "preview_html")
  const shown = name === "show_html" && r.problems.length === 0
  const base = { seq: n, tool: name, title, page: pageFile, bytes: Buffer.byteLength(page.html), source: args.file ? "file" : "html",
    hash: createHash("sha1").update(page.html).digest("hex").slice(0, 12), height: args.height, checked: r.checked,
    forced: r.forced, problems: r.problems, notes: r.notes, heights: r.heights, background: r.background,
    endlessHeight: r.endlessHeight, unpromptedSends: r.unpromptedSends, ms: Date.now() - started }

  if (name === "preview_html") {
    record({ ...base, shown: false })
    const head = !r.checked ? `not checked (${r.reason})`
      : r.problems.length ? `problems:\n${r.problems.map((p) => `- ${describeProblem(p)}`).join("\n")}`
      : `no problems: ${r.heights.desktop} px tall on a desktop, ${r.heights.phone} px on a phone`
    const images = r.screenshots
      ? [{ type: "image", data: r.screenshots.phone.toString("base64"), mimeType: "image/png" },
        { type: "image", data: r.screenshots.desktop.toString("base64"), mimeType: "image/png" }]
      : []
    return { content: [{ type: "text", text: head + notesText(r) + (images.length ? `\nScreenshots: phone (${PHONE.width} px), then desktop.` : "") }, ...images] }
  }

  record({ ...base, shown: shown || !r.checked })
  if (!r.checked) return { content: [{ type: "text", text: `shown without a check (${r.reason})` }] }
  if (!shown) {
    return { content: [{ type: "text", text: `not shown — fix the page and call show_html again:\n` +
      r.problems.map((p) => `- ${describeProblem(p)}`).join("\n") + notesText(r) }] }
  }
  return { content: [{ type: "text", text: `shown (id: h_${n}): ${r.heights.desktop} px tall on a desktop, ${r.heights.phone} px on a phone` + notesText(r) }] }
})

if (import.meta.main) {
  await server.connect(new StdioServerTransport())
  const quit = async () => { (await browser)?.close().catch(() => {}); process.exit(0) }
  process.stdin.on("close", quit)
  process.on("SIGTERM", quit)
}
