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
//   zmx bundle      → POSIX only: the daemon + framed helper + manifest must
//                     materialize as ONE directory, pass the broker's own
//                     `verifyHelperManifest` (which re-hashes both binaries
//                     against the manifest beside them), and the helper must
//                     actually EXEC and report our ABI. This is the check that
//                     would have caught the gap Plan 3 left behind: nothing
//                     materialized the bundle, so a compiled release failed
//                     every workspace terminal with `backend-unavailable`.
//                     `MUX_ASSET_PROBE_ALLOW_NO_ZMX=1` downgrades it to a
//                     warning, for a deliberate SUPERMUX_SKIP_ZMX=1 build.
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
import { curatorPromptPath, environmentMdPath, ptyHelperPath, zmxBundleDir } from "../src/core/runtime-assets"
import { HELPER_ABI, helperBinaries, verifyHelperManifest } from "../src/core/terminal/zmx/helper"

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

// ── the zmx bundle (POSIX) ─────────────────────────────────────────────────
// Only meaningful compiled: in source mode zmxBinDir() points at the repo's own
// build/zmx/out and nothing is copied, which proves nothing about packaging.
if (process.platform === "win32") {
  console.log("asset-probe: zmx bundle skipped (Windows workspace terminals use sessiond)")
} else if (!IS_COMPILED) {
  console.log("asset-probe: zmx bundle skipped (source mode reads build/zmx/out directly)")
} else {
  const allowMissing = process.env.MUX_ASSET_PROBE_ALLOW_NO_ZMX === "1"
  const dir = zmxBundleDir(stateDir)
  const bundle = helperBinaries(dir)
  let manifest
  try {
    // The broker's OWN gate, not a re-implementation of it: it re-hashes both
    // binaries against the manifest that landed beside them, so a truncated or
    // half-materialized copy fails here exactly as it would at attach time.
    manifest = verifyHelperManifest(bundle)
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error)
    if (!allowMissing) fail(`zmx bundle did not materialize usably: ${message}`)
    console.log(`asset-probe: zmx bundle ABSENT, allowed by MUX_ASSET_PROBE_ALLOW_NO_ZMX=1 (${message})`)
    done(0, "ASSET PROBE OK")
  }
  // Materialized, intact, and OURS — now prove the kernel agrees it is a
  // program. A copy that is byte-correct but lost its exec bit passes every
  // hash check and then fails at the one moment that matters.
  const probe = Bun.spawnSync({ cmd: [bundle.helper, "--version-json"], stdout: "pipe", stderr: "pipe" })
  if (!probe.success) {
    fail(`materialized helper ${bundle.helper} did not run: exit ${probe.exitCode} ${probe.stderr.toString().trim()}`)
  }
  let reportedAbi: unknown
  try {
    reportedAbi = (JSON.parse(probe.stdout.toString()) as { abi?: unknown }).abi
  } catch (error) {
    fail(`materialized helper --version-json was not JSON: ${probe.stdout.toString().slice(0, 200)}`)
  }
  if (Number(reportedAbi) !== HELPER_ABI) {
    fail(`materialized helper reports ABI ${String(reportedAbi)}, this build speaks ${HELPER_ABI}`)
  }
  const zmxStat = statSync(bundle.zmx)
  if ((zmxStat.mode & 0o111) === 0) fail(`materialized zmx is not executable: ${bundle.zmx}`)
  console.log(
    `asset-probe: zmx bundle OK (zmx ${manifest.zmx.commit.slice(0, 12)}, ${zmxStat.size} bytes, ` +
      `helper ABI ${HELPER_ABI} confirmed by the running binary, target ${manifest.target})`,
  )
}

done(0, "ASSET PROBE OK")
