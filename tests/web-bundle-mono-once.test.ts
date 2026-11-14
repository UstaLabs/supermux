// #142 car 3: JetBrains Mono ships ONCE in the web client. terminal-compose and editor-compose each
// carry the same three faces (byte-identical); the app maps the editor's resource paths onto the
// terminal's (apps/web .../SharedResourcePath.kt) and stageForBroker drops the editor's copy after
// proving the two are still identical. This pins that the two lists agree (Gradle cannot run here).
import { describe, expect, test } from "bun:test"
import { readFileSync } from "fs"
import { createHash } from "crypto"
import { join } from "path"

const apps = join(import.meta.dirname, "..", "apps")
const aliasKt = readFileSync(join(apps, "web/src/wasmJsMain/kotlin/dev/supermux/web/SharedResourcePath.kt"), "utf8")
const webGradle = readFileSync(join(apps, "web/build.gradle.kts"), "utf8")
const mainKt = readFileSync(join(apps, "web/src/wasmJsMain/kotlin/dev/supermux/web/Main.kt"), "utf8")

function faces(src: string, name: string): string[] {
  const m = src.match(new RegExp(`${name}\\s*=\\s*listOf\\(([^)]*)\\)`))
  expect(m).not.toBeNull()
  return [...m![1]!.matchAll(/"([^"]+)"/g)].map((x) => x[1]!).sort()
}

describe("JetBrains Mono ships once", () => {
  test("the app's alias list and stageForBroker's drop list are the same three faces", () => {
    const app = faces(aliasKt, "SHARED_MONO_FACES")
    expect(app).toEqual(["jetbrains_mono_bold.ttf", "jetbrains_mono_italic.ttf", "jetbrains_mono_regular.ttf"])
    expect(faces(webGradle, "sharedMonoFaces")).toEqual(app)
  })

  test("Main.kt routes every resource path through the alias", () => {
    expect(mainKt).toMatch(/resourcePathMapping \{ path -> "assets\/" \+ versionedResourcePath\(sharedResourcePath\(path\)\) \}/)
  })

  test("the two libraries' faces are byte-identical today (stageForBroker re-checks the staged copies)", () => {
    for (const face of faces(aliasKt, "SHARED_MONO_FACES")) {
      const sha = (mod: string) => createHash("sha256").update(readFileSync(join(apps, mod, "src/commonMain/composeResources/font", face))).digest("hex")
      expect(sha("editor-compose")).toBe(sha("terminal-compose"))
    }
  })
})
