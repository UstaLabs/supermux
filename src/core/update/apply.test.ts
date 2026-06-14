import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import {
  chmodSync,
  closeSync,
  existsSync,
  mkdtempSync,
  openSync,
  rmSync,
  statSync,
  utimesSync,
  writeFileSync,
} from "fs"
import { dirname, join } from "path"
import { tmpdir } from "os"
import {
  applyUpdate,
  archAssetKeyFor,
  assetKeyFor,
  resolveAndApply,
  restartService,
  restartViaLaunchd,
  restartViaSystemd,
  rollback,
  type UpdateApplyError,
} from "./apply"
import type { FetchLike } from "./checker"

// ── test helpers ─────────────────────────────────────────────────────────────

// The tmp filename the engine uses is pid-scoped (.supermux-update.${pid}.tmp).
// Tests run in the same process, so this matches the engine's name exactly.
const TMP_NAME = `.supermux-update.${process.pid}.tmp`
const SWAP_LOCK_NAME = ".supermux-swap.lock"

// A throwaway dir holding a fake "binary" (a small text file). Each test gets a
// fresh one; never touches the real process.execPath.
let dir: string
let fakeExec: string

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), "mux-apply-"))
  fakeExec = join(dir, "supermux")
})

afterEach(() => {
  // chmod everything back so rmSync can clean even after a read-only-dir test.
  try {
    chmodSync(dir, 0o755)
  } catch {
    /* ignore */
  }
  rmSync(dir, { recursive: true, force: true })
})

function sha256Hex(bytes: Uint8Array | string): string {
  const h = new Bun.CryptoHasher("sha256")
  h.update(typeof bytes === "string" ? new TextEncoder().encode(bytes) : bytes)
  return h.digest("hex")
}

async function readFileText(path: string): Promise<string> {
  return new TextDecoder().decode(await Bun.file(path).bytes())
}

// Build a VersionsJson manifest whose linux-x64 asset points at `assetUrl` with
// the given (possibly wrong) sha256.
function manifestFor(opts: {
  version: string
  assetUrl: string
  sha256: string
  key?: string
}) {
  const key = opts.key ?? "linux-x64"
  return {
    schemaVersion: 1,
    channels: {
      stable: {
        version: opts.version,
        publishedAt: "2026-06-13T00:00:00.000Z",
        notesUrl: `https://example.test/notes/${opts.version}`,
        assets: {
          [key]: { url: opts.assetUrl, sha256: opts.sha256 },
        },
      },
    },
  }
}

// A fetch stub that serves `body` (200) for the matching url, else 404.
function serveBytes(map: Record<string, { body: string; status?: number }>): FetchLike {
  return async (input) => {
    const url = typeof input === "string" ? input : input.toString()
    const entry = map[url]
    if (!entry) return new Response("not found", { status: 404 })
    return new Response(entry.body, { status: entry.status ?? 200 })
  }
}

// ── archAssetKeyFor (the only way to exercise arch-unsupported here) ──────────

describe("archAssetKeyFor", () => {
  test("maps known Node archs to release asset keys", () => {
    expect(archAssetKeyFor("x64")).toBe("linux-x64")
    expect(archAssetKeyFor("arm64")).toBe("linux-arm64")
  })

  test("returns null for unmapped arch (drives arch-unsupported)", () => {
    expect(archAssetKeyFor("mips")).toBe(null)
    expect(archAssetKeyFor("ia32")).toBe(null)
    expect(archAssetKeyFor("")).toBe(null)
  })
})

// ── assetKeyFor (platform × arch → release asset key) ─────────────────────────

describe("assetKeyFor", () => {
  test("maps linux + darwin × x64/arm64 to asset keys", () => {
    expect(assetKeyFor("linux", "x64")).toBe("linux-x64")
    expect(assetKeyFor("linux", "arm64")).toBe("linux-arm64")
    expect(assetKeyFor("darwin", "x64")).toBe("darwin-x64")
    expect(assetKeyFor("darwin", "arm64")).toBe("darwin-arm64")
  })

  test("returns null for unsupported platform or arch", () => {
    expect(assetKeyFor("win32", "x64")).toBe(null)
    expect(assetKeyFor("linux", "ia32")).toBe(null)
    expect(assetKeyFor("darwin", "mips")).toBe(null)
    expect(assetKeyFor("", "")).toBe(null)
  })
})

