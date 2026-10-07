// Assembles versions.json from one release's channel block plus the manifest that is
// currently published. The release's own version picks the channel it lands in:
//   vX.Y.Z-alpha.N → channels.alpha only; channels.stable is carried over UNTOUCHED, so a
//                    stable build (which reads nothing but channels.stable) never sees it.
//   vX.Y.Z         → channels.stable, and channels.alpha too unless a newer alpha is already
//                    published (alpha users roll onto the stable; an old-line hotfix never
//                    drags them backwards).
import { compareVersions, isPrerelease } from "../../src/core/update/versions"

export interface ChannelBlock {
  version: string
  [key: string]: unknown
}

export interface Manifest {
  schemaVersion: number
  channels: { stable: ChannelBlock; alpha?: ChannelBlock }
}

function channelOf(previous: unknown, name: "stable" | "alpha"): ChannelBlock | null {
  const block = (previous as { channels?: Record<string, unknown> } | null)?.channels?.[name]
  if (typeof block !== "object" || block === null) return null
  return typeof (block as ChannelBlock).version === "string" ? (block as ChannelBlock) : null
}

export function assembleManifest(release: ChannelBlock, previous: unknown): Manifest {
  const previousStable = channelOf(previous, "stable")
  const previousAlpha = channelOf(previous, "alpha")

  if (isPrerelease(release.version)) {
    if (!previousStable) {
      throw new Error(
        `refusing to publish prerelease ${release.version}: no published channels.stable to carry over`,
      )
    }
    return { schemaVersion: 1, channels: { stable: previousStable, alpha: release } }
  }

  const alphaIsAhead =
    previousAlpha !== null && compareVersions(previousAlpha.version, release.version) > 0
  return {
    schemaVersion: 1,
    channels: { stable: release, alpha: alphaIsAhead ? previousAlpha : release },
  }
}
