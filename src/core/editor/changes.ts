// src/core/editor/changes.ts
// The Changes pane's list-first protocol (spec 2026-09-29-changes-pane-lazy-diff-design.md):
// a cheap per-repo file list with counts and base blob SHAs, and base texts by blob on demand.
// Everything here is async — the broker is one event loop.

import { open, stat } from "fs/promises"
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

async function sizeOf(abs: string): Promise<number | null> {
  try { return (await stat(abs)).size } catch { return null }
}

/** Line count + binary sniff for an untracked file (git's own counting: a final line without a
 *  newline still counts). Files over UNTRACKED_READ_LIMIT get null counts, not read. */
async function untrackedEntry(repoAbs: string, path: string): Promise<ChangedFile> {
  const abs = join(repoAbs, path)
  const size = await sizeOf(abs)
  const entry: ChangedFile = { path, status: "added", added: null, removed: 0, binary: false, oldPath: null, baseBlob: null, size }
  if (size === null || size > UNTRACKED_READ_LIMIT) return { ...entry, removed: null }
  try {
    const fh = await open(abs, "r")
    try {
      const buf = await fh.readFile()
      const sniff = buf.subarray(0, Math.min(buf.length, BINARY_SNIFF_BYTES))
      if (sniff.includes(0)) return { ...entry, binary: true, removed: null }
      const text = buf.toString("utf-8")
      let lines = 0
      for (let i = 0; i < text.length; i++) if (text.charCodeAt(i) === 10) lines++
      if (text.length > 0 && !text.endsWith("\n")) lines++
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
    const [raw, numstat, others] = await Promise.all([
      gitAsync(repoAbs, ["diff", "--raw", "-z", "--no-abbrev", "-M", baseSha], { trim: false }),
      gitAsync(repoAbs, ["diff", "--numstat", "-z", "-M", baseSha], { trim: false }),
      gitAsync(repoAbs, ["ls-files", "--others", "--exclude-standard", "-z"], { trim: false }),
    ])
    const tracked = parseRawNumstat(raw, numstat)
    // Sub-repo directories end with '/': they are listed as their own repos.
    const untrackedPaths = others.split("\0").filter((p) => p && !p.endsWith("/"))
    const total = tracked.length + untrackedPaths.length
    const keptTracked = tracked.slice(0, MAX_LIST_FILES)
    const room = MAX_LIST_FILES - keptTracked.length
    const [trackedSized, untracked] = await Promise.all([
      mapLimit(keptTracked, UNTRACKED_CONCURRENCY, async (f) => ({
        ...f,
        size: f.status === "deleted" ? null : await sizeOf(join(repoAbs, f.path)),
      })),
      mapLimit(untrackedPaths.slice(0, Math.max(0, room)), UNTRACKED_CONCURRENCY, (p) => untrackedEntry(repoAbs, p)),
    ])
    const files = [...trackedSized, ...untracked]
    return { repo: relPath, baseSha, files, truncated: total > files.length, total }
  } catch (err) {
    return { repo: relPath, baseSha, files: [], truncated: false, total: 0, error: err instanceof Error ? err.message : String(err) }
  }
}

/** The Changes list for a workdir (spec §2.1). Base resolution is the same as fs/diff's. */
export async function listChanges(
  workdir: string,
  baseCommits: Record<string, string>,
  createdAt?: string,
  baseSpec?: string,
): Promise<ChangesList> {
  const spec = parseBaseSpec(baseSpec)
  const repos = await discoverRepos(workdir)
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
  if (!Number.isFinite(size) || size > limit) return { ok: false, code: "TOO_LARGE", size }
  let text: string
  try {
    text = await gitAsync(repoAbs, ["cat-file", "blob", sha], { trim: false })
  } catch {
    return { ok: false, code: "MISSING" }
  }
  if (text.slice(0, BINARY_SNIFF_BYTES).includes("\0")) return { ok: false, code: "BINARY" }
  return { ok: true, text }
}
