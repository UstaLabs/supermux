// #142 car 1: every Compose module is compiled WITHOUT the Compose compiler's source-information and
// trace-marker strings (and the calls that carry them). On the web client that is 276 KB of the gzip
// ceiling (CI: 9381 -> 9105 KB). `-Psupermux.composeSourceInfo=true` brings both back for a local
// Layout Inspector / composition-tracing session. Gradle cannot run on the GMKtec box, so the build
// script itself is pinned here; CI's stageForBroker proves the effect.
import { describe, expect, test } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"

const root = readFileSync(join(import.meta.dirname, "..", "apps", "build.gradle.kts"), "utf8")

describe("apps/build.gradle.kts compose-lean default", () => {
  test("turns source information and trace markers off for every Compose module", () => {
    expect(root).toContain('plugins.withId("org.jetbrains.kotlin.plugin.compose")')
    expect(root).toMatch(/includeSourceInformation\.set\(\s*composeSourceInfo\s*\)/)
    expect(root).toMatch(/includeTraceMarkers\.set\(\s*composeSourceInfo\s*\)/)
  })

  test("the escape hatch is opt-in: off unless -Psupermux.composeSourceInfo=true", () => {
    expect(root).toMatch(/val composeSourceInfo\s*=\s*providers\.gradleProperty\("supermux\.composeSourceInfo"\)\.orNull\s*==\s*"true"/)
  })
})