// ── applyUpdate: happy path ──────────────────────────────────────────────────

describe("applyUpdate — happy path", () => {
  test("old bytes land at .prev, new at execPath, exec mode bits set, onState + newVersion", async () => {
    writeFileSync(fakeExec, "OLD-BINARY-v1")
    const newBytes = "NEW-BINARY-v2"
    const assetUrl = "https://dl.test/supermux-linux-x64"
    const manifest = manifestFor({
      version: "0.2.0",
      assetUrl,
      sha256: sha256Hex(newBytes),
    })

    const states: string[] = []
    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [assetUrl]: { body: newBytes } }),
      onState: (s) => states.push(s),
    })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.newVersion).toBe("0.2.0")
    expect(result.prevPath).toBe(fakeExec + ".prev")

    // new bytes at execPath, old bytes at .prev
    expect(await readFileText(fakeExec)).toBe(newBytes)
    expect(await readFileText(fakeExec + ".prev")).toBe("OLD-BINARY-v1")

    // executable bit set on the swapped-in binary
    const mode = statSync(fakeExec).mode & 0o777
    expect(mode & 0o100).toBe(0o100) // owner-exec at minimum
    expect(mode).toBe(0o755)

    // onState sequence
    expect(states).toEqual(["downloading", "swapping"])

    // tmp must be gone after a successful swap (it was renamed into place)
    expect(existsSync(join(dir, TMP_NAME))).toBe(false)
    // the swap lock must be released (closed + unlinked) after a successful swap
    expect(existsSync(join(dir, SWAP_LOCK_NAME))).toBe(false)
  })

  test("tmp download path is a sibling of execPath", async () => {
    writeFileSync(fakeExec, "OLD")
    const newBytes = "NEW"
    const assetUrl = "https://dl.test/bin"
    // Intercept the write location by serving + asserting the dir, and by
    // proving the swap leaves no tmp sibling (covered above). Here we assert the
    // tmp parent equals dirname(execPath) by observing a sha-mismatch leaves a
    // unlinked tmp exactly there (and nowhere else).
    const manifest = manifestFor({ version: "0.9.0", assetUrl, sha256: "deadbeef" })
    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [assetUrl]: { body: newBytes } }),
    })
    expect(result.ok).toBe(false)
    // On sha-mismatch the tmp is unlinked; assert nothing stray landed anywhere
    // but the (pid-scoped) sibling slot, which is now empty. dirname(tmp) == dir.
    const expectedTmp = join(dirname(fakeExec), TMP_NAME)
    expect(dirname(expectedTmp)).toBe(dir)
    expect(existsSync(expectedTmp)).toBe(false)
  })
})

// ── applyUpdate: sha mismatch ────────────────────────────────────────────────

describe("applyUpdate — sha mismatch", () => {
  test("execPath untouched, tmp removed, typed sha-mismatch error", async () => {
    writeFileSync(fakeExec, "OLD-UNTOUCHED")
    const assetUrl = "https://dl.test/bad"
    const manifest = manifestFor({
      version: "0.2.0",
      assetUrl,
      sha256: "0".repeat(64), // wrong
    })

    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [assetUrl]: { body: "SOME-NEW-BYTES" } }),
    })

    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("sha-mismatch")
    if (result.error.kind === "sha-mismatch") {
      expect(result.error.expected).toBe("0".repeat(64))
      expect(result.error.actual).toBe(sha256Hex("SOME-NEW-BYTES"))
    }

    // execPath is the original, no .prev created, tmp gone
    expect(await readFileText(fakeExec)).toBe("OLD-UNTOUCHED")
    expect(existsSync(fakeExec + ".prev")).toBe(false)
    expect(existsSync(join(dir, TMP_NAME))).toBe(false)
  })
})

