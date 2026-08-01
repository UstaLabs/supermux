import { test, expect } from "bun:test"
import { readdirSync, readFileSync, statSync } from "fs"
import { join } from "path"

// Guardrail: no wiring-level legacy identifier may survive outside docs + the
// allowlisted files below. The brand name lives in src/shared/brand.ts; wiring
// uses the neutral "mux" / MUX_ token. This is the rename's safety net.
const LEGACY = /(claudemux|agentmux|AGENTMUX_)/
const ALLOW = [
  "tests/no-legacy-names.test.ts", // this file
  "scripts/migrate-to-mux.ts", // references old names in order to migrate them
]

function walk(dir: string, acc: string[] = []): string[] {
  for (const e of readdirSync(dir)) {
    if (e === "node_modules" || e === ".git" || e === "static") continue
    const p = join(dir, e)
    statSync(p).isDirectory() ? walk(p, acc) : acc.push(p)
  }
  return acc
}

test("no legacy claudemux/agentmux identifiers survive in src/ or scripts/", () => {
  const roots = ["src", "scripts"]
  const files = roots
    .flatMap((r) => walk(r))
    // Test files are exempt: the legitimate way to pin the rename is an
    // assertion that the old name is ABSENT (`expect(x).not.toContain(
    // "agentmux-shim")`), and a substring grep cannot tell that apart from a
    // real leak. Wiring that a test merely *references* still lives in a
    // non-test file, which this guard does scan.
    .filter((f) => !/\.(test|spec)\.ts$/.test(f))
    .filter((f) => /\.(ts|vue|json|html)$/.test(f) && !ALLOW.some((a) => f.endsWith(a)))
  const offenders: string[] = []
  for (const f of files) {
    const txt = readFileSync(f, "utf8")
    if (LEGACY.test(txt)) {
      const lineNums = txt
        .split("\n")
        .map((l, i) => [i + 1, l] as const)
        .filter(([, l]) => LEGACY.test(l))
        .map(([n]) => n)
      offenders.push(`${f}: ${lineNums.join(",")}`)
    }
  }
  expect(offenders).toEqual([])
})
