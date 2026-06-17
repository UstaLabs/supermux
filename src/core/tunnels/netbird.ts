// NetBird tunnel provider for `supermux connect`.
//
// NetBird is a private WireGuard mesh: every device that wants to reach the
// broker must join the same NetBird network and run the NetBird client. There is
// no public URL — the broker is reachable at this host's overlay IP (a 100.x.x.x
// CGNAT address) over plain http (the overlay is already an encrypted private
// network, so TLS isn't required to keep it off the public internet).
//
// Caveat carried into `up`'s notes: the broker listens on 127.0.0.1 by default,
// which a mesh peer can't reach even over the overlay. Binding the overlay IP (or
// 0.0.0.0) is a separate follow-up; we surface it so a dead link is diagnosable.
//
// Every process call goes through the injected `ctx.run` (no Bun.spawn here, no
// network) so the whole provider is unit-testable.

import type { ConnectCtx, TunnelProvider, TunnelResult } from "./types"
import { which } from "./run"

/** Matches a NetBird overlay IP (the 100.64.0.0/10 CGNAT range NetBird hands out). */
const OVERLAY_IP = /\b100\.\d{1,3}\.\d{1,3}\.\d{1,3}\b/

export const netbirdProvider: TunnelProvider = {
  id: "netbird",
  label: "NetBird (private mesh)",
  bin: "netbird",
  modes: [
    {
      id: "mesh",
      label: "NetBird mesh — private overlay; every device must run NetBird",
      stable: true,
    },
  ],

  async detect(): Promise<boolean> {
    return which("netbird")
  },

  async install(ctx: ConnectCtx): Promise<boolean> {
    if (process.platform !== "linux" && process.platform !== "darwin") {
      ctx.println("Automatic NetBird install is only wired up for Linux/macOS.")
      ctx.println("Install it from https://docs.netbird.io/how-to/installation and re-run.")
      return false
    }

    const ok = await ctx.confirm("Install the NetBird client now?", ctx.yes)
    if (!ok) {
      ctx.println("Skipping NetBird install. Install it yourself, then re-run:")
      ctx.println("  curl -fsSL https://pkgs.netbird.io/install.sh | sh")
      return false
    }

    const r = await ctx.run(["sh", "-c", "curl -fsSL https://pkgs.netbird.io/install.sh | sh"])
    if (r.code === 0) return true

    ctx.println("NetBird install failed. Install it manually, then re-run:")
    ctx.println("  curl -fsSL https://pkgs.netbird.io/install.sh | sh")
    if (r.stderr.trim()) ctx.println(r.stderr.trim())
    return false
  },

  async login(ctx: ConnectCtx): Promise<boolean> {
    // `netbird up` joins the network (browser SSO or a configured setup key) and
    // brings the overlay interface online. It's also the persistent connect step.
    const r = await ctx.run(["netbird", "up"])
    return r.code === 0
  },

  async up(ctx: ConnectCtx): Promise<TunnelResult> {
    // Resolve this host's overlay IP from `netbird status`. The daemon is already
    // persistent (NetBird runs its own service), so there's nothing to provision —
    // we just need the address peers will dial.
    const detailed = await ctx.run(["netbird", "status", "-d"])
    let out = detailed.stdout
    if (!OVERLAY_IP.test(out)) {
      const plain = await ctx.run(["netbird", "status"])
      out = plain.stdout
    }

    const ip = out.match(OVERLAY_IP)?.[0]
    if (!ip) {
      throw new Error("could not find this host's NetBird overlay IP — is `netbird up` connected?")
    }

    const publicUrl = `http://${ip}:${ctx.port}`
    return {
      publicUrl,
      stable: true,
      notes: [
        "Private mesh: only devices joined to your NetBird network can reach this — your phone must run the NetBird app.",
        "IMPORTANT: the broker listens on 127.0.0.1 by default; for a mesh peer to reach it the broker must bind the overlay IP or 0.0.0.0. If the link doesn't load, that bind change is needed (tracked as a follow-up).",
      ],
    }
  },

  async down(ctx: ConnectCtx): Promise<void> {
    // Best-effort: drop off the overlay. Swallow everything — `down` is idempotent
    // and must never throw (the daemon may already be down or absent).
    try {
      await ctx.run(["netbird", "down"])
    } catch {
      // already down / not installed
    }
  },

  async status(ctx: ConnectCtx): Promise<{ up: boolean; url?: string }> {
    const r = await ctx.run(["netbird", "status"])
    return { up: /Connected|Management: Connected/i.test(r.stdout) }
  },
}
