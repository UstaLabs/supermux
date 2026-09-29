// src/core/editor/changes.ts
// The Changes pane's list-first protocol (spec 2026-09-29-changes-pane-lazy-diff-design.md):
// a cheap per-repo file list with counts and base blob SHAs, and base texts by blob on demand.
// Everything here is async — the broker is one event loop.

import { spawn } from "node:child_process"
import { lstat, open } from "fs/promises"
import { join } from "path"
import { gitAsync } from "../git/exec"
import { mapLimit } from "../fs/pool"
import { discoverRepos, parseBaseSpec, resolveSpecBase, REPO_CONCURRENCY } from "./workdir-diff"

export interface ChangedFile {
  path: string
  status: "modified" | "added" | "deleted" | "renamed" | "typechange"
  added: number | null
  removed: number | null
  binary: boolean
  oldPath: string | null
  baseBlob: string | null
  /** Working-copy bytes; null for a deleted file. Filled by listChanges, not by the parser. */
  size?: number | null
}

const ZERO_SHA = /^0+$/

function statusOf(letter: string): ChangedFile["status"] {
  switch (letter) {
    case "A": return "added"
    case "D": return "deleted"
    case "R": return "renamed"
    case "C": return "added"
    case "T": return "typechange"
    default: return "modified"
  }
}

/** Joins `git diff --raw -z --no-abbrev -M` with `git diff --numstat -z -M` (same base) by path. */
export function parseRawNumstat(raw: string, numstat: string): ChangedFile[] {
  const counts = new Map<string, { added: number | null; removed: number | null; binary: boolean }>()
  const ns = numstat.split("\0")
  for (let i = 0; i < ns.length; i++) {
    const rec = ns[i]!
    if (!rec) continue
    const t1 = rec.indexOf("\t")
    const t2 = t1 < 0 ? -1 : rec.indexOf("\t", t1 + 1)
    if (t2 < 0) continue
    const a = rec.slice(0, t1)
    const r = rec.slice(t1 + 1, t2)
    const p = rec.slice(t2 + 1)   // may itself contain tabs
    let path = p
    if (p === "") {           // rename/copy: the paths follow as two NUL-separated fields
      i += 2
      path = ns[i] ?? ""
    }
    const binary = a === "-" && r === "-"
    counts.set(path, { added: binary ? null : Number(a), removed: binary ? null : Number(r), binary })
  }

  const out: ChangedFile[] = []
  const rw = raw.split("\0")
  for (let i = 0; i < rw.length; i++) {
    const header = rw[i]!
    if (!header.startsWith(":")) continue
    const parts = header.slice(1).split(" ")
    const srcSha = parts[2] ?? ""
    const letter = (parts[4] ?? "M").charAt(0)
    let oldPath: string | null = null
    let path = rw[++i] ?? ""
    if (letter === "R" || letter === "C") {   // C doesn't occur with -M; consumed only to stay aligned with the NUL fields
      oldPath = path
      path = rw[++i] ?? ""
    }
    const c = counts.get(path)
    out.push({
      path,
      status: statusOf(letter),
      added: c?.added ?? null,
      removed: c?.removed ?? null,
      binary: c?.binary ?? false,
      oldPath: letter === "R" ? oldPath : null,
      baseBlob: letter !== "C" && srcSha && !ZERO_SHA.test(srcSha) ? srcSha : null,
    })
  }
  return out
}

export const MAX_LIST_FILES = 3000
/** Untracked files at most this big are read to count lines and sniff binary. */
const UNTRACKED_READ_LIMIT = 1024 * 1024
const BINARY_SNIFF_BYTES = 8 * 1024
const UNTRACKED_CONCURRENCY = 16

export interface RepoChanges {
  repo: string
  baseSha: string
  files: ChangedFile[]
  truncated: boolean
  total: number
  error?: string
}

export interface ChangesList { repos: RepoChanges[] }

/** lstat, never stat: a symlink is measured (and later diffed) as its target string, not followed. */
async function sizeOf(abs: string): Promise<number | null> {
  try { return (await lstat(abs)).size } catch { return null }
}

const LS_FILES_TIMEOUT_MS = 30_000

