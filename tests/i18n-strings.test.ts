// The language guards (card #123; scripts/i18n-strings.ts has the rules). A count that rises is new hard-coded UI
// text; a count that falls means the baseline should be lowered:
//   bun scripts/i18n-strings.ts --write
import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { catalogs, kotlinSources, lf, repoPath, compare, countHits, parityProblems, parseCatalog, readBaseline, scanRepo, scanText, type Change } from "../scripts/i18n-strings"

const hits = scanRepo()
const drift = compare(countHits(hits), readBaseline())

function explain(change: Change): string {
  const lines = hits.filter(h => h.file === change.file).map(h => `    ${h.file}:${h.line}  ${h.text}`)
  return [`${change.file} ${change.was} → ${change.now}: move the text into composeResources/files/i18n/values/ (and values-tr/)`, ...lines].join("\n")
}

test("no new hard-coded UI text", () => {
  expect(drift.rose.map(explain)).toEqual([])
})

test("the baseline only shrinks: lower it when a count falls (bun scripts/i18n-strings.ts --write)", () => {
  expect(drift.fell.map(c => `${c.file} ${c.was} → ${c.now}`)).toEqual([])
})

test("every translation has exactly the English keys, with the same placeholders", () => {
  const all = catalogs()
  expect(all.length).toBeGreaterThan(0)
  const problems = all.flatMap(({ english, translations }) => {
    const en = parseCatalog(readFileSync(english, "utf8"))
    return Object.entries(translations).flatMap(([lang, path]) =>
      parityProblems(en, parseCatalog(readFileSync(path, "utf8"))).map(p => `${path} (${lang}): ${p}`),
    )
  })
  expect(problems).toEqual([])
})

test("no string sits in Compose's own values/ (its generated accessors cost ~150 B of wasm per key)", () => {
  expect(catalogs().length).toBeGreaterThan(0)
  const valuesDirs = [...new Bun.Glob("apps/*/src/*/composeResources/values*/*.xml").scanSync({ cwd: "." })]
  expect(valuesDirs.map(repoPath)).toEqual([])
})

test("strings go through localized(), which follows the language picked in Settings", () => {
  const direct = kotlinSources()
    .filter(f => !f.includes("/ui/i18n/"))
    .flatMap(f => lf(readFileSync(f, "utf8")).split("\n").map((t, i) => ({ f, i, t })))
    .filter(({ t }) => /\b(?:plural)?[sS]tringResource\(/.test(t) && !/^\s*(\/\/|\*)/.test(t))
    .map(({ f, i, t }) => `${f}:${i + 1}  ${t.trim()}`)
  expect(direct).toEqual([])
})

test("translated text in capitals uses the app language's rules (Turkish İ), never uppercase()", () => {
  const plain = kotlinSources()
    .flatMap(f => lf(readFileSync(f, "utf8")).split("\n").map((t, i) => ({ f, i, t })))
    .filter(({ t }) => /localized(?:Plural)?\([^)]*\)\)?\.uppercase\(\)/.test(t))
    .map(({ f, i, t }) => `${f}:${i + 1}  ${t.trim()}`)
  expect(plain).toEqual([])
})

test("an i18n-ok comment never swallows the code after it on its line", () => {
  // `{ Text("…") // i18n-ok: x },` comments out the closing `},` — put the marker on the line above instead.
  const swallowed = kotlinSources()
    .flatMap(f => lf(readFileSync(f, "utf8")).split("\n").map((t, i) => ({ f, i, t })))
    .filter(({ t }) => /\/\/\s*i18n-ok\b.*\s[})\]],?\s*$/.test(t) && /\{[^}]*\/\/\s*i18n-ok/.test(t))
    .map(({ f, i, t }) => `${f}:${i + 1}  ${t.trim()}`)
  expect(swallowed).toEqual([])
})

test("a Windows checkout (CRLF) reads the same: markers, multi-line titles and line numbers", () => {
  const unix = '    Text("Supermux") // i18n-ok: the brand\n    KitToggleRow(\n        "Show replies",\n    )\n    Text("Hello there")\n'
  const crlf = unix.replace(/\n/g, "\r\n")
  expect(scanText("apps/x/A.kt", crlf)).toEqual(scanText("apps/x/A.kt", unix))
  expect(scanText("apps/x/A.kt", crlf).map(h => h.line)).toEqual([3, 5])
})

test("paths use / on every OS, as the baseline's keys do (Bun's Glob answers with \\ on Windows)", () => {
  expect(repoPath("apps\\ui\\src\\commonMain\\kotlin\\A.kt")).toBe("apps/ui/src/commonMain/kotlin/A.kt")
  expect(kotlinSources().every(f => !f.includes("\\"))).toBe(true)
})

test("the ui module ships Turkish", () => {
  expect(catalogs().find(c => c.dir === "apps/ui/src/commonMain/composeResources/files/i18n")?.translations.tr).toBeDefined()
})

test("UI literals are found; tags, templates and marked lines are not", () => {
  const kt = (s: string) => scanText("apps/x/A.kt", s).length
  expect(kt('        Text("Hello there")')).toBe(1)
  expect(kt('    label = "Theme",')).toBe(1)
  expect(kt('    KitSectionHead("This app")')).toBe(1)
  expect(kt('    KitToggleRow(stringResource(Res.string.x),')).toBe(0)
  expect(kt('    Modifier.testTag("general_theme")')).toBe(0)
  expect(kt('    Text("${count}")')).toBe(0)
  expect(kt('    Text("$a · $b")')).toBe(0)
  expect(kt('    Text("Supermux") // i18n-ok: the brand')).toBe(0)
  expect(kt('    Text("Supermux") // i18n-ok')).toBe(1)
  expect(kt('    // Text("old")')).toBe(0)
})

test("a title on the next line and text picked by if/when are found too", () => {
  const kt = (s: string) => scanText("apps/x/A.kt", s).length
  expect(kt('    KitToggleRow(\n        "Show replies as they\'re written",\n')).toBe(1)
  expect(kt('    Text(\n        if (count == 1) "1 new message" else "$count new messages",')).toBe(2)
  expect(kt('        !offline -> "Connecting…"\n        inChat -> "Offline · messages send when you\'re back"')).toBe(2)
  // A wire value or an id picked by when is no text: one lowercase word.
  expect(kt('        LOW -> "low"\n        MEDIUM -> "medium"')).toBe(0)
  expect(kt('    /* Text("old") */ val x = 1')).toBe(0)
})

test("parity reports missing, extra and placeholder drift", () => {
  const en = parseCatalog('<resources><string name="a">A %1$s</string><string name="b">B</string><plurals name="n"><item quantity="one">%d x</item><item quantity="other">%d xs</item></plurals></resources>')
  const tr = parseCatalog('<resources><string name="a">A</string><string name="c">C</string><plurals name="n"><item quantity="other">%d x</item></plurals></resources>')
  expect(parityProblems(en, tr).sort()).toEqual(["missing: b", "not in English: c", "placeholders differ: a (%1$s vs )"])
})