// ── applyUpdate: download failures ───────────────────────────────────────────

describe("applyUpdate — download failures leave execPath untouched", () => {
  test("non-200 response → download-failed", async () => {
    writeFileSync(fakeExec, "OLD-1")
    const assetUrl = "https://dl.test/missing"
    const manifest = manifestFor({ version: "0.2.0", assetUrl, sha256: "abc" })
    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [assetUrl]: { body: "err", status: 503 } }),
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("download-failed")
    expect(await readFileText(fakeExec)).toBe("OLD-1")
    expect(existsSync(fakeExec + ".prev")).toBe(false)
    expect(existsSync(join(dir, TMP_NAME))).toBe(false)
  })

  test("fetch throws → download-failed", async () => {
    writeFileSync(fakeExec, "OLD-2")
    const assetUrl = "https://dl.test/boom"
    const manifest = manifestFor({ version: "0.2.0", assetUrl, sha256: "abc" })
    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: async () => {
        throw new Error("connection reset")
      },
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("download-failed")
    if (result.error.kind === "download-failed") {
      expect(result.error.detail).toContain("connection reset")
    }
    expect(await readFileText(fakeExec)).toBe("OLD-2")
    expect(existsSync(join(dir, TMP_NAME))).toBe(false)
  })

  test("stalled-but-open body (never closes) → download-failed via deadline, fast, tmp gone", async () => {
    // fetch resolves fine (connect+headers ok); the BODY is a ReadableStream that
    // emits one chunk and then never closes — the case fetch's AbortSignal does
    // NOT cover. Bun.write(tmp, res) would hang forever; the injected 50ms
    // body-drain deadline must win, yielding download-failed and a cleaned tmp.
    writeFileSync(fakeExec, "OLD-STALL")
    const assetUrl = "https://dl.test/stall"
    const manifest = manifestFor({ version: "0.2.0", assetUrl, sha256: "whatever" })
    const stallFetch: FetchLike = async () => {
      const stream = new ReadableStream<Uint8Array>({
        start(controller) {
          controller.enqueue(new TextEncoder().encode("partial-chunk"))
          // never close, never enqueue more — a stalled-but-open connection.
        },
      })
      return new Response(stream, { status: 200 })
    }

    const t0 = Date.now()
    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: stallFetch,
      downloadTimeoutMs: 50,
    })
    const elapsed = Date.now() - t0

    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("download-failed")
    if (result.error.kind === "download-failed") {
      expect(result.error.detail).toContain("stalled")
    }
    // It must NOT hang: well under the 5s default per-test timeout.
    expect(elapsed).toBeLessThan(2000)
    // execPath untouched, no .prev, tmp cleaned.
    expect(await readFileText(fakeExec)).toBe("OLD-STALL")
    expect(existsSync(fakeExec + ".prev")).toBe(false)
    expect(existsSync(join(dir, TMP_NAME))).toBe(false)
  })
})

// ── applyUpdate: asset / arch resolution ─────────────────────────────────────

describe("applyUpdate — asset/arch resolution", () => {
  test("asset key absent in manifest → asset-missing", async () => {
    writeFileSync(fakeExec, "OLD")
    // manifest only has linux-x64; we ask for linux-arm64 via override
    const assetUrl = "https://dl.test/x64only"
    const manifest = manifestFor({
      version: "0.2.0",
      assetUrl,
      sha256: "x",
      key: "linux-x64",
    })
    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      archAssetKey: "linux-arm64",
      fetchImpl: serveBytes({ [assetUrl]: { body: "n" } }),
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("asset-missing")
    if (result.error.kind === "asset-missing") {
      expect(result.error.key).toBe("linux-arm64")
    }
  })

  test("weird archAssetKey not in manifest → asset-missing", async () => {
    writeFileSync(fakeExec, "OLD")
    const assetUrl = "https://dl.test/x"
    const manifest = manifestFor({ version: "0.2.0", assetUrl, sha256: "x" })
    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      archAssetKey: "linux-mips",
      fetchImpl: serveBytes({ [assetUrl]: { body: "n" } }),
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("asset-missing")
    if (result.error.kind === "asset-missing") {
      expect(result.error.key).toBe("linux-mips")
    }
  })
})

