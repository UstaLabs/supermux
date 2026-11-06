// The `windows` lane in .github/workflows/ci.yml runs the broker and core test suite on Windows through this file,
// against tests/windows-quarantine.txt: the files that fail on Windows today. That list may only shrink. The run
// fails when a file NOT on it fails (a new Windows regression), when a file on it now passes (take it off), and
// when it names a file the lane does not run. The files ci.yml's `windows-desktop` job runs are left to that job.
//
// Files run in chunks, each one `bun test --isolate` with a JUnit report, so one hung or crashing file cannot sink
// the rest. A file the report leaves out (it threw while loading), and every file of a chunk that failed without
// naming one, is re-run on its own to decide.
//
//   bun scripts/test-windows.ts [--quarantine <file>] [--chunk <n>] [--report <out.json>]
//   bun scripts/test-windows.ts --from-report <report.json> --write-quarantine <file>
import { Glob } from "bun"
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join, resolve } from "node:path"

export type QuarantineEntry = { file: string; reason: string; card: string; flaky: boolean; hangs: boolean }
export type FileStatus = "passed" | "failed" | "timeout" | "unreported"
export type FileResult = { file: string; status: FileStatus }
export type SuiteCounts = { tests: number; failures: number; errors: number }
export type Verdict = { newFailures: string[]; nowPassing: string[]; staleEntries: string[]; quarantinedFailing: string[] }

const ROOT = resolve(import.meta.dir, "..")
const TEST_ROOTS = ["src", "packages", "tests", "scripts"]
// bun test's own default names: *.test.*, *_test.*, *.spec.*, *_spec.* for .js/.jsx/.ts/.tsx.
const TEST_FILE = /[._](test|spec)\.(js|jsx|ts|tsx)$/
// A file that hangs on Windows (waiting on something POSIX) costs a whole chunk's timeout, then its own: keep both
// short. Files the quarantine marks "hangs" skip the chunks and run alone with a shorter one still.
const CHUNK_SIZE = 20
const CHUNK_TIMEOUT_MS = 8 * 60_000
const FILE_TIMEOUT_MS = 2 * 60_000
const HANG_TIMEOUT_MS = 90_000

const HEADER = `# Test files that fail on Windows today. scripts/test-windows.ts (the \`windows\` lane in ci.yml) runs them anyway:
# - a file NOT listed here that fails is a new Windows regression, and fails the lane;
# - a listed file that now passes fails the lane too: take it off. The list only shrinks.
# Format: <test file> | <why it fails on Windows> | <card>[ | flaky][ | hangs]
# "flaky" exempts a file that fails only sometimes from the "now passes" check; "hangs" runs a file that never
# finishes on Windows alone, with a short timeout, instead of letting it stall a chunk.
# Refresh from a CI run's report (the windows-test-report artifact):
#   bun scripts/test-windows.ts --from-report windows-test-report.json --write-quarantine tests/windows-quarantine.txt
`

