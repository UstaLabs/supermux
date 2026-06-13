// Atomic update-apply + reversible-rollback engine.
//
// Two layers:
//   • applyUpdate(manifest)  — pure-ish, fully testable. Takes a manifest
//     EXPLICITLY and applies channels.stable as-is. It does NOT fetch
//     versions.json — the caller decides which manifest to trust.
//   • resolveAndApply(url)   — the caller-facing helper for the CLI/API. It
//     ALWAYS re-fetches a fresh versions.json itself (10s timeout) and never
//     accepts a checker's cached manifest. This is the guard against Task-2's
//     stale-manifest hazard: the checker's latestManifest() can lag its
//     advertised `latest` (the GH fallback fills `latest` with no manifest), so
//     apply must work from freshly-fetched truth, not a cached belief.
//
// Mechanics:
//   arch map x64→linux-x64, arm64→linux-arm64 (anything else → arch-unsupported).
//   download to <dirname(execPath)>/.supermux-update.${pid}.tmp (same fs as the
//     binary, so the final rename is atomic; pid-scoped so two concurrent
//     processes never clobber each other's tmp). non-200 → download-failed.
//   download timeout: fetch's AbortSignal only covers connect+headers — it does
//     NOT propagate to the body drain, so a stalled-but-open connection makes
//     `Bun.write(tmp, res)` hang forever (empirically proven). We therefore race
//     the body write against a hard deadline (opts.downloadTimeoutMs, default
//     2 min) and treat a deadline win as download-failed (tmp cleaned).
//   sha256 hex over the WRITTEN FILE bytes (read back from disk — verify what
//     actually landed, not what came over the wire); mismatch → unlink tmp.
//   chmod 0755 tmp, then the SWAP.
//
//   SWAP — hardlink-then-rename, with NO empty-execPath window (replaces the
//   old double-rename, which had a kill-9 window where execPath was momentarily
//   absent). On the same filesystem a hard link is just a second name for the
//   same inode, so:
//     1. unlink any stale .prev (link() refuses to overwrite an existing name)
//     2. linkSync(execPath, prevPath)  — the old binary now has TWO names;
//        execPath is still fully valid and pointing at the running inode
//     3. renameSync(tmpPath, execPath) — atomic REPLACE of execPath; at no
//        instant is execPath absent. (rename over an existing path is atomic on
//        POSIX; renaming/replacing a running ELF is safe on Linux — the inode
//        survives for already-mapped pages.)
//   A hard kill at ANY point leaves a working binary at execPath. If step 3
//   throws, execPath was never touched — cleanup is just unlink prevPath.
//
//   ROLLBACK — the mirrored pattern (see rollback() for the full window note).
//
//   CONCURRENCY — the entire swap sequence (and rollback's) is guarded by an
//   exclusive on-disk lock (<dir>/.supermux-swap.lock, openSync "wx"). A second
//   apply/rollback while the lock is held returns `busy`. A lock older than
//   STALE_LOCK_MS is assumed to be from a crashed process: it's unlinked and the
//   acquire is retried once.
import { chmodSync, closeSync, existsSync, linkSync, openSync, renameSync, statSync, unlinkSync } from "fs"
import { dirname, join } from "path"
import { isUpdateAvailable, parseVersionsJson, type VersionsJson } from "./versions"
import type { FetchLike } from "./checker"

const TMP_NAME = `.supermux-update.${process.pid}.tmp`
const PREV_SUFFIX = ".prev"
const ROLLBACK_TMP_SUFFIX = ".rollback-tmp"
const SWAP_LOCK_NAME = ".supermux-swap.lock"

const DOWNLOAD_TIMEOUT_MS = 120_000 // 2 min
const MANIFEST_TIMEOUT_MS = 10_000 // 10 s
const STALE_LOCK_MS = 10 * 60 * 1000 // 10 min — older lock = crashed process

export type UpdateApplyError =
  | { kind: "arch-unsupported"; arch: string }
  | { kind: "asset-missing"; key: string }
  | { kind: "download-failed"; detail: string }
  | { kind: "sha-mismatch"; expected: string; actual: string }
  | { kind: "swap-failed"; detail: string }
  | { kind: "busy" }

export type ApplyState = "downloading" | "swapping"

function errToString(err: unknown): string {
  if (err instanceof Error) return err.message
  return String(err)
}

