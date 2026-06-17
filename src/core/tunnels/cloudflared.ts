// Cloudflare Tunnel provider for `supermux connect`.
//
// Two modes (see types.ts → TunnelMode; order matters, modes[0] is the default):
//   • named — a stable https://<host> backed by a persistent service. Needs a CF
//     account + a domain ON Cloudflare. This is the default; it survives reboots.
//   • quick — a throwaway *.trycloudflare.com URL (no account). Changes every
//     restart ⇒ you re-pair; never advertised as "your link" (carries a ⚠️ note).
//
// Every external process call goes through `ctx.run([...argv])` so the whole
// provider is unit-testable with a faked Run — NO Bun.spawn, NO network here.
// URLs are scraped from CLI output with `extractFirstUrl`.

import { which, extractFirstUrl } from "./run"
import { resolveMode, type TunnelProvider, type ConnectCtx, type TunnelResult } from "./types"

const TUNNEL_NAME = "supermux"
const DOWNLOAD_DOCS = "https://developers.cloudflare.com/cloudflare-tunnel/downloads/"

/**
 * Reduce `ctx.publicUrlHint` to a bare hostname for the named mode. Accepts a
 * full URL ("https://mux.example.com/x" → "mux.example.com") or an already-bare
 * host ("mux.example.com" → "mux.example.com"). Returns undefined when empty so
 * up() knows to prompt.
 */
