import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"

// ci.yml's checks of the webview library (card #93): CI used to prove only that its modules configure. A Windows
// checkout (autocrlf) hands back CRLF: normalized, as release-workflow.test.ts does.
const ci = readFileSync(resolve(import.meta.dir, "..", ".github", "workflows", "ci.yml"), "utf8").replaceAll("\r\n", "\n")

/** One job's text, by the two anchors that bound it in the file. */
function jobBetween(start: string, end: string): string {
  const from = ci.indexOf(start)
  expect(from, `missing job: ${start.trim()}`).toBeGreaterThanOrEqual(0)
  const to = ci.indexOf(end, from)
  expect(to, `missing the job after ${start.trim()}: ${end.trim()}`).toBeGreaterThan(from)
  return ci.slice(from, to)
}

const changesJob = jobBetween("\n  changes:\n", "\n  ts:\n")
const kotlinJob = jobBetween("\n  kotlin:\n", "\n  webview-wasm:\n")

test("a change to any webview module starts the kotlin lane", () => {
  expect(changesJob).toContain("      webview: ${{ steps.f.outputs.webview }}\n")
  expect(changesJob).toContain("            webview:\n              - 'apps/webview-*/**'\n")
  expect(kotlinJob).toContain("needs.changes.outputs.webview == 'true'")
})

test("the kotlin lane tests the webview library and checks its ABI dump, after the app's own suites", () => {
  const appSuites = kotlinJob.indexOf("./gradlew :shared:jvmTest :ui:jvmTest :desktop:test")
  const webview = kotlinJob.indexOf(
    "run: xvfb-run -a ./gradlew :webview-compose:jvmTest :webview-compose:checkKotlinAbi :webview-sample:jvmTest --build-cache --console=plain",
  )
  expect(appSuites, "missing the app's own suites").toBeGreaterThanOrEqual(0)
  expect(webview, "missing the webview library's step").toBeGreaterThan(appSuites)
  // Kotlin 2.4's in-process compiler strategy crashed :webview-compose:compileTestKotlinWasmJs.
  expect(kotlinJob).not.toContain("kotlin.compiler.execution.strategy=in-process")
})

// Card #112: the library's wasm suite (a real browser) and iOS simulator suite, each on its own job between the kotlin
// lane and android-journey.
const wasmJob = jobBetween("\n  webview-wasm:\n", "\n  webview-ios:\n")
const iosJob = jobBetween("\n  webview-ios:\n", "\n  android-journey:\n")

test("a change to any webview module starts the wasm job, which runs the suite in headless Chrome", () => {
  expect(wasmJob).toContain("github.event_name == 'pull_request'")
  expect(wasmJob).toContain("needs.changes.outputs.webview == 'true'")
  expect(wasmJob).toContain("needs.changes.outputs.shared == 'true'")
  expect(wasmJob).toContain("runs-on: ubuntu-latest")
  expect(wasmJob).toContain(
    "run: ./gradlew :webview-compose:wasmJsBrowserTest --build-cache --console=plain",
  )
  expect(wasmJob).not.toContain("kotlin.compiler.execution.strategy=in-process")
})

test("a change to the webview library itself (or its toolchain) starts the iOS simulator job", () => {
  expect(changesJob).toContain("      webview_lib: ${{ steps.f.outputs.webview_lib }}\n")
  expect(changesJob).toContain(
    "            webview_lib:\n" +
      "              - 'apps/webview-compose/**'\n" +
      "              - 'apps/webview-compose-testing/**'\n" +
      "              - 'apps/gradle/**'\n" +
      "              - 'apps/build.gradle.kts'\n" +
      "              - 'apps/settings.gradle.kts'\n",
  )
  expect(iosJob).toContain("github.event_name == 'pull_request'")
  expect(iosJob).toContain("needs.changes.outputs.webview_lib == 'true'")
  expect(iosJob).toContain("runs-on: macos-26")
  expect(iosJob).toContain("timeout-minutes: 40")
})

test("the iOS job runs the simulator suite on the iPhone it picked, not the Mac's own sm-iPhone", () => {
  const pick = iosJob.indexOf("id: sim")
  const run = iosJob.indexOf(
    "run: ./gradlew :webview-compose:iosSimulatorArm64Test -Pwebview.simulator=${{ steps.sim.outputs.udid }} --build-cache --console=plain",
  )
  expect(pick, "missing the simulator pick").toBeGreaterThanOrEqual(0)
  expect(run, "missing the simulator suite").toBeGreaterThan(pick)
})
