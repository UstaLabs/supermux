import { randomBytes } from "crypto"
import { stat } from "fs/promises"
import { dirname, sep } from "path"
import { gitAsync } from "../git/exec"
import { DirCache, type CachedDir } from "./dir-cache"
import { DirWatchers } from "./dir-watchers"
import { toFsError } from "./errors"
import { applyOp, readText, statEntry, writeText, type OpOptions } from "./file-ops"
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
}

const toSnapshot = (path: string, c: CachedDir): DirSnapshot => ({
  path, real: c.real, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

const dirFrame = (path: string, c: CachedDir): FsFrame => ({
  type: "fs_dir", path, version: c.version, entries: c.entries, ...(c.truncated ? { truncated: c.truncated } : {}),
})

const isUnder = (root: string, p: string) => p === root || p.startsWith(root.endsWith(sep) ? root : root + sep)

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
  /** Inode of each watched folder when its watch started: a folder deleted and recreated at the same
   *  path between two flushes leaves the old watch dead, so a changed inode means "re-watch". */
  private readonly inoOf = new Map<string, number>()

  constructor(opts: FileSystemServiceOpts<S> = {}) {
    this.bootId = opts.bootId ?? randomBytes(4).toString("hex")
    this.repo = new RepoInfoCache({ noRepoTtlMs: opts.noRepoTtlMs })
    this.cache = new DirCache({ repo: this.repo, bootId: this.bootId, maxEntries: opts.maxEntries, maxDirs: opts.maxDirs })
    this.searches = new SearchIndexes(this.repo)
    this.opOpts = { trashDir: opts.trashDir }
    this.emit = opts.emit ?? (() => {})
    this.watchers = new DirWatchers((real) => void this.onFlush(real), {
      debounceMs: opts.debounceMs,
      onFallback: (dir) => log.warn("fs_watch_fallback", { dir }),
    })
    this.gitWatchers = new DirWatchers((gitDir) => void this.onGitFlush(gitDir), { debounceMs: Math.max(opts.debounceMs ?? 100, 50) * 5 })
    this.subs = new SubscriptionRegistry<S>({
      onFirst: (real) => this.startWatching(real),
      onLast: (real) => this.stopWatching(real),
    }, { graceMs: opts.graceMs, maxPerSocket: opts.maxSubsPerSocket })
  }

  get watcherCount(): number { return this.watchers.size }

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

  /** Hold `path` for `sock` and send its snapshot (or `unchanged` when `since` is current). */
  async subscribe(sock: S, path: string, since?: string): Promise<void> {
    let p = path
    try {
      p = normalizeAbsPath(path)
      const real = await realKey(p)
      if (this.subs.add(sock, p, real) === "limit") {
        this.emit(sock, { type: "fs_err", path, code: "TOO_MANY_SUBS", message: "too many folder subscriptions on this connection" })
        return
      }
      const c = await this.cache.getOrLoad(real)
      this.emit(sock, since !== undefined && since === c.version
        ? { type: "fs_dir", path: p, version: c.version, unchanged: true }
        : dirFrame(p, c))
    } catch (e) {
      const err = toFsError(e)
      if (this.subs.realOf(sock, p) !== undefined) this.subs.remove(sock, p)
      this.emit(sock, { type: "fs_err", path, code: err.code, message: err.message })
    }
  }

  unsubscribe(sock: S, path: string): void {
    try { this.subs.remove(sock, normalizeAbsPath(path)) } catch { /* bad path: nothing to remove */ }
  }

  /** The socket closed: release everything it held. */
  dropSocket(sock: S): void {
    this.subs.dropSocket(sock)
  }

  close(): void {
    this.watchers.closeAll()
    this.gitWatchers.closeAll()
  }

  // ── change propagation ────────────────────────────────────────────────────

  /** Folders our own write/op touched: refresh search indexes and git state, and reload cached ones. */
  private async refresh(dirs: string[]): Promise<void> {
    for (const d of new Set(dirs)) {
      const real = await realKey(d).catch(() => null)
      if (!real) continue
      if (this.cache.get(real)) {
        await this.onFlush(real)
      } else {
        this.searches.invalidateContaining(real)
        const root = this.repo.knownRepoFor(real)
        if (root) this.repo.invalidate(root)
      }
    }
  }

  /** A watched (or cached) folder may have changed: re-read it and its watched ancestors in the repo. */
  private async onFlush(real: string): Promise<void> {
    this.searches.invalidateContaining(real)
    await this.rewatchIfReplaced(real)
    const known = this.repo.knownRepoFor(real)
    if (known) this.repo.invalidate(known)
    await this.reloadAndPush(real)
    // `git init` right here: forget the cached "no repo" answer, watch the new git dir, then re-read
    // so entries get annotated (watch first, so a `git add` right after the push is not missed).
    if (known === null && this.cache.get(real)?.entries.some((e) => e.name === ".git")) {
      this.repo.forgetRootOf(real)
      if (this.subs.isWatched(real)) await this.trackRepo(real)
      await this.reloadAndPush(real)
    }
    const root = this.repo.knownRepoFor(real)
    // A folder that became part of a repo (here, or via an expired "no repo" answer) gets its git dir watched.
    if (root && this.subs.isWatched(real) && (!this.repoOfWatched.has(real) || !this.gitDirOfRoot.has(root))) {
      await this.trackRepo(real)
    }
    if (root) {
      for (const other of this.subs.watchedReals()) {
        if (other !== real && isUnder(other, real) && isUnder(root, other)) await this.reloadAndPush(other)
      }
    }
  }

  private async onGitFlush(gitDir: string): Promise<void> {
    const root = this.gitDirOf.get(gitDir)
    if (!root) return
    this.repo.invalidate(root)
    for (const real of this.subs.watchedReals()) if (isUnder(root, real)) await this.reloadAndPush(real)
  }

  private async reloadAndPush(real: string): Promise<void> {
    let res: { snap: CachedDir; changed: boolean }
    try {
      res = await this.cache.load(real)
    } catch (e) {
      const code = toFsError(e).code
      if (code === "ENOENT" || code === "ENOTDIR") {
        // The watch on a deleted folder is dead for good: drop everyone now (no grace) so the
        // watcher is closed and a later subscribe to a recreated folder starts a fresh one.
        for (const { sock, path } of this.subs.removeAllFor(real)) this.emit(sock, { type: "fs_gone", path })
        this.cache.forget(real)
      }
      return
    }
    if (!res.changed) return
    for (const { sock, path } of this.subs.subscribersOf(real)) this.emit(sock, dirFrame(path, res.snap))
  }

  // ── watch lifecycle ───────────────────────────────────────────────────────

  private startWatching(real: string): void {
    this.cache.pin(real)
    this.watchers.watch(real)
    void this.noteIno(real)
    void this.trackRepo(real)
  }

  private stopWatching(real: string): void {
    this.watchers.unwatch(real)
    this.inoOf.delete(real)
    this.cache.unpin(real)
    this.untrackRepo(real)
  }

  private async noteIno(real: string): Promise<void> {
    const st = await stat(real).catch(() => null)
    if (st && this.watchers.has(real) && !this.inoOf.has(real)) this.inoOf.set(real, st.ino)
  }

  private async rewatchIfReplaced(real: string): Promise<void> {
    const before = this.inoOf.get(real)
    if (before === undefined || !this.watchers.has(real)) return
    const st = await stat(real).catch(() => null)
    if (!st || st.ino === before || !this.watchers.has(real)) return
    this.watchers.unwatch(real)
    this.watchers.watch(real)
    this.inoOf.set(real, st.ino)
  }

  // ── git dir watching (index / HEAD changes don't touch the working folders) ─

  private async trackRepo(real: string): Promise<void> {
    const root = await this.repo.repoFor(real)
    if (!root || !this.subs.isWatched(real)) return
    if (!this.repoOfWatched.has(real)) {
      this.repoOfWatched.set(real, root)
      this.repoRefs.set(root, (this.repoRefs.get(root) ?? 0) + 1)
    }
    await this.ensureGitWatch(root)
  }

  /** Watch the repo's git dir once. A failure (e.g. `git init` still running) is retried on a later flush. */
  private async ensureGitWatch(root: string): Promise<void> {
    if (this.gitDirOfRoot.has(root) || this.resolvingGitDir.has(root)) return
    this.resolvingGitDir.add(root)
    const gitDir = await gitAsync(root, ["rev-parse", "--absolute-git-dir"], { timeoutMs: 5_000 }).catch(() => null)
    this.resolvingGitDir.delete(root)
    if (!gitDir || !this.repoRefs.has(root) || this.gitDirOfRoot.has(root)) return
    this.gitDirOfRoot.set(root, gitDir)
    this.gitDirOf.set(gitDir, root)
    this.gitWatchers.watch(gitDir)
  }

  private untrackRepo(real: string): void {
    const root = this.repoOfWatched.get(real)
    if (!root) return
    this.repoOfWatched.delete(real)
    const n = (this.repoRefs.get(root) ?? 1) - 1
    if (n > 0) { this.repoRefs.set(root, n); return }
    this.repoRefs.delete(root)
    const gitDir = this.gitDirOfRoot.get(root)
    if (gitDir === undefined) return
    this.gitDirOfRoot.delete(root)
    this.gitDirOf.delete(gitDir)
    this.gitWatchers.unwatch(gitDir)
  }
}
