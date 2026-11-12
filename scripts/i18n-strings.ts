// The language guards (card #123). Two checks over the Kotlin apps:
//
// 1. Hard-coded UI text. A string literal the user reads (Text("…"), label = "…", a Kit row's title) belongs in
//    composeResources/values/strings.xml. Today's code has many, so tests/i18n-hardcoded.baseline.json records how
//    many each file has, and tests/i18n-strings.test.ts fails when a count RISES (new hard-coded text) or FALLS
//    (lower the baseline: it only shrinks). Rewrite it from the current tree with:
//      bun scripts/i18n-strings.ts --write
//    A literal that is no UI text (a tag, a key, a sample) carries `// i18n-ok: <why>` on its line or the line above.
// 2. Catalog parity. Every values-<lang>/strings.xml has exactly the English keys, with the same placeholders.
import { Glob } from "bun"
import { existsSync, readFileSync, writeFileSync } from "node:fs"
import { join, resolve } from "node:path"

export type Hit = { file: string; line: number; text: string }
export type Counts = Record<string, number>
export type Change = { file: string; was: number; now: number }
export type Catalog = Map<string, string[]>

const ROOT = resolve(import.meta.dir, "..")
export const BASELINE = join(ROOT, "tests", "i18n-hardcoded.baseline.json")

/** Harnesses and samples a user never runs, and engines whose literals are error messages, never shown as UI. */
const NOT_SHIPPED = ["apps/mermaid-core/", "apps/terminal-core/", "apps/editor-core/", "apps/editor-sample/", "apps/terminal-sample/", "apps/webview-sample/", "apps/webview-wpetest/", "apps/webview-wintest/", "apps/webview-compose-testing/"]

// A literal in a position the user reads: a Text's first argument (also when an if picks it), a named UI parameter,
// or a Kit row's title — each also on the next line. Matched over the whole file, comments blanked first.
const STR = String.raw`"((?:[^"\\\n]|\\.)*)"`
const UI_LITERAL = [
  new RegExp(String.raw`\bText\(\s*` + STR, "g"),
  new RegExp(String.raw`\bText\(\s*if\s*\([^)]*\)\s*` + STR + String.raw`\s*else\s*` + STR, "g"),
  new RegExp(String.raw`\b(?:label|title|text|contentDescription|placeholder|subtitle|description|hint|tooltip|button|message|confirmLabel|dismissLabel|actionLabel|emptyText)\s*=\s*` + STR, "g"),
  new RegExp(String.raw`\bKit[A-Z]\w*\(\s*` + STR, "g"),
]
// Text a when branch or an if/else picks: a sentence or a phrase ("Connecting…", "Offline · …"), never one
// lowercase word (a wire value, an id).
const PICKED = new RegExp(String.raw`(?:->|\belse)\s*` + STR, "g")
const OK = /\/\/\s*i18n-ok\b(.*)$/

/** Words remain once templates are taken out: "$count" and "${a} · ${b}" are no text to translate. */
function isText(literal: string): boolean {
  const words = literal.replace(/\$\{[^}]*\}/g, " ").replace(/\$\w+/g, " ").replace(/\\./g, " ")
  return /[A-Za-z]{2,}/.test(words)
}

function isPhrase(literal: string): boolean {
  return isText(literal) && /^[A-Z0-9$]/.test(literal) && /[\s…]/.test(literal)
}

/** Comments become spaces of the same length, so offsets and line numbers still point into the source. */
function blankComments(source: string): string {
  return source.replace(/"(?:[^"\\\n]|\\.)*"|\/\*[\s\S]*?\*\/|\/\/[^\n]*/g, m => (m.startsWith('"') ? m : m.replace(/[^\n]/g, " ")))
}

export function scanText(file: string, source: string): Hit[] {
  const lines = source.split("\n")
  const code = blankComments(source)
  const lineAt = (offset: number) => code.slice(0, offset).split("\n").length
  const marked = (line: number) => {
    const ok = OK.exec(lines[line - 1]!) ?? (line > 1 ? OK.exec(lines[line - 2]!) : null)
    return ok !== null && ok[1]!.replace(/^[:\s]+/, "").trim() !== ""
  }
  const found = new Map<number, Hit>()
  const add = (offset: number, literal: string) => {
    const line = lineAt(offset)
    if (!marked(line)) found.set(offset, { file, line, text: lines[line - 1]!.trim() })
  }
  for (const pattern of UI_LITERAL) {
    for (const m of code.matchAll(pattern)) {
      for (let g = 1; g < m.length; g++) {
        if (m[g] === undefined || !isText(m[g]!)) continue
        add(m.index! + m[0].lastIndexOf(`"${m[g]}"`), m[g]!)
      }
    }
  }
  for (const m of code.matchAll(PICKED)) {
    if (isPhrase(m[1]!)) add(m.index! + m[0].indexOf('"'), m[1]!)
  }
  return [...found.values()].sort((a, b) => a.line - b.line)
}

