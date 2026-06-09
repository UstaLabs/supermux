import { realpathSync, statSync } from "fs"
import { resolve } from "path"
import { home as defaultHome } from "../../shared/home"

export function normalizeWorkdirInput(input: string, homeDir = defaultHome()): string {
  const trimmed = input.trim()
  const home = trimTrailingSlash(resolve(homeDir))
  const expanded = trimmed === "~"
    ? home
    : trimmed.startsWith("~/")
      ? resolve(home, trimmed.slice(2))
      : expandHomePrefixedTilde(trimmed, home)
  const resolved = resolve(expanded)
  return trimTrailingSlash(resolved)
}

function expandHomePrefixedTilde(input: string, homeDir: string): string {
  const homeTilde = `${homeDir}/~`
  if (input === homeTilde) return homeDir
  if (input.startsWith(`${homeTilde}/`)) {
    return resolve(homeDir, input.slice(homeTilde.length + 1))
  }
  return input
}

function trimTrailingSlash(path: string): string {
  return path.length > 1 ? path.replace(/\/+$/, "") : path
}

export function normalizeExistingWorkdir(input: string, homeDir = defaultHome()): string {
  const normalized = normalizeWorkdirInput(input, homeDir)
  let st
  try {
    st = statSync(normalized)
  } catch {
    throw new Error(`working directory does not exist: ${normalized}`)
  }
  if (!st.isDirectory()) {
    throw new Error(`working directory is not a directory: ${normalized}`)
  }
  return realpathSync.native(normalized)
}

export function uniqueKnownWorkdirs(workdirs: string[], homeDir = defaultHome()): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const workdir of workdirs) {
    const normalized = normalizeWorkdirInput(workdir, homeDir)
    const key = normalized
    if (seen.has(key)) continue
    seen.add(key)
    result.push(normalized)
  }
  return result.sort((a, b) => a.localeCompare(b))
}