/**
 * Map a Node `process.arch` value to the release asset key used in versions.json.
 * Returns null for any arch we don't ship a binary for — the caller turns that
 * into an `arch-unsupported` error. Exported so the unmapped path is unit-testable
 * even on hosts where process.arch can't be faked.
 */
export function archAssetKeyFor(arch: string): string | null {
  switch (arch) {
    case "x64":
      return "linux-x64"
    case "arm64":
      return "linux-arm64"
    default:
      return null
  }
}

function sha256Hex(bytes: Uint8Array): string {
  const h = new Bun.CryptoHasher("sha256")
  h.update(bytes)
  return h.digest("hex")
}

/**
 * Try to acquire the exclusive on-disk swap lock for `dir`. `openSync(path,"wx")`
 * fails with EEXIST if the lock already exists, giving us atomic mutual exclusion
 * across processes for the whole swap (and rollback) sequence.
 *
 * Returns:
 *   { ok: true, fd }            — lock held; caller MUST releaseSwapLock(fd).
 *   { ok: false, busy: true }   — a live (non-stale) lock exists → caller maps to
 *                                 the typed `busy` error.
 *   { ok: false, busy: false, detail } — a real fs error (e.g. EACCES on a
 *                                 read-only dir) → caller maps to swap-failed.
 *
 * On EEXIST we check the lock's age: older than STALE_LOCK_MS ⇒ the holder is
 * assumed crashed, so we unlink and retry exactly once; still-fresh (or a second
 * EEXIST after the unlink race) ⇒ busy.
 */
function acquireSwapLock(
  dir: string,
): { ok: true; fd: number } | { ok: false; busy: boolean; detail?: string } {
  const lockPath = join(dir, SWAP_LOCK_NAME)
  try {
    return { ok: true, fd: openSync(lockPath, "wx") }
  } catch (err) {
    if ((err as NodeJS.ErrnoException).code !== "EEXIST") {
      // A genuine error (permissions, missing dir, ...). Not "busy".
      return { ok: false, busy: false, detail: errToString(err) }
    }
    // Lock exists. Stale (crashed-process) lock? Check its age and retry once.
    let mtimeMs: number
    try {
      mtimeMs = statSync(lockPath).mtimeMs
    } catch {
      // Vanished between open and stat — race; retry the open once.
      try {
        return { ok: true, fd: openSync(lockPath, "wx") }
      } catch {
        return { ok: false, busy: true }
      }
    }
    if (Date.now() - mtimeMs <= STALE_LOCK_MS) return { ok: false, busy: true } // fresh → busy
    // Stale: drop it and retry exactly once.
    try {
      unlinkSync(lockPath)
    } catch {
      /* lost the race to remove it; fall through to the retry which will EEXIST */
    }
    try {
      return { ok: true, fd: openSync(lockPath, "wx") }
    } catch {
      return { ok: false, busy: true }
    }
  }
}

/** Release the swap lock: close the fd and remove the lockfile. Best-effort. */
function releaseSwapLock(fd: number, dir: string): void {
  try {
    closeSync(fd)
  } catch {
    /* ignore */
  }
  try {
    unlinkSync(join(dir, SWAP_LOCK_NAME))
  } catch {
    /* ignore */
  }
}

/**
 * Apply an update from an EXPLICIT manifest. Downloads channels.stable's asset
 * for this arch, sha256-verifies the bytes on disk, then atomically swaps it in
 * (keeping the old binary at execPath+".prev"). The manifest is applied as-is;
 * coherence (does `latest` match a real asset?) is the caller's job to ensure by
 * passing a FRESH manifest — see resolveAndApply.
 */
export async function applyUpdate(opts: {
  manifest: VersionsJson
  execPathOverride?: string // tests ONLY; default process.execPath
  archAssetKey?: string // default from process.arch map; tests override
  fetchImpl?: FetchLike
  onState?: (s: ApplyState) => void
  downloadTimeoutMs?: number // body-drain deadline; default 2 min (tests pass tiny)
}): Promise<
  { ok: true; prevPath: string; newVersion: string } | { ok: false; error: UpdateApplyError }
