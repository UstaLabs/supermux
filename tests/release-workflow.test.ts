import { expect, test } from "bun:test"
import { readFileSync } from "node:fs"
import { resolve } from "node:path"

const workflow = readFileSync(
  resolve(import.meta.dir, "..", ".github", "workflows", "release.yml"),
  "utf8",
)
const windowsStart = workflow.indexOf("  build-desktop-windows:")
// The NEXT job, not the far-away `release:` job: anchoring on `release:` swept the macOS and
// iOS lanes into "the Windows job", so any assertion that something is ABSENT from Windows
// was really asking whether it was absent from three jobs at once.
const windowsEnd = workflow.indexOf("\n  build-compose-desktop-macos:", windowsStart)
const windowsJob = workflow.slice(windowsStart, windowsEnd)

const androidStart = workflow.indexOf("  build-android:")
const androidEnd = workflow.indexOf("\n  build-desktop-linux:", androidStart)
const androidJob = workflow.slice(androidStart, androidEnd)

const publishStart = workflow.indexOf("  publish-website:")
const publishJob = workflow.slice(publishStart)

const ci = readFileSync(resolve(import.meta.dir, "..", ".github", "workflows", "ci.yml"), "utf8")

/** One job's text, by the two anchors that bound it in the file. */
function jobBetween(source: string, start: string, end: string): string {
  const from = source.indexOf(start)
  expect(from, `missing job: ${start.trim()}`).toBeGreaterThanOrEqual(0)
  const to = end ? source.indexOf(end, from) : -1
  return source.slice(from, to > from ? to : undefined)
}

const binariesJob = jobBetween(workflow, "  build-binaries:", "\n  update-flow:")
const linuxDesktopJob = jobBetween(workflow, "  build-desktop-linux:", "\n  build-terminal-jni-windows:")
const releaseTerminalPackagesJob = jobBetween(workflow, "  terminal-packages:", "\n  build-android:")
const ciTerminalPackagesJob = jobBetween(ci, "  terminal-packages:", "\n  ui:")

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
    /"\$SHA_ANDROID" "\$SHA_DESKTOP_LINUX" "\$SHA_DESKTOP_WINDOWS" \\\n\s*"\$SHA_COMPOSE_DESKTOP_MACOS" > site\/versions\.json/,
  )
})

// ── Plan 4 Task 6: what a release artifact must now CONTAIN ─────────────────
//
// Every assertion below stands for one way a release used to be able to ship
// something that installs, boots, and cannot open a terminal.

test("the compiled binary's lane builds the pinned zmx and proves it materializes", () => {
  // The pin is checked BEFORE anything is compiled from it…
  const checkPatches = binariesJob.indexOf("scripts/build-zmx.sh --check-patches")
  const build = binariesJob.indexOf("scripts/build-binary.sh")
  expect(checkPatches).toBeGreaterThanOrEqual(0)
  expect(checkPatches).toBeLessThan(build)
  // …and the asset probe runs AFTER the build, which is what proves the embedded
  // bundle can be copied out of $bunfs and exec'd (Plan 3 shipped a binary where
  // nothing did, and every other check in this lane passed).
  expect(binariesJob.indexOf("scripts/asset-probe.ts")).toBeGreaterThan(build)
  expect(binariesJob).toContain("~/.local/zig")
})

test("the smoke test gates the workspace-terminal backend, not just the PWA", () => {
  const smoke = readFileSync(resolve(import.meta.dir, "..", "scripts", "smoke-binary.sh"), "utf8")
  // Reads the broker's OWN boot-time readiness line rather than re-deciding it.
  expect(smoke).toContain('"workspaceTerminals"')
  expect(smoke).toContain("SUPERMUX_SMOKE_ALLOW_NO_ZMX")
})

test("each desktop distribution carries its client terminal engine", () => {
  // The JNI library the APP draws with — nothing to do with which host backend it
  // talks to. :terminal-core only warns when it is missing, so the packaging job
  // has to build it and then read it back out of the packaged jar.
  expect(linuxDesktopJob).toContain("native/build.sh linux-x64")
  expect(linuxDesktopJob).toContain("dev/supermux/terminal/native/linux-x64/libsupermux_terminal_jni.so")
  expect(composeMacJob).toContain("native/build.sh macos-arm64")
  expect(composeMacJob).toContain("dev/supermux/terminal/native/macos-arm64/libsupermux_terminal_jni.dylib")
  // Windows has no pinned Zig host, so its engine is cross-built on Linux and handed over.
  expect(workflow).toContain("build-terminal-jni-windows:")
  expect(windowsJob).toContain("needs: build-terminal-jni-windows")
  expect(windowsJob).toContain("dev/supermux/terminal/native/windows-x64/supermux_terminal_jni.dll")
  // And the licence files travel with it, on all three.
  for (const job of [linuxDesktopJob, composeMacJob, windowsJob]) {
    expect(job).toContain("META-INF/dev.supermux.terminal/LICENSE")
  }
})

