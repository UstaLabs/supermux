export interface HostInfo {
  hostId: string
  name: string
  platform: string
  version: string
  protocolVersion: number
}

export interface HostBody {
  hostId: string
  name: string
  protocolVersion: number
  platform?: string
  version?: string
}

/** Public callers get identity only; authed callers also get platform + version. */
export function buildHostBody(info: HostInfo, authed: boolean): HostBody {
  const base: HostBody = { hostId: info.hostId, name: info.name, protocolVersion: info.protocolVersion }
  if (authed) { base.platform = info.platform; base.version = info.version }
  return base
}
