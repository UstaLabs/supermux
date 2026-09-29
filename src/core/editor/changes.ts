// src/core/editor/changes.ts
// The Changes pane's list-first protocol (spec 2026-09-29-changes-pane-lazy-diff-design.md):
// a cheap per-repo file list with counts and base blob SHAs, and base texts by blob on demand.
// Everything here is async — the broker is one event loop.

export interface ChangedFile {
  path: string
  status: "modified" | "added" | "deleted" | "renamed" | "typechange"
  added: number | null
  removed: number | null
  binary: boolean
  oldPath: string | null
  baseBlob: string | null
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