test("POSIX desktop packages carry the verified zmx bundle; Windows keeps sessiond", () => {
  for (const job of [linuxDesktopJob, composeMacJob]) {
    expect(job).toContain("check-zmx-bundle.sh")
    expect(job).toContain("zmx-manifest.json")
  }
  // The Windows lane must NOT stage a second workspace-terminal backend.
  expect(windowsJob).not.toContain("check-zmx-bundle.sh")
  expect(windowsJob).toContain("mux-sessiond.exe")
})

test("the packages are consumed as a stranger would, and never published publicly", () => {
  for (const job of [releaseTerminalPackagesJob, ciTerminalPackagesJob]) {
    // SHA/ABI of every native artifact, against the manifest its own build wrote.
    expect(job).toContain(":terminal-core:verifyNativeArtifactsForHost")
    // The wasm engine is BUILT here, not assumed present.
    expect(job).toContain("apps/terminal-core/wasm/build.sh")
    // Published to a directory on the runner. Nothing else.
    expect(job).toContain("publishAllPublicationsToLocalTestRepository")
    expect(job).not.toContain("publishAllPublicationsToMavenCentral")
    expect(job).not.toContain("sonatype")
    // The separate builds that share no project or classpath with supermux.
    expect(job).toContain("-p terminal-core/consumer-smoke jvmTest")
    expect(job).toContain("-p terminal-core/consumer-smoke wasmJsBrowserTest")
    expect(job).toContain("-p terminal-compose/consumer-smoke jvmTest")
    // Licences inside the artifacts, not merely in the repo.
    expect(job).toContain("META-INF/dev.supermux.terminal/THIRD-PARTY-NOTICES.md")
  }
})

test("CI verifies the zmx pin automatically", () => {
  // scripts/build-zmx.sh --check-patches existed from Plan 3 and nothing ran it.
  expect(ci).toContain("scripts/build-zmx.sh --check-patches")
})

test("every verification lane GATES the release instead of running beside it", () => {
  // `terminal-packages` — the lane built to verify the packages a stranger
  // consumes — ran on every tag, went red on a mismatch, and the GitHub Release
  // was created and its assets uploaded anyway, because it was not in `needs`.
  // A check that cannot stop a release is a check nobody is obliged to read.
  //
  // This asserts the RULE rather than a copy of the list: every job in the
  // workflow gates the release except the ones named below, each with the
  // reason it is exempt. A new lane is therefore gating by default, and making
  // it an exception means saying so here.
  // From `jobs:` onward only — above it, `on:` has two-space keys of its own
  // (`push:`, `workflow_dispatch:`) that are not jobs.
  const jobsSection = workflow.slice(workflow.indexOf("\njobs:\n"))
  expect(jobsSection.length, "the workflow has no jobs: block").toBeGreaterThan(0)
  const jobs = [...jobsSection.matchAll(/^ {2}([a-z][a-z0-9-]*):$/gm)].map(match => match[1]!)
  expect(jobs).toContain("terminal-packages")
  expect(jobs).not.toContain("push")

  const exempt = new Map([
    ["release", "is the job being gated"],
    ["publish-website", "runs AFTER the release, and needs it"],
    ["build-terminal-jni-windows", "gates transitively, through build-desktop-windows"],
    // Deliberate: this lane WARNS rather than fails when the signing secrets are
    // absent, so that binary and docker releases keep shipping without them.
    // Requiring it would invert that decision.
    ["build-ios-testflight", "a separate channel that is allowed to be unconfigured"],
    ["assign-testflight", "same channel, and it is conditional on an upload happening"],
  ])

  const needs = jobBetween(workflow, "  release:", "\n  publish-website:")
  for (const job of jobs) {
    if (exempt.has(job)) continue
    expect(needs, `${job} does not gate the release`).toContain(`\n      - ${job}\n`)
  }

  // A SKIPPED prerequisite skips its dependents, so a gate that can be skipped
  // is a gate that can quietly cancel the release. None of these carry an `if:`.
  for (const job of ["terminal-packages", "update-flow"]) {
    const text = jobBetween(workflow, `  ${job}:`, "\n  ")
    expect(text, `${job} is conditional and cannot be a prerequisite`).not.toMatch(/^ {4}if:/m)
  }
})
