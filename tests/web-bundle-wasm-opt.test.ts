// #142 car 2: the web wasm's wasm-opt run gets two flags on top of KGP 2.4.10's own list:
// --generate-global-effects before the optimisation passes and --minimize-rec-groups after them.
// Measured in CI on the compose-lean build (probe run 43531091146): gzip 5,529,689 -> 5,469,716 bytes
// (-59 KB). Gradle cannot run on the GMKtec box, so the build script is pinned here.
import { describe, expect, test } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"

const web = readFileSync(join(import.meta.dirname, "..", "apps", "web", "build.gradle.kts"), "utf8")

describe("apps/web wasm-opt size flags", () => {
  test("every BinaryenExec task of the web app gets the two flags", () => {
    expect(web).toContain("tasks.withType<org.jetbrains.kotlin.gradle.targets.wasm.binaryen.BinaryenExec>().configureEach")
    expect(web).toMatch(/"--generate-global-effects"/)
    expect(web).toMatch(/"--minimize-rec-groups"/)
  })

  test("global effects go in front of KGP's first pass, rec-group minimising after its last", () => {
    expect(web).toMatch(/indexOf\("--type-ssa"\)/)
    expect(web).toMatch(/kgp\.take\(i\) \+ "--generate-global-effects" \+ kgp\.drop\(i\) \+ "--minimize-rec-groups"/)
  })

  test("a KGP upgrade that changes its own list stops the build instead of silently shifting the flags", () => {
    expect(web).toMatch(/re-measure #142/)
  })
})
