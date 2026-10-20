// The keepers of a state directory, for a test that has to hold one still or clean up after it. A keeper that cannot
// answer makes the close of a core fail ("keeper shutdown timed out"); one that is left behind keeps its agent running.
import { existsSync, readdirSync, readFileSync } from "node:fs"
import { join, sep } from "node:path"

/** What each keeper under `dir` recorded in its status.json (`keeperPid`, `agentPid`, `socket`), however deep `dir` holds them. */
export function keepersIn(dir) {
  if (!existsSync(dir)) return []
  const found = []
  for (const path of readdirSync(dir, { recursive: true })) {
    if (!path.split(sep).includes("keepers") || !path.endsWith(`${sep}status.json`)) continue
    try {
      found.push(JSON.parse(readFileSync(join(dir, path), "utf8")))
    } catch {
      // Being written: the next look finds it.
    }
  }
  return found
}

const signal = (pid, name) => {
  try {
    process.kill(pid, name)
  } catch {
    // Gone already.
  }
}

/** Stops the keepers (SIGSTOP). A stopped process answers nothing, so closing the core cannot confirm them gone and fails. */
export const freeze = keepers => keepers.forEach(({ keeperPid }) => signal(keeperPid, "SIGSTOP"))

/** Ends the keepers and their agents, stopped or not. */
export function killAll(keepers) {
  for (const { keeperPid, agentPid } of keepers) {
    signal(keeperPid, "SIGKILL")
    if (agentPid) signal(agentPid, "SIGKILL")
  }
}
