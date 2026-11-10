import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { mkdirSync, mkdtempSync, readFileSync, symlinkSync, writeFileSync, existsSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { createHash } from "crypto"
import { FsHtmlPageStore, MAX_PAGE_BYTES, showHtml, type HtmlCard, type ShowHtmlDeps, type ToolOutcome } from "./service"
import type { PageCheck } from "./check"

let root: string
let workdir: string
let outside: string
beforeEach(() => {
  root = mkdtempSync(join(tmpdir(), "show-html-"))
  workdir = join(root, "work")
  outside = join(root, "outside")
  mkdirSync(workdir); mkdirSync(outside)
})
afterEach(() => rmSync(root, { recursive: true, force: true }))

function setup(check: PageCheck = { problems: [], notes: [] }) {
  const posted: Array<{ session: string; card: HtmlCard }> = []
  const checked: string[] = []
  const store = new FsHtmlPageStore(join(root, "html"))
  const deps: ShowHtmlDeps = {
    workdir: (s) => (s === "s1" ? workdir : undefined),
    check: async (html) => { checked.push(html); return check },
    store,
    post: async (session, card) => { posted.push({ session, card }); return { ok: true } },
    forbidden: [join(root, "state")],
  }
  return { deps, posted, checked, store }
}
const text = (r: ToolOutcome) => (r.ok ? String(r.value) : r.error)
const err = (r: ToolOutcome) => (r.ok ? "" : r.error)

describe("show_html handler", () => {
  test("clean page: stored by its SHA-256, a card posted, result says shown with the id and heights", async () => {
    const { deps, posted, store } = setup({ problems: [], notes: [], height: 910, desktopHeight: 640 })
    const html = "<p>hi</p>"
    const r = await showHtml("s1", { title: "Chart", html }, deps)
    const id = createHash("sha256").update(html).digest("hex")
    expect(r.ok).toBe(true)
    expect(text(r)).toBe(`shown (id: ${id}): 640 px tall on a desktop, 910 px on a phone`)
    expect(store.read(id)).toBe(html)
    expect(existsSync(join(root, "html", `${id}.html`))).toBe(true)
    expect(posted).toEqual([{ session: "s1", card: { id, title: "Chart", height: 910, desktop_height: 640 } }])
  })

  test("problems: not stored, no card, and the result lists them in plain words", async () => {
    const { deps, posted, store } = setup({ problems: [{ kind: "script-error", message: "x is not defined" } as any, { kind: "blank" } as any], notes: [] })
    const r = await showHtml("s1", { title: "T", html: "<p>x</p>" }, deps)
    expect(r.ok).toBe(true)
    const t = text(r)
    expect(t).toStartWith("not shown")
    expect(t).toContain("fix the page and call show_html again")
    expect(t).toContain("script error: x is not defined")
    expect(t).toContain("blank: nothing is visible at phone width")
    expect(posted).toEqual([])
    expect(existsSync(join(root, "html"))).toBe(false)
    expect(store.read(createHash("sha256").update("<p>x</p>").digest("hex"))).toBeUndefined()
  })

  test("not checked (no browser): shown, and the result says so; notes follow; height falls back to the argument", async () => {
    const { deps, posted } = setup({ problems: [], notes: ["not checked: no Chrome, Edge or Chromium on the host", "2 requests failed"] })
    const r = await showHtml("s1", { title: "T", html: "<p>x</p>", height: 300 }, deps)
    expect(text(r)).toStartWith("shown without a check (no Chrome, Edge or Chromium on the host)")
    expect(text(r)).toContain("2 requests failed")
    expect(posted[0]!.card.height).toBe(300)
    expect(posted[0]!.card.desktop_height).toBeUndefined()
  })

  test("notes after a clean check follow the result line", async () => {
    const { deps } = setup({ problems: [], notes: ["console.error: boom"], height: 100, desktopHeight: 90 })
    expect(text(await showHtml("s1", { title: "T", html: "<p>x</p>" }, deps))).toContain("\nconsole.error: boom")
  })

  test("a file inside the workdir is read; the same content is one stored page", async () => {
    const { deps, posted } = setup()
    writeFileSync(join(workdir, "p.html"), "<b>file</b>")
    const r = await showHtml("s1", { title: "T", file: "p.html" }, deps)
    expect(text(r)).toStartWith("shown")
    expect(posted[0]!.card.id).toBe(createHash("sha256").update("<b>file</b>").digest("hex"))
  })

  test("a file outside the workdir is refused: ../, absolute, and a symlink out; nothing is checked or posted", async () => {
    const { deps, posted, checked } = setup()
    writeFileSync(join(outside, "secret.html"), "<b>secret</b>")
    symlinkSync(join(outside, "secret.html"), join(workdir, "link.html"))
    for (const file of ["../outside/secret.html", join(outside, "secret.html"), "link.html"]) {
      const r = await showHtml("s1", { title: "T", file }, deps)
      expect(r.ok).toBe(false)
      expect(err(r)).toContain("inside")
    }
    expect(posted).toEqual([]); expect(checked).toEqual([])
  })

  test("a file under a forbidden folder is refused even when it is in the workdir", async () => {
    const { deps } = setup()
    deps.forbidden = [join(workdir, "state")]
    mkdirSync(join(workdir, "state")); writeFileSync(join(workdir, "state", "a.html"), "x")
    expect((await showHtml("s1", { title: "T", file: "state/a.html" }, deps)).ok).toBe(false)
  })

  test("a missing file is an error", async () => {
    const { deps } = setup()
    const r = await showHtml("s1", { title: "T", file: "nope.html" }, deps)
    expect(r.ok).toBe(false)
  })

  test("over 2 MB is refused, html or file; exactly 2 MB is fine", async () => {
    const { deps, checked } = setup()
    const big = "a".repeat(MAX_PAGE_BYTES + 1)
    const r1 = await showHtml("s1", { title: "T", html: big }, deps)
    expect(r1.ok).toBe(false); expect(err(r1)).toContain("2 MB")
    writeFileSync(join(workdir, "big.html"), big)
    expect((await showHtml("s1", { title: "T", file: "big.html" }, deps)).ok).toBe(false)
    expect(checked).toEqual([])
    expect((await showHtml("s1", { title: "T", html: "a".repeat(MAX_PAGE_BYTES) }, deps)).ok).toBe(true)
  })

  test("size counts bytes, not characters", async () => {
    const { deps } = setup()
    expect((await showHtml("s1", { title: "T", html: "é".repeat(MAX_PAGE_BYTES / 2 + 1) }, deps)).ok).toBe(false)
  })

  test("both html and file, or neither, is refused", async () => {
    const { deps } = setup()
    expect((await showHtml("s1", { title: "T", html: "<p/>", file: "p.html" }, deps)).ok).toBe(false)
    expect((await showHtml("s1", { title: "T" }, deps)).ok).toBe(false)
  })

  test("title: 1-80 characters, required; height: 80-2000", async () => {
    const { deps } = setup()
    expect((await showHtml("s1", { html: "<p/>" } as any, deps)).ok).toBe(false)
    expect((await showHtml("s1", { title: "", html: "<p/>" }, deps)).ok).toBe(false)
    expect((await showHtml("s1", { title: "x".repeat(81), html: "<p/>" }, deps)).ok).toBe(false)
    expect((await showHtml("s1", { title: "x".repeat(80), html: "<p/>" }, deps)).ok).toBe(true)
    expect((await showHtml("s1", { title: "T", html: "<p/>", height: 79 }, deps)).ok).toBe(false)
    expect((await showHtml("s1", { title: "T", html: "<p/>", height: 2001 }, deps)).ok).toBe(false)
    expect((await showHtml("s1", { title: "T", html: "<p/>", height: 80 }, deps)).ok).toBe(true)
  })

  test("an unknown session is refused", async () => {
    const { deps } = setup()
    expect((await showHtml("nope", { title: "T", html: "<p/>" }, deps)).ok).toBe(false)
  })

  test("each call makes a new card, even for the same page", async () => {
    const { deps, posted } = setup()
    await showHtml("s1", { title: "T", html: "<p/>" }, deps)
    await showHtml("s1", { title: "T", html: "<p/>" }, deps)
    expect(posted.length).toBe(2)
  })

  test("a failed post is an error and the agent is told", async () => {
    const { deps } = setup()
    deps.post = async () => ({ ok: false, error: "no channel" })
    const r = await showHtml("s1", { title: "T", html: "<p/>" }, deps)
    expect(r.ok).toBe(false)
    expect(err(r)).toContain("no channel")
  })
})

describe("FsHtmlPageStore", () => {
  test("only a SHA-256 id reads a page; anything else is undefined, never a path", () => {
    const store = new FsHtmlPageStore(join(root, "html"))
    const id = store.put("<i/>")
    expect(readFileSync(join(root, "html", `${id}.html`), "utf8")).toBe("<i/>")
    for (const bad of ["../html/" + id, id + "/..", "abc", "", id.toUpperCase().replace(/E/g, "G")]) expect(store.read(bad)).toBeUndefined()
  })
})
