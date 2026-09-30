import { randomBytes } from "crypto"
import { readFile, stat } from "fs/promises"
import { dirname, join, sep } from "path"
import { gitAsync } from "../git/exec"
import { DirCache, type CachedDir } from "./dir-cache"
import { DirWatchers, type DirWatchersOpts } from "./dir-watchers"
import { toFsError } from "./errors"
import { applyOp, rawFile, readText, statEntry, writeText, type OpOptions } from "./file-ops"
import { normalizeAbsPath, realKey } from "./paths"
import { RepoInfoCache } from "./repo-info"
import { SearchIndexes } from "./search-index"
import { SubscriptionRegistry } from "./subscriptions"
import type { DirSnapshot, FsEntry, FsFrame, FsOp, SearchHit } from "./types"
import { makeLogger } from "../../shared/log"

const log = makeLogger("fs")

export interface FileSystemServiceOpts<S> {
  bootId?: string
  trashDir?: string
  maxEntries?: number
  maxDirs?: number
  /** Deliver a frame to one subscriber socket. */
  emit?: (sock: S, frame: FsFrame) => void
  debounceMs?: number
  graceMs?: number
  maxSubsPerSocket?: number
  /** How long a "not in a git repo" answer is trusted (default 30 s). */
  noRepoTtlMs?: number
  /** Per-repo coalescing window for git re-annotation (default 500 ms). */
  repoDebounceMs?: number
  /** Test hook: replaces `fs.watch` for folder watches. */
  watchFn?: DirWatchersOpts["watchFn"]
  /** Polling interval when `fs.watch` is unavailable for a folder (default 5 s). */
  pollMs?: number
}

