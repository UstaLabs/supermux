import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"

const workflow = readFileSync(
  resolve(import.meta.dir, "..", ".github", "workflows", "release.yml"),
  "utf8",
)
const windowsStart = workflow.indexOf("  build-desktop-windows:")
const windowsEnd = workflow.indexOf("\n  release:", windowsStart)
const windowsJob = workflow.slice(windowsStart, windowsEnd)

const androidStart = workflow.indexOf("  build-android:")
const androidEnd = workflow.indexOf("\n  build-desktop-linux:", androidStart)
const androidJob = workflow.slice(androidStart, androidEnd)

const publishStart = workflow.indexOf("  publish-website:")
const publishJob = workflow.slice(publishStart)

function position(needle: string): number {
  const index = windowsJob.indexOf(needle)
  expect(index, `missing Windows release step: ${needle}`).toBeGreaterThanOrEqual(0)
  return index
}

function androidPosition(needle: string): number {
  const index = androidJob.indexOf(needle)
  expect(index, `missing Android release step: ${needle}`).toBeGreaterThanOrEqual(0)
  return index
}

test("Windows release validates the broker and desktop before packaging", () => {
  expect(windowsStart).toBeGreaterThanOrEqual(0)
  expect(windowsEnd).toBeGreaterThan(windowsStart)

  const setupBun = position("oven-sh/setup-bun@v2")
  position('bun-version: "1.3.14"')
  const bunInstall = position("bun install")
  const bunTests = position("bun test")
  const typecheck = position("bun run typecheck")
  const hostTests = position(":desktop:test --tests 'dev.supermux.desktop.host.*'")
  const packageMsi = position(":desktop:packageMsi")

  expect(setupBun).toBeLessThan(bunInstall)
  expect(bunInstall).toBeLessThan(bunTests)
  expect(bunTests).toBeLessThan(typecheck)
  expect(typecheck).toBeLessThan(packageMsi)
  expect(hostTests).toBeLessThan(packageMsi)
})

test("Windows release stages and inspects the complete native host image", () => {
  position("../scripts/stage-desktop-binaries.sh windows-x64")
  position("Get-Item desktop/resources/windows-x64/supermux-broker.exe")
  position("Get-Item desktop/resources/windows-x64/mux-sessiond.exe")
  position("Get-Item desktop/resources/windows-x64/frpc.exe")
  position(":desktop:createDistributable")
  position("desktop/build/compose/binaries/main/app")
  position("supermux-broker.exe")
  position("mux-sessiond.exe")
  position("frpc.exe")
})

test("Windows release preserves stable MSI and checksum names", () => {
  position("dist/supermux-windows.msi")
  position("supermux-windows.msi.sha256")
})

test("Android release auto-injects monotonic versionCode and tag versionName", () => {
  expect(androidStart).toBeGreaterThanOrEqual(0)
  expect(androidEnd).toBeGreaterThan(androidStart)

  androidPosition("ANDROID_VERSION_CODE_FLOOR")
  androidPosition("Resolve Android client version")
  androidPosition("-PsupermuxVersionCode=")
  androidPosition("-PsupermuxVersionName=")
  androidPosition("android-client.env")

  // versionCode must be derived from the floor + run number (not a fixed source default).
  expect(androidJob).toContain("ANDROID_VERSION_CODE_FLOOR + GITHUB_RUN_NUMBER")
  // Tag builds map versionName to the release tag (strip leading v).
  expect(androidJob).toContain('NAME="${GITHUB_REF_NAME#v}"')
  // Assemble must receive the resolved CI version props.
  expect(androidJob).toContain("steps.ver.outputs.versionCode")
  expect(androidJob).toContain("steps.ver.outputs.versionName")
})

test("publish-website prefers CI-baked Android client version over gradle defaults", () => {
  expect(publishStart).toBeGreaterThanOrEqual(0)
  expect(publishJob).toContain("dist/android-client.env")
  expect(publishJob).toContain("CLIENT_ANDROID_VERSION")
  expect(publishJob).toContain("CLIENT_ANDROID_CODE")
  // Must not be the only source: old sed-from-versionName line is gone.
  expect(publishJob).not.toContain('sed -n \'s/.*versionName = "\\([^"]*\\)".*/\\1/p\' apps/android/build.gradle.kts')
})
