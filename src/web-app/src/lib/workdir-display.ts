import { stripFilePathRefSuffix } from "./file-path-ref"

export interface WorkdirDisplay {
  key: string
  label: string
}

export function workdirDisplay(workdir: string, homeDir?: string | null): WorkdirDisplay {
  const key = normalizeWorkdirKey(workdir, homeDir)
  const home = homeDir ?? inferHomeDir(key)
  return { key, label: labelForWorkdirKey(key, home) }
}

/** Map an agent-mentioned path to a workdir-relative path for the editor API. */
export function toWorkdirRelativePath(
  path: string,
  workdir: string,
  homeDir?: string | null,
): string | null {
  const root = normalizeWorkdirKey(workdir, homeDir)
  const trimmed = stripFilePathRefSuffix(path.trim())

  if (!trimmed.startsWith("/") && !trimmed.startsWith("~/") && trimmed !== "~") {
    return trimmed.replace(/^\.\//, "")
  }

  const abs = normalizeWorkdirKey(trimmed, homeDir)
  if (abs === root) return ""
  return abs.startsWith(`${root}/`) ? abs.slice(root.length + 1) : null
}

export function normalizeWorkdirKey(workdir: string, homeDir?: string | null): string {
  const trimmed = workdir.trim()
  const home = normalizeHomeDir(homeDir ?? inferHomeDir(trimmed))
  const expanded = home && trimmed === "~"
    ? home
    : home && trimmed.startsWith("~/")
      ? `${home}/${trimmed.slice(2)}`
      : expandHomePrefixedTilde(trimmed, home)
  const normalized = expanded.replace(/\/+/g, "/")
  return normalized.length > 1 ? normalized.replace(/\/+$/, "") : normalized
}

function expandHomePrefixedTilde(workdir: string, homeDir?: string | null): string {
  if (!homeDir) return workdir
  const homeTilde = `${homeDir}/~`
  if (workdir === homeTilde) return homeDir
  if (workdir.startsWith(`${homeTilde}/`)) {
    return `${homeDir}/${workdir.slice(homeTilde.length + 1)}`
  }
  return workdir
}

function normalizeHomeDir(homeDir?: string | null): string | null {
  if (!homeDir) return null
  const normalized = homeDir.replace(/\/+/g, "/")
  return normalized.length > 1 ? normalized.replace(/\/+$/, "") : normalized
}

/** Best-effort home dir when the server hasn't sent one yet. */
export function inferHomeDir(workdir?: string): string | null {
  const probe = workdir ?? ""
  const m = probe.match(/^(\/(?:home|Users)\/[^/]+)/)
  return m?.[1] ?? null
}

function labelForWorkdirKey(key: string, homeDir?: string | null): string {
  if (homeDir && (key === homeDir || key.startsWith(homeDir + "/"))) {
    const rest = key.slice(homeDir.length)
    return rest ? `~${rest}` : "~"
  }
  return shortenAbsolute(key)
}

function shortenAbsolute(path: string): string {
  const parts = path.split("/").filter(Boolean)
  if (parts.length <= 2) return path
  return ".../" + parts.slice(-2).join("/")
}
