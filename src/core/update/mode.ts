// Detect how this broker was installed, and who owns its updates.
import { existsSync } from "fs"
import { IS_COMPILED } from "../../shared/build-info"
import type { UpdateMode } from "./checker"

/** How the broker was installed: a compiled binary, a Docker container, or a source checkout. */
export type InstallMode = "binary" | "source" | "docker"

export function detectInstallMode(): InstallMode {
  if (existsSync("/.dockerenv")) return "docker"
  if (IS_COMPILED) return "binary"
  return "source"
}

/**
 * Who updates this broker. A broker the desktop app manages (MUX_MANAGED_BY=desktop) is updated
 * WITH the app — it must never swap its own binary, or the next app update silently downgrades it.
 */
export function detectUpdateMode(): UpdateMode {
  if (process.env.MUX_MANAGED_BY === "desktop") return "managed"
  return detectInstallMode()
}