/** One spelling for a test file everywhere: forward slashes, no leading "./". */
export function normalizeTestPath(path: string): string {
  return path.replace(/\\/g, "/").replace(/^\.\//, "")
}

const FLAGS = new Set(["flaky", "hangs"])

/** `<file> | <why> | <card>[ | flaky][ | hangs]` per line; `#` comments and blank lines are skipped. */
export function parseQuarantine(text: string): QuarantineEntry[] {
  const entries: QuarantineEntry[] = []
  const seen = new Set<string>()
  for (const [index, raw] of text.split(/\r?\n/).entries()) {
    const line = raw.trim()
    if (!line || line.startsWith("#")) continue
    const [file, reason, card, ...flags] = line.split("|").map(part => part.trim())
    const badFlags = flags.some(flag => !FLAGS.has(flag)) || new Set(flags).size !== flags.length
    if (!file || !reason || !card || badFlags) {
      throw new Error(`windows quarantine line ${index + 1}: want "<file> | <reason> | <card>[ | flaky][ | hangs]", got: ${raw}`)
    }
    const path = normalizeTestPath(file)
    if (seen.has(path)) throw new Error(`windows quarantine line ${index + 1}: ${path} is listed twice`)
    seen.add(path)
    entries.push({ file: path, reason, card, flaky: flags.includes("flaky"), hangs: flags.includes("hangs") })
  }
  return entries
}

export function formatQuarantine(entries: readonly QuarantineEntry[]): string {
  const lines = [...entries]
    .sort((a, b) => a.file.localeCompare(b.file))
    .map(e => [e.file, e.reason, e.card, ...(e.flaky ? ["flaky"] : []), ...(e.hangs ? ["hangs"] : [])].join(" | "))
  return `${HEADER}\n${lines.join("\n")}${lines.length ? "\n" : ""}`
}

function decodeXml(text: string): string {
  return text
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&")
}

/** Per-file counts from a bun JUnit report. */
export function parseJunit(xml: string): Map<string, SuiteCounts> {
  const suites = new Map<string, SuiteCounts>()
  for (const match of xml.matchAll(/<testsuite\b([^>]*)>/g)) {
    const attrs = match[1]!
    const attr = (name: string) => attrs.match(new RegExp(`\\b${name}="([^"]*)"`))?.[1]
    const file = attr("file") ?? attr("name")
    if (!file) continue
    const key = normalizeTestPath(decodeXml(file))
    const counts = { tests: Number(attr("tests") ?? 0), failures: Number(attr("failures") ?? 0), errors: Number(attr("errors") ?? 0) }
    // Bun nests a suite per describe block inside the file's own, every one named after the file: the outermost
    // holds the totals, and a failure in any of them must fail the file, whatever order they come in.
    const seen = suites.get(key)
    suites.set(key, seen
      ? { tests: Math.max(seen.tests, counts.tests), failures: Math.max(seen.failures, counts.failures), errors: Math.max(seen.errors, counts.errors) }
      : counts)
  }
  return suites
}

export function resultsForChunk(files: readonly string[], suites: Map<string, SuiteCounts>): FileResult[] {
  return files.map(file => {
    const counts = suites.get(normalizeTestPath(file))
    if (!counts) return { file, status: "unreported" }
    return { file, status: counts.failures + counts.errors > 0 ? "failed" : "passed" }
  })
}

export function verdict(results: readonly FileResult[], quarantine: readonly QuarantineEntry[], allFiles: readonly string[]): Verdict {
  const listed = new Map(quarantine.map(entry => [entry.file, entry]))
  const existing = new Set(allFiles.map(normalizeTestPath))
  const v: Verdict = { newFailures: [], nowPassing: [], staleEntries: [], quarantinedFailing: [] }
  for (const result of results) {
    const file = normalizeTestPath(result.file)
    const entry = listed.get(file)
    if (result.status !== "passed") (entry ? v.quarantinedFailing : v.newFailures).push(file)
    else if (entry && !entry.flaky) v.nowPassing.push(file)
  }
  for (const entry of quarantine) if (!existing.has(entry.file)) v.staleEntries.push(entry.file)
  return v
}

export function failed(v: Verdict): boolean {
  return v.newFailures.length + v.nowPassing.length + v.staleEntries.length > 0
}

export function chunk<T>(items: readonly T[], size: number): T[][] {
  if (!Number.isInteger(size) || size < 1) throw new Error(`chunk size must be a positive integer, got ${size}`)
  const out: T[][] = []
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size))
  return out
}

/** The files `bun test` would run: its default names, under the four roots, outside node_modules. */
export function discoverTestFiles(root: string, roots: readonly string[] = TEST_ROOTS): string[] {
  const files: string[] = []
  for (const dir of roots) {
    if (!existsSync(join(root, dir))) continue
    for (const path of new Glob("**/*").scanSync({ cwd: join(root, dir), onlyFiles: true })) {
      const file = normalizeTestPath(`${dir}/${path}`)
      if (file.split("/").includes("node_modules")) continue // platform-ok: a repo-relative path, already normalised to "/"
      if (TEST_FILE.test(file)) files.push(file)
    }
  }
  return files.sort()
}

