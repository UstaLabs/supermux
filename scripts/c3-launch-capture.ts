/**
 * Writes the effective launch of every broker agent (worker + PA) as seen through the broker's
 * core-hosts into tests/c3-launch/<name>.json. Run once on the pre-C3 code to record the
 * baseline (`before.json`) that tests/c3-launch-equivalence.test.ts compares against.
 *
 *   bun scripts/c3-launch-capture.ts before
 */
import { mkdtempSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"

const name = process.argv[2] ?? "before"
const root = mkdtempSync(join(tmpdir(), "c3-launch-"))
process.env.HOME = join(root, "home")
process.env.MUX_HOME = join(root, "mux")
process.env.MUX_STATE_DIR = join(root, "mux", "state")
const { scratchLayout, effectiveLaunch, AGENTS } = await import("../tests/c3-launch/effective-launch")
const s = scratchLayout(root)
const out: Record<string, unknown> = {}
for (const agent of AGENTS) for (const role of ["worker", "pa"] as const) out[`${agent}/${role}`] = await effectiveLaunch(agent, role, s)
writeFileSync(join(import.meta.dirname, "..", "tests", "c3-launch", `${name}.json`), JSON.stringify(out, null, 2) + "\n")
console.log(`wrote tests/c3-launch/${name}.json (${Object.keys(out).length} launches)`)
