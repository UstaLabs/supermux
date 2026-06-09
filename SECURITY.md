# Security Policy

## Reporting a vulnerability

Please report security issues privately to the maintainer (open a GitHub security
advisory or email the address in the repo profile) rather than filing a public
issue. We aim to acknowledge within a few days.

## Threat model & deployment notes

supermux is a **single-user, self-hosted** broker that drives local agent CLIs and
exposes a web PWA + optional Telegram bot. Treat the machine it runs on as trusted.

- **Never expose the web port without the front auth.** All browser access is gated
  by an HttpOnly `cmux_token` device cookie (paired via `bun run pair`). Requests
  without a valid cookie get 401. Mutating requests additionally require a
  same-origin `Origin` header (CSRF defense-in-depth on top of `SameSite=Lax`).
- **`/internal/agent-hook/*`** is for local agent hooks only and is gated by a
  per-boot secret embedded in the hook URLs. It is not part of the public API.
- **Secrets** (Telegram/provider API keys) live in `~/.mux/state/.env` — keep that
  file `0600` and never commit it. The VAPID private key lives in
  `~/.mux/state/push-keys.json` (`0600`).
- **Reverse-proxied dev servers** (`expose_port`) default to the same device
  cookie (`Domain=.<base>`); a request without it gets a static 401. Proxies can
  be marked **public** per subdomain (opt-in): those skip broker auth entirely —
  anyone who knows the URL can reach whatever is listening on the forwarded port.
  Treat public proxies like exposing a port on the internet.

## Authentication

- Device tokens are random, stored only as SHA-256 hashes, compared in constant
  time, and rate-limited on failure. The token is delivered to the browser solely
  as an HttpOnly cookie — it is never readable from JavaScript.
