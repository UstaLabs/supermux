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

function position(needle: string): number {
  const index = windowsJob.indexOf(needle)
  expect(index, `missing Windows release step: ${needle}`).toBeGreaterThanOrEqual(0)
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
