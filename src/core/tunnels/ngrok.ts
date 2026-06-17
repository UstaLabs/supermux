// ngrok provider for `supermux connect`.
//
// Two modes (see types.ts → TunnelMode; order matters, modes[0] is the default):
//   • reserved — a stable https://<domain> backed by a long-running `ngrok http
//     --domain=<d>` (ngrok's free tier grants ONE reserved domain). The default;
//     it survives restarts because the domain is fixed.
//   • random — a throwaway *.ngrok-free.app URL read from ngrok's local API. It
//     changes every restart ⇒ you re-pair; never advertised as "your link" (it
//     carries a ⚠️ note).
//
// Every external process call goes through `ctx.run([...argv])` so the whole
// provider is unit-testable with a faked Run — NO Bun.spawn, NO network here.
// Even the 4040 local-API read goes through `ctx.run(["curl", ...])` so it's
// fakeable; URLs are pulled from that JSON.

import { which, extractFirstUrl } from "./run"
import { resolveMode, type TunnelProvider, type ConnectCtx, type TunnelResult } from "./types"

const DOWNLOAD_URL = "https://ngrok.com/download"
const AUTHTOKEN_URL = "dashboard.ngrok.com/get-started/your-authtoken"
const LOCAL_API = "http://127.0.0.1:4040/api/tunnels"

/**
 * Reduce `ctx.publicUrlHint` to a bare hostname for the reserved mode. Accepts a
 * full URL ("https://mux.ngrok.app/x" → "mux.ngrok.app") or an already-bare host
 * ("mux.ngrok.app" → "mux.ngrok.app"). Returns undefined when empty so up() knows
 * to prompt.
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

export const ngrokProvider: TunnelProvider = {
  id: "ngrok",
  label: "ngrok",
  bin: "ngrok",
  modes: [
    { id: "reserved", label: "Reserved domain (stable)", stable: true },
    { id: "random", label: "Random URL (throwaway)", stable: false },
  ],

  /** Client on PATH? */
  async detect(_ctx: ConnectCtx): Promise<boolean> {
    return which("ngrok")
  },

  /**
   * Offer to install ngrok (consent-gated). Best-effort:
   *   • darwin → `brew install ngrok`
   *   • linux  → print the official download URL (no safe one-liner) → false
   * Returns true only if ngrok ends up present. Never throws.
   */
  async install(ctx: ConnectCtx): Promise<boolean> {
    // Already there? Nothing to do.
    if (which("ngrok")) return true

    // Consent. With --yes we assume yes and never prompt (install.sh / CI).
    const ok = ctx.yes || (await ctx.confirm("Install ngrok? (public https tunnel client)", true))
    if (!ok) return false

    if (process.platform === "darwin") {
      try {
        await ctx.run(["brew", "install", "ngrok"])
      } catch {
        // Swallow — we re-check PATH below; brew may be absent or offline.
      }
      return which("ngrok")
    }

    // Linux (and anything else): no universally-safe auto-install. Point at docs.
    ctx.println("Couldn't auto-install ngrok. Install it from:")
    ctx.println(`  ${DOWNLOAD_URL}`)
    return false
  },

  /**
   * Authenticate by pasting the account authtoken. `ngrok config add-authtoken`
   * stores it in ngrok's own config; supermux never persists the secret. No TTY /
   * empty paste ⇒ print guidance and bail.
   */
  async login(ctx: ConnectCtx): Promise<boolean> {
    const token = await ctx.ask(`Paste your ngrok authtoken (${AUTHTOKEN_URL}): `)
    if (!token) {
      ctx.println(`Get your authtoken from ${AUTHTOKEN_URL}, then re-run to finish login.`)
      return false
    }
    const r = await ctx.run(["ngrok", "config", "add-authtoken", token])
    return r.code === 0
  },

  /** Provision the tunnel for the resolved mode and return its public URL. */
  async up(ctx: ConnectCtx): Promise<TunnelResult> {
    const mode = resolveMode(this, ctx)

    if (mode.id === "reserved") {
      // Domain: prefer the user-supplied hint, else ask. (No TTY ⇒ ask ⇒ null.)
      const domain =
        hostFromHint(ctx.publicUrlHint) ??
        hostFromHint((await ctx.ask("Your reserved ngrok domain (e.g. mux.ngrok.app): ")) ?? undefined)
      if (!domain) throw new Error("a reserved domain is required for a stable ngrok tunnel")

      // Best-effort: install ngrok as an OS service so the durable run survives
      // reboots. Tolerate failure (not all platforms / first-run setups support it).
      try {
        await ctx.run(["ngrok", "service", "install"])
      } catch {
        // optional — the foreground run below still serves the tunnel
      }

      // The durable run binds the reserved domain to the local port. It's long-
      // running (serves the tunnel), so we don't await its output — the URL is
      // simply https://<domain>. Kick it off best-effort.
      try {
        await ctx.run(["ngrok", "http", `--domain=${domain}`, ctx.port])
      } catch {
        // long-running / may not return promptly — the URL is deterministic anyway
      }

      return { publicUrl: `https://${domain}`, stable: true }
    }

    // random: start an anonymous tunnel, then read its URL from the local 4040 API.
    // `ngrok http` is long-running; the 8s timeout bounds the wait (the process
    // would otherwise run forever serving the tunnel) while the API comes up.
    await ctx.run(["ngrok", "http", ctx.port], { timeoutMs: 8000 })

    const api = await ctx.run(["curl", "-s", LOCAL_API])
    let url: string | undefined
    try {
      const parsed = JSON.parse(api.stdout) as { tunnels?: Array<{ public_url?: string }> }
      url = parsed.tunnels?.[0]?.public_url ?? undefined
    } catch {
      // Fall back to scraping a URL out of whatever the API returned.
      url = extractFirstUrl(api.stdout)
    }
    if (!url) throw new Error("could not read the ngrok tunnel URL from the local API")

    return {
      publicUrl: url,
      stable: false,
      notes: [
        "⚠️ Throwaway URL — it changes each restart and you'll re-pair. ngrok's free tier offers ONE reserved domain; use that for an always-on box.",
      ],
    }
  },

  /**
   * Tear down the persistent ngrok service. Idempotent and best-effort: safe to
   * run when nothing is installed, and any error is swallowed.
   */
  async down(ctx: ConnectCtx): Promise<void> {
    try {
      await ctx.run(["ngrok", "service", "uninstall"])
    } catch {
      // not installed / already gone
    }
  },

  /** Up iff the local 4040 API answers and reports a tunnel. */
  async status(ctx: ConnectCtx): Promise<{ up: boolean; url?: string }> {
    const r = await ctx.run(["curl", "-s", LOCAL_API])
    const up = r.code === 0 && /public_url/.test(r.stdout)
    return { up, url: up ? extractFirstUrl(r.stdout) : undefined }
  },
}
