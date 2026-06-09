import { readFileSync, writeFileSync, renameSync, chmodSync } from "fs"

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
  chmodSync(tmp, 0o600)
  renameSync(tmp, path)
}