> {
  const execPath = opts.execPathOverride ?? process.execPath
  const fetchImpl = opts.fetchImpl ?? fetch
  const onState = opts.onState ?? (() => {})
  const downloadTimeoutMs = opts.downloadTimeoutMs ?? DOWNLOAD_TIMEOUT_MS

  // 1. Resolve the asset key for this arch (override wins for tests).
  let assetKey: string
  if (opts.archAssetKey !== undefined) {
    assetKey = opts.archAssetKey
  } else {
    const mapped = archAssetKeyFor(process.arch)
    if (mapped === null) {
      return { ok: false, error: { kind: "arch-unsupported", arch: process.arch } }
    }
    assetKey = mapped
  }

  const channel = opts.manifest.channels.stable
  const asset = channel.assets[assetKey]
  if (!asset) {
    return { ok: false, error: { kind: "asset-missing", key: assetKey } }
  }

  const dir = dirname(execPath)
  const tmpPath = join(dir, TMP_NAME)
  const prevPath = execPath + PREV_SUFFIX

  // 2. Download → tmp (sibling of execPath, same fs for an atomic rename later).
  //    The fetch's AbortSignal only guards connect+headers; it does NOT abort a
  //    stalled body drain (proven). So we race the body write (Bun.write) against
  //    a hard deadline and treat a deadline win as download-failed. The timer is
  //    cleared in a finally so a fast success never keeps the process alive.
  onState("downloading")
  let res: Response
  try {
    res = await fetchImpl(asset.url, { signal: AbortSignal.timeout(downloadTimeoutMs) })
  } catch (err) {
    return { ok: false, error: { kind: "download-failed", detail: errToString(err) } }
  }
  if (!res.ok) {
    return { ok: false, error: { kind: "download-failed", detail: `HTTP ${res.status}` } }
  }
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    const deadline = new Promise<never>((_, rej) => {
      timer = setTimeout(
        () =>
          rej(
            new Error(
              `download stalled (no completion within ${Math.round(downloadTimeoutMs / 1000)}s)`,
            ),
          ),
        downloadTimeoutMs,
      )
    })
    await Promise.race([Bun.write(tmpPath, res), deadline])
  } catch (err) {
    // Stalled or write error: best-effort cleanup of a partial tmp; never leave
    // junk behind.
    try {
      if (existsSync(tmpPath)) unlinkSync(tmpPath)
    } catch {
      /* ignore */
    }
    return { ok: false, error: { kind: "download-failed", detail: errToString(err) } }
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }

  // 3. sha256 over the bytes that ACTUALLY landed on disk (read back).
  const written = await Bun.file(tmpPath).bytes()
  const actual = sha256Hex(written)
  if (actual !== asset.sha256) {
    try {
      unlinkSync(tmpPath)
    } catch {
      /* ignore */
    }
    return {
      ok: false,
      error: { kind: "sha-mismatch", expected: asset.sha256, actual },
    }
  }

  // 4. Make the downloaded binary executable.
  try {
    chmodSync(tmpPath, 0o755)
  } catch (err) {
    try {
      unlinkSync(tmpPath)
    } catch {
      /* ignore */
    }
    return { ok: false, error: { kind: "swap-failed", detail: `chmod: ${errToString(err)}` } }
  }

  // 5. Swap, hardlink-then-rename, NO empty-execPath window (see file header).
  //    Guarded by the exclusive swap lock so a concurrent apply/rollback can't
  //    interleave its renames with ours.
  onState("swapping")
  const lock = acquireSwapLock(dir)
  if (!lock.ok) {
    // Either a live concurrent swap holds the lock (busy) or we couldn't create
    // it at all (a real fs error → swap-failed). Either way execPath is untouched;
    // drop our tmp.
    try {
      unlinkSync(tmpPath)
    } catch {
      /* ignore */
    }
    return lock.busy
      ? { ok: false, error: { kind: "busy" } }
      : { ok: false, error: { kind: "swap-failed", detail: `acquire lock: ${lock.detail}` } }
  }
  try {
    // Step 1: ensure no stale .prev — linkSync refuses to overwrite an existing
    // name, so a leftover .prev from a prior run would make the link fail.
    try {
      if (existsSync(prevPath)) unlinkSync(prevPath)
    } catch (err) {
      try {
        unlinkSync(tmpPath)
      } catch {
        /* ignore */
      }
      return {
        ok: false,
        error: { kind: "swap-failed", detail: `clear stale prev: ${errToString(err)}` },
      }
    }

    // Step 2: linkSync(execPath, prevPath) — old binary now has two names;
    // execPath is still valid and pointing at the running inode.
    try {
      linkSync(execPath, prevPath)
    } catch (err) {
      // execPath untouched (no second name created). Clean the tmp and report.
      try {
        unlinkSync(tmpPath)
      } catch {
        /* ignore */
      }
      return {
        ok: false,
        error: { kind: "swap-failed", detail: `link current→prev: ${errToString(err)}` },
      }
    }

    // Step 3: renameSync(tmpPath, execPath) — atomic REPLACE; at no instant is
    // execPath absent. On failure execPath was never touched: just unlink the
    // .prev hardlink we made (and the tmp we couldn't install).
    try {
      renameSync(tmpPath, execPath)
    } catch (err) {
      try {
        unlinkSync(prevPath)
      } catch {
        /* ignore */
      }
      try {
        if (existsSync(tmpPath)) unlinkSync(tmpPath)
      } catch {
        /* ignore */
      }
      return {
        ok: false,
        error: { kind: "swap-failed", detail: `rename tmp→execPath: ${errToString(err)}` },
      }
    }
  } finally {
    releaseSwapLock(lock.fd, dir)
  }

  return { ok: true, prevPath, newVersion: channel.version }
}

