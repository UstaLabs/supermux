// #165: applet widgets v2. An applet describes its widget as native blocks laid out per size, with actions its buttons
// run and, where a graphic needs it, a picture part drawn from its widget.html. The broker reads what the applet
// answers, keeps only what is valid and fills every size, so a phone never has to guess.

/** Where a widget can be placed. Home Screen: small…tall (+ Android's slim 2×1); Lock Screen: the three accessories. */
export const HOME_SIZES = ["small", "medium", "large", "xlarge", "wide", "tall", "slim"] as const
export type HomeSize = (typeof HOME_SIZES)[number]
export const ACCESSORY_SIZES = ["circular", "rectangular", "inline"] as const

/** The size a missing one borrows from, nearest first. */
const NEAREST: Record<HomeSize, HomeSize[]> = {
  small: ["medium", "tall", "large", "wide", "slim", "xlarge"],
  medium: ["wide", "large", "small", "xlarge", "tall", "slim"],
  large: ["xlarge", "tall", "medium", "small", "wide", "slim"],
  xlarge: ["large", "medium", "wide", "tall", "small", "slim"],
  wide: ["medium", "slim", "small", "large", "xlarge", "tall"],
  tall: ["large", "small", "medium", "xlarge", "wide", "slim"],
  slim: ["small", "wide", "medium", "tall", "large", "xlarge"],
}

export interface WidgetAction { title: string; method: "POST" | "PUT" | "DELETE"; path: string; confirm: boolean }

/** applet.json "widget": a path (v1, "/widget.json") or the full form. */
export interface WidgetConfig {
  /** Where the applet answers its widget document. */
  data?: string
  /** Its picture page, drawn by the phone (graphics only). */
  html?: string
  accent?: string
  actions: Record<string, WidgetAction>
}

export type Tone = "up" | "down" | "warn" | "bad" | "ok"
export interface ButtonRef { title: string; action: string; args?: Record<string, string | number | boolean>; style?: "fill" }
export type Block =
  | { type: "header"; title?: string; sub?: string }
  | { type: "stat"; value: string; label?: string; trend?: string; tone?: Tone; size?: "sm" | "lg"; until?: string }
  | { type: "stats"; items: { value: string; label?: string; tone?: Tone }[] }
  | { type: "progress"; value: number; label?: string; right?: string }
  | { type: "ring"; value: number; text?: string; sub?: string }
  | { type: "chart"; kind: "bars" | "line"; points: number[]; labels?: string[]; label?: string }
  | { type: "list"; title?: string; rows: Row[] }
  | { type: "toggles"; grid?: boolean; items: { title: string; on: boolean; sub?: string; action: string; args?: ButtonRef["args"] }[] }
  | { type: "buttons"; items: ButtonRef[] }
  | { type: "image"; src: string; label?: string }
  | { type: "html"; part: string; height: number; label: string; caption?: string; alt?: Block }
  | { type: "text"; text: string }
  | { type: "columns"; cols: Block[][]; w?: number[] }
  | { type: "gap"; size?: "s" | "l" }
  | { type: "grow" }
export interface Row { title: string; sub?: string; value?: string; color?: string; icon?: string; href?: string; button?: ButtonRef }

export interface Accessories {
  circular: { ring?: number; text: string; label?: string; action?: { action: string; args?: ButtonRef["args"] } }
  rectangular: { lines: string[]; button?: ButtonRef }
  inline: string
}

export interface WidgetDoc {
  v: 2
  /** Where a tap that isn't on a row or button opens the applet ("/#board"). */
  href?: string
  /** "full": the whole widget is one picture of widget.html (the flagged escape hatch); [label] is what VoiceOver says. */
  snapshot?: "full"
  label?: string
  /** Every size, filled in. */
  layouts: Record<HomeSize, Block[]> & Accessories
  /** Later versions of some sizes, each shown from its time on (no reload needed). */
  timeline: { at: string; layouts: Partial<Record<HomeSize, Block[]>> }[]
}

