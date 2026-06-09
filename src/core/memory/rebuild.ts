import { writeFileSync } from "fs"
import { join } from "path"
import { buildAgentsMd } from "./index-builder"

export function rebuildIndex(root: string): void {
  const agentsMd = buildAgentsMd(root)
  writeFileSync(join(root, "agents.md"), agentsMd, { encoding: "utf8" })
}
