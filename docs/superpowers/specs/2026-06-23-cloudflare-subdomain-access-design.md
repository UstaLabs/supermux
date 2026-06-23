# Cloudflare Named Tunnel — Subdomain Access — Design

**Date:** 2026-06-23
**Status:** Approved (brainstorming)
**Branch:** mux/supermux-10

## Problem

`supermux connect` → Cloudflare → **named tunnel** mode mints a stable URL on the
user's own domain (e.g. `https://mux.example.com`) and pairs the device, but the
broker UI is **unreachable at that host** — every request returns a bare `404`.

Root cause: the named branch of `up()` (`src/core/tunnels/cloudflared.ts`) does
only three things:

1. `cloudflared tunnel create supermux`
2. `cloudflared tunnel route dns supermux <host>`  ← points DNS at the tunnel
3. `cloudflared service install`

It never writes cloudflared's **ingress config** — the rule mapping
`<host> → http://localhost:<port>`. DNS resolves to the tunnel, but the tunnel has
no route for the host, so it falls through to its default `404`. (Confirmed by the
reported symptom: a bare 404, not a DNS resolve error and not 1033/530.) Quick-tunnel
mode works because it passes `--url http://localhost:<port>`, an implicit single
ingress.

Ironically, supermux's *manual* setup snippet (`src/core/settings/exposure.ts`)
documents exactly the ingress block the automated path omits.

Secondary, related gap: exposing apps (`expose_port`) only gets per-app subdomains
when `MUX_PROXY_BASE_DOMAIN` is set — but nothing in the connect flow ever sets it,
so users are stuck on path mode (`/p/<slug>/`), which breaks apps that assume the
site root.

## Goal

1. A named Cloudflare tunnel serves the broker UI at its chosen host (fix the 404).
2. During `supermux connect` (named mode), optionally offer **wildcard subdomains**
   for exposed apps, so `expose_port` serves `app.example.com` instead of `/p/app/`.

## Decisions locked (brainstorming)

- **Approach A** — write a real cloudflared `config.yml` with explicit ingress and
  install the service against it. Rejected alternatives: **B** (single catch-all
  `--url`) needs a config/token to persist anyway and can't coexist with per-app
  subdomains; **C** (token / dashboard-managed) adds an API token + network calls,
  is harder to unit-test, and is overkill for one broker host.
- **Persistence = env var only** (`MUX_PROXY_BASE_DOMAIN`), the mechanism `main.ts`
  already reads. The dormant `wildcardBaseDomain` AppConfig field (defined in
  `app-config.ts` but never set and never read) is left as-is — wiring it up is
  separate cleanup (see Out of scope).
- **Wildcard prompt defaults to No** (conservative — wildcard DNS has Cloudflare
  plan implications).
- **Base domain auto-derived** from the host (strip the first label) and shown for
  confirmation, rather than asked cold.

## Architecture

### Part A — broker UI host works (the 404 fix)

`src/core/tunnels/cloudflared.ts`, named branch of `up()`. After `tunnel create`:

1. **Resolve tunnel id + credentials path.** Parse the UUID from `tunnel create`
   stdout (`Created tunnel supermux with id <UUID>`); on a re-run where the tunnel
   already exists, read it from `cloudflared tunnel list --output json` (match by
   name `supermux`). Credentials file = `<home>/.cloudflared/<UUID>.json` (the path
   `tunnel create` writes).
2. **Write `<home>/.cloudflared/config.yml`** (the file `service install` reads):

   ```yaml
   tunnel: supermux
   credentials-file: <home>/.cloudflared/<UUID>.json
   ingress:
     - hostname: <host>
       service: http://localhost:<port>
     - service: http_status:404
   ```

   (Plus the wildcard rule from Part B when enabled.)
3. `cloudflared tunnel route dns supermux <host>` (unchanged).
4. `cloudflared service install` — now reads the config above.

The single ingress rule is the missing piece causing the 404. Everything is still
routed through `ctx.run([...])`, so the provider stays unit-testable with the fake
`Run`.

### Part B — wildcard for exposed apps (opt-in)

**Prompt** (`src/cli-connect.ts`, after the host is resolved, named mode only):

> Also give exposed apps their own subdomains? Sets up `*.<base>` so `expose_port`
> serves `app.<base>` instead of `/p/app/`. [y/N]

Default **No**. Non-interactive control: a `--wildcard` flag (and optional
`--wildcard-domain <d>` to override the derived base); `--yes` without `--wildcard`
⇒ no wildcard.

**Base-domain derivation:** strip the first DNS label of the host
(`mux.example.com` → `example.com`); show it for confirmation. If the host has only
two labels (looks like an apex, `example.com`), use the host itself as the base.
Public-suffix edge cases (e.g. `example.co.uk`) are why we confirm rather than trust
the derivation — the user can correct it.

