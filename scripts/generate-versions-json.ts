// scripts/generate-versions-json.ts
// usage:
//   bun scripts/generate-versions-json.ts <version> <linux-x64-sha> <linux-arm64-sha> <darwin-arm64-sha> \
//     [android-apk-sha] [desktop-linux-sha] [desktop-windows-sha] [compose-desktop-macos-sha]
//
// Optional env for client marketing versions:
//   CLIENT_ANDROID_VERSION / CLIENT_ANDROID_CODE
//     (CI release sets these from the APK build: tag versionName + monotonic versionCode)
//   CLIENT_DESKTOP_VERSION
//   CLIENT_IOS_VERSION / CLIENT_IOS_BUILD
//
// Optional env VERSIONS_PREVIOUS = path to the versions.json that is published right now. The
// release's version picks its channel (see scripts/lib/versions-manifest.ts): a prerelease
// (0.12.0-alpha.1) lands in channels.alpha and REQUIRES the previous manifest, because its
// channels.stable is carried over untouched.
//
// Emits versions.json (distribution spec §A schema) on stdout. The release
// workflow publishes this to supermux.dev; the broker's update checker polls
// broker assets, while native clients poll `clients` + desktop/android asset keys.
//
// The macOS client is the Compose Multiplatform desktop app:
//   compose-desktop-macos  → supermux-desktop-macos.dmg  (Supermux Desktop.app)
// The key is historical — it once sat beside a `desktop-macos` entry for the retired native
// SwiftUI Supermux.app — and stays because installed clients (ClientPlatform.DESKTOP_MACOS) read it.
import { readFileSync } from "fs"
import { assembleManifest } from "./lib/versions-manifest"

const [
  version,
  shaLinuxX64,
  shaLinuxArm64,
  shaDarwinArm64,
  shaAndroid,
  shaDesktopLinux,
  shaDesktopWindows,
  shaComposeDesktopMacos,
] = process.argv.slice(2)

if (!version || !shaLinuxX64 || !shaLinuxArm64 || !shaDarwinArm64) {
  console.error(
    "usage: generate-versions-json.ts <version> <linux-x64-sha256> <linux-arm64-sha256>" +
      " <darwin-arm64-sha256> [android-apk-sha256] [desktop-linux-sha256]" +
      " [desktop-windows-sha256] [compose-desktop-macos-sha256]",
  )
  process.exit(2)
}

const base = `https://github.com/UstaLabs/supermux/releases/download/v${version}`
const assets: Record<string, { url: string; sha256: string }> = {
  "linux-x64": { url: `${base}/supermux-linux-x64`, sha256: shaLinuxX64 },
  "linux-arm64": { url: `${base}/supermux-linux-arm64`, sha256: shaLinuxArm64 },
  "darwin-arm64": { url: `${base}/supermux-darwin-arm64`, sha256: shaDarwinArm64 },
}

if (shaAndroid) {
  assets["android"] = { url: `${base}/supermux-android.apk`, sha256: shaAndroid }
}
if (shaDesktopLinux) {
  assets["desktop-linux"] = { url: `${base}/supermux-linux.deb`, sha256: shaDesktopLinux }
}
if (shaDesktopWindows) {
  assets["desktop-windows"] = { url: `${base}/supermux-windows.msi`, sha256: shaDesktopWindows }
}
// Compose Multiplatform mac client (apps/desktop); read by ClientPlatform.DESKTOP_MACOS.
if (shaComposeDesktopMacos) {
  assets["compose-desktop-macos"] = {
    url: `${base}/supermux-desktop-macos.dmg`,
    sha256: shaComposeDesktopMacos,
  }
}

const clients: Record<string, { version: string; versionCode?: number; build?: number }> = {}

const androidVer = process.env.CLIENT_ANDROID_VERSION?.trim()
if (androidVer) {
  const entry: { version: string; versionCode?: number } = { version: androidVer }
  const code = process.env.CLIENT_ANDROID_CODE?.trim()
  if (code && /^\d+$/.test(code)) entry.versionCode = Number(code)
  clients.android = entry
}

const desktopVer = process.env.CLIENT_DESKTOP_VERSION?.trim()
if (desktopVer) {
  clients.desktop = { version: desktopVer }
}

const iosVer = process.env.CLIENT_IOS_VERSION?.trim()
if (iosVer) {
  const entry: { version: string; build?: number } = { version: iosVer }
  const build = process.env.CLIENT_IOS_BUILD?.trim()
  if (build && /^\d+$/.test(build)) entry.build = Number(build)
  clients.ios = entry
}

const release: { version: string; [key: string]: unknown } = {
  version,
  publishedAt: new Date().toISOString(),
  notesUrl: `https://github.com/UstaLabs/supermux/releases/tag/v${version}`,
  assets,
}
if (Object.keys(clients).length > 0) {
  release.clients = clients
}

// No file / unreadable / not JSON all mean "nothing published yet".
let previous: unknown = null
const previousPath = process.env.VERSIONS_PREVIOUS?.trim()
if (previousPath) {
  try {
    previous = JSON.parse(readFileSync(previousPath, "utf8"))
  } catch {
    previous = null
  }
}

console.log(JSON.stringify(assembleManifest(release, previous), null, 2))