/** Streams `git ls-files --others` so a huge untracked tree (an un-ignored node_modules) can't
 *  overflow a buffer: only the first `keep` paths are held, the rest are just counted. Sub-repo
 *  directories (trailing '/') are skipped. On timeout/error the partial result is returned. */
function listUntracked(repoAbs: string, keep: number): Promise<{ paths: string[]; total: number; error?: string }> {
  return new Promise((resolve) => {
    const paths: string[] = []
    let total = 0
    let rest = ""
    let error: string | undefined
    let done = false
    const child = spawn("git", ["ls-files", "--others", "--exclude-standard", "-z"], { cwd: repoAbs, stdio: ["ignore", "pipe", "ignore"] })
    const take = (p: string) => {
      if (!p || p.endsWith("/")) return
      total++
      if (paths.length < keep) paths.push(p)
    }
    const finish = () => {
      if (done) return
      done = true
      clearTimeout(timer)
      resolve({ paths, total, ...(error ? { error } : {}) })
    }
    const timer = setTimeout(() => { error = "git ls-files timed out"; child.kill("SIGKILL") }, LS_FILES_TIMEOUT_MS)
    child.stdout.setEncoding("utf-8")
    child.stdout.on("data", (chunk: string) => {
      const parts = (rest + chunk).split("\0")
      rest = parts.pop() ?? ""
      for (const p of parts) take(p)
    })
    child.on("error", (e) => { error = e.message; finish() })
    child.on("close", (code) => {
      take(rest)
      if (code !== 0 && !error) error = `git ls-files exited with ${code}`
      finish()
    })
  })
}

/** Line count + binary sniff for an untracked file (git's own counting: a final line without a
 *  newline still counts). Symlinks and non-regular files are never opened; files over
 *  UNTRACKED_READ_LIMIT get null counts. */
async function untrackedEntry(repoAbs: string, path: string): Promise<ChangedFile> {
  const abs = join(repoAbs, path)
  const entry: ChangedFile = { path, status: "added", added: null, removed: 0, binary: false, oldPath: null, baseBlob: null, size: null }
  let st
  try { st = await lstat(abs) } catch { return { ...entry, removed: null } }
  entry.size = st.size
  // git diffs a symlink as its target string: one line.
  if (st.isSymbolicLink()) return { ...entry, added: 1 }
  if (!st.isFile() || st.size > UNTRACKED_READ_LIMIT) return { ...entry, removed: null }
  try {
    const fh = await open(abs, "r")
    try {
      // One byte more than we expect: filling the whole buffer means the file grew past its lstat size.
      const buf = Buffer.alloc(Math.min(st.size, UNTRACKED_READ_LIMIT) + 1)
      const { bytesRead } = await fh.read(buf, 0, buf.length, 0)
      if (bytesRead >= buf.length) return { ...entry, removed: null }
      const data = buf.subarray(0, bytesRead)
      if (data.subarray(0, BINARY_SNIFF_BYTES).includes(0)) return { ...entry, binary: true, removed: null }
      let lines = 0
      for (let i = data.indexOf(10); i >= 0; i = data.indexOf(10, i + 1)) lines++
      if (bytesRead > 0 && data[bytesRead - 1] !== 10) lines++
      return { ...entry, added: lines }
    } finally {
      await fh.close()
    }
  } catch {
    return { ...entry, removed: null }
  }
}

async function repoChanges(repoAbs: string, relPath: string, baseSha: string): Promise<RepoChanges> {
  try {
    const [raw, numstat] = await Promise.all([
      gitAsync(repoAbs, ["diff", "--raw", "-z", "--no-abbrev", "-M", baseSha], { trim: false }),
      gitAsync(repoAbs, ["diff", "--numstat", "-z", "-M", baseSha], { trim: false }),
    ])
    const tracked = parseRawNumstat(raw, numstat)
    const keptTracked = tracked.slice(0, MAX_LIST_FILES)
    const room = Math.max(0, MAX_LIST_FILES - keptTracked.length)
    const others = await listUntracked(repoAbs, room)
    const total = tracked.length + others.total
    const [trackedSized, untracked] = await Promise.all([
      mapLimit(keptTracked, UNTRACKED_CONCURRENCY, async (f) => ({
        ...f,
        size: f.status === "deleted" ? null : await sizeOf(join(repoAbs, f.path)),
      })),
      mapLimit(others.paths, UNTRACKED_CONCURRENCY, (p) => untrackedEntry(repoAbs, p)),
    ])
    const files = [...trackedSized, ...untracked]
    return { repo: relPath, baseSha, files, truncated: total > files.length, total, ...(others.error ? { error: others.error } : {}) }
  } catch (err) {
    return { repo: relPath, baseSha, files: [], truncated: false, total: 0, error: err instanceof Error ? err.message : String(err) }
  }
}

