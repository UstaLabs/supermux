// src/core/forge/cloned.ts
import { execFileSync } from "child_process"
import { existsSync, readdirSync, statSync, rmSync } from "fs"
import { join, resolve, relative, isAbsolute } from "path"
import { ForgeError } from "./types"

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
  const isRepo = (p: string) => { const g = join(p, ".git"); try { return existsSync(g) && statSync(g).isDirectory() } catch { return false } }
  for (const host of dirs(root)) {
    if (host === "local") {
      for (const name of dirs(join(root, host))) {
        const path = join(root, host, name)
        if (isRepo(path)) out.push({ path, host: "local", owner: "local", name, fullName: name, sizeBytes: dirSize(path) })
      }
      continue
    }
    for (const owner of dirs(join(root, host)))
      for (const name of dirs(join(root, host, owner))) {
        const path = join(root, host, owner, name)
        if (isRepo(path)) out.push({ path, host, owner, name, fullName: `${owner}/${name}`, sizeBytes: dirSize(path) })
      }
  }
  return out
}

export function removeCloned(root: string, path: string): void {
  if (!isInsideRoot(root, path)) throw new ForgeError("not_found", "path is outside the projects root")
  const g = join(path, ".git")
  if (!existsSync(g) || !statSync(g).isDirectory()) throw new ForgeError("not_found", "not a cloned repository")
  rmSync(path, { recursive: true, force: true })
}
