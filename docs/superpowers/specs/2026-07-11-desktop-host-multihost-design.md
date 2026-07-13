# Desktop-as-Host, Multi-Host Clients & Free Relay — Design

**Date:** 2026-07-11 (rev 3 — trimmed to essentials on Ahmet's direction)
**Status:** Direction approved by Ahmet; reviewed by GPT-5.6-Sol (research + spec review rounds); rev 3 keeps only decisions that prevent breaking rework or security holes — everything else is implementation-plan material.
**Evidence base:** joint Claude×Sol research (codebase audit, 25/25 verified web claims). Artifacts: `~/.cache/supermux-windows-research/`; `~/.mux/domains/claudemux.md` (2026-07-11).

## 1. Summary

Every native client gains a **multi-host** model: a list of paired brokers instead of today's single `(baseUrl, token)`. Desktop apps additionally **embed the broker** so the desktop is itself a host ("This computer"), paired via QR in the first-run wizard. Reachability comes from a **free bundled relay** (host dials out; phones connect through `relay.supermux.dev`). Windows ships **client-first**; the **native Windows host** (no WSL) is the committed next initiative.

Goals: (1) onboarding = install desktop app → QR → scan → agents from anywhere; (2) zero regression for existing server users; (3) per-host UI truth comes from existing per-host APIs (`/agents/status`, announced display streams, probes) — no new capability layer.

Non-goals (v1): P2P hole-punching, E2E relay framing, Telegram/WhatsApp multi-host, relay multi-region, Windows-native hosting (initiative 2).

## 2. Decisions locked (by Ahmet unless noted)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Reachability | Own the tunnel (bundled relay); not LAN-only, not Tailscale-dependent |
| D2 | Relay pricing | **Free**, generous; fair-use throttles on heavy flows; natural paid tier later |
| D3 | Windows host | **Native, no WSL** (joint research verdict) |
| D4 | Sequencing | Initiative 1 = multi-host + relay + mac/Linux hosts + Windows client; Initiative 2 = Windows-native host |
| D5 | Session list | **Merged all-hosts fleet list** (host badges, filter chips, host picker in new-session) |
| D6 | Desktop hosting | **Host by default** — wizard IS the QR screen; login keep-alive checked by default (can be unchecked; Settings toggles exist) |
| D7 | POSIX backend | tmux stays; desktop apps bundle a static tmux |
| D8 | tmux replacement | Windows only (`mux-sessiond`); never on POSIX without explicit triggers |
| D9 | Web PWA | Single-host in v1 (origin-bound cookie auth; it IS a host's UI) |
| D10 | BYO connectivity | Tailscale/VPN/reverse-proxy URLs stay first-class: add-host accepts a typed URL, the relay is **default but per-host disableable**, and a relay-less host pairs via a direct-URL-only QR |
| D11 | Relay data plane | **Adopt frp** (self-operated `frps` + a thin supermux identity sidecar) behind a `RelayProvider` boundary, gated on a 1-day spike with four pass/fail gates; the rev-3 custom protocol is the documented fallback. Deletes the broker transport-seam refactor from v1. Alternatives rejected: Cloudflare Tunnel (1,000-tunnel default account limit vs 5k+ target; white-label ToS needs written approval), embedded Tailscale/headscale ("phase 2 disguised as phase 1") |

## 3. Host model

**Identity.** Broker generates an Ed25519 keypair on first run (`~/.mux/state/host-key`, 0600). `host_id` = base32(first 128 bits of SHA-256(pubkey)), 26 chars; relay hostname `h-<id>.relay.supermux.dev`. The key authenticates relay registration only; device auth stays token-based. Threat model, stated honestly: v1 relay is not E2E, so a stolen host key means interception and host impersonation until re-pair — accepted for v1, disclosed, E2E is the phase-2 fix.

**Client records.** Clients keep a list:

```
PairedHost { recordId (client UUID, the internal key), hostId?, displayName,
             directUrl?, relayUrl?, token, lastSeenAt }
```

`recordId` exists from day one so old brokers without `GET /host` still work; `hostId` backfills when learned. Metadata lives in normal app storage; **tokens go in the platform secure store, one per host** (the JVM desktop `SecureTokenStore` is an in-memory stub today — a real Keychain/DPAPI/libsecret impl is an initiative-1 deliverable). Existing single-host installs migrate transparently to `PairedHost[0]`.

**`GET /host`** (new): `{hostId, name, protocolVersion}` unauthenticated (pairing + adoption probe); `platform`/`version` added when authenticated, display-only.

**Pairing.** QR/link payload: `{v:1, action:"pair", hostId, name, relayUrl, directUrl?, claimSecret}`. `POST /pair/claim {claimSecret, deviceName}` → `{host, deviceToken}`. The host stores a hash of the secret, expires it in minutes, consumes it atomically once, and — unlike today's trust-on-first-connect claim, which 403s once any device exists — accepts claims on configured hosts (minting one requires an authed device or the local wizard). The client aborts if the response's `hostId` differs from the QR's. Legacy `/pair` stays for the PWA.

**Transport preference.** Loopback → direct → relay. Direct URLs include user-supplied ones (Tailscale MagicDNS/tailnet IPs, VPN, reverse proxy) — BYO connectivity is first-class (D10), the relay is just the zero-config default and can be disabled per host (the QR then carries `directUrl` only). Plain HTTP is auto-allowed for loopback, and for addresses whose route verifiably goes through a VPN/tailnet interface (the OS exposes this; a bare 100.64/10 address proves nothing — it's generic CGNAT space); any other plain-HTTP URL needs an explicit labeled opt-in so the bearer never leaks to an unencrypted network.

## 4. Relay v1 (D11: frp data plane, supermux identity)

- **Stack:** edge (Caddy/nginx, wildcard TLS for `*.relay.supermux.dev`) → `frps` HTTP vhost routing → host-side `frpc` sidecar → `localhost:9898`. The broker sees ordinary local connections — HTTP, streaming, and `server.upgrade()` WebSockets all work unchanged. No transport seams, no custom framing in v1.
- **Identity stays ours; frp is a dumb data plane.** The host authenticates to a small supermux control endpoint with its host key (§3) and receives a short-lived, host-scoped lease; `frpc` presents `hostId` + lease as metadata; an frps auth plugin (Login/NewProxy hooks) validates the lease and permits exactly one vhost claim: `h-<hostId>`. Newest lease wins; the displaced connection is rejected on its next control operation. Host A can never claim `h-B`, TCP ports, or extra proxies.
- **Boundary for replaceability:** the host side talks to a `RelayProvider` interface; clients know only `https://h-<id>.relay.supermux.dev`. No frp identifiers in pairing records or client APIs — swapping the data plane later (incl. to the custom protocol) touches nothing user-facing.
- **Spike gates (1 day, pass all or fall back to the custom protocol from rev 3 — git 8e63513):** ① identity binding (cross-host subdomain claims rejected), ② deterministic newest-wins replacement without reconnect fights, ③ WebSocket fidelity through edge→frps→frpc→broker (control WS, terminal, upload, proxied dev-server WS), ④ capacity: 500–1,000 idle clients benchmarked and extrapolated to 5k on one VM, plus a slow-consumer test showing bounded memory and responsive control traffic.
- **Ops:** pin a tested frps/frpc compatibility range (frp is mid wire-protocol-v2 transition — server-first staged upgrades); bundle `frpc` (~13 MB/platform) with the desktop apps; the edge strips client-supplied forwarded headers and re-adds trusted ones; basic rate/size/concurrency limits day one via edge + frps config + plugin.
- The relay keeps no durable content — ephemeral connection/lease/rate state only.
- Privacy statement (docs + marketing stay consistent): encrypted in transit to and from the relay; **not end-to-end** in v1 — supermux infrastructure could technically read forwarded traffic, including tokens.
- Relay outage degrades to direct/LAN; clients distinguish "relay unreachable" from "host offline".

## 5. Client UX (iOS, Android, desktop)

- **Merged fleet list:** all sessions from all hosts; host badge per row; filter chips (`All · host… · +`); one control WS per online host (feature streams open extra host-scoped sockets). Offline host → greyed group with last-seen, rendered from a persisted last-snapshot per host (cached outside the secure store, dropped when the host is forgotten).
- **Push:** clients send their local `recordId` when registering the push token with a host; the host echoes it inside the existing E2E-encrypted payload, so grouping/clear/tap route by `(recordId, session)` without the broker ever needing to know client-local ids. Legacy pushes without it route best-effort. Tokens register per host.
- **Add host:** QR scanner + paste-link → §3 claim; also a plain "enter host URL" path (Tailscale/VPN/reverse-proxy users) that hits `GET /host` then mints a claim from the host's own UI. Host sheet: rename, forget (+ best-effort revoke), connection path indicator.
- **New-session:** host picker pill (hidden with one host); agent options filtered by that host's `/agents/status` (fixes today's hardcoded four-agent list).

## 6. Desktop-as-host (macOS + Linux)

- App supervises a broker sidecar (bundled Bun + broker + static tmux; state in `~/.mux`). **Adopt, don't duplicate:** if a healthy broker already answers on :9898, adopt it; never stop/reconfigure a broker the app didn't start without explicit confirmation; foreign process on the port → run the sidecar on an alternate persisted port.
- **First-run wizard = the QR screen** (D6): headline + pairing QR within seconds; keep-alive at login checked by default (launchd / systemd-user), can be unchecked; wizard discloses the relay ("encrypted in transit; not yet end-to-end"). Unchecking keep-alive = hosting only while the app runs. Settings expose hosting + keep-alive separately.
- Wizard lists detected agent CLIs (`/agents/status`) with install nudges; the app auto-pairs to its own local host ("This computer").
- **Windows build:** client-only + an enabled "Host from this PC — coming next" card (explainer + preview signup for demand measurement; advanced docs: a WSL broker pairs as a separate host, no path bridging).

## 7. Windows-native host (initiative 2 — summary; own spec when scheduled)

Per-user **`mux-sessiond`** daemon (logon Scheduled Task, never a session-0 Service) owning ConPTYs + per-session Job Objects + `@xterm/headless` screens; capability-scoped named pipes; broker restarts freely, sessiond survives. PowerShell default; Git Bash optional (Claude Code ≥2.1.120 doesn't require it). Order: **Codex first** (structured app-server) → **Claude** (3–4 eng-wks, gated on VT-replay test infra) → OpenCode preview; Cursor "native unverified". Release gates: real-Windows probe of Bun's ConPTY `terminal` option + pinned Bun; Win10 1809+ baseline. Start trigger: initiative 1 stable + preview-signup demand.

## 8. Phase-0 portability seam (inside initiative 1, ~1–2 wks)

- Terminal/session targets become opaque IDs (no tmux-shaped names in wire formats).
- `GET /host` identity endpoint.
- tmux absence no longer broker-fatal: disables tmux-backed Claude sessions + persistent terminals with clear status; codex/cursor/opencode still run (today `preflight.ts` makes tmux globally fatal).
- Define (not implement) the platform-neutral session-control interface (create/write/sendKey/capture/attach/interrupt/…) — tmux adapter is the only impl; `LocalEndpoint` type (`unix` now, `windows-pipe` reserved).
- NOT in scope: porting installers/updater/credential helpers (initiative 2).

## 9. Edge cases

- Relay down ≠ host down (direct path keeps working; distinct client states).
- Old broker (no `/host`): fully usable keyed by `recordId`; hostId backfills after upgrade; adding the same host twice dedupes once hostId is known.
- Expired/used claim → clear error + regenerated QR.
- Session names collide across hosts by design; identity is `(recordId, sessionId)`, the badge disambiguates.
- Existing users: no re-pairing, no behavior change with a single host.

## 10. Testing

- Relay: the four §4 spike gates become the regression suite — identity binding (cross-host claims rejected), lease expiry/replay fail-closed, deterministic replacement, WS fidelity through the full edge→frps→frpc→broker chain, slow-consumer boundedness; plus failure injection (edge/plugin/frps/frpc/broker killed independently → distinguishable diagnostics) and an frps↔frpc upgrade test within the pinned compatibility window.
- Pairing: one-time claim semantics (reuse/expiry), claims on configured hosts, payload validation.
- Clients: migration to `PairedHost[0]`; secure-store per platform; fleet list online/offline/stale states; per-host push routing; launcher agent filtering; wizard QR < 5 s cold start.
- E2E pre-ship: phone on cellular ↔ NATed desktop via real relay — pair, chat, terminal, upload, host reboot, relay restart.

## 11. Rollout order

1. Phase-0 seam + `GET /host` (no behavior change).
2. **frp spike (1 day, four gates)** — pass: continue; fail: swap §4 back to the rev-3 custom protocol (8e63513) and re-plan.
3. Relay stack (edge + frps + identity sidecar + host-key lease endpoint + bundled frpc); dogfood with THIS server as host #1.
4. Multi-host storage + fleet list + add-host on Android → iOS → desktop; per-host push.
5. mac/Linux desktop host embedding + wizard + bundled tmux + adoption rules.
6. Windows desktop client + preview card.
7. Initiative 2 per §7 trigger.

## 12. Open items (non-blocking)

- Soft fair-use cap values (from dogfood telemetry).
- Merged-view pagination past ~200 sessions (defer until real).
- E2E upgrade path (QR payload is versioned; E2E transport framing is the phase-2 successor to the frp data plane, swapped behind `RelayProvider`).
