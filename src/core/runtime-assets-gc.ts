import { existsSync, readdirSync, rmSync, statSync } from "fs"
import { join } from "path"

/**
 * Remove every subdirectory inside <stateDir>/runtime-assets whose name does
 * NOT match keepVersion. Files (non-directories) at the runtime-assets root
 * are left alone.
 *
 * Returns the names of the removed directories, sorted alphabetically.
 * Returns [] if the runtime-assets directory does not exist.
 */
export function sweepRuntimeAssets(stateDir: string, keepVersion: string): string[] {
  const runtimeAssetsDir = join(stateDir, "runtime-assets")
  if (!existsSync(runtimeAssetsDir)) return []

  const entries = readdirSync(runtimeAssetsDir)
  const removed: string[] = []

  for (const entry of entries) {
    if (entry === keepVersion) continue
    const fullPath = join(runtimeAssetsDir, entry)
    let isDir: boolean
    try {
      isDir = statSync(fullPath).isDirectory()
    } catch {
      continue
    }
    if (!isDir) continue
    try {
      rmSync(fullPath, { recursive: true, force: true })
      removed.push(entry)
    } catch {
      // Best-effort housekeeping: skip a dir we can't remove (e.g. EACCES);
      // it'll be retried next boot. Don't abort the sweep or lose prior results.
      continue
    }
  }

  return removed.sort()
}
