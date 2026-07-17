export interface ExposedLinksPublicUrlOptions {
  hostId: string
  relayDomain?: string
  relayUrl?: string
  publicUrl?: string
}

export function hostRelayUrl(hostId: string, relayDomain: string): string {
  return `https://h-${hostId}.${relayDomain.trim()}`
}

export function exposedLinksPublicUrl(opts: ExposedLinksPublicUrlOptions): string | undefined {
  const relayDomain = opts.relayDomain?.trim()
  if (!relayDomain) return opts.publicUrl
  return opts.relayUrl?.trim() || hostRelayUrl(opts.hostId, relayDomain)
}
