import type { DisplayProvider, DisplayInstance, ProvisionOpts } from "../types"

const MACOS_VNC_PORT = 5900

export class MacosScreenProvider implements DisplayProvider {
  readonly name = "macos-screen" as const

  unavailableReason(_hasBin: (bin: string) => boolean): string | null {
    if (process.platform !== "darwin") return "macos-screen provider requires macOS"
    return null
  }

  async provision(_opts: ProvisionOpts): Promise<DisplayInstance> {
    // Verify Screen Sharing is actually listening on loopback.
    try {
      const sock = await Bun.connect({ hostname: "127.0.0.1", port: MACOS_VNC_PORT, socket: { data() {} } })
      sock.end()
    } catch {
      throw new Error(
        "macOS Screen Sharing not reachable on 127.0.0.1:5900 — enable it in System Settings › General › Sharing › Screen Sharing",
      )
    }
    return {
      display: "screen",
      vncPort: MACOS_VNC_PORT,
      // Shared OS server — nothing to tear down.
      teardown: async () => {},
    }
  }
}
