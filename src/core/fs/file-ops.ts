import { readFile, writeFile, rename, mkdir, open, lstat, stat, realpath, rm } from "fs/promises"
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
  try {
    await mkdir(dirname(path), { recursive: true })
    const tmp = `${path}.tmp`
    const buf = Buffer.from(text, "utf-8")
    await writeFile(tmp, buf)
    await rename(tmp, path)
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
        await moveToTrash(op.path, opts)
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

async function moveToTrash(path: string, opts: OpOptions): Promise<void> {
  const platform = opts.platform ?? process.platform
  const trash = opts.trashDir ?? defaultTrashDir(platform)
  const filesDir = platform === "darwin" ? trash : join(trash, "files")
  await mkdir(filesDir, { recursive: true })
  const name = await uniqueName(filesDir, basename(path))
  if (platform !== "darwin") {
    const infoDir = join(trash, "info")
    await mkdir(infoDir, { recursive: true })
    const date = new Date().toISOString().slice(0, 19)
    await writeFile(join(infoDir, `${name}.trashinfo`), `[Trash Info]\nPath=${encodeURI(path)}\nDeletionDate=${date}\n`)
  }
  try {
    await rename(path, join(filesDir, name))
  } catch (e) {
    if ((e as { code?: string }).code === "EXDEV") {
      // The trash is on another filesystem; the app already confirmed, so delete for real.
      await rm(path, { recursive: true, force: true })
      if (platform !== "darwin") await rm(join(trash, "info", `${name}.trashinfo`), { force: true })
      return
    }
    throw e
  }
}

/** Keep our own TOO_LARGE/BINARY/EISDIR codes; map node errors. */
function toFsErrorKeep(e: unknown): FsError {
  return e instanceof FsError ? e : toFsError(e)
}