/**
 * Caller-facing apply for CLI/API. ALWAYS re-fetches a fresh versions.json (never
 * a cached/stale checker manifest), then applies channels.stable from that fresh
 * truth. newVersion always reflects the FETCHED manifest, not any caller belief.
 */
export async function resolveAndApply(opts: {
  url: string // versions.json URL (MUX_UPDATE_URL already resolved by caller)
  currentVersion: string
  execPathOverride?: string
  archAssetKey?: string
  fetchImpl?: FetchLike
  onState?: (s: "checking" | ApplyState) => void
  downloadTimeoutMs?: number // forwarded to applyUpdate's body-drain deadline
}): Promise<
  | { ok: true; prevPath: string; newVersion: string }
  | {
      ok: false
      error:
        | UpdateApplyError
        | { kind: "manifest-unavailable"; detail: string }
        | { kind: "already-current" }
    }
> {
  const fetchImpl = opts.fetchImpl ?? fetch
  const onState = opts.onState ?? (() => {})

  // 1. Fresh fetch + parse (10s timeout). ANY failure → manifest-unavailable.
  onState("checking")
  let manifest: VersionsJson
  try {
    const res = await fetchImpl(opts.url, { signal: AbortSignal.timeout(MANIFEST_TIMEOUT_MS) })
    if (!res.ok) {
      return {
        ok: false,
        error: { kind: "manifest-unavailable", detail: `HTTP ${res.status}` },
      }
    }
    const json = await res.json()
    const parsed = parseVersionsJson(json)
    if (!parsed.ok) {
      return { ok: false, error: { kind: "manifest-unavailable", detail: parsed.error } }
    }
    manifest = parsed.data
  } catch (err) {
    return { ok: false, error: { kind: "manifest-unavailable", detail: errToString(err) } }
  }

  // 2. Compare the FRESHLY-fetched version against current. Not an update → stop.
  //    NOTE: `already-current` also covers the remote version being OLDER than
  //    current (isUpdateAvailable is strictly remote > current). We never auto-
  //    apply a downgrade here; a deliberate downgrade goes through rollback().
  const stable = manifest.channels.stable
  if (!isUpdateAvailable(opts.currentVersion, stable.version)) {
    return { ok: false, error: { kind: "already-current" } }
  }

  // 3. Apply the fresh manifest. onState forwards downloading/swapping.
  return applyUpdate({
    manifest,
    execPathOverride: opts.execPathOverride,
    archAssetKey: opts.archAssetKey,
    fetchImpl,
    onState,
    downloadTimeoutMs: opts.downloadTimeoutMs,
  })
}

/**
 * Roll back to the binary saved at execPath+".prev". Mirrors the apply swap's
 * hardlink-then-rename so there is NO empty-execPath window, and stays itself
 * reversible:
 *   1. unlink any stale .rollback-tmp (link() refuses to overwrite a name)
 *   2. linkSync(execPath, .rollback-tmp) — current (just-running) gets a 2nd
 *      name; execPath stays valid throughout.
 *   3. renameSync(prev → execPath)       — atomic REPLACE; execPath is never
 *      absent. NOTE: this leaves the .prev slot EMPTY (prev was renamed away)
 *      until step 4 restocks it — a brief .prev-slot gap. That's acceptable:
 *      execPath always holds a working binary; reversibility is restored at
 *      step 4.
 *   4. renameSync(.rollback-tmp → prev)  — restock .prev with the just-running
 *      binary, so a second rollback swaps forward again (reversibility).
 * After success: execPath = previous, .prev = just-running.
 * On step-3 failure: execPath untouched (current still at execPath AND
 *   .rollback-tmp), prev untouched — clean the rbTmp and report.
 * On step-4 failure: execPath already holds the rolled-back binary (fine) and
 *   rbTmp still holds the other; we report swap-failed and LEAVE rbTmp for
 *   manual recovery (no safe automatic move remains — restocking .prev IS the
 *   step that just failed).
 * The whole sequence is guarded by the exclusive swap lock (shared with apply).
 */
