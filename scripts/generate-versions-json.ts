// scripts/generate-versions-json.ts
// usage: bun scripts/generate-versions-json.ts <version> <linux-x64-sha> <linux-arm64-sha> <darwin-arm64-sha> [android-apk-sha]
// Emits versions.json (distribution spec §A schema) on stdout. The release
// workflow publishes this to supermux.dev; the broker's update checker
// (Stage 2) polls it. The optional android entry is a download descriptor for
// the phone app — the broker's update checker keys on its own platform and
// ignores unknown asset keys, so surfacing it here is purely for the website.
const [version, shaLinuxX64, shaLinuxArm64, shaDarwinArm64, shaAndroid] = process.argv.slice(2)
if (!version || !shaLinuxX64 || !shaLinuxArm64 || !shaDarwinArm64) {
  console.error(
    "usage: generate-versions-json.ts <version> <linux-x64-sha256> <linux-arm64-sha256>" +
      " <darwin-arm64-sha256> [android-apk-sha256]",
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
console.log(JSON.stringify({
  schemaVersion: 1,
  channels: {
    stable: {
      version,
      publishedAt: new Date().toISOString(),
      notesUrl: `https://github.com/UstaLabs/supermux/releases/tag/v${version}`,
      assets,
    },
  },
}, null, 2))
