import { existsSync, readFileSync } from "fs"
import { join } from "path"
import { getMuxHome, initMux } from "./init"
import { rebuildIndex } from "./rebuild"

/**
 * The memory part of every session's instructions: the live domain index. An Agent's own memory
 * and `personal/` are named in its header (agent-header.ts); nothing is inlined, because an Agent's
 * instructions are fixed at creation and must not depend on what those files contain.
 */
export function buildMemoryPreamble(): string {
  const home = getMuxHome()
  if (!existsSync(join(home, "agents.md"))) initMux(home)
  rebuildIndex(home)
  return readFileSync(join(home, "agents.md"), "utf8").trim() + "\n"
}
