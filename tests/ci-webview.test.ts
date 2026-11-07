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
const kotlinJob = jobBetween("\n  kotlin:\n", "\n  android-journey:\n")

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
