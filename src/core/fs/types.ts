export type GitLetter = "M" | "A" | "D" | "R" | "?" | "U" | "*"

export interface FsEntry {
  name: string
  type: "file" | "dir" | "symlink"
  size?: number
  mtime?: number
  ignored: boolean
  git?: GitLetter
  target?: "file" | "dir"
}

export interface DirSnapshot {
  path: string
  real: string
  version: string
  entries: FsEntry[]
  truncated?: { total: number }
}

export interface SearchHit {
  path: string
  name: string
  type: "file" | "dir"
  score: number
  hits: number[]
}

export type FsOp =
  | { op: "rename" | "move"; path: string; to: string }
  | { op: "mkdir" | "touch" | "delete"; path: string }

export type FsFrame =
  | { type: "fs_dir"; path: string; version: string; entries: FsEntry[]; truncated?: { total: number } }
  | { type: "fs_dir"; path: string; version: string; unchanged: true }
  | { type: "fs_gone"; path: string }
  | { type: "fs_err"; path: string; code: string; message: string }
