// Fixture for tests/integration/zmx-workspace.test.ts.
//
// Two things live here rather than in the test file: the PREREQUISITE GATE
// (which decides whether the suite can run at all, and must be answerable
// before `describe` is called) and the REFERENCE CLIENT ENGINE — the real
// terminal-core wasm module the supermux clients render with, driven from Bun.
//
// THE REFERENCE ENGINE ROUTE. `apps/terminal-core` is Kotlin Multiplatform, and
// the same VT engine ships three ways: a Kotlin/Native library, a JVM one, and
// `supermux-terminal.wasm` with the `st_*` C ABI. The cheapest reliable route
// from a Bun test is the wasm one, and it is the SAME binary the web client
// loads — so a restore that decodes here is a restore the browser can draw.
// `terminal-loader.mjs`'s own `loadRuntime()` is not used: it fetches over
// http(s) by design (browsers cannot compile a file:// URL), so the module is
// compiled from disk and handed to the loader's exported `TerminalRuntime`,
// which is the whole st_* binding and the part under test.
//
// The codec decoded below is documented in apps/terminal-core/native/README.md
// ("Codec"); it is decoded here rather than imported because the only
// TypeScript decoder in this repo is the Kotlin one compiled to Wasm, which
// would mean starting a second runtime to read the first one's output.
import { accessSync, constants, existsSync, readFileSync } from "fs"
import { join } from "path"
import { helperBinaries, verifyHelperManifest, type HelperBinaries } from "../../src/core/terminal/zmx/helper"

export const REPO_ROOT = join(import.meta.dirname, "..", "..")

/** The wasm the browser client loads, built by apps/terminal-core/native/build.sh. */
export const WASM_PATH = join(REPO_ROOT, "apps", "terminal-core", "build", "wasm", "supermux-terminal.wasm")

/**
 * CI sets this. It turns "I cannot run this here" from a skip into a failure.
 *
 * A native artifact that is missing on a laptop is an unmet prerequisite and
 * says nothing about the code. The same artifact missing in CI means the build
 * step did not run, and a suite that skips itself there is a suite that has
 * stopped testing anything — silently, and exactly when it matters.
 */
export const REQUIRED = process.env.MUX_ZMX_INTEGRATION === "1"

export type Prerequisites =
  | { ok: true; binaries: HelperBinaries; shellSource: string }
  | { ok: false; reason: string }

/** Everything native this suite needs, checked before a single target exists. */
export function prerequisites(): Prerequisites {
  if (process.platform === "win32") {
    return { ok: false, reason: "zmx is the POSIX backend; Windows runs sessiond (sessiond-term.test.ts)" }
  }
  const binaries = helperBinaries(process.env.MUX_ZMX_BIN_DIR?.trim() || join(REPO_ROOT, "build", "zmx", "out"))
  if (!existsSync(binaries.helper) || !existsSync(binaries.zmx)) {
    return { ok: false, reason: `zmx binaries are missing at ${binaries.dir} (run scripts/build-zmx.sh)` }
  }
  try {
    verifyHelperManifest(binaries)
  } catch (error) {
    return { ok: false, reason: `zmx manifest does not describe the binaries: ${errorText(error)}` }
  }
  if (!existsSync(WASM_PATH)) {
    return { ok: false, reason: `the reference terminal engine is missing at ${WASM_PATH} (run apps/terminal-core/native/build.sh)` }
  }
  const shellSource = "/bin/bash"
  try {
    accessSync(shellSource, constants.X_OK)
  } catch {
    return { ok: false, reason: `${shellSource} is not executable; the suite runs a real shell` }
  }
  const runtimeDir = process.env.XDG_RUNTIME_DIR?.trim()
  if (!runtimeDir || !existsSync(runtimeDir)) {
    // Not a nicety: `sockaddr_un.sun_path` is 103 usable bytes and a socket
    // basename is 22 of them, so the socket directory has to be SHORT. A
    // repo-relative scratch path under a worktree is already past the budget.
    return { ok: false, reason: "XDG_RUNTIME_DIR is unset; a private socket dir would not fit in sun_path" }
  }
  return { ok: true, binaries, shellSource }
}

export function errorText(error: unknown): string {
  return error instanceof Error ? error.message : String(error)
}

// ---------------------------------------------------------------- engine ----

const ST_MAGIC = 0x53545654
const ST_ABI = 2

export type ViewportCell = {
  text: string
  width: number
  fg: bigint
  bg: bigint
  flags: number
  underline: number
}

export type ViewportRow = { index: number; cells: ViewportCell[]; text: string }

export type Viewport = {
  generation: number
  columns: number
  rows: number
  grid: ViewportRow[]
  cursorColumn: number
  cursorRow: number
  cursorShape: number
  cursorVisible: boolean
  alternateScreen: boolean
  mouseTracking: boolean
  bracketedPaste: boolean
  alternateScroll: boolean
  historyRows: number
  viewportTop: number
  full: boolean
  held: boolean
}

/** BOLD, from TerminalConstants.kt (`1 shl 0`). */
export const CELL_BOLD = 1 << 0

const decoder = new TextDecoder()

