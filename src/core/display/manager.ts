import { randomBytes } from "crypto"
import { makeLogger } from "../../shared/log"
import { startScrcpy } from "./scrcpy/backend"
import type { ScrcpyInstance } from "./scrcpy/backend"
import type { DisplayProvider, DisplayInstance, DisplayStreamInfo, ProviderName } from "./types"

const log = makeLogger("display.manager")

function hasBin(bin: string): boolean {
  // `which` works on both Linux and macOS; Bun.spawnSync returns exitCode 0 when found.
  const r = Bun.spawnSync(["which", bin], { stdout: "ignore", stderr: "ignore" })
  return r.exitCode === 0
}

interface Entry {
  info: DisplayStreamInfo
  vnc?: DisplayInstance
  scrcpy?: ScrcpyInstance
}

export interface DisplayManagerOpts {
  providers: DisplayProvider[]
  onAdded: (info: DisplayStreamInfo) => void
  onRemoved: (id: string) => void
  binCheck?: (bin: string) => boolean // override for tests
}

export class DisplayManager {
  private entries = new Map<string, Entry>()
  constructor(private readonly opts: DisplayManagerOpts) {}

  private pickProvider(name?: ProviderName): DisplayProvider {
    const check = this.opts.binCheck ?? hasBin
    const candidates = name
      ? this.opts.providers.filter((p) => p.name === name)
      : this.opts.providers
    if (candidates.length === 0) throw new Error(`no display provider named "${name}"`)
    // First candidate whose availability check passes; otherwise surface its reason.
    let lastReason = "no available display provider"
    for (const p of candidates) {
      const reason = p.unavailableReason(check)
      if (!reason) return p
      lastReason = reason
    }
    throw new Error(lastReason)
  }

  async start(args: { sessionDisplayName: string; provider?: ProviderName; device?: string; width?: number; height?: number }): Promise<DisplayStreamInfo> {
    const id = "d-" + randomBytes(4).toString("hex")

    if (args.provider === "scrcpy") {
      if (!args.device) throw new Error("provider 'scrcpy' requires a device (adb serial)")
      const inst = await startScrcpy(args.device, { maxSize: args.width })
      const info: DisplayStreamInfo = {
        id,
        sessionName: args.sessionDisplayName,
        provider: "scrcpy",
        display: args.device,
        status: "running",
        createdAt: new Date().toISOString(),
        transport: "h264",
      }
      this.entries.set(id, { info, scrcpy: inst })
      log.info("display_started", { id, provider: "scrcpy", display: args.device })
      this.opts.onAdded(info)
      return info
    }

    const provider = this.pickProvider(args.provider)
    const instance = await provider.provision({ width: args.width, height: args.height })
    const info: DisplayStreamInfo = {
      id,
      sessionName: args.sessionDisplayName,
      provider: provider.name,
      display: instance.display,
      status: "running",
      createdAt: new Date().toISOString(),
      transport: "vnc",
    }
    this.entries.set(id, { info, vnc: instance })
    log.info("display_started", { id, provider: provider.name, display: instance.display })
    this.opts.onAdded(info)
    return info
  }

  async stop(id: string): Promise<void> {
    const e = this.entries.get(id)
    if (!e) return
    this.entries.delete(id)
    try {
      if (e.scrcpy) await e.scrcpy.teardown()
      else if (e.vnc) await e.vnc.teardown()
    } catch (err: any) { log.warn("teardown_failed", { id, err: err?.message }) }
    this.opts.onRemoved(id)
  }

  async killAllForSession(sessionName: string): Promise<void> {
    const ids = [...this.entries.values()].filter((e) => e.info.sessionName === sessionName).map((e) => e.info.id)
    for (const id of ids) await this.stop(id)
  }

  getPort(id: string): number | undefined {
    return this.entries.get(id)?.vnc?.vncPort
  }

  getScrcpy(id: string): ScrcpyInstance | undefined {
    return this.entries.get(id)?.scrcpy
  }

  get(id: string): DisplayStreamInfo | undefined {
    return this.entries.get(id)?.info
  }

  list(): DisplayStreamInfo[] {
    return [...this.entries.values()].map((e) => e.info)
  }

  async stopAll(): Promise<void> {
    for (const id of [...this.entries.keys()]) await this.stop(id)
  }
}