**When enabled:**
- add a 2nd DNS route: `cloudflared tunnel route dns supermux "*.<base>"`;
- add a wildcard ingress rule ahead of the catch-all:
  ```yaml
    - hostname: "*.<base>"
      service: http://localhost:<port>
  ```
  One wildcard rule covers both the broker host and every app host; the broker
  decides per-Host via `extractSubdomain` + `proxyMainHost`.
- write `MUX_PROXY_BASE_DOMAIN=<base>` to `.env`.

**No broker code changes.** `main.ts` already reads `process.env.MUX_PROXY_BASE_DOMAIN`
into `proxyBaseDomain`, and the web channel already routes subdomains
(`extractSubdomain`, `buildProxyPublicUrl`, cookie `Domain=.<base>`). It begins
working after the broker restart the connect flow already performs.

### Env wiring

`src/core/tunnels/public-url.ts`. Extend the `.env` writer so it can **set and
clear** `MUX_PROXY_BASE_DOMAIN` alongside `MUX_WEB_PORT` / `MUX_WEB_PUBLIC_URL`:

- wildcard enabled → set `MUX_PROXY_BASE_DOMAIN=<base>`;
- wildcard not chosen, and on `connect --off` / `--switch` → **delete** the key, so
  a stale base domain never lingers and silently breaks routing.

`main.ts`'s `.env` loader only sets keys unset in `process.env` and reads the base
domain straight from env, so writing `.env` + the existing restart is sufficient; no
store write (the store field is unread).

## Edge cases

- **Wildcard DNS must be *proxied* through Cloudflare.** Some plans restrict proxied
  wildcard records. If `route dns "*.<base>"` fails, do **not** abort the whole
  setup: keep Part A (broker host works), skip `MUX_PROXY_BASE_DOMAIN`, and print a
  clear message that exposed apps stay on path mode (`/p/<slug>/`). Part A must never
  regress because Part B failed.
- **Existing user `config.yml`.** Don't silently clobber a file we didn't write.
  Plan: write a clearly-marked supermux-managed `config.yml`; if a foreign one exists,
  back it up (e.g. `config.yml.bak`) and warn, telling the user the ingress block to
  merge if they prefer to keep theirs.
- **Re-run / "already exists".** `tunnel create` returning "already exists" stays
  success (existing behavior); resolve the UUID via `tunnel list --output json`.
  Rewriting `config.yml` + re-running `route dns` is idempotent.
- **`~` / home expansion.** The installed service runs as the user; write absolute
  paths (resolved `home`) in `config.yml`, not a literal `~`.
- **Cookie re-scope.** Enabling wildcard makes the auth cookie `Domain=.<base>`;
  existing device cookies on the bare host may not carry. The connect flow already
  re-pairs after setup, which covers this.
- **`service install` permissions.** Already handled — on failure the provider prints
  the manual `cloudflared tunnel run supermux` fallback, now backed by a real
  `config.yml` so that fallback actually serves traffic too.

## Testing

All via the existing fake-`Run` harness (no real processes / network):

- `cloudflared.test.ts` (Part A): named `up()` writes `config.yml` with the broker-host
  ingress + catch-all, in the right order relative to `route dns` / `service install`;
  UUID parsed from `create`; UUID resolved from `tunnel list` on "already exists".
- `cloudflared.test.ts` (Part B): wildcard on → asserts the 2nd `route dns "*.<base>"`
  and the wildcard ingress rule; wildcard off → neither appears.
- `cli-connect.test.ts`: the wildcard prompt (y → enabled; empty/default → off);
  `--wildcard` / `--wildcard-domain` flags; `.env` gains `MUX_PROXY_BASE_DOMAIN` when
  enabled and is **cleared** on `--off` and when re-run without wildcard.
- `public-url.test.ts`: the env writer sets and deletes `MUX_PROXY_BASE_DOMAIN`
  without disturbing other keys.
- Base-domain derivation: unit-test the strip-first-label helper (subdomain host,
  apex host).
- Full suite stays green.

## Out of scope

- **Wiring up the dormant `wildcardBaseDomain` AppConfig field** + a
  settings/onboarding UI for it. Tracked separately; this change uses the env var that
  already drives routing. (Worth fixing — a value set there today does nothing.)
- Wildcard support for the **quick** trycloudflare mode (impossible — Cloudflare owns
  that domain).
- Other providers (tailscale / ngrok / netbird) — only cloudflared named mode changes.
- Automating Cloudflare **plan/zone** detection for wildcard proxying (we attempt it
  and fall back to path mode on failure).