/** The v1 glance ({value, label, lines}): still answered to apps that predate v2. */
export interface WidgetSummary { value?: string; label?: string; lines?: string[] }

export interface ParsedWidget { doc: WidgetDoc; summary: WidgetSummary | null; problems: string[] }

const MAX_DOC_BYTES = 48 * 1024
const BLOCK_TYPES = new Set(["header", "stat", "stats", "progress", "ring", "chart", "list", "toggles", "buttons", "image", "html", "text", "columns", "gap", "grow"])
const TONES = new Set(["up", "down", "warn", "bad", "ok"])
const isObj = (x: unknown): x is Record<string, unknown> => !!x && typeof x === "object" && !Array.isArray(x)
const text = (x: unknown, max: number): string =>
  typeof x === "string" ? x.trim().slice(0, max) : typeof x === "number" && Number.isFinite(x) ? String(x).slice(0, max) : ""
const opt = <K extends string>(k: K, v: string) => (v ? { [k]: v } as Record<K, string> : {})
const frac = (x: unknown) => (typeof x === "number" && Number.isFinite(x) ? Math.min(1, Math.max(0, x)) : 0)
const COLOR = /^#[0-9a-fA-F]{6}$/
/** A path into the applet (an href, an image): relative to its page, never another site. */
const appPath = (x: unknown): string => {
  const s = text(x, 300)
  return s && !/^[a-z][a-z0-9+.-]*:/i.test(s) && !s.startsWith("//") ? s : ""
}

/** applet.json "widget": a string path, or {data, html, accent, actions}. Null with an error when it can't be used. */
export function parseWidgetConfig(raw: unknown): { ok: true; config: WidgetConfig | undefined } | { ok: false; error: string } {
  if (raw === undefined) return { ok: true, config: undefined }
  if (typeof raw === "string") {
    if (!raw.trim().startsWith("/")) return { ok: false, error: `"widget" must be a path starting with /` } // platform-ok: URL path
    return { ok: true, config: { data: raw.trim(), actions: {} } }
  }
  if (!isObj(raw)) return { ok: false, error: `"widget" must be a path or an object` }
  const out: WidgetConfig = { actions: {} }
  for (const k of ["data", "html"] as const) {
    if (raw[k] === undefined) continue
    if (typeof raw[k] !== "string" || !(raw[k] as string).startsWith("/")) return { ok: false, error: `"widget.${k}" must be a path starting with /` } // platform-ok: URL path
    out[k] = (raw[k] as string).trim()
  }
  if (raw.accent !== undefined) {
    if (typeof raw.accent !== "string" || !COLOR.test(raw.accent)) return { ok: false, error: `"widget.accent" must be a colour like #2f9e8c` }
    out.accent = raw.accent
  }
  if (raw.actions !== undefined) {
    if (!isObj(raw.actions)) return { ok: false, error: `"widget.actions" must be an object of actions` }
    for (const [name, a] of Object.entries(raw.actions)) {
      if (!/^[a-z][a-z0-9-]{0,31}$/.test(name)) return { ok: false, error: `widget action "${name}": a name is lowercase letters, digits and dashes` }
      if (!isObj(a) || typeof a.path !== "string" || !a.path.startsWith("/")) return { ok: false, error: `widget action "${name}" needs a "path" starting with /` } // platform-ok: URL path
      const method = a.method === undefined ? "POST" : String(a.method).toUpperCase()
      if (method !== "POST" && method !== "PUT" && method !== "DELETE") return { ok: false, error: `widget action "${name}": method must be POST, PUT or DELETE` }
      out.actions[name] = { title: text(a.title, 30) || name, method, path: a.path, confirm: a.confirm === true }
    }
  }
  if (!out.data) return { ok: false, error: `"widget.data" is required (the path that answers the widget, e.g. /widget.json)` }
  return { ok: true, config: out }
}