/** Decode a kind-1 (viewport) envelope. Strict: trailing bytes are a failure. */
export function decodeViewport(bytes: Uint8Array): Viewport {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (view.getUint32(0, true) !== ST_MAGIC) throw new Error("viewport envelope has the wrong magic")
  if (view.getUint16(4, true) !== ST_ABI) throw new Error(`viewport envelope abi ${view.getUint16(4, true)}`)
  if (view.getUint16(6, true) !== 1) throw new Error("not a viewport envelope")
  let at = 12
  const i32 = () => { const value = view.getInt32(at, true); at += 4; return value }
  const u32 = () => { const value = view.getUint32(at, true); at += 4; return value }
  const i64 = () => { const value = view.getBigInt64(at, true); at += 8; return Number(value) }
  const u64 = () => { const value = view.getBigUint64(at, true); at += 8; return value }
  const bool = () => { const value = bytes[at]; at += 1; return value === 1 }
  const str = () => { const n = u32(); const s = decoder.decode(bytes.subarray(at, at + n)); at += n; return s }

  const generation = i64()
  const columns = i32(), rows = i32()
  i32(); i32() // cell width/height in px: not part of what a restore has to carry
  const rowCount = u32()
  const grid: ViewportRow[] = []
  for (let r = 0; r < rowCount; r++) {
    const index = i32()
    const cellCount = u32()
    const cells: ViewportCell[] = []
    for (let c = 0; c < cellCount; c++) {
      cells.push({ text: str(), width: i32(), fg: u64(), bg: u64(), flags: i32(), underline: i32() })
    }
    // Width-0 cells are the tail of a wide grapheme, not a character of their own.
    const text = cells.filter(cell => cell.width !== 0)
      .map(cell => cell.text === "" ? " " : cell.text).join("").replace(/\s+$/, "")
    grid.push({ index, cells, text })
  }
  const cursorColumn = i32(), cursorRow = i32(), cursorShape = i32(), cursorVisible = bool()
  const alternateScreen = bool(), mouseTracking = bool(), bracketedPaste = bool(), alternateScroll = bool()
  const historyRows = i64(), viewportTop = i64(), full = bool()
  const linkCount = u32()
  for (let i = 0; i < linkCount; i++) { i32(); i32(); i32(); str() }
  if (bool()) { i64(); i32(); i64(); i32() } // selection
  const held = bool()
  if (at !== bytes.length) throw new Error(`viewport envelope has ${bytes.length - at} trailing bytes`)
  return {
    generation, columns, rows, grid, cursorColumn, cursorRow, cursorShape, cursorVisible,
    alternateScreen, mouseTracking, bracketedPaste, alternateScroll, historyRows, viewportTop, full, held,
  }
}

/** Response effects (tag 1) of a kind-2 envelope: what this engine would type
 * back at the pty if anybody let it. */
export function decodeResponses(bytes: Uint8Array): string[] {
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  if (view.getUint16(6, true) !== 2) throw new Error("not an effects envelope")
  const count = view.getUint32(12, true)
  let at = 16
  const out: string[] = []
  for (let i = 0; i < count; i++) {
    const tag = bytes[at]!
    at += 1
    if (tag === 1 || tag === 2 || tag === 3) {
      const n = view.getUint32(at, true)
      const text = decoder.decode(bytes.subarray(at + 4, at + 4 + n))
      at += 4 + n
      if (tag === 1) out.push(text)
    } else if (tag === 4) {
      // bell
    } else if (tag === 5) {
      at += 1
      const hasText = bytes[at] === 1
      at += 1
      if (hasText) at += 4 + view.getUint32(at, true)
    } else {
      throw new Error(`unknown effect tag ${tag}`)
    }
  }
  return out
}

export interface ReferenceEngine {
  /** Feed bytes as a REPLAY (origin 1) or as live output (origin 0). */
  feed(bytes: Uint8Array, origin?: number): void
  viewport(): Viewport
  /** Scroll to an absolute row, so history can be read back. */
  scrollTo(row: number): void
  /** Responses this engine produced since the last drain. */
  responses(): string[]
  destroy(): void
}

/**
 * One terminal in the real client engine.
 *
 * `loadRuntime()` from the loader is deliberately bypassed (it fetches http(s)
 * URLs); everything after instantiation is the loader's own `TerminalRuntime`,
 * which is the binding the clients use.
 */
export async function referenceEngine(columns: number, rows: number): Promise<ReferenceEngine> {
  const { TerminalRuntime } = await import(join(REPO_ROOT, "apps", "terminal-core", "wasm", "terminal-loader.mjs"))
  const instance = await WebAssembly.instantiate(await WebAssembly.compile(readFileSync(WASM_PATH)), {})
  const runtime = new TerminalRuntime(instance)
  const { status, handle } = runtime.create(columns, rows, 8, 16, 4000, 8 << 20)
  if (status !== 0) throw new Error(`st_create -> ${status}`)
  const check = (what: string, result: number) => {
    if (result !== 0) throw new Error(`${what} -> ${result}`)
  }
  const read = (what: string, result: { status: number; bytes: Uint8Array | null }) => {
    if (result.status !== 0 || !result.bytes) throw new Error(`${what} -> ${result.status}`)
    return result.bytes
  }
  return {
    feed: (bytes, origin = 1) => check("st_feed", runtime.feed(handle, bytes, origin)),
    viewport: () => decodeViewport(read("st_read_viewport", runtime.readViewport(handle, 1))),
    scrollTo: row => check("st_scroll_to", runtime.scrollTo(handle, row)),
    responses: () => decodeResponses(read("st_drain_effects", runtime.drainEffects(handle))),
    destroy: () => { runtime.destroy(handle) },
  }
}