const toSnapshot = (path: string, c: CachedDir): DirSnapshot => ({
  path, real: c.real, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

const dirFrame = (path: string, c: CachedDir): FsFrame => ({
  type: "fs_dir", path, real: c.real, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

const isUnder = (root: string, p: string) => p === root || p.startsWith(root.endsWith(sep) ? root : root + sep)

const statKey = async (p: string) => {
  const st = await stat(p).catch(() => null)
  return st ? `${st.ino}:${st.size}:${st.mtimeMs}` : "-"
}

/** Cheap summary of what git status depends on in a git dir: index, HEAD, the branch it points to, packed refs. */
async function gitStateFingerprint(gitDir: string): Promise<string> {
  const head = await readFile(join(gitDir, "HEAD"), "utf-8").catch(() => "")
  const ref = /^ref: (refs\/\S+)/.exec(head)?.[1]
  const parts = await Promise.all([
    statKey(join(gitDir, "index")), statKey(join(gitDir, "packed-refs")), statKey(join(gitDir, "MERGE_HEAD")),
    ref ? statKey(join(gitDir, ref)) : Promise.resolve("-"),
  ])
  return `${head.trim()}|${parts.join("|")}`
}

/** Folder identity: inode alone is reused on ext4 after delete + recreate, so add the birth time. */
const identityOf = (st: { ino: number; birthtimeMs: number }) => `${st.ino}:${st.birthtimeMs > 0 ? st.birthtimeMs : ""}`

const GIT_LOCK_RECHECK_MS = 100
/** A lock left behind by a crashed git is not polled forever: 50 × 100 ms, then wait for the next event. */
const GIT_LOCK_RECHECKS = 50

interface RepoTick { folders: Set<string>; firstAt: number; timer?: ReturnType<typeof setTimeout> }

/** The host's one file-system service: addressed by absolute path, shared by every caller. */
export class FileSystemService<S = unknown> {
  readonly bootId: string
  readonly repo: RepoInfoCache
  readonly cache: DirCache
  private readonly searches: SearchIndexes
  private readonly opOpts: OpOptions
  private readonly emit: (sock: S, frame: FsFrame) => void
  private readonly subs: SubscriptionRegistry<S>
  private readonly watchers: DirWatchers
  private readonly gitWatchers: DirWatchers
  /** git dir → repo root; repo root → number of watched folders inside it; watched folder → its root. */
  private readonly gitDirOf = new Map<string, string>()
  private readonly repoRefs = new Map<string, number>()
  private readonly repoOfWatched = new Map<string, string>()
  private readonly gitDirOfRoot = new Map<string, string>()
  private readonly resolvingGitDir = new Set<string>()
  /** git dir → fingerprint of its state files at the last re-annotation it triggered. */
  private readonly gitPrint = new Map<string, string>()
  /** git dir → its identity when the watch started (a removed or re-created `.git` changes it). */
  private readonly gitIdOf = new Map<string, string>()
  private readonly gitRecheck = new Map<string, ReturnType<typeof setTimeout>>()
  /** Identity (inode + birth time) of each watched folder when its watch started: a folder deleted and
   *  recreated at the same path between two flushes leaves the old watch dead, so a change means "re-watch". */
  private readonly idOf = new Map<string, string>()
  /** Per repo: watched folders waiting for a coalesced git re-annotation. */
  private readonly repoTicks = new Map<string, RepoTick>()
  private readonly repoDebounceMs: number
  /** Subscribes in flight per socket; `dropped` tells them the socket closed while they were awaiting. */
  private readonly subscribing = new Map<S, { dropped: boolean; n: number }>()
  private closed = false

  constructor(opts: FileSystemServiceOpts<S> = {}) {
    this.bootId = opts.bootId ?? randomBytes(4).toString("hex")
    this.repo = new RepoInfoCache({ noRepoTtlMs: opts.noRepoTtlMs })
    this.cache = new DirCache({ repo: this.repo, bootId: this.bootId, maxEntries: opts.maxEntries, maxDirs: opts.maxDirs })
    this.searches = new SearchIndexes(this.repo)
    this.opOpts = { trashDir: opts.trashDir }
    this.emit = opts.emit ?? (() => {})
    this.repoDebounceMs = opts.repoDebounceMs ?? 500
    this.watchers = new DirWatchers((real) => this.background(this.onFlush(real), real), {
      debounceMs: opts.debounceMs,
      pollMs: opts.pollMs,
      watchFn: opts.watchFn,
      onFallback: (dir) => log.warn("fs_watch_fallback", { dir }),
    })
    // No extra debounce here: the per-repo tick coalesces git-dir changes with folder changes.
    this.gitWatchers = new DirWatchers((gitDir) => this.background(this.onGitFlush(gitDir), gitDir), {
      debounceMs: opts.debounceMs,
      // No filename filter: Bun reports a rename only under its FROM name (tmp → index arrives as
      // "tmp"), so a filter can drop real changes. The state fingerprint in onGitFlush decides.
    })
    this.subs = new SubscriptionRegistry<S>({
      onFirst: (real) => this.startWatching(real),
      onLast: (real) => this.stopWatching(real),
    }, { graceMs: opts.graceMs, maxPerSocket: opts.maxSubsPerSocket })
  }

  get watcherCount(): number { return this.watchers.size }
  get gitWatcherCount(): number { return this.gitWatchers.size }
  /** Git-dir flushes handled so far (tests / debug). */
  gitFlushCount = 0

  // ── request / response ────────────────────────────────────────────────────

  /** A folder's snapshot. Watched folders are served from cache; others are re-read. */
  async list(path: string): Promise<DirSnapshot> {
    const p = normalizeAbsPath(path)
    const real = await realKey(p)
    const c = this.cache.isPinned(real) ? await this.cache.getOrLoad(real) : (await this.cache.load(real)).snap
    return toSnapshot(p, c)
  }

  async stat(path: string): Promise<FsEntry & { real: string }> {
    return statEntry(normalizeAbsPath(path))
  }

  async read(path: string): Promise<string> {
    return readText(normalizeAbsPath(path))
  }

  /** A file to serve byte-for-byte (images and other binaries the editor previews). */
  async raw(path: string): Promise<{ path: string; size: number }> {
    const p = normalizeAbsPath(path)
    return { path: p, ...(await rawFile(p)) }
  }

  async write(path: string, text: string): Promise<{ size: number; mtime: number }> {
    const p = normalizeAbsPath(path)
    const r = await writeText(p, text)
    await this.refresh([dirname(p)])
    return r
  }

  async search(scope: string, q: string, limit = 50): Promise<SearchHit[]> {
    const s = await realKey(normalizeAbsPath(scope))
    return this.searches.query(s, q, limit)
  }

  async op(op: FsOp): Promise<void> {
    const path = normalizeAbsPath(op.path)
    const norm: FsOp = "to" in op ? { ...op, path, to: normalizeAbsPath(op.to) } : { ...op, path }
    await applyOp(norm, this.opOpts)
    await this.refresh("to" in norm ? [dirname(norm.path), dirname(norm.to)] : [dirname(norm.path)])
  }

  // ── subscriptions ─────────────────────────────────────────────────────────

  /**
   * Hold `path` for `sock` and send its snapshot (or `unchanged` when `since` is current).
   * Every frame about it (fs_dir / fs_gone / fs_err) carries the NORMALISED absolute path (`/a/./b/` →
   * `/a/b`), so clients should subscribe with normalised paths to key frames by what they sent.
   */
  async subscribe(sock: S, path: string, since?: string): Promise<void> {
    let p = path
    const tok = this.beginSubscribe(sock)
    try {
      p = normalizeAbsPath(path)
      const real = await realKey(p)
      if (tok.dropped) return // the socket closed meanwhile: holding anything now would leak it forever
      if (this.subs.add(sock, p, real) === "limit") {
        this.safeEmit(sock, { type: "fs_err", path: p, code: "TOO_MANY_SUBS", message: "too many folder subscriptions on this connection" })
        return
      }
      const c = await this.cache.getOrLoad(real)
      if (tok.dropped) { this.subs.remove(sock, p); return }
      this.safeEmit(sock, since !== undefined && since === c.version
        ? { type: "fs_dir", path: p, version: c.version, unchanged: true }
        : dirFrame(p, c))
    } catch (e) {
      const err = toFsError(e)
      if (this.subs.realOf(sock, p) !== undefined) this.subs.remove(sock, p)
      // `p` is the normalised path, or the raw one when it could not be normalised (EINVAL).
      if (!tok.dropped) this.safeEmit(sock, { type: "fs_err", path: p, code: err.code, message: err.message })
    } finally {
      this.endSubscribe(sock, tok)
    }
  }

  private beginSubscribe(sock: S): { dropped: boolean; n: number } {
    let tok = this.subscribing.get(sock)
    if (!tok) { tok = { dropped: false, n: 0 }; this.subscribing.set(sock, tok) }
    tok.n++
    return tok
  }

  private endSubscribe(sock: S, tok: { dropped: boolean; n: number }): void {
    if (--tok.n === 0 && this.subscribing.get(sock) === tok) this.subscribing.delete(sock)
  }

  unsubscribe(sock: S, path: string): void {
    try { this.subs.remove(sock, normalizeAbsPath(path)) } catch { /* bad path: nothing to remove */ }
  }

  /** The socket closed: release everything it held. */
  dropSocket(sock: S): void {
    const tok = this.subscribing.get(sock)
    if (tok) { tok.dropped = true; this.subscribing.delete(sock) }
    this.subs.dropSocket(sock)
  }

  close(): void {
    this.closed = true
    for (const t of this.repoTicks.values()) if (t.timer) clearTimeout(t.timer)
    this.repoTicks.clear()
    for (const t of this.gitRecheck.values()) clearTimeout(t)
    this.gitRecheck.clear()
    this.watchers.closeAll()
    this.gitWatchers.closeAll()
  }

  /** A subscriber socket that throws (closed mid-send) must not stop delivery to the others. */
  private safeEmit(sock: S, frame: FsFrame): void {
    try {
      this.emit(sock, frame)
    } catch (e) {
      log.warn("fs_emit_failed", { type: frame.type, error: String(e) })
    }
  }

  /** Fire-and-forget work: a failure is logged, never an unhandled rejection. */
  private background(p: Promise<unknown>, path: string): void {
    p.catch((e) => log.warn("fs_background_failed", { path, error: String(e) }))
  }

  // ── change propagation ────────────────────────────────────────────────────

  /** Folders our own write/op touched: refresh search indexes and git state, and reload cached ones. */
  private async refresh(dirs: string[]): Promise<void> {
    for (const d of new Set(dirs)) {
      const real = await realKey(d).catch(() => null)
      if (!real) continue
      if (this.cache.get(real)) {
        await this.onFlush(real, true)
      } else {
        this.searches.invalidateContaining(real)
        const root = this.repo.knownRepoFor(real)
        if (root) this.repo.invalidate(root)
      }
    }
  }

  /**
   * A watched (or cached) folder may have changed: re-read it now (with the git state at hand), and queue
   * the repo-wide re-annotation (fresh git status for it and its watched ancestors) on the repo's tick.
   * `ownOp`: our own write/op, whose git state is refreshed right away.
   */
  private async onFlush(real: string, ownOp = false): Promise<void> {
    if (this.closed) return
    this.searches.invalidateContaining(real)
    // The folder itself went away or was replaced: whatever git says about it may have changed.
    const dead = this.watchers.isDead(real)
    const replaced = (await this.rewatchIfReplaced(real)) || dead
    const known = this.repo.knownRepoFor(real)
    if (known && ownOp) this.repo.invalidate(known)
    let changed = await this.reloadAndPush(real)
    // `git init` right here: forget the cached "no repo" answer, watch the new git dir, then re-read
    // so entries get annotated (watch first, so a `git add` right after the push is not missed).
    if (known === null && this.cache.get(real)?.entries.some((e) => e.name === ".git")) {
      this.repo.forgetRootOf(real)
      if (this.subs.isWatched(real)) await this.trackRepo(real)
      changed = (await this.reloadAndPush(real)) || changed
    }
    const root = this.repo.knownRepoFor(real)
    if (!root) return
    // A git dir watch that died (e.g. `.git` removed) is dropped so it can be set up again. The dead flag
    // alone is not enough: Bun may report only the first event of a batch, losing the self-delete.
    const gitDir = this.gitDirOfRoot.get(root)
    if (gitDir !== undefined && (this.gitWatchers.isDead(gitDir) || await this.gitDirReplaced(gitDir))) {
      if (this.gitDirOfRoot.get(root) === gitDir) this.dropGitWatch(root)
    }
    // A folder that became part of a repo (here, or via an expired "no repo" answer) gets its git dir watched.
    if (this.subs.isWatched(real) && (!this.repoOfWatched.has(real) || !this.gitDirOfRoot.has(root))) {
      await this.trackRepo(real)
    }
    // Nothing in the listing changed (a spurious event, or a poll of an idle folder): no git status.
    // Otherwise every poll would run git status every 5 s per repo, forever.
    if (!changed && !replaced && !ownOp) return
    const affected = this.subs.watchedReals().filter((other) => isUnder(other, real) && isUnder(root, other))
    this.queueRepo(root, ownOp ? affected.filter((o) => o !== real) : affected)
  }

  /** The repo's git dir changed (index, HEAD, refs): every watched folder in it is re-annotated on the tick. */
  private async onGitFlush(gitDir: string, recheck = 0): Promise<void> {
    this.gitFlushCount++
    if (!this.gitDirOf.has(gitDir)) return
    const print = await gitStateFingerprint(gitDir)
    if (print === this.gitPrint.get(gitDir) && !this.gitWatchers.isDead(gitDir)) {
      // A git command still holds the index: its final rename may reach us as no event at all (Bun
      // reports only the first event of a batch), so look again shortly.
      if (this.gitDirOf.has(gitDir) && await stat(join(gitDir, "index.lock")).then(() => true, () => false)) this.recheckGit(gitDir, recheck)
      return
    }
    if (!this.gitDirOf.has(gitDir)) return // the watch was dropped while we fingerprinted
    this.gitPrint.set(gitDir, print)
    const root = this.gitDirOf.get(gitDir)
    if (!root) return
    this.queueRepo(root, this.subs.watchedReals().filter((r) => isUnder(root, r)))
  }

  private recheckGit(gitDir: string, done: number): void {
    if (this.closed || this.gitRecheck.has(gitDir) || done >= GIT_LOCK_RECHECKS) return
    const t = setTimeout(() => {
      this.gitRecheck.delete(gitDir)
      this.background(this.onGitFlush(gitDir, done + 1), gitDir)
    }, GIT_LOCK_RECHECK_MS)
    ;(t as { unref?: () => void }).unref?.()
    this.gitRecheck.set(gitDir, t)
  }

  /** Coalesce per repo: one git read, then one reload of each affected folder, per debounce window. */
  private queueRepo(root: string, folders: string[]): void {
    if (this.closed || folders.length === 0) return
    const now = Date.now()
    let t = this.repoTicks.get(root)
    if (!t) { t = { folders: new Set(), firstAt: now }; this.repoTicks.set(root, t) }
    for (const f of folders) t.folders.add(f)
    if (t.timer) clearTimeout(t.timer)
    // Trailing debounce, but under constant churn still run at least every 4 windows.
    const wait = Math.max(0, Math.min(this.repoDebounceMs, this.repoDebounceMs * 4 - (now - t.firstAt)))
    const tick = t
    tick.timer = setTimeout(() => {
      if (this.repoTicks.get(root) === tick) this.repoTicks.delete(root)
      this.background(this.runRepoTick(root, tick.folders), root)
    }, wait)
    ;(tick.timer as { unref?: () => void }).unref?.()
  }

  private async runRepoTick(root: string, folders: Set<string>): Promise<void> {
    if (this.closed) return
    this.repo.invalidate(root)
    await Promise.all([...folders].filter((f) => this.subs.isWatched(f)).map((f) => this.reloadAndPush(f)))
  }

  /** Re-read a folder and push it if it changed. True when the listing changed (or the folder went away). */
  private async reloadAndPush(real: string): Promise<boolean> {
    let res: { snap: CachedDir; changed: boolean }
    try {
      res = await this.cache.load(real)
    } catch (e) {
      const code = toFsError(e).code
      if (code === "ENOENT" || code === "ENOTDIR") {
        // The watch on a deleted folder is dead for good: drop everyone now (no grace) so the
        // watcher is closed and a later subscribe to a recreated folder starts a fresh one.
        for (const { sock, path } of this.subs.removeAllFor(real)) this.safeEmit(sock, { type: "fs_gone", path })
        this.cache.forget(real)
        return true
      }
      return false
    }
    if (!res.changed) return false
    for (const { sock, path } of this.subs.subscribersOf(real)) this.safeEmit(sock, dirFrame(path, res.snap))
    return true
  }

  // ── watch lifecycle ───────────────────────────────────────────────────────

  private startWatching(real: string): void {
    if (this.closed) return
    this.cache.pin(real)
    this.watchers.watch(real)
    this.background(this.noteIdentity(real), real)
    this.background(this.trackRepo(real), real)
  }

  private stopWatching(real: string): void {
    this.watchers.unwatch(real)
    this.idOf.delete(real)
    this.cache.unpin(real)
    this.untrackRepo(real)
  }

  private async noteIdentity(real: string): Promise<void> {
    const st = await stat(real).catch(() => null)
    if (st && this.watchers.has(real) && !this.idOf.has(real)) this.idOf.set(real, identityOf(st))
  }

  /** Re-watch when the watch reported the folder itself went away, or the folder's identity changed.
   *  True when it re-watched. */
  private async rewatchIfReplaced(real: string): Promise<boolean> {
    if (!this.watchers.has(real)) return false
    const before = this.idOf.get(real)
    if (before === undefined && !this.watchers.isDead(real)) return false
    const st = await stat(real).catch(() => null)
    if (!st || this.closed || !this.watchers.has(real)) return false // gone: the reload reports it
    const id = identityOf(st)
    if (id === before && !this.watchers.isDead(real)) return false
    this.watchers.unwatch(real)
    this.watchers.watch(real)
    this.idOf.set(real, id)
    return true
  }

  // ── git dir watching (index / HEAD changes don't touch the working folders) ─

  private async trackRepo(real: string): Promise<void> {
    const root = await this.repo.repoFor(real)
    if (!root || this.closed || !this.subs.isWatched(real)) return
    if (!this.repoOfWatched.has(real)) {
      this.repoOfWatched.set(real, root)
      this.repoRefs.set(root, (this.repoRefs.get(root) ?? 0) + 1)
      this.repo.hold(root)
    }
    await this.ensureGitWatch(root)
  }

  /** Watch the repo's git dir once. A failure (e.g. `git init` still running) is retried on a later flush. */
  private async ensureGitWatch(root: string): Promise<void> {
    if (this.closed || this.gitDirOfRoot.has(root) || this.resolvingGitDir.has(root)) return
    this.resolvingGitDir.add(root)
    try {
      await this.startGitWatch(root)
    } finally {
      this.resolvingGitDir.delete(root)
    }
  }

  private async startGitWatch(root: string): Promise<void> {
    const gitDir = await gitAsync(root, ["rev-parse", "--absolute-git-dir"], { timeoutMs: 5_000 }).catch(() => null)
    if (!gitDir || this.closed || !this.repoRefs.has(root) || this.gitDirOfRoot.has(root)) return
    // Fingerprint and identity are taken BEFORE the watch starts (a later change differs from them).
    const [print, st] = await Promise.all([gitStateFingerprint(gitDir), stat(gitDir).catch(() => null)])
    if (this.closed || !this.repoRefs.has(root) || this.gitDirOfRoot.has(root)) return
    this.gitDirOfRoot.set(root, gitDir)
    this.gitDirOf.set(gitDir, root)
    this.gitPrint.set(gitDir, print)
    if (st) this.gitIdOf.set(gitDir, identityOf(st))
    this.gitWatchers.watch(gitDir)
    // Whatever git did before the watch existed (after the listings were annotated) was seen by nobody:
    // re-annotate the repo's watched folders once.
    this.queueRepo(root, this.subs.watchedReals().filter((r) => isUnder(root, r)))
  }

  private untrackRepo(real: string): void {
    const root = this.repoOfWatched.get(real)
    if (!root) return
    this.repoOfWatched.delete(real)
    this.repo.release(root)
    const n = (this.repoRefs.get(root) ?? 1) - 1
    if (n > 0) { this.repoRefs.set(root, n); return }
    this.repoRefs.delete(root)
    this.dropGitWatch(root)
  }

  private dropGitWatch(root: string): void {
    const gitDir = this.gitDirOfRoot.get(root)
    if (gitDir === undefined) return
    this.gitDirOfRoot.delete(root)
    this.gitDirOf.delete(gitDir)
    this.gitPrint.delete(gitDir)
    this.gitIdOf.delete(gitDir)
    const t = this.gitRecheck.get(gitDir)
    if (t) { clearTimeout(t); this.gitRecheck.delete(gitDir) }
    this.gitWatchers.unwatch(gitDir)
  }

  private async gitDirReplaced(gitDir: string): Promise<boolean> {
    const before = this.gitIdOf.get(gitDir)
    if (before === undefined) return false
    const st = await stat(gitDir).catch(() => null)
    return !st || identityOf(st) !== before
  }
}
