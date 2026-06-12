// scripts/generate-versions-json.ts
// usage: bun scripts/generate-versions-json.ts <version> <x64-sha256> <arm64-sha256>
// Emits versions.json (distribution spec §A schema) on stdout. The release
// workflow publishes this to supermux.dev; the broker's update checker
// (Stage 2) polls it.
const [version, shaX64, shaArm64] = process.argv.slice(2)
if (!version || !shaX64 || !shaArm64) {
  console.error("usage: generate-versions-json.ts <version> <x64-sha256> <arm64-sha256>")
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
        "linux-x64": { url: `${base}/supermux-linux-x64`, sha256: shaX64 },
        "linux-arm64": { url: `${base}/supermux-linux-arm64`, sha256: shaArm64 },
      },
    },
  },
}, null, 2))
