import { afterAll, expect, test } from "bun:test"
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

// The CodeMirror bundle shipped to the Android WebView editor is a COMMITTED
// build artifact (`apps/android/src/main/assets/editor/cm6.js`). Its sources and
// exact dependency versions live in `apps/android/codemirror/`
// (`cm6-entry.mjs` + `package.json` + `bun.lock`). This test proves the two have
// not drifted apart: rebuild the entry with the same flags as the package's
// `build` script and demand byte-for-byte equality.
//
// It needs the bundle's dependencies on disk, which are NOT vendored, so it
// skips itself (loudly) when they are missing.

const ROOT = join(import.meta.dir, "..")
const PKG_DIR = join(ROOT, "apps/android/codemirror")
const ENTRY = join(PKG_DIR, "cm6-entry.mjs")
const COMMITTED = join(ROOT, "apps/android/src/main/assets/editor/cm6.js")

const hasDeps = existsSync(join(PKG_DIR, "node_modules/@codemirror/view/package.json"))
const maybeTest = hasDeps ? test : test.skip

const tmp = mkdtempSync(join(tmpdir(), "cm6-drift-"))
afterAll(() => rmSync(tmp, { recursive: true, force: true }))

maybeTest(
  hasDeps
    ? "committed cm6.js is exactly what apps/android/codemirror rebuilds"
    : "committed cm6.js drift check (SKIPPED: run `cd apps/android/codemirror && bun install`)",
  async () => {
    const outfile = join(tmp, "cm6.js")
    // Same flags as apps/android/codemirror/package.json's "build" script.
    // cwd is the package dir so module resolution uses ITS node_modules (and
    // never falls through to the repo-root one, which would silently pull in
    // different versions).
    const built = await Bun.build({
      entrypoints: [ENTRY],
      target: "browser",
      format: "iife",
      minify: true,
      root: PKG_DIR,
    })
    expect(built.success).toBe(true)
    expect(built.outputs.length).toBe(1)
    await Bun.write(outfile, await built.outputs[0]!.arrayBuffer())

    const fresh = readFileSync(outfile)
    const committed = readFileSync(COMMITTED)
    if (!fresh.equals(committed)) {
      throw new Error(
        `apps/android/src/main/assets/editor/cm6.js is stale or was built from different deps.\n` +
          `  committed: ${committed.length} bytes\n` +
          `  rebuilt:   ${fresh.length} bytes\n` +
          `Fix: cd apps/android/codemirror && bun install --frozen-lockfile && bun run build, then commit the bundle.`,
      )
    }
    expect(fresh.length).toBe(committed.length)
  },
  60_000,
)
