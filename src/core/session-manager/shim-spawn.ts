// THE single source of truth for "how do you start the shim". Source mode:
// `bun run <abs path to src/shim/index.ts>` (exactly what spawn-helper and
// trust.ts hardcoded before). Compiled mode: the running binary itself with
// the `shim` subcommand — process.execPath is the real on-disk binary path,
// safe to hand to child processes and to write into MCP config files.
import { resolve as resolvePath } from "path"
import { IS_COMPILED } from "../../shared/build-info"

const SHIM_ENTRY = resolvePath(import.meta.dirname, "..", "..", "shim", "index.ts")

export function shimSpawnSpec(): { shimCommand: string; shimArgs: string[] } {
  if (IS_COMPILED) return { shimCommand: process.execPath, shimArgs: ["shim"] }
  return { shimCommand: "bun", shimArgs: ["run", SHIM_ENTRY] }
}
