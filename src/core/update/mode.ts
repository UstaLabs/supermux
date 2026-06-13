// Detect whether this process is running as a compiled binary, in a Docker
// container, or from a source checkout. Task 5 reuses this export.
import { existsSync } from "fs"
import { IS_COMPILED } from "../../shared/build-info"
import type { UpdateMode } from "./checker"

export function detectUpdateMode(): UpdateMode {
  if (existsSync("/.dockerenv")) return "docker"
  if (IS_COMPILED) return "binary"
  return "source"
}
