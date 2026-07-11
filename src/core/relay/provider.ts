export type RelayState = "disabled" | "connecting" | "online" | "error"

export interface RelayStatus {
  state: RelayState
  relayUrl?: string  // https://h-<hostId>.relay.supermux.dev when online
  detail?: string
}

/** The swappable relay data plane boundary. frp is one implementation. */
export interface RelayProvider {
  start(): Promise<void>
  stop(): Promise<void>
  status(): RelayStatus
}

/** Relay off (LAN/direct only). Default until frp is configured + spike-passed. */
export class NullRelayProvider implements RelayProvider {
  async start(): Promise<void> {}
  async stop(): Promise<void> {}
  status(): RelayStatus { return { state: "disabled" } }
}
