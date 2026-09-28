import { readFile, writeFile, rename, mkdir, open, lstat, stat, realpath, rm, unlink } from "fs/promises"
import { randomBytes } from "crypto"
import { homedir } from "os"
import { basename, dirname, join } from "path"
import { FsError, toFsError } from "./errors"
import type { FsEntry, FsOp } from "./types"

export const MAX_READ_BYTES = 1024 * 1024
const BINARY_SCAN_BYTES = 8 * 1024

export async function readText(path: string): Promise<string> {
  let buf: Buffer
  try {
    const s = await stat(path)
    if (s.isDirectory()) throw new FsError("EISDIR", `is a directory: ${path}`)
    if (!s.isFile()) throw new FsError("EINVAL", `not a regular file: ${path}`)
    if (s.size > MAX_READ_BYTES) throw new FsError("TOO_LARGE", `File too large (${s.size} bytes); limit is 1MB`)
    buf = await readFile(path)
  } catch (e) {
    throw toFsErrorKeep(e)
  }
  const n = Math.min(buf.length, BINARY_SCAN_BYTES)
  for (let i = 0; i < n; i++) if (buf[i] === 0) throw new FsError("BINARY", "File appears to be binary (null byte detected)")
  return buf.toString("utf-8")
}

export async function writeText(path: string, text: string): Promise<{ size: number; mtime: number }> {
  const tmp = `${path}.${randomBytes(6).toString("hex")}.tmp`
  try {
    await mkdir(dirname(path), { recursive: true })
    const buf = Buffer.from(text, "utf-8")
    try {
      await writeFile(tmp, buf)
      await rename(tmp, path)
    } catch (e) {
      await unlink(tmp).catch(() => {})
      throw e
    }
    const s = await stat(path)
    return { size: buf.length, mtime: Math.round(s.mtimeMs) }
  } catch (e) {
    throw toFsError(e)
  }
}

export async function statEntry(path: string): Promise<FsEntry & { real: string }> {
  try {
    const l = await lstat(path)
    const real = await realpath(path).catch(() => path)
    const name = basename(path)
    if (l.isSymbolicLink()) {
      const s = await stat(path).catch(() => null)
      if (!s) return { name, type: "symlink", ignored: false, real }
      return s.isDirectory()
        ? { name, type: "symlink", target: "dir", mtime: Math.round(s.mtimeMs), ignored: false, real }
        : { name, type: "symlink", target: "file", size: s.size, mtime: Math.round(s.mtimeMs), ignored: false, real }
    }
    if (l.isDirectory()) return { name, type: "dir", mtime: Math.round(l.mtimeMs), ignored: false, real }
    return { name, type: "file", size: l.size, mtime: Math.round(l.mtimeMs), ignored: false, real }
  } catch (e) {
    throw toFsError(e)
  }
}

async function exists(p: string): Promise<boolean> {
  try {
    await lstat(p)
    return true
  } catch {
    return false
  }
}

export interface OpOptions {
  trashDir?: string
  platform?: NodeJS.Platform
  /** The user's home folder, which delete refuses (default `os.homedir()`; tests override it). */
  homeDir?: string
}

/** `/`, a mount root (its device differs from its parent's) and the home folder are never deleted. */
async function refuseProtected(path: string, opts: OpOptions): Promise<void> {
  const parent = dirname(path)
  if (parent === path) throw new FsError("EACCES", "refusing to delete the root folder")
  const home = opts.homeDir ?? homedir()
  const real = await realpath(path).catch(() => path)
  const homeReal = await realpath(home).catch(() => home)
  if (path === home || real === homeReal) throw new FsError("EACCES", `refusing to delete the home folder: ${path}`)
  // lstat: a symlink pointing at a mount root is an ordinary entry in its parent and may go.
  const [self, up] = await Promise.all([lstat(path), stat(parent)])
  if (!self.isSymbolicLink() && self.dev !== up.dev) throw new FsError("EACCES", `refusing to delete a mount point: ${path}`)
}

export async function applyOp(op: FsOp, opts: OpOptions = {}): Promise<void> {
  try {
    switch (op.op) {
      case "rename":
      case "move":
        if (await exists(op.to)) throw new FsError("EEXIST", `already exists: ${op.to}`)
        await rename(op.path, op.to)
        return
      case "mkdir":
        await mkdir(op.path, { recursive: true })
        return
      case "touch": {
        const fh = await open(op.path, "wx") // fails with EEXIST if present
        await fh.close()
        return
      }
      case "delete":
        await lstat(op.path) // ENOENT early
        await refuseProtected(op.path, opts)
        // A real delete happens only when the caller explicitly asked for it (e.g. after EXDEV).
        if (op.permanent === true) await rm(op.path, { recursive: true, force: false })
        else await moveToTrash(op.path, opts)
        return
    }
  } catch (e) {
    throw toFsError(e)
  }
}

function defaultTrashDir(platform: NodeJS.Platform): string {
  return platform === "darwin" ? join(homedir(), ".Trash") : join(homedir(), ".local", "share", "Trash")
}

async function uniqueName(dir: string, name: string): Promise<string> {
  if (!(await exists(join(dir, name)))) return name
  for (let i = 2; ; i++) {
    const candidate = `${name}.${i}`
    if (!(await exists(join(dir, candidate)))) return candidate
  }
}

/** RFC 3986 percent-encoding for the freedesktop trashinfo Path= field. */
function encodeTrashPath(path: string): string {
  return encodeURI(path).replace(/#/g, "%23").replace(/\?/g, "%3F")
}

/** Local-time YYYY-MM-DDThh:mm:ss (no timezone), per the freedesktop trash spec. */
function localDeletionDate(): string {
  const d = new Date()
  const pad = (n: number) => String(n).padStart(2, "0")
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

async function moveToTrash(path: string, opts: OpOptions): Promise<void> {
  const platform = opts.platform ?? process.platform
  const trash = opts.trashDir ?? defaultTrashDir(platform)
  const filesDir = platform === "darwin" ? trash : join(trash, "files")
  await mkdir(filesDir, { recursive: true })
  const name = await uniqueName(filesDir, basename(path))
  const infoPath = join(trash, "info", `${name}.trashinfo`)
  if (platform !== "darwin") {
    const infoDir = join(trash, "info")
    await mkdir(infoDir, { recursive: true })
    await writeFile(infoPath, `[Trash Info]\nPath=${encodeTrashPath(path)}\nDeletionDate=${localDeletionDate()}\n`)
  }
  try {
    await rename(path, join(filesDir, name))
  } catch (e) {
    // Any failure means the file never made it to the trash; remove the
    // now-untracked info record so the spec's "info first" ordering never
    // leaves a dangling .trashinfo behind.
    if (platform !== "darwin") await unlink(infoPath).catch(() => {})
    // The trash is on another filesystem. Never fall back to a real delete here: the user was
    // promised "moved to the trash". The app asks, then sends `permanent: true`.
    if ((e as { code?: string }).code === "EXDEV") {
      throw new FsError("EXDEV", "Can't move to the trash across filesystems; delete permanently instead?")
    }
    throw e
  }
}

/** Keep our own TOO_LARGE/BINARY/EISDIR codes; map node errors. */
function toFsErrorKeep(e: unknown): FsError {
  return e instanceof FsError ? e : toFsError(e)
}
