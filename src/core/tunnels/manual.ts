// Self-serve ("bring your own proxy/tunnel") provider for `supermux connect`.
//
// Unlike the other providers, this one runs NO external client: there is nothing
// to detect, install, or log into, and `up()` provisions nothing. It simply
// *guides* the user — it tells them which local port the broker listens on,
// hands them ready-to-paste reverse-proxy snippets (from settings/exposure.ts),
// and explains the `MUX_WEB_PUBLIC_URL` + re-run step. All output is the returned
// `notes[]`; the provider never spawns a process or touches the network.
//
// Two ways it's used:
//   • bare `supermux connect manual` (no --public-url) → publicUrl "" ⇒ the
//     orchestrator treats the result as informational and skips writing/restart.
//   • `supermux connect manual --public-url https://…` → ctx.publicUrlHint is
//     set ⇒ we echo it back as publicUrl and the orchestrator wires it through
//     (writes the URL, restarts the broker, re-pairs).

import { reverseProxySnippets } from "../settings/exposure"
import type { ConnectCtx, TunnelProvider, TunnelResult } from "./types"

export const manualProvider: TunnelProvider = {
  id: "manual",
  label: "Self-serve (bring your own proxy/tunnel)",
  // No client binary — this provider only prints guidance.
  bin: undefined,
  modes: [{ id: "manual", label: "I'll run my own reverse proxy / tunnel", stable: true }],

  /** Nothing to detect — there's no client. */
  async detect(_ctx: ConnectCtx): Promise<boolean> {
    return true
  },

  /** Nothing to install — there's no client. */
  async install(_ctx: ConnectCtx): Promise<boolean> {
    return true
  },

  /** Nothing to authenticate against. */
  async login(_ctx: ConnectCtx): Promise<boolean> {
    return true
  },

  /**
   * Provision nothing; build the guidance the user needs to expose the broker
   * with their own proxy/tunnel. The broker listens on http://localhost:<port>;
   * the user points their tunnel there and sets MUX_WEB_PUBLIC_URL to their
   * public https URL (or re-runs this with --public-url to have us wire it).
   */
  async up(ctx: ConnectCtx): Promise<TunnelResult> {
    const s = reverseProxySnippets({
      publicUrl: ctx.publicUrlHint || "https://your-domain.example.com",
      port: ctx.port,
    })

    const notes = [
      `The broker listens on http://localhost:${ctx.port}. Point your own tunnel / reverse proxy at that port, then set MUX_WEB_PUBLIC_URL to your public https URL.`,
      "Re-run `supermux connect manual --public-url https://your-url` to wire it automatically (writes the URL, restarts, re-pairs).",
      `Caddy:\n${s.caddy}`,
      `nginx:\n${s.nginx}`,
    ]

    // publicUrl "" ⇒ orchestrator treats it as informational (skips write/restart).
    // A user-supplied --public-url (ctx.publicUrlHint) is echoed back to be wired.
    return { publicUrl: ctx.publicUrlHint ?? "", stable: true, notes }
  },

  /** Nothing was provisioned, so there's nothing to tear down. */
  async down(_ctx: ConnectCtx): Promise<void> {
    return
  },

  /** No client / service to probe — never reports "up". */
  async status(_ctx: ConnectCtx): Promise<{ up: boolean; url?: string }> {
    return { up: false }
  },
}