/** Fills an action's path from its args: /api/issues/{id}/tested + {id: 164} → /api/issues/164/tested. */
export function actionPath(path: string, args: Record<string, unknown>): string | null {
  let missing = false
  const out = path.replace(/\{([a-zA-Z0-9_]+)\}/g, (_, k: string) => {
    const v = args[k]
    if (typeof v !== "string" && typeof v !== "number" && typeof v !== "boolean") { missing = true; return "" }
    return encodeURIComponent(String(v))
  })
  return missing ? null : out
}

class Reader {
  problems: string[] = []
  constructor(private readonly actions: Set<string>) {}

  args(x: unknown): ButtonRef["args"] | undefined {
    if (!isObj(x)) return undefined
    const out: Record<string, string | number | boolean> = {}
    for (const [k, v] of Object.entries(x).slice(0, 8)) {
      if (typeof v === "string") out[k] = v.slice(0, 200)
      else if ((typeof v === "number" && Number.isFinite(v)) || typeof v === "boolean") out[k] = v
    }
    return Object.keys(out).length ? out : undefined
  }

  action(name: unknown, where: string): string | null {
    const a = text(name, 32)
    if (!a) { this.problems.push(`${where}: a button needs an "action"`); return null }
    if (!this.actions.has(a)) { this.problems.push(`${where}: action "${a}" isn't in applet.json "widget.actions"; the button is left out`); return null }
    return a
  }

  button(x: unknown, where: string): ButtonRef | undefined {
    if (!isObj(x)) return undefined
    const action = this.action(x.action, where)
    const title = text(x.title, 30)
    if (!action) return undefined
    if (!title) { this.problems.push(`${where}: a button needs a "title"`); return undefined }
    const args = this.args(x.args)
    return { title, action, ...(args ? { args } : {}), ...(x.style === "fill" ? { style: "fill" as const } : {}) }
  }

  blocks(x: unknown, where: string, depth = 0): Block[] {
    if (!Array.isArray(x)) { this.problems.push(`${where}: a layout is a list of blocks`); return [] }
    return x.slice(0, 16).map((b, i) => this.block(b, `${where}[${i}]`, depth)).filter((b): b is Block => !!b)
  }

