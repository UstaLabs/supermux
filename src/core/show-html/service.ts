// show_html, the broker's half (#104 step 3): validate the call, check the page, and either list its problems
// (nothing stored, nothing shown) or store it by its SHA-256 and post a chat message that carries the card.
// Every collaborator is injected, so the handler's tests need no browser and no broker.
import { createHash } from "crypto"
import { existsSync, mkdirSync, readFileSync, realpathSync, renameSync, writeFileSync } from "fs"
import { join, resolve } from "path"
import { paths } from "@supermux/platform"
import { describeProblem, type PageCheck } from "./check"

export const MAX_PAGE_BYTES = 2 * 1024 * 1024
export const MAX_TITLE = 80
export const MIN_HEIGHT = 80
export const MAX_HEIGHT = 2000
export const DEFAULT_HEIGHT = 280

/** What a chat message carries for an html card. `id` is the page's SHA-256; GET /html/<id> serves it. */
export interface HtmlCard {
  id: string
  title: string
  /** The card's first-paint height at phone width: measured by the check, else the call's `height`. */
  height?: number
  /** The measured height at the desktop chat column's width. */
  desktop_height?: number
}

export type ToolOutcome = { ok: true; value?: unknown } | { ok: false; error: string }

const ID_RE = /^[0-9a-f]{64}$/

/** Pages by the SHA-256 of their text, one file each under `dir`. The same page twice is one file. */
export class FsHtmlPageStore {
  constructor(private readonly dir: string) {}

  put(html: string): string {
    const id = createHash("sha256").update(html).digest("hex")
    const file = join(this.dir, `${id}.html`)
    if (!existsSync(file)) {
      mkdirSync(this.dir, { recursive: true })
      const tmp = `${file}.${process.pid}.tmp`
      writeFileSync(tmp, html)
      renameSync(tmp, file)
    }
    return id
  }

  /** The page's text, or undefined: an id that is not a SHA-256 never reaches the file system. */
  read(id: string): string | undefined {
    if (!ID_RE.test(id)) return undefined
    try { return readFileSync(join(this.dir, `${id}.html`), "utf8") } catch { return undefined }
  }
}

export interface ShowHtmlDeps {
  /** The session's working folder, or undefined for a session the broker does not have. */
  workdir(sessionId: string): string | undefined
  check(html: string): Promise<PageCheck>
  store: Pick<FsHtmlPageStore, "put">
  post(sessionId: string, card: HtmlCard): Promise<{ ok: true } | { ok: false; error: string }>
  /** Folders a `file` may never come from, even inside the workdir (the broker's own state). */
  forbidden?: string[]
}

const fail = (error: string): ToolOutcome => ({ ok: false, error })

/** `file` as a real path inside the session's working folder (after symlinks), or the reason it is not. */
function resolveFile(workdir: string, file: string, forbidden: string[]): { path: string } | { error: string } {
  const outside = { error: "file must be inside the session's working folder" }
  let root: string, real: string
  try {
    root = realpathSync(workdir)
    real = realpathSync(resolve(workdir, file))
  } catch (e: any) {
    if (e?.code === "ENOENT") {
      // A path that cannot be resolved yet: a lexical escape is still the more useful answer.
      return paths.isInside(resolve(workdir, file), resolve(workdir)) ? { error: `file not found: ${file}` } : outside
    }
    return { error: `cannot read ${file}: ${e?.message ?? e}` }
  }
  if (!paths.isInside(real, root)) return outside
  for (const f of forbidden) {
    let fr = resolve(f)
    try { fr = realpathSync(fr) } catch {}
    if (paths.isInside(real, fr)) return { error: "file must be inside the session's working folder, not in supermux's own state" }
  }
  return { path: real }
}

export async function showHtml(sessionId: string, args: Record<string, unknown>, deps: ShowHtmlDeps): Promise<ToolOutcome> {
  const { title, html, file, height } = args
  if (typeof title !== "string" || title.length < 1 || title.length > MAX_TITLE) return fail(`title must be 1-${MAX_TITLE} characters`)
  if (height !== undefined && (typeof height !== "number" || !Number.isInteger(height) || height < MIN_HEIGHT || height > MAX_HEIGHT)) {
    return fail(`height must be a whole number from ${MIN_HEIGHT} to ${MAX_HEIGHT}`)
  }
  if ((html === undefined) === (file === undefined)) return fail("pass exactly one of html or file")
  if (html !== undefined && typeof html !== "string") return fail("html must be a string")
  if (file !== undefined && (typeof file !== "string" || !file)) return fail("file must be a path")
  const workdir = deps.workdir(sessionId)
  if (!workdir) return fail("no such session")

  let page: string
  if (typeof html === "string") {
    page = html
  } else {
    const r = resolveFile(workdir, file as string, deps.forbidden ?? [])
    if ("error" in r) return fail(r.error)
    try { page = readFileSync(r.path, "utf8") } catch (e: any) { return fail(`cannot read ${file}: ${e?.message ?? e}`) }
  }
  if (Buffer.byteLength(page) > MAX_PAGE_BYTES) return fail("the page is over 2 MB: make it smaller")
  if (!page.trim()) return fail("the page is empty")

  const check = await deps.check(page)
  if (check.problems.length > 0) {
    return { ok: true, value: "not shown — fix the page and call show_html again:\n" + check.problems.map((p) => `- ${describeProblem(p)}`).join("\n") }
  }

  const id = deps.store.put(page)
  const measured = check.height
  const card: HtmlCard = {
    id, title,
    ...((measured ?? (height as number | undefined)) !== undefined ? { height: measured ?? (height as number) } : {}),
    ...(measured !== undefined && check.desktopHeight !== undefined ? { desktop_height: check.desktopHeight } : {}),
  }
  const posted = await deps.post(sessionId, card)
  if (!posted.ok) return fail(`the page could not be posted to the chat: ${posted.error}`)

  const unchecked = check.notes.find((n) => n.startsWith("not checked"))
  const rest = check.notes.filter((n) => n !== unchecked)
  let line: string
  if (unchecked) {
    line = `shown without a check (${unchecked.replace(/^not checked:?\s*/, "")})`
  } else {
    line = `shown (id: ${id})` +
      (card.desktop_height !== undefined && card.height !== undefined ? `: ${card.desktop_height} px tall on a desktop, ${card.height} px on a phone` : "")
  }
  return { ok: true, value: [line, ...rest].join("\n") }
}
