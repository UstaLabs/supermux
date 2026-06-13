// scripts/asset-probe.ts — runtime-assets materialization probe.
//
// Stage-1's CI proves the binary boots and serves its embedded PWA, but NOT
// that it can materialize an embedded asset OUT of /$bunfs/ for a spawned
// CHILD process — the runtime-assets path. That path is the exact class of the
// env.md ENAMETOOLONG landmine (a $bunfs source mishandled as a real file).
// This entry closes the gap: COMPILED, it forces the materializers to read
// from $bunfs and write real on-disk files, then asserts they are correct.
//
//   pty-helper      → must exist, be executable (mode & 0o111), be a non-empty
//                     ELF (magic \x7fELF), and its bytes must equal the
//                     embedded source (in compiled mode that proves the copy
//                     came out of $bunfs intact).
//   knowledge-curator.md → must exist with content length > 500.
//   environment.md  → must exist with content length > 1000 (this is THE asset
//                     whose mishandling caused the ENAMETOOLONG Claude-spawn
//                     failure; assert it materializes to a real file).
//
// IS_COMPILED true  → materializeAsset reads the /$bunfs/ virtual source (the
//                     real signal CI exercises).
// IS_COMPILED false → the helpers return repo paths directly (a weaker smoke,
//                     still valid). `bun scripts/asset-probe.ts` runs this form.
//
// On success: prints `ASSET PROBE OK` and exits 0.
// On any failure: prints the failure and exits 1. A mktemp stateDir is created
// and removed on every exit path (process.exit does NOT run finally blocks, so
// cleanup happens explicitly via a single done() before each exit).
import { existsSync, mkdtempSync, readFileSync, rmSync, statSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { IS_COMPILED } from "../src/shared/build-info"
import { curatorPromptPath, environmentMdPath, ptyHelperPath } from "../src/core/runtime-assets"

// The SAME embedded sources runtime-assets reads from. Imported with the same
// `type: "file"` attribute (NOT a different attribute — that would trip bun's
// specifier-dedup-ignores-attributes hazard documented in runtime-assets.ts);
// identical attribute is safe and resolves to the identical $bunfs path when
// compiled / repo path in source mode. Reading these back gives us the exact
// bytes the materializer should have produced.
import ptyHelperEmbedded from "../src/core/terminal/pty-helper" with { type: "file" }
import curatorEmbedded from "../prompts/knowledge-curator.md" with { type: "file" }
import environmentEmbedded from "../prompts/environment.md" with { type: "file" }

const ELF_MAGIC = Uint8Array.from([0x7f, 0x45, 0x4c, 0x46]) // \x7fELF

const stateDir = mkdtempSync(join(tmpdir(), "supermux-asset-probe-"))

// process.exit() does NOT unwind finally, so clean up explicitly then exit.
function done(code: number, msg?: string): never {
  try {
    rmSync(stateDir, { recursive: true, force: true })
  } catch {
    /* best-effort cleanup */
  }
  if (msg) {
    if (code === 0) console.log(msg)
    else console.error(msg)
  }
  process.exit(code)
}

function fail(msg: string): never {
  done(1, `ASSET PROBE FAIL: ${msg}`)
}

function bytesEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  for (let i = 0; i < a.length; i++) if (a[i] !== b[i]) return false
  return true
}

console.log(`asset-probe: compiled=${IS_COMPILED} stateDir=${stateDir}`)

// ── pty-helper ────────────────────────────────────────────────────────────
const ptyPath = ptyHelperPath(stateDir)
if (!existsSync(ptyPath)) fail(`pty-helper path does not exist: ${ptyPath}`)
const ptyStat = statSync(ptyPath)
if ((ptyStat.mode & 0o111) === 0) {
  fail(`pty-helper is not executable (mode ${ptyStat.mode.toString(8)}): ${ptyPath}`)
}
if (ptyStat.size <= 0) fail(`pty-helper is empty: ${ptyPath}`)
const ptyBytes = readFileSync(ptyPath)
if (ptyBytes.length < 4 || !bytesEqual(ptyBytes.subarray(0, 4), ELF_MAGIC)) {
  const head = Array.from(ptyBytes.subarray(0, 4))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join(" ")
  fail(`pty-helper is not an ELF (first bytes ${head}): ${ptyPath}`)
}
const ptySource = readFileSync(ptyHelperEmbedded)
if (!bytesEqual(ptyBytes, ptySource)) {
  fail(`pty-helper materialized bytes (${ptyBytes.length}) != embedded source bytes (${ptySource.length})`)
}
console.log(`asset-probe: pty-helper OK (${ptyBytes.length} bytes, exec, ELF, bytes == embedded)`)

// ── knowledge-curator.md ────────────────────────────────────────────────────
const curatorPath = curatorPromptPath(stateDir)
if (!existsSync(curatorPath)) fail(`curator prompt path does not exist: ${curatorPath}`)
const curatorBytes = readFileSync(curatorPath)
const curatorText = curatorBytes.toString("utf8")
if (curatorText.length <= 500) fail(`curator prompt too short (${curatorText.length} <= 500): ${curatorPath}`)
if (!bytesEqual(curatorBytes, readFileSync(curatorEmbedded))) {
  fail(`curator materialized bytes != embedded source bytes`)
}
console.log(`asset-probe: knowledge-curator.md OK (${curatorText.length} chars, bytes == embedded)`)

// ── environment.md (the ENAMETOOLONG landmine asset) ───────────────────────
const envPath = environmentMdPath(stateDir)
if (!existsSync(envPath)) fail(`environment.md path does not exist: ${envPath}`)
const envBytes = readFileSync(envPath)
const envText = envBytes.toString("utf8")
if (envText.length <= 1000) fail(`environment.md too short (${envText.length} <= 1000): ${envPath}`)
if (!bytesEqual(envBytes, readFileSync(environmentEmbedded))) {
  fail(`environment.md materialized bytes != embedded source bytes`)
}
console.log(`asset-probe: environment.md OK (${envText.length} chars, bytes == embedded)`)

done(0, "ASSET PROBE OK")