  block(b: unknown, where: string, depth: number): Block | null {
    if (!isObj(b) || typeof b.type !== "string") { this.problems.push(`${where}: a block needs a "type"`); return null }
    if (!BLOCK_TYPES.has(b.type)) { this.problems.push(`${where}: unknown block type "${b.type}"`); return null }
    const tone = typeof b.tone === "string" && TONES.has(b.tone) ? { tone: b.tone as Tone } : {}
    switch (b.type) {
      case "header": return { type: "header", ...opt("title", text(b.title, 40)), ...opt("sub", text(b.sub, 40)) }
      case "stat": {
        const value = text(b.value, 16)
        if (!value) { this.problems.push(`${where}: a stat needs a "value"`); return null }
        const until = typeof b.until === "string" && !Number.isNaN(Date.parse(b.until)) ? { until: b.until } : {}
        const size = b.size === "sm" || b.size === "lg" ? { size: b.size as "sm" | "lg" } : {}
        return { type: "stat", value, ...opt("label", text(b.label, 48)), ...opt("trend", text(b.trend, 48)), ...tone, ...size, ...until }
      }
      case "stats": {
        const items = (Array.isArray(b.items) ? b.items : []).slice(0, 4).filter(isObj).map((i) => ({
          value: text(i.value, 12), ...opt("label", text(i.label, 24)), ...(typeof i.tone === "string" && TONES.has(i.tone) ? { tone: i.tone as Tone } : {}),
        })).filter((i) => i.value)
        return items.length ? { type: "stats", items } : null
      }
      case "progress": return { type: "progress", value: frac(b.value), ...opt("label", text(b.label, 40)), ...opt("right", text(b.right, 24)) }
      case "ring": return { type: "ring", value: frac(b.value), ...opt("text", text(b.text, 8)), ...opt("sub", text(b.sub, 16)) }
      case "chart": {
        const points = (Array.isArray(b.points) ? b.points : []).slice(-60).map(Number).filter(Number.isFinite)
        if (points.length < 2) { this.problems.push(`${where}: a chart needs at least 2 points`); return null }
        const labels = Array.isArray(b.labels) ? b.labels.slice(0, 12).map((l) => text(l, 8)) : undefined
        return { type: "chart", kind: b.kind === "line" ? "line" : "bars", points, ...(labels?.length ? { labels } : {}), ...opt("label", text(b.label, 48)) }
      }
      case "list": {
        const rows = (Array.isArray(b.rows) ? b.rows : []).slice(0, 8).filter(isObj).map((r, i): Row | null => {
          const title = text(r.title, 100)
          if (!title) return null
          const color = typeof r.color === "string" && COLOR.test(r.color) ? { color: r.color } : {}
          const button = this.button(r.button, `${where}.rows[${i}].button`)
          return { title, ...opt("sub", text(r.sub, 60)), ...opt("value", text(r.value, 16)), ...color, ...opt("icon", text(r.icon, 40)), ...opt("href", appPath(r.href)), ...(button ? { button } : {}) }
        }).filter((r): r is Row => !!r)
        return { type: "list", ...opt("title", text(b.title, 40)), rows }
      }
      case "toggles": {
        const items = (Array.isArray(b.items) ? b.items : []).slice(0, 6).filter(isObj).map((t, i) => {
          const action = this.action(t.action, `${where}.items[${i}]`)
          const title = text(t.title, 40)
          if (!action || !title) return null
          const args = this.args(t.args)
          return { title, on: t.on === true, ...opt("sub", text(t.sub, 12)), action, ...(args ? { args } : {}) }
        }).filter((t): t is NonNullable<typeof t> => !!t)
        return items.length ? { type: "toggles", ...(b.grid === true ? { grid: true } : {}), items } : null
      }
      case "buttons": {
        const items = (Array.isArray(b.items) ? b.items : []).slice(0, 3).map((x, i) => this.button(x, `${where}.items[${i}]`)).filter((x): x is ButtonRef => !!x)
        return items.length ? { type: "buttons", items } : null
      }
      case "image": {
        const src = appPath(b.src)
        if (!src) { this.problems.push(`${where}: an image needs a "src" inside the applet`); return null }
        return { type: "image", src, ...opt("label", text(b.label, 120)) }
      }
      case "html": {
        const part = text(b.part, 32)
        const label = text(b.label, 200)
        const height = typeof b.height === "number" && Number.isFinite(b.height) ? Math.round(Math.min(400, Math.max(16, b.height))) : 0
        if (!part) { this.problems.push(`${where}: a picture needs a "part"`); return null }
        if (!label) { this.problems.push(`${where}: a picture needs a "label" (what VoiceOver says); it is left out`); return null }
        if (!height) { this.problems.push(`${where}: a picture needs a "height"`); return null }
        const alt = b.alt !== undefined ? this.block(b.alt, `${where}.alt`, depth + 1) : null
        if (!alt) this.problems.push(`${where}: a picture should have an "alt" block, shown until the picture is drawn`)
        return { type: "html", part, height, label, ...opt("caption", text(b.caption, 48)), ...(alt && alt.type !== "html" ? { alt } : {}) }
      }
      case "text": { const t = text(b.text, 160); return t ? { type: "text", text: t } : null }
      case "columns": {
        if (depth > 0) { this.problems.push(`${where}: columns can't be inside columns`); return null }
        const cols = (Array.isArray(b.cols) ? b.cols : []).slice(0, 3).map((c, i) => this.blocks(c, `${where}.cols[${i}]`, depth + 1))
        if (!cols.length) return null
        const w = Array.isArray(b.w) && b.w.length === cols.length && b.w.every((n) => typeof n === "number" && n > 0) ? { w: b.w as number[] } : {}
        return { type: "columns", cols, ...w }
      }
      case "gap": return { type: "gap", ...(b.size === "s" || b.size === "l" ? { size: b.size } : {}) }
      case "grow": return { type: "grow" }
    }
    return null
  }
}

