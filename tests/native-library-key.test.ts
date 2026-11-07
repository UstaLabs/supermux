// One native-library key, two libraries (#29 step 10). terminal-core and editor-syntax each package a JNI library per
// `<os>-<arch>` and extract it into the user's cache folder. Neither library is on Compose, so Skiko's hostOs is not
// theirs to use, and neither depends on the other: each carries the same two functions, word for word, between the
// markers below. terminal-core's NativeLibraryKeyTest tests them once; this test keeps the copies identical and the
// builds' target names inside what the key can name.
import { expect, test } from "bun:test"
import { readFileSync } from "fs"
import { join } from "path"

const root = join(import.meta.dir, "..")
const read = (path: string) => readFileSync(join(root, path), "utf8")

const TERMINAL_CORE = "apps/terminal-core/src/jvmMain/kotlin/dev/supermux/terminal/JvmNativeLibrary.kt"
const EDITOR_SYNTAX = "apps/editor-syntax/src/jvmAndAndroidMain/kotlin/dev/supermux/editor/syntax/SesNativeLoader.kt"
const BUILDS = ["apps/terminal-core/build.gradle.kts", "apps/editor-syntax/build.gradle.kts"]
const BEGIN = "// native-library-key: begin"
const END = "// native-library-key: end"

function sharedBlock(path: string): string {
  const source = read(path)
  const begin = source.indexOf(BEGIN)
  const end = source.indexOf(END)
  expect(begin, `${path} has no "${BEGIN}"`).toBeGreaterThanOrEqual(0)
  expect(end, `${path} has no "${END}" after its begin marker`).toBeGreaterThan(begin)
  return source.slice(begin, end)
}

/** The `"<os>-<arch>" to "<library>"` keys of a build's `jvmNativeTargets`. */
function buildTargets(path: string): string[] {
  const map = /val jvmNativeTargets = linkedMapOf\(([\s\S]*?)\n\)/.exec(read(path))?.[1]
  expect(map, `${path} has no jvmNativeTargets`).toBeDefined()
  return [...map!.matchAll(/"([^"]+)" to "/g)].map(m => m[1]!)
}

test("terminal-core and editor-syntax carry the same native-library key and cache folder", () => {
  expect(sharedBlock(EDITOR_SYNTAX)).toBe(sharedBlock(TERMINAL_CORE))
})

test("every desktop target a build packages is a key the shared function names", () => {
  const block = sharedBlock(TERMINAL_CORE)
  const oses = new Set([...block.matchAll(/os\.startsWith\("[^"]+"\)[^\n]*->\s*"([a-z]+)"/g)].map(m => m[1]))
  const arches = new Set([...block.matchAll(/^\s*(?:"[a-z0-9_]+",\s*)*"[a-z0-9_]+"\s*->\s*"([a-z0-9]+)"/gm)].map(m => m[1]))
  expect([...oses].sort()).toEqual(["linux", "macos", "windows"])
  expect([...arches].sort()).toEqual(["arm64", "x64"])
  for (const build of BUILDS) {
    const targets = buildTargets(build)
    expect(targets.length, build).toBeGreaterThan(0)
    for (const target of targets) {
      const [os, arch, ...rest] = target.split("-")
      expect({ build, target, named: oses.has(os!) && arches.has(arch!) && rest.length === 0 }).toEqual({
        build,
        target,
        named: true,
      })
    }
  }
})
