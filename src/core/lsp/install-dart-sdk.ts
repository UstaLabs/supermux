#!/usr/bin/env bun
/** Install Dart SDK under ~/.mux/lsp/dart-sdk (no sudo, no TTY). */
import { existsSync } from "node:fs"
import { mkdir, rm } from "node:fs/promises"
import { join } from "node:path"
import { muxLspHome } from "./paths"
import { spawnProcess } from "../process/launcher"

export function dartArch(): "arm64" | "x64" {
  return process.arch === "arm64" ? "arm64" : "x64"
}

/** The Dart SDK archive for [platform] (Dart names its builds linux, macos and windows). */
export function dartSdkZipName(arch: "arm64" | "x64", platform: NodeJS.Platform): string {
  const os = platform === "darwin" ? "macos" : platform === "win32" ? "windows" : "linux"
  return `dartsdk-${os}-${arch}-release.zip`
}

export function dartSdkZipUrl(arch: "arm64" | "x64", platform: NodeJS.Platform): string {
  return `https://storage.googleapis.com/dart-archive/channels/stable/release/latest/sdk/${dartSdkZipName(arch, platform)}`
}

/** The dart binary's file name in an SDK's bin folder. */
export function dartExecutable(platform: NodeJS.Platform): string {
  return platform === "win32" ? "dart.exe" : "dart"
}

/** The command that unpacks [zip] into [into]: unzip, or on Windows its tar (bsdtar reads zip; there is no unzip). */
export function extractZipArgv(zip: string, into: string, platform: NodeJS.Platform): string[] {
  return platform === "win32" ? ["tar", "-xf", zip, "-C", into] : ["unzip", "-qo", zip, "-d", into]
}

// platform-ok: which Dart SDK build to download and how to unpack it (an artifact per OS)
const HOST = process.platform

/** This host's dart binary name (dart.exe on Windows), for finding an installed SDK. */
export const DART_EXE = dartExecutable(HOST)

async function main(): Promise<void> {
  const home = muxLspHome()
  const sdkDir = join(home, "dart-sdk")
  const dartBin = join(sdkDir, "bin", dartExecutable(HOST))
  if (existsSync(dartBin)) {
    console.log(`Dart SDK already installed at ${sdkDir}`)
    return
  }
  await mkdir(home, { recursive: true })
  const arch = dartArch()
  const zipPath = join(home, dartSdkZipName(arch, HOST))
  const url = dartSdkZipUrl(arch, HOST)
  console.log(`Downloading ${url}`)
  const res = await fetch(url)
  if (!res.ok) throw new Error(`download failed: HTTP ${res.status}`)
  await Bun.write(zipPath, res)
  console.log("Extracting…")
  await rm(sdkDir, { recursive: true, force: true })
  const unzip = spawnProcess(extractZipArgv(zipPath, home, HOST), {
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
