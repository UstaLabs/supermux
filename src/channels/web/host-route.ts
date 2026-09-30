export type HostMode = "binary" | "source" | "docker"

export interface HostInfo {
  hostId: string
  name: string
  platform: string
  version: string
  protocolVersion: number
  /** `versionString()` — version + commit, so two "dev" builds still compare unequal. */
  build?: string
  mode?: HostMode
  /** `MUX_MANAGED_BY` (e.g. "desktop"), absent when nothing manages this broker. */
  managedBy?: string
  stateDir?: string
}

export interface HostBody {
  hostId: string
  name: string
  protocolVersion: number
  platform?: string
  version?: string
  build?: string
  mode?: HostMode
  managedBy?: string
  stateDir?: string
}

/**
 * Public callers get identity only; authed callers also get platform + version. A DIRECT loopback
 * caller (the desktop app deciding whether to adopt, update or take over this broker) gets
 * everything — it runs as the same user and could read the state dir anyway.
 */
export function buildHostBody(info: HostInfo, authed: boolean, directLoopback = false): HostBody {
  const base: HostBody = { hostId: info.hostId, name: info.name, protocolVersion: info.protocolVersion }
  if (authed || directLoopback) { base.platform = info.platform; base.version = info.version }
  if (directLoopback) {
    if (info.build !== undefined) base.build = info.build
    if (info.mode !== undefined) base.mode = info.mode
    if (info.managedBy !== undefined) base.managedBy = info.managedBy
    if (info.stateDir !== undefined) base.stateDir = info.stateDir
  }
  return base
}