/** The first stat or ring a layout shows (for the accessories and the v1 summary). */
function firstFigure(blocks: Block[]): { value: string; label?: string; ring?: number } | null {
  for (const b of blocks) {
    if (b.type === "stat") return { value: b.value, ...(b.label ? { label: b.label } : {}) }
    if (b.type === "ring") return { value: b.text ?? `${Math.round(b.value * 100)}%`, ...(b.sub ? { label: b.sub } : {}), ring: b.value }
    if (b.type === "stats" && b.items[0]) return { value: b.items[0].value, ...(b.items[0].label ? { label: b.items[0].label } : {}) }
    if (b.type === "columns") for (const c of b.cols) { const f = firstFigure(c); if (f) return f }
  }
  return null
}
function firstRows(blocks: Block[]): Row[] {
  for (const b of blocks) {
    if (b.type === "list" && b.rows.length) return b.rows
    if (b.type === "columns") for (const c of b.cols) { const r = firstRows(c); if (r.length) return r }
  }
  return []
}

/** v1 {value, label, lines} → a summary (null when it says nothing). */
export function parseWidgetSummary(raw: unknown): WidgetSummary | null {
  if (!isObj(raw)) return null
  const value = text(raw.value, 12)
  const label = text(raw.label, 40)
  const lines = Array.isArray(raw.lines) ? raw.lines.map((l) => text(l, 80)).filter(Boolean).slice(0, 3) : []
  if (!value && !label && !lines.length) return null
  return { ...(value ? { value } : {}), ...(label ? { label } : {}), ...(lines.length ? { lines } : {}) }
}

function fromSummary(s: WidgetSummary, title: string): Partial<Record<HomeSize, Block[]>> {
  const head: Block = { type: "header", title }
  const stat: Block | null = s.value ? { type: "stat", value: s.value, ...(s.label ? { label: s.label } : {}) } : s.label ? { type: "text", text: s.label } : null
  const rows: Block | null = s.lines?.length ? { type: "list", rows: s.lines.map((l) => ({ title: l })) } : null
  const small = [head, ...(stat ? [stat] : [])]
  return {
    small: rows && !stat ? [head, rows] : small,
    medium: rows ? [{ type: "columns", w: [0.45, 0.55], cols: [small, [rows]] }] : small,
    large: [...small, ...(rows ? [{ type: "gap" } as Block, rows] : [])],
  }
}

/**
 * What an applet answered at its widget path, made safe and complete: v2 ({layouts}) or v1 ({value, label, lines}).
 * Null when it says nothing usable. [actions] are the names applet.json declares; buttons naming others are left out.
 */
