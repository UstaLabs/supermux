import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"

// A Windows checkout (autocrlf) hands back CRLF, and the Windows release job runs this file —
// normalize so the multi-line assertions below mean the same thing on every runner.
const workflow = readFileSync(
  resolve(import.meta.dir, "..", ".github", "workflows", "release.yml"),
  "utf8",
).replaceAll("\r\n", "\n")
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

const composeMacStart = workflow.indexOf("  build-compose-desktop-macos:")
const composeMacEnd = workflow.indexOf("\n  build-ios-testflight:", composeMacStart)
const composeMacJob = workflow.slice(composeMacStart, composeMacEnd)

function composeMacPosition(needle: string): number {
  const index = composeMacJob.indexOf(needle)
  expect(index, `missing Compose macOS release step: ${needle}`).toBeGreaterThanOrEqual(0)
  return index
}

test("Compose macOS desktop is the only macOS lane", () => {
  expect(composeMacStart).toBeGreaterThanOrEqual(0)
  expect(composeMacEnd).toBeGreaterThan(composeMacStart)

  // The native SwiftUI Supermux.app lane is retired: no job, no xcodebuild of a Mac scheme,
  // no supermux-macos.dmg asset anywhere in the workflow.
  expect(workflow).not.toContain("build-desktop-macos:")
  expect(workflow).not.toContain("supermux-macos.dmg")
  expect(workflow).not.toContain("SupermuxMac")
  // The Compose Multiplatform lane keeps its stable asset name.
  composeMacPosition(":desktop:packageDmg")
  composeMacPosition("stage-desktop-binaries.sh macos-arm64")
  composeMacPosition("dist/supermux-desktop-macos.dmg")
  composeMacPosition("-PsmMacSignIdentity=")
  composeMacPosition("notarytool submit")
})

test("release job publishes the Compose macOS DMG", () => {
  const releaseStart = workflow.indexOf("  release:")
  const releaseJob = workflow.slice(releaseStart, workflow.indexOf("  publish-website:", releaseStart))
  expect(releaseJob).toContain("build-compose-desktop-macos")
  expect(releaseJob).toContain("dist/supermux-desktop-macos.dmg")
  expect(releaseJob).toContain("dist/supermux-desktop-macos.dmg.sha256")
})

test("publish-website includes compose-desktop-macos sha for versions.json", () => {
  expect(publishJob).toContain("supermux-desktop-macos.dmg.sha256")
  expect(publishJob).toContain("SHA_COMPOSE_DESKTOP_MACOS")
  // The retired SwiftUI DMG's positional arg is gone from the generate-versions-json call.
  expect(publishJob).not.toContain("SHA_DESKTOP_MACOS=")
  expect(publishJob).not.toContain('"$SHA_DESKTOP_MACOS"')
  // generate-versions-json.ts takes POSITIONAL shas: a reorder here would silently label one
  // installer with another's checksum, so pin the exact tail of the call (across the `\`
  // line continuation).
  expect(publishJob).toMatch(
    /"\$SHA_ANDROID" "\$SHA_DESKTOP_LINUX" "\$SHA_DESKTOP_WINDOWS" \\\n\s*"\$SHA_COMPOSE_DESKTOP_MACOS" > versions\.new\.json/,
  )
})

// ── release channels: an alpha tag must never reach what a stable install reads ──────────────

const releaseStart = workflow.indexOf("\n  release:")
const releaseJob = workflow.slice(releaseStart, publishStart)
const dockerStart = workflow.indexOf("\n  docker:")
const dockerJob = workflow.slice(dockerStart, androidStart)

test("everything that publishes waits on the tag's channel + branch guard", () => {
  expect(workflow).toContain('v*-*) CHANNEL=alpha; BRANCH=dev ;;')
  expect(workflow).toContain('*) CHANNEL=stable; BRANCH=main ;;')
  expect(workflow).toContain('git merge-base --is-ancestor "$GITHUB_SHA" "origin/$BRANCH"')
  expect(dockerJob).toContain("needs: classify")
  expect(releaseJob).toContain("needs: [classify,")
  expect(publishJob).toContain("needs: [classify, release]")
})

test("an alpha tag is a GitHub pre-release, so releases/latest keeps pointing at stable", () => {
  expect(releaseJob).toContain('if [ "$CHANNEL" = "alpha" ]; then prerelease=(--prerelease); fi')
  expect(releaseJob).toContain('gh release create "${GITHUB_REF_NAME}" --generate-notes "${prerelease[@]}"')
  // The rerun path uploads into an existing release and must not leave it promoted.
  expect(releaseJob).toContain('gh release edit "${GITHUB_REF_NAME}" --prerelease --latest=false')
})

test("docker :latest is stable-only; an alpha tag moves :alpha", () => {
  // metadata-action adds `latest` to every git tag on its own unless told not to — that is how
  // v0.12.0-alpha.1 landed on :latest.
  expect(dockerJob).toMatch(/flavor: \|\n\s+latest=false/)
  expect(dockerJob).toContain(
    "type=raw,value=latest,enable=${{ github.ref_type == 'tag' && needs.classify.outputs.channel == 'stable' }}",
  )
  expect(dockerJob).toContain(
    "type=raw,value=alpha,enable=${{ github.ref_type == 'tag' && needs.classify.outputs.channel == 'alpha' }}",
  )
})

test("publish-website builds on the published manifest and leaves the pinned compose to stable", () => {
  expect(publishJob).toContain("VERSIONS_PREVIOUS=site/versions.json")
  // Never redirect straight into the live manifest: a generator failure would truncate it.
  expect(publishJob).not.toContain("> site/versions.json")
  expect(publishJob).toContain("mv versions.new.json site/versions.json")
  const gate = publishJob.indexOf('if [ "$CHANNEL" = "stable" ]; then')
  const compose = publishJob.indexOf("> site/docker-compose.yml")
  expect(gate).toBeGreaterThanOrEqual(0)
  expect(compose).toBeGreaterThan(gate)
})

test("every desktop installer is built with the tag as the app's version", () => {
  const packaging = workflow.split("\n").filter((l) => /gradlew(\.bat)? :desktop:(package|createDistributable)/.test(l))
  expect(packaging.length).toBe(4)
  expect(workflow.match(/-PsupermuxVersion="\$\{\{ steps\.ver\.outputs\.version \}\}"/g)?.length).toBe(4)
  expect(publishJob).toContain('CLIENT_DESKTOP_VERSION="$V"')
})