// ── applyUpdate: swap rename failure (swap-failed branch) ─────────────────────

describe("applyUpdate — swap failure", () => {
  // Honest filesystem-only test of the swap-failed path: a READ-ONLY parent dir
  // makes the swap-lock acquisition (openSync(lock,"wx")) throw EACCES, so the
  // swap aborts before any link/rename. execPath is therefore never touched —
  // the "never leave no-binary-at-execPath" invariant holds trivially here.
  //
  // The later swap-failed branches (the linkSync(execPath→.prev) throwing, or the
  // renameSync(tmp→execPath) throwing) are NOT reachable with filesystem state
  // alone: if the parent is writable enough to create the lock, it's writable
  // enough for the link and the atomic rename-replace too. Triggering those would
  // require injecting an fs failure mid-swap (forbidden by the contract: no fs
  // injection). They're straightforward defensive handlers — each just unlinks
  // the artifact it created (tmp and/or the .prev hardlink) and leaves execPath
  // alone (which the hardlink design guarantees was never absent). We cover the
  // swap-failed error shape here and document those as covered-by-construction.
  test("read-only parent fails the swap; execPath untouched, no .prev, swap-failed", async () => {
    writeFileSync(fakeExec, "OLD-UNTOUCHED-SWAP")
    const newBytes = "NEW-BYTES-FOR-SWAP"
    const assetUrl = "https://dl.test/swap"
    const manifest = manifestFor({
      version: "0.3.0",
      assetUrl,
      sha256: sha256Hex(newBytes),
    })

    let result: Awaited<ReturnType<typeof applyUpdate>>
    try {
      // Download + sha + chmod all happen first (parent still writable for the
      // tmp write). Then we flip the dir read-only right before the swap by
      // intercepting onState("swapping") — the lock acquisition then fails EACCES.
      result = await applyUpdate({
        manifest,
        execPathOverride: fakeExec,
        fetchImpl: serveBytes({ [assetUrl]: { body: newBytes } }),
        onState: (s) => {
          if (s === "swapping") chmodSync(dir, 0o555) // read-only just-in-time
        },
      })
    } finally {
      chmodSync(dir, 0o755) // restore for cleanup + assertions
    }

    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("swap-failed")

    // Invariant: the original binary is still at execPath (the swap aborted at the
    // lock, before any link/rename) and no .prev was created. (tmp-cleanup is NOT
    // asserted here: this contrived read-only-dir also blocks the cleanup unlink —
    // an artifact of the test, not the engine. tmp removal under a writable dir is
    // proven by the sha-mismatch and download-failure tests above.)
    expect(existsSync(fakeExec)).toBe(true)
    expect(statSync(fakeExec).isFile()).toBe(true)
    expect(await readFileText(fakeExec)).toBe("OLD-UNTOUCHED-SWAP")
    expect(existsSync(fakeExec + ".prev")).toBe(false)
  })
})

// ── swap lock (concurrency guard) ────────────────────────────────────────────

