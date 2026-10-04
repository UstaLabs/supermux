import type { InstallMode } from "../../core/update/mode"
import type { HostRequirements } from "../../core/git/requirement"
import type { KeepAwakeState } from "../../core/power/keep-awake"

export type HostMode = InstallMode

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
  /** false when the broker found no usable git (see `core/git/requirement`). Kept for older desktops. */
  gitAvailable?: boolean
  /** What this computer still needs to run agents (`core/git/requirement`). */
  requirements?: HostRequirements
  /** "Keep this computer awake" (`core/power/keep-awake`): the setting and whether it holds. */
  keepAwake?: KeepAwakeState
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
  gitAvailable?: boolean
  requirements?: HostRequirements
  keepAwake?: KeepAwakeState
}

/**
 * Public callers get identity only; authed callers also get platform + version. A DIRECT loopback
 * caller (the desktop app deciding whether to adopt, update or take over this broker) gets
 * everything — it runs as the same user and could read the state dir anyway.
 */
export function buildHostBody(info: HostInfo, authed: boolean, directLoopback = false): HostBody {
  const base: HostBody = { hostId: info.hostId, name: info.name, protocolVersion: info.protocolVersion }
  if (authed || directLoopback) {
    base.platform = info.platform
    base.version = info.version
    // Every client (phone, PWA, desktop) shows "This computer needs git" from this.
    if (info.requirements !== undefined) base.requirements = info.requirements
    if (info.keepAwake !== undefined) base.keepAwake = info.keepAwake
  }
  if (directLoopback) {
    if (info.build !== undefined) base.build = info.build
    if (info.mode !== undefined) base.mode = info.mode
    if (info.managedBy !== undefined) base.managedBy = info.managedBy
    if (info.stateDir !== undefined) base.stateDir = info.stateDir
    if (info.gitAvailable !== undefined) base.gitAvailable = info.gitAvailable
  }
  return base
}