export function rollback(opts: { execPathOverride?: string }):
  | { ok: true; restoredFrom: string }
  | {
      ok: false
      error: { kind: "no-prev" } | { kind: "swap-failed"; detail: string } | { kind: "busy" }
    } {
  const execPath = opts.execPathOverride ?? process.execPath
  const dir = dirname(execPath)
  const prevPath = execPath + PREV_SUFFIX
  const rbTmp = execPath + ROLLBACK_TMP_SUFFIX

  if (!existsSync(prevPath)) {
    return { ok: false, error: { kind: "no-prev" } }
  }

  const lock = acquireSwapLock(dir)
  if (!lock.ok) {
    return lock.busy
      ? { ok: false, error: { kind: "busy" } }
      : { ok: false, error: { kind: "swap-failed", detail: `acquire lock: ${lock.detail}` } }
  }
  try {
    // Re-check prev under the lock: a concurrent swap may have changed things
    // between our pre-check and acquiring the lock.
    if (!existsSync(prevPath)) {
      return { ok: false, error: { kind: "no-prev" } }
    }

    // Step 1: clear any stale .rollback-tmp so linkSync won't EEXIST.
    try {
      if (existsSync(rbTmp)) unlinkSync(rbTmp)
    } catch (err) {
      return {
        ok: false,
        error: { kind: "swap-failed", detail: `clear stale rollback-tmp: ${errToString(err)}` },
      }
    }

    // Step 2: linkSync(execPath, rbTmp) — current gets a second name; execPath
    // stays valid.
    try {
      linkSync(execPath, rbTmp)
    } catch (err) {
      return { ok: false, error: { kind: "swap-failed", detail: `link current→tmp: ${errToString(err)}` } }
    }

    // Step 3: prev → execPath (atomic replace; no window). On failure execPath
    // is untouched (current is still at execPath and rbTmp); drop the rbTmp link.
    try {
      renameSync(prevPath, execPath)
    } catch (err) {
      try {
        unlinkSync(rbTmp)
      } catch {
        /* ignore */
      }
      return { ok: false, error: { kind: "swap-failed", detail: `prev→execPath: ${errToString(err)}` } }
    }

    // Step 4: rbTmp → prev (restock .prev with the just-running binary). On
    // failure execPath already holds the rolled-back binary; rbTmp still holds
    // the other. No safe automatic move remains (this WAS the restock), so we
    // leave rbTmp in place for manual recovery and report swap-failed.
    try {
      renameSync(rbTmp, prevPath)
    } catch (err) {
      return {
        ok: false,
        error: {
          kind: "swap-failed",
          detail: `tmp→prev (rbTmp left at ${rbTmp} for manual recovery): ${errToString(err)}`,
        },
      }
    }
  } finally {
    releaseSwapLock(lock.fd, dir)
  }

  return { ok: true, restoredFrom: prevPath }
}

/**
 * If running under systemd (INVOCATION_ID present), schedule a detached restart
 * of the user unit and return true. Otherwise return false (caller falls back to
 * a "restart required" state). The actual spawn is intentionally NOT unit-tested
 * (no systemd in CI); the INVOCATION_ID gate is factored so the false path is.
 */
export function restartViaSystemd(opts: { unit?: string }): boolean {
  if (!process.env.INVOCATION_ID) return false
  const unit = opts.unit ?? process.env.MUX_SERVICE_UNIT ?? "supermux"
  // Detached so it survives this process exiting; the `sleep 1` lets the current
  // process exit cleanly before systemctl restarts the unit. The unit name is
  // passed as an ARGV positional ("$1"), NOT interpolated into the script, so a
  // hostile unit value cannot inject shell. (sh -c SCRIPT NAME ARG... binds
  // NAME→$0, ARG→$1...; we pass "--" as $0 so `unit` lands in $1 — verified.)
  const proc = Bun.spawn(
    ["sh", "-c", 'sleep 1; exec systemctl --user restart "$1"', "--", unit],
    {
      stdin: "ignore",
      stdout: "ignore",
      stderr: "ignore",
    },
  )
  proc.unref()
  return true
}
