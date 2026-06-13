// src/core/forge/cloned.ts
import { execFileSync } from "child_process"
import { existsSync, readdirSync, statSync, rmSync } from "fs"
import { join, resolve, relative, isAbsolute } from "path"

export interface ClonedRepo {
  path: string; host: string; owner: string; name: string; fullName: string; sizeBytes: number
}

export function isInsideRoot(root: string, target: string): boolean {
  const rel = relative(resolve(root), resolve(target))
  return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel)
}

function dirSize(dir: string): number {
  try { return parseInt(execFileSync("du", ["-sk", dir], { encoding: "utf8" }).split(/\s+/)[0] ?? "0", 10) * 1024 }
  catch { return 0 }
}

export function scanCloned(root: string): ClonedRepo[] {
  if (!existsSync(root)) return []
  const out: ClonedRepo[] = []
  const dirs = (p: string) => { try { return readdirSync(p).filter((d) => { try { return statSync(join(p, d)).isDirectory() } catch { return false } }) } catch { return [] } }
  for (const host of dirs(root)) {
    for (const owner of dirs(join(root, host))) {
      // 3-level: root/host/owner/name
      for (const name of dirs(join(root, host, owner))) {
        const path = join(root, host, owner, name)
        if (!existsSync(join(path, ".git"))) continue
        out.push({ path, host, owner, name, fullName: `${owner}/${name}`, sizeBytes: dirSize(path) })
      }
      // 2-level: root/owner/name  (host="" for local repos)
      const path2 = join(root, host, owner)
      if (existsSync(join(path2, ".git"))) {
        out.push({ path: path2, host: "", owner: host, name: owner, fullName: `${host}/${owner}`, sizeBytes: dirSize(path2) })
      }
    }
  }
  return out
}

export function removeCloned(root: string, path: string): void {
  if (!isInsideRoot(root, path)) throw new Error("refusing to delete outside the projects root")
  rmSync(path, { recursive: true, force: true })
}
