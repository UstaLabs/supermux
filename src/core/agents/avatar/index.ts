// Agent avatars (FORMAT.md): validation, storage next to the Agent's memory, and a browser-free
// contact-sheet preview (renderer.js → SVG → PNG with resvg).
import { createHash } from "crypto"
import { existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from "fs"
import { join } from "path"
import { Resvg } from "@resvg/resvg-js"
import { getMuxHome } from "../../memory/init"
import { agentMemoryPaths } from "../../memory/agent-memory"

// renderer.js is shared with tools/preview.ts and mirrored by the Kotlin renderer.
import "./renderer.js"

type Renderer = {
  render(spec: unknown, ctx: Record<string, unknown>, px: number, opts?: { clip?: boolean }): string
  validate(spec: unknown): string[]
}
const R = (globalThis as unknown as { SupermuxAvatar: Renderer }).SupermuxAvatar

export const AVATAR_FORMAT_DOC = join(import.meta.dir, "FORMAT.md")
export const AVATAR_EXAMPLE = join(import.meta.dir, "example-fox.json")
export const AVATAR_MAX_BYTES = 64 * 1024
export const AVATAR_MAX_PARTS = 150

export type AvatarCheck = { ok: boolean; errors: string[]; warnings: string[]; spec?: unknown; text?: string }

function countParts(parts: unknown): number {
  if (!Array.isArray(parts)) return 0
  return parts.reduce((n: number, p) => n + 1 + countParts((p as { children?: unknown })?.children), 0)
}

/** Parse + validate an avatar document (a JSON string or an already-parsed object). */
export function checkAvatar(input: unknown): AvatarCheck {
  let text: string
  let spec: unknown
  if (typeof input === "string") {
    text = input
    try { spec = JSON.parse(input) } catch (e) { return { ok: false, errors: [`invalid JSON: ${(e as Error).message}`], warnings: [] } }
  } else if (input && typeof input === "object") {
    spec = input
    text = JSON.stringify(input, null, 1)
  } else {
    return { ok: false, errors: ["no avatar given"], warnings: [] }
  }
  if (Buffer.byteLength(text) > AVATAR_MAX_BYTES) return { ok: false, errors: [`avatar is over ${AVATAR_MAX_BYTES / 1024} KB`], warnings: [] }
  const all = R.validate(spec)
  const warnings = all.filter((e) => e.startsWith("warning:"))
  const errors = all.filter((e) => !e.startsWith("warning:"))
  const parts = countParts((spec as { parts?: unknown }).parts)
  if (parts > AVATAR_MAX_PARTS) errors.push(`too many parts: ${parts} (max ${AVATAR_MAX_PARTS})`)
  return { ok: errors.length === 0, errors, warnings, spec, text }
}

const ROWS: Array<[string, boolean]> = [["idle", false], ["working", false], ["waiting", false], ["asleep", false], ["listening", false], ["speaking", false], ["idle", true]]
const LEVELS = [0.05, 0.6, 0.95, 0.3, 0.8, 0.15, 0.7, 0.4]

/**
 * The contact sheet as a PNG: one row per state (idle, working, waiting, asleep, listening,
 * speaking, idle+hover), 8 frames across 2 s plus a forced blink, each 120 px and clipped like
 * the app; then the same states at the real sidebar size (34 px) shown ×3, on dark and light.
 */
export function renderAvatarSheet(spec: unknown): Buffer {
  const CELL = 120, GAP = 12, LABEL = 110, COLS = 9
  const smallTop = 40 + ROWS.length * (CELL + GAP) + 20
  const SMALL = 34, ZOOM = 3
  const W = LABEL + COLS * (CELL + GAP)
  const H = smallTop + 2 * (SMALL * ZOOM + GAP) + 30
  const out: string[] = [`<rect width="${W}" height="${H}" fill="#1c1c1e"/>`]
  const text = (x: number, y: number, s: string, anchor = "end", fill = "#999") =>
    `<text x="${x}" y="${y}" fill="${fill}" font-family="monospace" font-size="12" text-anchor="${anchor}">${s}</text>`
  const ctxFor = (st: string, hov: boolean, i: number, blink: boolean) => ({
    state: st, prevState: st, switchedAt: 2000, t: 2000 + i * 250, hovered: hov, hoverAt: 2000,
    pointer: hov ? [0.8, -0.5] : null, level: st === "listening" || st === "speaking" ? LEVELS[i] : 0, forceBlink: blink,
  })
  const place = (svg: string, x: number, y: number, w: number) =>
    svg.replace(/^<svg ([^>]*?)width="\d+" height="\d+"/, `<svg $1x="${x}" y="${y}" width="${w}" height="${w}"`)

  for (let c = 0; c < COLS; c++) out.push(text(LABEL + c * (CELL + GAP) + CELL / 2, 24, c === 8 ? "blink" : `${c * 250} ms`, "middle"))
  ROWS.forEach(([st, hov], r) => {
    const y = 40 + r * (CELL + GAP)
    out.push(text(LABEL - 10, y + CELL / 2, st + (hov ? "+hover" : "")))
    for (let c = 0; c < COLS; c++) {
      const x = LABEL + c * (CELL + GAP)
      out.push(`<rect x="${x - 0.5}" y="${y - 0.5}" width="${CELL + 1}" height="${CELL + 1}" fill="none" stroke="#444" stroke-dasharray="3 3"/>`)
      out.push(place(R.render(spec, ctxFor(st, hov, Math.min(c, 7), c === 8), CELL, { clip: true }), x, y, CELL))
    }
  })
  // Real sidebar size: rasterised at 34 px, then scaled up without smoothing (below).
  const states = ["idle", "working", "waiting", "asleep", "listening", "speaking"]
  const smalls: Array<{ x: number; y: number; png: Buffer }> = []
  ;[["#1c1c1e", "#999"], ["#f4f4f5", "#555"]].forEach(([bg, fg], row) => {
    const y = smallTop + row * (SMALL * ZOOM + GAP)
    out.push(text(LABEL - 10, y + (SMALL * ZOOM) / 2, row === 0 ? "34px dark" : "34px light"))
    states.forEach((st, c) => {
      const x = LABEL + c * (SMALL * ZOOM + GAP)
      out.push(`<rect x="${x}" y="${y}" width="${SMALL * ZOOM}" height="${SMALL * ZOOM}" fill="${bg}"/>`)
      out.push(text(x + (SMALL * ZOOM) / 2, y + SMALL * ZOOM - 4, st, "middle", fg))
      const tiny = R.render(spec, ctxFor(st, false, 2, false), SMALL, { clip: true })
      smalls.push({ x, y, png: new Resvg(tiny).render().asPng() })
    })
  })
  for (const s of smalls) {
    // Nearest-neighbour upscale of the real 34 px raster, embedded as an image.
    out.push(`<image x="${s.x}" y="${s.y}" width="${SMALL * ZOOM}" height="${SMALL * ZOOM}" style="image-rendering:pixelated" ` +
      `href="data:image/png;base64,${s.png.toString("base64")}"/>`)
  }
  const sheet = `<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}">${out.join("")}</svg>`
  return Buffer.from(new Resvg(sheet, { font: { loadSystemFonts: true } }).render().asPng())
}

/** Writes the contact sheet under ~/.mux/tmp/avatars and returns its path. */
export function writeAvatarPreview(spec: unknown, label: string, home: string = getMuxHome()): string {
  const dir = join(home, "tmp", "avatars")
  mkdirSync(dir, { recursive: true })
  const file = join(dir, `${label.replace(/[^A-Za-z0-9_-]/g, "_")}-${Date.now()}.png`)
  writeFileSync(file, renderAvatarSheet(spec))
  return file
}

// ── Storage: ~/.mux/agent-memory/<id>.avatar.json ──────────────────────────────────────────────

export function avatarPath(agentId: string, home?: string): string {
  return join(agentMemoryPaths(agentId, home).dir, `${agentId}.avatar.json`)
}

export function readAvatar(agentId: string, home?: string): string | null {
  const p = avatarPath(agentId, home)
  return existsSync(p) ? readFileSync(p, "utf8") : null
}

/** Atomically replaces the Agent's avatar file. Caller validates first. */
export function writeAvatar(agentId: string, text: string, home?: string): void {
  const p = avatarPath(agentId, home)
  mkdirSync(agentMemoryPaths(agentId, home).dir, { recursive: true, mode: 0o700 })
  const tmp = `${p}.tmp-${process.pid}`
  writeFileSync(tmp, text, { encoding: "utf8", mode: 0o600 })
  renameSync(tmp, p)
}

const revCache = new Map<string, { mtimeMs: number; size: number; rev: string | undefined }>()

/**
 * A short content hash of the Agent's avatar (undefined when it has none or the file is invalid),
 * sent with the session snapshot so clients refetch only when it changes.
 */
export function avatarRev(agentId: string, home?: string): string | undefined {
  let p: string
  try { p = avatarPath(agentId, home) } catch { return undefined }
  let st: { mtimeMs: number; size: number }
  try { st = statSync(p) } catch { return undefined }
  const hit = revCache.get(p)
  if (hit && hit.mtimeMs === st.mtimeMs && hit.size === st.size) return hit.rev
  const text = readFileSync(p, "utf8")
  const rev = checkAvatar(text).ok ? createHash("sha1").update(text).digest("hex").slice(0, 12) : undefined
  revCache.set(p, { mtimeMs: st.mtimeMs, size: st.size, rev })
  return rev
}