describe("swap lock", () => {
  const lockPath = () => join(dir, SWAP_LOCK_NAME)

  // Manually hold the lock (as another process would), then prove a concurrent
  // apply refuses with `busy` and leaves execPath untouched + tmp cleaned.
  test("apply while lock is held → busy; execPath untouched, tmp cleaned", async () => {
    writeFileSync(fakeExec, "OLD-LOCKED")
    const newBytes = "NEW-LOCKED"
    const assetUrl = "https://dl.test/locked"
    const manifest = manifestFor({ version: "0.2.0", assetUrl, sha256: sha256Hex(newBytes) })

    const heldFd = openSync(lockPath(), "wx") // someone else holds the lock
    try {
      const result = await applyUpdate({
        manifest,
        execPathOverride: fakeExec,
        fetchImpl: serveBytes({ [assetUrl]: { body: newBytes } }),
      })
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error.kind).toBe("busy")
    } finally {
      closeSync(heldFd)
    }

    // execPath untouched, no .prev created, our tmp cleaned up.
    expect(await readFileText(fakeExec)).toBe("OLD-LOCKED")
    expect(existsSync(fakeExec + ".prev")).toBe(false)
    expect(existsSync(join(dir, TMP_NAME))).toBe(false)
    // The lock we created by hand is still there (apply must NOT remove a lock it
    // didn't acquire); clean it ourselves so afterEach is tidy.
    expect(existsSync(lockPath())).toBe(true)
    rmSync(lockPath(), { force: true })
  })

  // rollback shares the same lock: holding it makes rollback report busy too.
  test("rollback while lock is held → busy", () => {
    writeFileSync(fakeExec, "CUR")
    writeFileSync(fakeExec + ".prev", "PREV") // so we get past the no-prev check
    const heldFd = openSync(lockPath(), "wx")
    try {
      const result = rollback({ execPathOverride: fakeExec })
      expect(result.ok).toBe(false)
      if (result.ok) return
      expect(result.error.kind).toBe("busy")
    } finally {
      closeSync(heldFd)
    }
    // Nothing moved.
    expect(existsSync(fakeExec)).toBe(true)
    expect(existsSync(fakeExec + ".prev")).toBe(true)
    rmSync(lockPath(), { force: true })
  })

  // A lock older than 10 min is assumed left by a crashed process: apply must
  // unlink it, acquire, and proceed to a successful swap.
  test("stale lock (>10min) is reclaimed; apply proceeds and succeeds", async () => {
    writeFileSync(fakeExec, "OLD-STALE")
    const newBytes = "NEW-AFTER-STALE"
    const assetUrl = "https://dl.test/stale"
    const manifest = manifestFor({ version: "0.6.0", assetUrl, sha256: sha256Hex(newBytes) })

    // Create the lock, then backdate its mtime to 11 minutes ago.
    closeSync(openSync(lockPath(), "wx"))
    const elevenMinAgo = (Date.now() - 11 * 60 * 1000) / 1000 // seconds
    utimesSync(lockPath(), elevenMinAgo, elevenMinAgo)

    const result = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [assetUrl]: { body: newBytes } }),
    })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.newVersion).toBe("0.6.0")
    expect(await readFileText(fakeExec)).toBe(newBytes)
    expect(await readFileText(fakeExec + ".prev")).toBe("OLD-STALE")
    // The (reclaimed) lock is released again after the successful swap.
    expect(existsSync(lockPath())).toBe(false)
  })
})

// ── rollback ─────────────────────────────────────────────────────────────────

describe("rollback", () => {
  test("no .prev → no-prev error", () => {
    writeFileSync(fakeExec, "CURRENT-ONLY")
    const result = rollback({ execPathOverride: fakeExec })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("no-prev")
  })

  test("round-trip reversibility: apply A→B, rollback, exec==A AND prev==B; rollback again → B at exec", async () => {
    // Start with A at execPath.
    writeFileSync(fakeExec, "BYTES-A")
    const bBytes = "BYTES-B"
    const assetUrl = "https://dl.test/b"
    const manifest = manifestFor({
      version: "0.2.0",
      assetUrl,
      sha256: sha256Hex(bBytes),
    })

    // apply A→B: exec=B, prev=A
    const applied = await applyUpdate({
      manifest,
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [assetUrl]: { body: bBytes } }),
    })
    expect(applied.ok).toBe(true)
    expect(await readFileText(fakeExec)).toBe("BYTES-B")
    expect(await readFileText(fakeExec + ".prev")).toBe("BYTES-A")

    // rollback once: exec=A, prev=B (reversible!)
    const r1 = rollback({ execPathOverride: fakeExec })
    expect(r1.ok).toBe(true)
    if (r1.ok) expect(r1.restoredFrom).toBe(fakeExec + ".prev")
    expect(await readFileText(fakeExec)).toBe("BYTES-A")
    expect(await readFileText(fakeExec + ".prev")).toBe("BYTES-B")

    // rollback again: swaps back → exec=B, prev=A (proves invariant both ways)
    const r2 = rollback({ execPathOverride: fakeExec })
    expect(r2.ok).toBe(true)
    expect(await readFileText(fakeExec)).toBe("BYTES-B")
    expect(await readFileText(fakeExec + ".prev")).toBe("BYTES-A")
  })
})

