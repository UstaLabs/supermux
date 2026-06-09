export type ProviderName = "linux-xvfb" | "macos-screen" | "scrcpy"

export type Transport = "vnc" | "h264"

export type DisplayStatus = "running" | "errored"

// What the browser/registry sees — never includes process handles or ports.
export interface DisplayStreamInfo {
  id: string
  sessionName: string
  provider: ProviderName
  display: string
  status: DisplayStatus
  createdAt: string
  transport: Transport
}

// What a provider returns after provisioning.
export interface DisplayInstance {
  display: string          // e.g. ":99" (X display) or "screen" (macOS)
  vncPort: number          // loopback TCP port the VNC server listens on
  teardown: () => Promise<void>
}

export interface ProvisionOpts {
  width?: number
  height?: number
}

export interface DisplayProvider {
  readonly name: ProviderName
  // Returns a human-readable reason string if a required binary/permission is
  // missing, or null if the provider is usable on this host.
  unavailableReason(hasBin: (bin: string) => boolean): string | null
  provision(opts: ProvisionOpts): Promise<DisplayInstance>
}
