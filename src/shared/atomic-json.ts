import { readFileSync, writeFileSync, renameSync, chmodSync } from "fs"
import { files } from "@supermux/platform"

export function readJsonOr<T>(path: string, fallback: T): T {
  try {
    return JSON.parse(readFileSync(path, "utf8")) as T
  } catch {
    return fallback
  }
}

export function writeJsonAtomic(path: string, value: unknown): void {
  const tmp = `${path}.tmp.${process.pid}`
  writeFileSync(tmp, JSON.stringify(value, null, 2))
  files.makePrivate(tmp)
  renameSync(tmp, path)
}