/**
 * The test files ci.yml's `windows-desktop` job hands to `bun test` (the broker's Windows seams, the release job's own
 * list). This lane leaves them to it, so no file runs twice on Windows.
 */
export function filesRunByWindowsDesktop(ci: string): string[] {
  const text = ci.replace(/\r\n/g, "\n")
  const start = text.indexOf("\n  windows-desktop:\n")
  if (start < 0) return []
  const rest = text.slice(start + 1)
  const end = rest.search(/\n  [\w-]+:\n/)
  const job = end < 0 ? rest : rest.slice(0, end)
  const files = job.match(/[\w./-]+[._](?:test|spec)\.(?:js|jsx|ts|tsx)\b/g) ?? []
  return [...new Set(files.map(normalizeTestPath))].sort()
}

/** The quarantine after a run: still-failing files keep their reason, new ones get a dated one, fixed ones go. */
export function quarantineFromReport(results: readonly FileResult[], existing: readonly QuarantineEntry[], today: string): string {
  const known = new Map(existing.map(entry => [entry.file, entry]))
  const entries: QuarantineEntry[] = []
  for (const result of results) {
    const file = normalizeTestPath(result.file)
    const entry = known.get(file)
    if (result.status !== "passed") {
      const hangs = result.status === "timeout"
      const reason = `${hangs ? "hangs" : "fails"} on Windows (first seen ${today})`
      entries.push(entry ?? { file, reason, card: "#29", flaky: false, hangs })
    } else if (entry?.flaky) {
      entries.push(entry)
    }
  }
  return formatQuarantine(entries)
}

/** Quarantined files marked "hangs" run alone, outside the chunks. */
export function splitRun(files: readonly string[], quarantine: readonly QuarantineEntry[]): { chunked: string[]; alone: string[] } {
  const hanging = new Set(quarantine.filter(entry => entry.hangs).map(entry => entry.file))
  return {
    chunked: files.filter(file => !hanging.has(normalizeTestPath(file))),
    alone: files.filter(file => hanging.has(normalizeTestPath(file))),
  }
}

function runBunTest(files: readonly string[], junit: string | undefined, timeoutMs: number): { code: number; timedOut: boolean } {
  const args = ["test", "--isolate"]
  if (junit) args.push("--reporter=junit", `--reporter-outfile=${junit}`)
  args.push(...files.map(file => `./${file}`))
  // env passed explicitly: without it Bun uses the environment it STARTED with (oven-sh/bun#40846).
  const run = Bun.spawnSync([process.execPath, ...args], {
    cwd: ROOT,
    env: process.env,
    stdout: "inherit",
    stderr: "inherit",
    timeout: timeoutMs,
  })
  // Killed by the timeout: no exit code, a signal instead.
  return { code: run.exitCode ?? 1, timedOut: run.exitCode === null }
}

function runAlone(file: string, timeoutMs: number): FileStatus {
  const run = runBunTest([file], undefined, timeoutMs)
  return run.timedOut ? "timeout" : run.code === 0 ? "passed" : "failed"
}

function summary(v: Verdict, results: readonly FileResult[], quarantine: readonly QuarantineEntry[]): string {
  const passed = results.filter(r => r.status === "passed").length
  const lines = [
    "",
    `Windows test files: ${results.length} run, ${passed} passed, ${results.length - passed} failed ` +
      `(${v.quarantinedFailing.length} of them quarantined; the list has ${quarantine.length}).`,
  ]
  const section = (title: string, files: string[]) => {
    if (files.length) lines.push("", title, ...files.map(file => `  ${file}`))
  }
  section("NEW failures, not in tests/windows-quarantine.txt (fix them, or quarantine them with a reason):", v.newFailures)
  section("Quarantined files that now PASS (take them off the list):", v.nowPassing)
  section("Quarantine entries for files this lane does not run, gone or left to windows-desktop (remove them):", v.staleEntries)
  return lines.join("\n")
}

