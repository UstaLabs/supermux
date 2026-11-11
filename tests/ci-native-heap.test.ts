import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"

// Kotlin/Native compiles and links the app's framework inside the Gradle daemon, which apps/gradle.properties caps at
// 2 GB: train 3 of #122 died there (`:ios:linkDebugFrameworkIosSimulatorArm64`: "Java heap space"). The Apple lanes
// give the daemon 4 GB through the runner's own ~/.gradle/gradle.properties, which wins over the project's, so the
// box's own default stays as it is. CRLF normalized, as release-workflow.test.ts does.
function workflow(name: string): string {
  return readFileSync(resolve(import.meta.dir, "..", ".github", "workflows", name), "utf8").replaceAll("\r\n", "\n")
}

function jobBetween(file: string, start: string, end: string): string {
  const from = file.indexOf(start)
  expect(from, `missing job: ${start.trim()}`).toBeGreaterThanOrEqual(0)
  const to = end ? file.indexOf(end, from) : file.length
  expect(to, `missing the job after ${start.trim()}: ${end.trim()}`).toBeGreaterThan(from)
  return file.slice(from, to)
}

const HEAP = 'echo "org.gradle.jvmargs=-Xmx4g" >> ~/.gradle/gradle.properties'

/** The heap step comes before the step that runs the K/N link ([build]). */
function expectHeapBefore(job: string, build: string) {
  const heap = job.indexOf(HEAP)
  expect(heap, "missing the 4 GB Gradle heap step").toBeGreaterThanOrEqual(0)
  const at = job.indexOf(build)
  expect(at, `missing ${build}`).toBeGreaterThan(heap)
}

test("ci's ios-tests gives the Gradle daemon 4 GB before xcodebuild links the framework", () => {
  const ci = workflow("ci.yml")
  expectHeapBefore(jobBetween(ci, "\n  ios-tests:\n", ""), "xcodebuild \\")
})

test("the release's iOS and macOS jobs give the Gradle daemon 4 GB too", () => {
  const release = workflow("release.yml")
  expectHeapBefore(jobBetween(release, "\n  build-ios-testflight:\n", "\n  assign-testflight:\n"), "xcodebuild")
  expectHeapBefore(jobBetween(release, "\n  build-compose-desktop-macos:\n", "\n  build-ios-testflight:\n"), "./gradlew")
})
