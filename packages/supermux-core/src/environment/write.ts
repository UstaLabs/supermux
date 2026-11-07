import { chmodSync, copyFileSync, lstatSync, unlinkSync, writeFileSync, type Stats } from "node:fs"
import { randomUUID } from "node:crypto"
import { files } from "@supermux/platform"

export function lstatSafe(path: string): Stats | undefined {
  try {
    return lstatSync(path)
  } catch {
    return undefined
  }
}

/** Write `body` beside `path` and rename into place. Refuses a symlink destination. */
export function writeFileNoFollow(path: string, body: string, mode: number): void {
  const current = lstatSafe(path)
  if (current?.isSymbolicLink()) throw new Error(`refusing to write through symlink ${path}`)
  const tmp = `${path}.mux-${process.pid}-${randomUUID()}.tmp`
  try {
    writeFileSync(tmp, body, { encoding: "utf8", mode })
    chmodSync(tmp, mode)
    files.replaceSync(tmp, path)
  } finally {
    try { unlinkSync(tmp) } catch { /* */ }
  }
}

/** Copy onto `dest`. If dest is a symlink, unlink it first so the canonical target is untouched. */
export function copyFileReplace(src: string, dest: string): void {
  const current = lstatSafe(dest)
  if (current?.isSymbolicLink()) unlinkSync(dest)
  const tmp = `${dest}.mux-${process.pid}-${randomUUID()}.tmp`
  try {
    copyFileSync(src, tmp)
    chmodSync(tmp, 0o600)
    files.replaceSync(tmp, dest)
    chmodSync(dest, 0o600)
  } finally {
    try { unlinkSync(tmp) } catch { /* */ }
  }
}