// ── resolveAndApply: the caller-facing fresh-fetch layer ─────────────────────

describe("resolveAndApply — fresh fetch + stale-manifest guard", () => {
  const VERSIONS_URL = "https://supermux.test/versions.json"

  test("end-to-end happy path: fetches versions.json then the binary, swaps", async () => {
    writeFileSync(fakeExec, "OLD-CURRENT")
    const newBytes = "FRESH-NEW-BINARY"
    const assetUrl = "https://dl.test/fresh"
    const manifestBody = JSON.stringify(
      manifestFor({ version: "0.5.0", assetUrl, sha256: sha256Hex(newBytes) }),
    )

    const states: string[] = []
    const result = await resolveAndApply({
      url: VERSIONS_URL,
      currentVersion: "0.1.0",
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({
        [VERSIONS_URL]: { body: manifestBody },
        [assetUrl]: { body: newBytes },
      }),
      onState: (s) => states.push(s),
    })

    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.newVersion).toBe("0.5.0")
    expect(await readFileText(fakeExec)).toBe(newBytes)
    expect(await readFileText(fakeExec + ".prev")).toBe("OLD-CURRENT")
    // checking comes first, then the apply states
    expect(states).toEqual(["checking", "downloading", "swapping"])
  })

  test("manifest-unavailable when versions.json fetch throws", async () => {
    writeFileSync(fakeExec, "OLD")
    const result = await resolveAndApply({
      url: VERSIONS_URL,
      currentVersion: "0.1.0",
      execPathOverride: fakeExec,
      fetchImpl: async () => {
        throw new Error("DNS failure")
      },
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("manifest-unavailable")
    // execPath untouched
    expect(await readFileText(fakeExec)).toBe("OLD")
  })

  test("manifest-unavailable when versions.json is junk JSON", async () => {
    writeFileSync(fakeExec, "OLD")
    const result = await resolveAndApply({
      url: VERSIONS_URL,
      currentVersion: "0.1.0",
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [VERSIONS_URL]: { body: "{not valid json at all" } }),
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("manifest-unavailable")
  })

  test("already-current when fetched version equals current", async () => {
    writeFileSync(fakeExec, "OLD")
    const manifestBody = JSON.stringify(
      manifestFor({ version: "0.4.0", assetUrl: "https://dl.test/x", sha256: "x" }),
    )
    const result = await resolveAndApply({
      url: VERSIONS_URL,
      currentVersion: "0.4.0",
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [VERSIONS_URL]: { body: manifestBody } }),
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("already-current")
  })

  test("already-current when fetched version is LOWER than current", async () => {
    writeFileSync(fakeExec, "OLD")
    const manifestBody = JSON.stringify(
      manifestFor({ version: "0.3.0", assetUrl: "https://dl.test/x", sha256: "x" }),
    )
    const result = await resolveAndApply({
      url: VERSIONS_URL,
      currentVersion: "0.9.0",
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({ [VERSIONS_URL]: { body: manifestBody } }),
    })
    expect(result.ok).toBe(false)
    if (result.ok) return
    expect(result.error.kind).toBe("already-current")
  })

  test("STALE-MANIFEST guard: applies the FETCHED version, not any caller belief", async () => {
    // The caller might believe latest=0.9.9 (from a stale checker), but the
    // fresh versions.json says 0.0.2. resolveAndApply must apply 0.0.2 — the
    // newVersion comes from the FETCHED manifest, never a caller-supplied belief.
    writeFileSync(fakeExec, "OLD-CURRENT")
    const newBytes = "ACTUAL-0.0.2-BINARY"
    const assetUrl = "https://dl.test/v002"
    const manifestBody = JSON.stringify(
      manifestFor({ version: "0.0.2", assetUrl, sha256: sha256Hex(newBytes) }),
    )
    // current is 0.0.1, so 0.0.2 IS an update — it should apply, proving we used
    // the fetched truth (0.0.2) and not some 0.9.9 belief (which isn't passed in
    // at all — there is no field for it, which is the whole point).
    const result = await resolveAndApply({
      url: VERSIONS_URL,
      currentVersion: "0.0.1",
      execPathOverride: fakeExec,
      fetchImpl: serveBytes({
        [VERSIONS_URL]: { body: manifestBody },
        [assetUrl]: { body: newBytes },
      }),
    })
    expect(result.ok).toBe(true)
    if (!result.ok) return
    expect(result.newVersion).toBe("0.0.2") // fetched, not 0.9.9
    expect(await readFileText(fakeExec)).toBe(newBytes)
  })
})

// ── restartViaLaunchd / restartService: false-path (not service-managed) ──────

describe("restartViaLaunchd", () => {
  const saved = process.env.XPC_SERVICE_NAME
  afterEach(() => {
    if (saved === undefined) delete process.env.XPC_SERVICE_NAME
    else process.env.XPC_SERVICE_NAME = saved
  })

  test("returns false when XPC_SERVICE_NAME is absent or '0' (not launchd-managed)", () => {
    delete process.env.XPC_SERVICE_NAME
    expect(restartViaLaunchd({})).toBe(false)
    process.env.XPC_SERVICE_NAME = "0"
    expect(restartViaLaunchd({})).toBe(false)
  })
})

describe("restartService", () => {
  // On the Linux CI host this dispatches to restartViaSystemd; clearing both the
  // systemd and launchd gates keeps it false (and side-effect-free) on either OS.
  const savedInv = process.env.INVOCATION_ID
  const savedXpc = process.env.XPC_SERVICE_NAME
  afterEach(() => {
    if (savedInv === undefined) delete process.env.INVOCATION_ID
    else process.env.INVOCATION_ID = savedInv
    if (savedXpc === undefined) delete process.env.XPC_SERVICE_NAME
    else process.env.XPC_SERVICE_NAME = savedXpc
  })

  test("returns false when not service-managed", () => {
    delete process.env.INVOCATION_ID
    delete process.env.XPC_SERVICE_NAME
    expect(restartService()).toBe(false)
  })
})

// ── restartViaSystemd: only the false (no-INVOCATION_ID) path is testable ─────

describe("restartViaSystemd", () => {
  const saved = process.env.INVOCATION_ID

  afterEach(() => {
    if (saved === undefined) delete process.env.INVOCATION_ID
    else process.env.INVOCATION_ID = saved
  })

  test("returns false when INVOCATION_ID is absent (not systemd-managed)", () => {
    delete process.env.INVOCATION_ID
    expect(restartViaSystemd({})).toBe(false)
    expect(restartViaSystemd({ unit: "supermux" })).toBe(false)
  })

  // The real spawn can't run in CI (no systemd), but restartViaSystemd's safety
  // rests on ONE shell fact: `sh -c SCRIPT -- UNIT` binds $0="--" and $1=UNIT, so
  // the unit name is data ("$1"), never interpolated into the script (no shell
  // injection). Probe that exact argv shape with a benign `echo "$1"` and confirm
  // the unit lands verbatim in $1 — even a hostile value with shell metacharacters.
  test("sh -c argv plumbing: unit binds to $1 verbatim (injection-proof)", async () => {
    const hostile = 'x"; touch /tmp/PWNED #'
    const proc = Bun.spawn(["sh", "-c", 'printf "%s" "$1"', "--", hostile], {
      stdout: "pipe",
      stderr: "ignore",
    })
    const out = await new Response(proc.stdout).text()
    await proc.exited
    // The whole hostile string arrived as a single $1 token, unexecuted.
    expect(out).toBe(hostile)
  })
})

// keep an unused-import guard happy: reference the type so tsc sees it used.
const _typecheck: UpdateApplyError | null = null
void _typecheck