export function parseWidget(raw: unknown, opts: { title: string; actions?: string[] }): ParsedWidget | null {
  if (!isObj(raw)) return null
  if (JSON.stringify(raw).length > MAX_DOC_BYTES) return null
  const r = new Reader(new Set(opts.actions ?? []))
  const given: Partial<Record<HomeSize, Block[]>> = {}
  let acc: Partial<Accessories> = {}
  const isV2 = raw.v === 2 || isObj(raw.layouts)
  if (isV2) {
    const layouts = isObj(raw.layouts) ? raw.layouts : {}
    for (const k of HOME_SIZES) if (layouts[k] !== undefined) { const b = r.blocks(layouts[k], `layouts.${k}`); if (b.length) given[k] = b }
    if (isObj(layouts.circular)) {
      const c = layouts.circular
      const t = text(c.text, 8)
      const action = isObj(c.action) ? r.action(c.action.action, "layouts.circular.action") : null
      const args = isObj(c.action) ? r.args(c.action.args) : undefined
      if (t) acc.circular = { text: t, ...(typeof c.ring === "number" ? { ring: frac(c.ring) } : {}), ...opt("label", text(c.label, 12)), ...(action ? { action: { action, ...(args ? { args } : {}) } } : {}) }
    }
    if (isObj(layouts.rectangular)) {
      const lines = (Array.isArray(layouts.rectangular.lines) ? layouts.rectangular.lines : []).map((l) => text(l, 40)).filter(Boolean).slice(0, 3)
      const button = r.button(layouts.rectangular.button, "layouts.rectangular.button")
      if (lines.length) acc.rectangular = { lines: button ? lines.slice(0, 2) : lines, ...(button ? { button } : {}) }
    }
    if (typeof layouts.inline === "string" && layouts.inline.trim()) acc.inline = text(layouts.inline, 48)
  } else {
    const s = parseWidgetSummary(raw)
    if (!s) return null
    Object.assign(given, fromSummary(s, opts.title))
  }
  const snapshot = isV2 && raw.snapshot === "full" ? "full" as const : undefined
  const label = text(raw.label, 200)
  if (snapshot) {
    if (!label) r.problems.push(`"snapshot": "full" needs a "label": the whole widget is one picture, and the label is all VoiceOver can say`)
    r.problems.push(`"snapshot": "full" is the escape hatch: VoiceOver hears only its label, its text doesn't grow, clear and tinted turn it grey. Prefer native blocks with a picture part`)
  }
  const anyGiven = HOME_SIZES.find((k) => given[k])
  if (!anyGiven) {
    if (!snapshot) { r.problems.push(`no layout for any size`); return null }
    given.small = [{ type: "header", title: opts.title }]
  }
  const layouts = {} as Record<HomeSize, Block[]>
  for (const k of HOME_SIZES) layouts[k] = given[k] ?? given[NEAREST[k].find((n) => given[n])!]!
  const fig = firstFigure(layouts.small)
  const rows = firstRows(layouts.medium)
  const circular = acc.circular ?? { text: fig?.value ?? opts.title.slice(0, 2), ...(fig?.ring !== undefined ? { ring: fig.ring } : {}), ...(fig?.label ? { label: fig.label.slice(0, 12) } : {}) }
  const rectangular = acc.rectangular ?? { lines: [opts.title, ...(fig ? [`${fig.value}${fig.label ? " " + fig.label : ""}`] : []), ...(rows[0] ? [rows[0].title] : [])].slice(0, 3) }
  const inline = acc.inline ?? (fig ? `${fig.value}${fig.label ? " " + fig.label : ""}` : opts.title)
  const timeline: WidgetDoc["timeline"] = []
  if (isV2 && Array.isArray(raw.timeline)) {
    for (const [i, t] of raw.timeline.slice(0, 12).entries()) {
      if (!isObj(t) || typeof t.at !== "string" || Number.isNaN(Date.parse(t.at)) || !isObj(t.layouts)) { r.problems.push(`timeline[${i}]: needs "at" (a time) and "layouts"`); continue }
      const later: Partial<Record<HomeSize, Block[]>> = {}
      for (const k of HOME_SIZES) if (t.layouts[k] !== undefined) { const b = r.blocks(t.layouts[k], `timeline[${i}].layouts.${k}`); if (b.length) later[k] = b }
      if (Object.keys(later).length) timeline.push({ at: new Date(t.at).toISOString(), layouts: later })
    }
    timeline.sort((a, b) => a.at.localeCompare(b.at))
  }
  const doc: WidgetDoc = {
    v: 2,
    ...opt("href", isV2 ? appPath(raw.href) : ""),
    ...(snapshot ? { snapshot } : {}),
    ...(label ? { label } : {}),
    layouts: { ...layouts, circular, rectangular, inline },
    timeline,
  }
  // Older apps draw only the v1 summary: the small size's first figure, else the Lock Screen circle's.
  const glance = fig ?? (acc.circular ? { value: acc.circular.text, ...(acc.circular.label ? { label: acc.circular.label } : {}) } : null)
  // A document that also carries the v1 fields keeps them as they are for older apps.
  const summary: WidgetSummary | null = isV2
    ? parseWidgetSummary(raw) ?? (glance ? { value: glance.value.slice(0, 12), ...(glance.label ? { label: glance.label.slice(0, 40) } : {}), ...(rows.length ? { lines: rows.slice(0, 3).map((x) => x.title.slice(0, 80)) } : {}) } : null)
    : parseWidgetSummary(raw)
  return { doc, summary, problems: r.problems }
}
