#!/usr/bin/env bun
/** Install Dart SDK under ~/.mux/lsp/dart-sdk (no sudo, no TTY). */
import { existsSync } from "node:fs"
import { mkdir, rm } from "node:fs/promises"
import { join } from "node:path"
import { muxLspHome } from "./paths"

export function dartArch(): "arm64" | "x64" {
  return process.arch === "arm64" ? "arm64" : "x64"
}

export function dartSdkZipUrl(arch: "arm64" | "x64"): string {
  const zip = `dartsdk-linux-${arch}-release.zip`
  return `https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/${zip}`
}

async function main(): Promise<void> {
  const home = muxLspHome()
  const sdkDir = join(home, "dart-sdk")
  const dartBin = join(sdkDir, "bin", "dart")
  if (existsSync(dartBin)) {
    console.log(`Dart SDK already installed at ${sdkDir}`)
    return
  }
  await mkdir(home, { recursive: true })
  const arch = dartArch()
  const zipName = `dartsdk-linux-${arch}-release.zip`
  const zipPath = join(home, zipName)
  const url = dartSdkZipUrl(arch)
  console.log(`Downloading ${url}`)
  const res = await fetch(url)
  if (!res.ok) throw new Error(`download failed: HTTP ${res.status}`)
  await Bun.write(zipPath, res)
  console.log("Extracting…")
  await rm(sdkDir, { recursive: true, force: true })
  const unzip = Bun.spawn(["unzip", "-qo", zipPath, "-d", home], {
    stdin: "ignore",
    stdout: "pipe",
    stderr: "pipe",
  })
  if ((await unzip.exited) !== 0) {
    const err = await new Response(unzip.stderr).text()
    throw new Error(err.trim() || "unzip failed — install unzip on the broker host")
  }
  await rm(zipPath, { force: true })
  if (!existsSync(dartBin)) {
    throw new Error(`extracted SDK missing ${dartBin}`)
  }
  console.log(`Dart SDK installed at ${sdkDir}`)
}

// Only run when executed directly (not when imported by tests).
if (import.meta.main) {
  try {
    await main()
  } catch (e) {
    console.error(e instanceof Error ? e.message : String(e))
    process.exit(1)
  }
}