function parseArgs(argv: readonly string[]) {
  const opt = {
    quarantine: join(ROOT, "tests", "windows-quarantine.txt"),
    chunk: CHUNK_SIZE,
    report: "",
    fromReport: "",
    writeQuarantine: "",
  }
  for (let i = 0; i < argv.length; i += 2) {
    const flag = argv[i]!
    const value = argv[i + 1]
    if (value === undefined) throw new Error(`${flag} needs a value`)
    if (flag === "--quarantine") opt.quarantine = value
    else if (flag === "--chunk") opt.chunk = Number(value)
    else if (flag === "--report") opt.report = value
    else if (flag === "--from-report") opt.fromReport = value
    else if (flag === "--write-quarantine") opt.writeQuarantine = value
    else throw new Error(`unknown flag ${flag}`)
  }
  return opt
}

async function main(argv: readonly string[]): Promise<number> {
  const opt = parseArgs(argv)
  if (opt.fromReport) {
    if (!opt.writeQuarantine) throw new Error("--from-report needs --write-quarantine <file>")
    const report = JSON.parse(readFileSync(opt.fromReport, "utf8")) as { results: FileResult[] }
    const existing = existsSync(opt.writeQuarantine) ? parseQuarantine(readFileSync(opt.writeQuarantine, "utf8")) : []
    writeFileSync(opt.writeQuarantine, quarantineFromReport(report.results, existing, new Date().toISOString().slice(0, 10)))
    console.log(`wrote ${opt.writeQuarantine}`)
    return 0
  }
  const ci = join(ROOT, ".github", "workflows", "ci.yml")
  const elsewhere = new Set(existsSync(ci) ? filesRunByWindowsDesktop(readFileSync(ci, "utf8")) : [])
  if (elsewhere.size) console.log(`left to the windows-desktop job: ${[...elsewhere].join(", ")}`)
  const files = discoverTestFiles(ROOT).filter(file => !elsewhere.has(file))
  const quarantine = existsSync(opt.quarantine) ? parseQuarantine(readFileSync(opt.quarantine, "utf8")) : []
  const { chunked, alone } = splitRun(files, quarantine)
  const work = mkdtempSync(join(tmpdir(), "mux-win-tests-"))
  const results: FileResult[] = []
  // Written after every chunk, so a run the job's own timeout cancels still leaves what it found.
  const report = () => {
    if (opt.report) writeFileSync(opt.report, `${JSON.stringify({ results, verdict: verdict(results, quarantine, files) }, null, 2)}\n`)
  }
  const started = Date.now()
  const elapsed = () => `${Math.round((Date.now() - started) / 1000)}s`
  try {
    for (const file of alone) {
      console.log(`\n── ${file}: quarantined as hanging, run alone (${elapsed()}) ──`)
      results.push({ file, status: runAlone(file, HANG_TIMEOUT_MS) })
    }
    report()
    for (const [index, group] of chunk(chunked, opt.chunk).entries()) {
      console.log(`\n── chunk ${index + 1}: ${group.length} files (${elapsed()}) ──`)
      const junit = join(work, `chunk-${index}.xml`)
      const run = runBunTest(group, junit, CHUNK_TIMEOUT_MS)
      const chunkResults = resultsForChunk(group, existsSync(junit) ? parseJunit(readFileSync(junit, "utf8")) : new Map())
      // A non-zero exit that no file accounts for (an error between tests, a crash at teardown): find the file.
      const unexplained = run.code !== 0 && chunkResults.every(r => r.status === "passed")
      for (const result of chunkResults) {
        if (result.status !== "unreported" && !unexplained) continue
        console.log(`\n── re-running ${result.file} on its own (${elapsed()}) ──`)
        result.status = runAlone(result.file, FILE_TIMEOUT_MS)
      }
      results.push(...chunkResults)
      report()
    }
  } finally {
    rmSync(work, { recursive: true, force: true })
  }
  const v = verdict(results, quarantine, files)
  report()
  console.log(summary(v, results, quarantine))
  return failed(v) ? 1 : 0
}

if (import.meta.main) process.exit(await main(process.argv.slice(2)))