export type RepoRef = { relPath: string; absPath: string }

export const REPO_CACHE_TTL_MS = 60_000

/** Workdir -> repo list with a TTL and in-flight dedup. `fresh` always rescans (and stores). */
export function createRepoCache(
  scan: (workdir: string) => Promise<RepoRef[]>,
  now: () => number = Date.now,
  ttlMs = REPO_CACHE_TTL_MS,
) {
  const done = new Map<string, { at: number; repos: RepoRef[] }>()
  const inflight = new Map<string, Promise<RepoRef[]>>()
  return async (workdir: string, opts?: { fresh?: boolean }): Promise<RepoRef[]> => {
    if (!opts?.fresh) {
      const hit = done.get(workdir)
      if (hit && now() - hit.at < ttlMs) return hit.repos
      const pending = inflight.get(workdir)
      if (pending) return pending
    }
    const p = scan(workdir).then((repos) => {
      const t = now()
      for (const [k, v] of done) if (t - v.at >= ttlMs) done.delete(k)
      done.set(workdir, { at: t, repos })
      return repos
    }).finally(() => { if (inflight.get(workdir) === p) inflight.delete(workdir) })
    inflight.set(workdir, p)
    return p
  }
}

export const discoverReposCached = createRepoCache(discoverRepos)

/** The Changes list for a workdir (spec §2.1). Base resolution is the same as fs/diff's. */
export async function listChanges(
  workdir: string,
  baseCommits: Record<string, string>,
  createdAt?: string,
  baseSpec?: string,
): Promise<ChangesList> {
  const spec = parseBaseSpec(baseSpec)
  const repos = await discoverReposCached(workdir, { fresh: true })
  const perRepo = await mapLimit(repos, REPO_CONCURRENCY, async (r) => {
    const baseSha = await resolveSpecBase(r.absPath, spec, baseCommits[r.relPath], createdAt)
    return repoChanges(r.absPath, r.relPath, baseSha)
  })
  return { repos: perRepo.filter((r) => r.files.length > 0 || r.error) }
}

export const BLOB_LIMIT = 1024 * 1024
export const BLOB_FORCE_LIMIT = 8 * 1024 * 1024

export type BlobRead =
  | { ok: true; text: string }
  | { ok: false; code: "BAD_SHA" | "MISSING" | "BINARY" }
  | { ok: false; code: "TOO_LARGE"; size: number }

/** One base-side blob as text (spec §2.2): size first, then the content, then a binary sniff. */
export async function readBaseBlob(repoAbs: string, sha: string, opts?: { force?: boolean }): Promise<BlobRead> {
  if (!/^[0-9a-f]{40}$/.test(sha)) return { ok: false, code: "BAD_SHA" }
  let size: number
  try {
    size = Number(await gitAsync(repoAbs, ["cat-file", "-s", sha], { timeoutMs: 5000 }))
  } catch {
    return { ok: false, code: "MISSING" }
  }
  const limit = opts?.force ? BLOB_FORCE_LIMIT : BLOB_LIMIT
  if (!Number.isFinite(size)) return { ok: false, code: "MISSING" }
  if (size > limit) return { ok: false, code: "TOO_LARGE", size }
  let text: string
  try {
    text = await gitAsync(repoAbs, ["cat-file", "blob", sha], { trim: false })
  } catch {
    return { ok: false, code: "MISSING" }
  }
  if (text.slice(0, BINARY_SNIFF_BYTES).includes("\0")) return { ok: false, code: "BINARY" }
  return { ok: true, text }
}
