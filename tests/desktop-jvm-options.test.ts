import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"

// #29 step 9: the JDK's ProcessBuilder on Windows accepts an "ambiguous" command line by default (the legacy mode: a
// program path with spaces taken as a program plus arguments). The desktop app turns that off, packaged and in dev runs.
const gradle = readFileSync(resolve(import.meta.dir, "..", "apps", "desktop", "build.gradle.kts"), "utf8").replaceAll("\r\n", "\n")

test("the desktop app's JVM refuses ambiguous Windows commands, packaged and in dev runs", () => {
  expect(gradle).toMatch(/val windowsJvmArgs = if \(windowsBuildHost\) \{\s*listOf\("-Djdk\.lang\.Process\.allowAmbiguousCommands=false"\)/)
  const app = gradle.slice(gradle.indexOf("compose.desktop {"))
  expect(app.slice(0, app.indexOf("nativeDistributions"))).toContain("jvmArgs += macJvmArgs + linuxJvmArgs + windowsJvmArgs")
  expect(gradle.match(/jvmArgs\(macJvmArgs \+ linuxJvmArgs \+ windowsJvmArgs\)/g)?.length).toBe(2)
})