function hostFromHint(hint?: string): string | undefined {
  if (!hint) return undefined
  const trimmed = hint.trim()
  if (!trimmed) return undefined
  try {
    // Has a scheme? Let URL pull the hostname out cleanly.
    if (/^[a-z][a-z0-9+.-]*:\/\//i.test(trimmed)) return new URL(trimmed).hostname
  } catch {
    // Fall through and treat it as a bare host below.
  }
  // Bare host (maybe with a path/port) — strip anything after the authority.
  return trimmed.replace(/^\/\//, "").split(/[/:?#]/)[0] || undefined
}

export const cloudflaredProvider: TunnelProvider = {
  id: "cloudflared",
  label: "Cloudflare Tunnel",
  bin: "cloudflared",
  modes: [
    { id: "named", label: "Named tunnel (stable; needs a domain on Cloudflare)", stable: true },
    { id: "quick", label: "Quick tunnel (throwaway URL)", stable: false },
  ],

  /** Client on PATH? */
  async detect(_ctx: ConnectCtx): Promise<boolean> {
    return which("cloudflared")
  },

  /**
   * Offer to install cloudflared (consent-gated). Best-effort:
   *   • darwin → `brew install cloudflared`
   *   • linux  → print the official download docs (no safe one-liner) → false
   * Returns true only if cloudflared ends up present. Never throws.
   */
  async install(ctx: ConnectCtx): Promise<boolean> {
    // Already there? Nothing to do.
    if (which("cloudflared")) return true

    // Consent. With --yes we assume yes and never prompt (install.sh / CI).
    const ok = ctx.yes || (await ctx.confirm("Install cloudflared? (Cloudflare Tunnel client)", true))
    if (!ok) return false

    if (process.platform === "darwin") {
      try {
        await ctx.run(["brew", "install", "cloudflared"])
      } catch {
        // Swallow — we re-check PATH below; brew may be absent or offline.
      }
      return which("cloudflared")
    }

    // Linux (and anything else): no universally-safe auto-install. Point at docs.
    ctx.println("Couldn't auto-install cloudflared. Install it from:")
    ctx.println(`  ${DOWNLOAD_DOCS}`)
    return which("cloudflared")
  },

  /**
   * Authenticate. Only the *named* mode needs a Cloudflare login (it provisions
   * DNS + a credentialed tunnel). `cloudflared tunnel login` opens a browser /
   * prints a URL. Quick mode is anonymous → no login.
   */
  async login(ctx: ConnectCtx): Promise<boolean> {
    if (resolveMode(this, ctx).id !== "named") return true
    const r = await ctx.run(["cloudflared", "tunnel", "login"])
    return r.code === 0
  },

  /** Provision the tunnel for the resolved mode and return its public URL. */
  async up(ctx: ConnectCtx): Promise<TunnelResult> {
    const mode = resolveMode(this, ctx)

    if (mode.id === "named") {
      // Hostname: prefer the user-supplied hint, else ask. (No TTY ⇒ ask ⇒ null.)
      const host =
        hostFromHint(ctx.publicUrlHint) ??
        hostFromHint(
          (await ctx.ask("Hostname for the tunnel (e.g. mux.yourdomain.com): ")) ?? undefined,
        )
      if (!host) throw new Error("a hostname is required for a named cloudflared tunnel")

      // 1. Create the tunnel. Tolerate a re-run: "already exists" is success here.
      const created = await ctx.run(["cloudflared", "tunnel", "create", TUNNEL_NAME])
      if (created.code !== 0 && !/already exists/i.test(created.stdout + created.stderr)) {
        throw new Error(`cloudflared tunnel create failed: ${created.stderr || created.stdout}`)
      }

      // 2. Route the hostname's DNS to the tunnel.
      await ctx.run(["cloudflared", "tunnel", "route", "dns", TUNNEL_NAME, host])

      // 3. Install it as a persistent OS service so it survives reboots. Best-
      //    effort: on failure, hand the user the manual run command.
      const svc = await ctx.run(["cloudflared", "service", "install"])
      if (svc.code !== 0) {
        ctx.println("Couldn't install cloudflared as a service. Run it yourself (keep it running):")
        ctx.println(`  cloudflared tunnel run ${TUNNEL_NAME}`)
      }

      return { publicUrl: `https://${host}`, stable: true }
    }

    // quick: an anonymous, PERSISTENT tunnel. We must NOT scrape-then-kill — that
    // kills the very tunnel we need. Launch it DETACHED (nohup ⇒ survives this
    // short-lived CLI, reparented to init), log to a file, record its PID for
    // down(), then poll the log for the *.trycloudflare.com URL it prints.
    const log = `${ctx.stateDir}/cloudflared-quick.log`
    const pidFile = `${ctx.stateDir}/cloudflared-quick.pid`
    await ctx.run([
      "sh",
      "-c",
      `mkdir -p "${ctx.stateDir}"; : > "${log}"; ` +
        `nohup cloudflared tunnel --no-autoupdate --url http://localhost:${ctx.port} > "${log}" 2>&1 < /dev/null & ` +
        `echo $! > "${pidFile}"`,
    ])

    // Poll the log for the URL (cloudflared reports it within a few seconds).
    let url: string | undefined
    for (let i = 0; i < 20; i++) {
      const r = await ctx.run(["sh", "-c", `cat "${log}" 2>/dev/null`])
      url = extractFirstUrl(r.stdout, /trycloudflare\.com/)
      if (url) break
      await Bun.sleep(1000)
    }
    if (!url) {
      throw new Error("could not get a quick-tunnel URL (cloudflared didn't report one in time)")
    }

    return {
      publicUrl: url,
      stable: false,
      notes: [
        "⚠️ Throwaway URL — it changes every restart and you'll re-pair. Not for an always-on box.",
        `Tunnel runs in the background (pid in ${pidFile}); stop it with \`supermux connect --off\`.`,
      ],
    }
  },

  /**
   * Tear down the persistent tunnel + service. Idempotent and best-effort: both
   * steps are safe to run when nothing exists, and any error is swallowed.
   */
  async down(ctx: ConnectCtx): Promise<void> {
    // Stop a detached quick tunnel if we started one (kill the recorded PID).
    try {
      const pidFile = `${ctx.stateDir}/cloudflared-quick.pid`
      await ctx.run([
        "sh",
        "-c",
        `[ -f "${pidFile}" ] && kill "$(cat "${pidFile}")" 2>/dev/null; rm -f "${pidFile}"`,
      ])
    } catch {
      // nothing to stop
    }
    try {
      await ctx.run(["cloudflared", "service", "uninstall"])
    } catch {
      // not installed / already gone
    }
    try {
      await ctx.run(["cloudflared", "tunnel", "cleanup", TUNNEL_NAME])
    } catch {
      // no such tunnel / nothing to clean
    }
  },

  /** Up iff `cloudflared tunnel list` succeeds and mentions our tunnel. */
  async status(ctx: ConnectCtx): Promise<{ up: boolean; url?: string }> {
    const r = await ctx.run(["cloudflared", "tunnel", "list"])
    return { up: r.code === 0 && new RegExp(TUNNEL_NAME).test(r.stdout) }
  },
}