export function kotlinSources(root: string = ROOT): string[] {
  const files: string[] = []
  for (const file of new Glob("apps/*/src/*/kotlin/**/*.kt").scanSync({ cwd: root })) {
    const set = file.split("/")[3]!
    if (/test/i.test(set)) continue
    if (NOT_SHIPPED.some(prefix => file.startsWith(prefix))) continue
    files.push(file)
  }
  return files.sort()
}

export function scanRepo(root: string = ROOT): Hit[] {
  return kotlinSources(root).flatMap(file => scanText(file, readFileSync(join(root, file), "utf8")))
}

export function countHits(hits: readonly Hit[]): Counts {
  const counts: Counts = {}
  for (const hit of hits) counts[hit.file] = (counts[hit.file] ?? 0) + 1
  return counts
}

export function compare(current: Counts, baseline: Counts): { rose: Change[]; fell: Change[] } {
  const rose: Change[] = []
  const fell: Change[] = []
  for (const file of [...new Set([...Object.keys(current), ...Object.keys(baseline)])].sort()) {
    const change = { file, was: baseline[file] ?? 0, now: current[file] ?? 0 }
    if (change.now > change.was) rose.push(change)
    else if (change.now < change.was) fell.push(change)
  }
  return { rose, fell }
}

export function formatBaseline(counts: Counts): string {
  const sorted: Counts = {}
  for (const file of Object.keys(counts).sort()) sorted[file] = counts[file]!
  return `${JSON.stringify(sorted, null, 2)}\n`
}

export function readBaseline(path: string = BASELINE): Counts {
  return existsSync(path) ? (JSON.parse(readFileSync(path, "utf8")) as Counts) : {}
}

// ── catalogs ──────────────────────────────────────────────────────────────────────────────────────────────────

/** key → its placeholders (sorted); a plural's key is `name#quantity`. */
export function parseCatalog(xml: string): Catalog {
  const catalog: Catalog = new Map()
  const body = xml.replace(/<!--[\s\S]*?-->/g, "")
  for (const m of body.matchAll(/<string\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/string>/g)) {
    catalog.set(m[1]!, placeholders(m[2]!))
  }
  for (const p of body.matchAll(/<plurals\s+name="([^"]+)"[^>]*>([\s\S]*?)<\/plurals>/g)) {
    for (const item of p[2]!.matchAll(/<item\s+quantity="([^"]+)"[^>]*>([\s\S]*?)<\/item>/g)) {
      catalog.set(`${p[1]}#${item[1]}`, placeholders(item[2]!))
    }
  }
  return catalog
}

function placeholders(text: string): string[] {
  return [...text.matchAll(/%(?:\d+\$)?[sd]/g)].map(m => m[0]).sort()
}

/** Each module's English catalog with its translations: `{ dir, english, translations: { lang: path } }`. */
export function catalogs(root: string = ROOT): { dir: string; english: string; translations: Record<string, string> }[] {
  const out = []
  for (const english of new Glob("apps/*/src/*/composeResources/values/*.xml").scanSync({ cwd: root })) {
    const dir = english.slice(0, english.indexOf("/values/"))
    const name = english.slice(english.lastIndexOf("/") + 1)
    const translations: Record<string, string> = {}
    for (const t of new Glob(`${dir}/values-*/${name}`).scanSync({ cwd: root })) {
      const lang = t.slice(dir.length + "/values-".length, t.lastIndexOf("/"))
      translations[lang] = t
    }
    out.push({ dir, english, translations })
  }
  return out.sort((a, b) => a.english.localeCompare(b.english))
}

/** What a translation gets wrong against English: missing keys, keys English no longer has, other placeholders. */
export function parityProblems(english: Catalog, translation: Catalog): string[] {
  const problems: string[] = []
  // Plural quantities differ by language (Turkish has one/other, others have few/many): compare plural names only.
  const base = (k: string) => k.split("#")[0]!
  const enKeys = new Set([...english.keys()].map(base))
  const trKeys = new Set([...translation.keys()].map(base))
  for (const k of enKeys) if (!trKeys.has(k)) problems.push(`missing: ${k}`)
  for (const k of trKeys) if (!enKeys.has(k)) problems.push(`not in English: ${k}`)
  for (const [k, ph] of english) {
    if (k.includes("#")) continue
    const other = translation.get(k)
    if (other && other.join(",") !== ph.join(",")) problems.push(`placeholders differ: ${k} (${ph.join(" ")} vs ${other.join(" ")})`)
  }
  return problems
}

if (import.meta.main) {
  if (process.argv[2] !== "--write") {
    console.error("usage: bun scripts/i18n-strings.ts --write   (rewrites tests/i18n-hardcoded.baseline.json)")
    process.exit(2)
  }
  writeFileSync(BASELINE, formatBaseline(countHits(scanRepo())))
  console.log(`wrote ${BASELINE}`)
}
