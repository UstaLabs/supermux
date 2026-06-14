// scripts/generate-versions-json.ts
// usage: bun scripts/generate-versions-json.ts <version> <linux-x64-sha> <linux-arm64-sha> <darwin-arm64-sha>
// Emits versions.json (distribution spec §A schema) on stdout. The release
// workflow publishes this to supermux.dev; the broker's update checker
// (Stage 2) polls it.
const [version, shaLinuxX64, shaLinuxArm64, shaDarwinArm64] = process.argv.slice(2)
if (!version || !shaLinuxX64 || !shaLinuxArm64 || !shaDarwinArm64) {
  console.error(
    "usage: generate-versions-json.ts <version> <linux-x64-sha256> <linux-arm64-sha256>" +
      " <darwin-arm64-sha256>",
  )
  process.exit(2)
}
const base = `https://github.com/UstaLabs/supermux/releases/download/v${version}`
console.log(JSON.stringify({
  schemaVersion: 1,
  channels: {
    stable: {
      version,
      publishedAt: new Date().toISOString(),
      notesUrl: `https://github.com/UstaLabs/supermux/releases/tag/v${version}`,
      assets: {
        "linux-x64": { url: `${base}/supermux-linux-x64`, sha256: shaLinuxX64 },
        "linux-arm64": { url: `${base}/supermux-linux-arm64`, sha256: shaLinuxArm64 },
        "darwin-arm64": { url: `${base}/supermux-darwin-arm64`, sha256: shaDarwinArm64 },
      },
    },
  },
}, null, 2))
