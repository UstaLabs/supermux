// The set of tunnel providers `supermux connect` knows about. Order = menu order.
import type { TunnelProvider } from "./types"
import { cloudflaredProvider } from "./cloudflared"
import { tailscaleProvider } from "./tailscale"
import { netbirdProvider } from "./netbird"
import { ngrokProvider } from "./ngrok"
import { manualProvider } from "./manual"

export const PROVIDERS: TunnelProvider[] = [
  cloudflaredProvider,
  tailscaleProvider,
  netbirdProvider,
  ngrokProvider,
  manualProvider,
]

export function getProvider(id: string): TunnelProvider | undefined {
  return PROVIDERS.find((p) => p.id === id)
}
