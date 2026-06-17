// Tailscale tunnel provider for `supermux connect`.
//
// Two stable modes, both backed by the already-persistent tailscaled daemon:
//   • serve  — reachable only by devices on YOUR tailnet (defense in depth)
//   • funnel — public HTTPS via Tailscale Funnel
// The public host comes from `tailscale status --json` → Self.DNSName.
//
// Every process call goes through ctx.run so the whole provider is unit-testable
// with zero network. Nothing here spawns directly or hits the wire.

import type { ConnectCtx, TunnelProvider, TunnelResult } from "./types"
import { resolveMode } from "./types"
import { which } from "./run"

/** Pull the tailnet host out of `tailscale status --json`, trailing dot stripped. */
function parseDnsName(stdout: string): string | undefined {
  try {
    const j = JSON.parse(stdout) as { Self?: { DNSName?: string } }
    const dns = j.Self?.DNSName?.replace(/\.$/, "")
    return dns && dns.length > 0 ? dns : undefined
  } catch {
    return undefined
  }
}

export const tailscaleProvider: TunnelProvider = {
  id: "tailscale",
  label: "Tailscale",
  bin: "tailscale",

  // modes[0] is the default and MUST be stable.
  modes: [
    { id: "serve", label: "Serve — reachable only by your Tailscale devices", stable: true },
    { id: "funnel", label: "Funnel — public https", stable: true },
  ],

  async detect(_ctx: ConnectCtx): Promise<boolean> {
    return which("tailscale")
  },

  /**
   * Offer to install the tailscale client (consent-gated). Returns false — never
   * throws — when the user declines or we can't install on this platform.
   */
  async install(ctx: ConnectCtx): Promise<boolean> {
    const ok = ctx.yes || (await ctx.confirm("Install Tailscale now?", true))
    if (!ok) {
      ctx.println("Skipped. Install Tailscale yourself: https://tailscale.com/download")
      return false
    }

    if (process.platform === "darwin") {
      const r = await ctx.run(["brew", "install", "tailscale"])
      if (r.code === 0) return true
      ctx.println("Could not install Tailscale via Homebrew. Install it yourself:")
      ctx.println("  https://tailscale.com/download/mac")
      return false
    }

    if (process.platform === "linux") {
      const r = await ctx.run(["sh", "-c", "curl -fsSL https://tailscale.com/install.sh | sh"])
      if (r.code === 0) return true
      ctx.println("Could not run the Tailscale install script. Install it yourself:")
      ctx.println("  https://tailscale.com/download/linux")
      return false
    }

    ctx.println("Automatic install isn't supported on this platform. Install Tailscale yourself:")
    ctx.println("  https://tailscale.com/download")
    return false
  },

  /**
   * Bring the node up / authenticate. `tailscale up` may print a browser auth URL
   * the user must visit — surface its stdout/stderr so they can act on it.
   */
  async login(ctx: ConnectCtx): Promise<boolean> {
    const r = await ctx.run(["tailscale", "up"])
    const out = `${r.stdout}${r.stderr}`.trim()
    if (out) ctx.println(out)
    return r.code === 0
  },

  /**
   * Provision a persistent tunnel to 127.0.0.1:ctx.port, then resolve the public
   * https URL from the tailnet host. Throws if the host can't be resolved (a
   * broken publicUrl is worse than a clear failure).
   */
  async up(ctx: ConnectCtx): Promise<TunnelResult> {
    const mode = resolveMode(this, ctx)

    if (mode.id === "funnel") {
      await ctx.run(["tailscale", "funnel", "--bg", ctx.port])
    } else {
      await ctx.run(["tailscale", "serve", "--bg", ctx.port])
    }

    const r = await ctx.run(["tailscale", "status", "--json"])
    const dns = parseDnsName(r.stdout)
    if (!dns) {
      throw new Error("could not resolve the Tailscale hostname — is `tailscale up` complete?")
    }

    const notes =
      mode.id === "funnel"
        ? ["Publicly reachable over HTTPS via Tailscale Funnel."]
        : [
            "Only devices on your tailnet can reach this — the phone/laptop you open it on must also run Tailscale.",
          ]

    return { publicUrl: `https://${dns}`, stable: true, notes }
  },

  /** Tear down both serve + funnel. Best-effort; swallow every error. */
  async down(ctx: ConnectCtx): Promise<void> {
    try {
      await ctx.run(["tailscale", "serve", "reset"])
    } catch {
      // best-effort
    }
    try {
      await ctx.run(["tailscale", "funnel", "--bg", "off"])
    } catch {
      // best-effort
    }
  },

  /** Best-effort current state from `tailscale status --json`. */
  async status(ctx: ConnectCtx): Promise<{ up: boolean; url?: string }> {
    const r = await ctx.run(["tailscale", "status", "--json"])
    const dns = parseDnsName(r.stdout)
    return { up: r.code === 0, url: dns ? `https://${dns}` : undefined }
  },
}
