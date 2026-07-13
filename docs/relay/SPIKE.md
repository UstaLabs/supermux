# frp relay spike — results

**Date:** 2026-07-11 · **frp version:** 0.61.1 (linux/amd64) · **Verdict: PASS — adopt frp.**

Ran a real local spike (no TLS/DNS needed — hit `frps` vhost HTTP port directly with a `Host:` header) wiring the **actual** supermux modules — `src/core/relay/auth-plugin.ts` (as the frps HTTP plugin) and `src/core/relay/lease.ts` (minting real leases) — against a stand-in broker. All four gates pass; one behavior (Gate 2) has a documented, non-blocking refinement.

## What the spike caught (why it was worth running)

The auth plugin's original `NewProxy` shape was wrong. frp 0.61 sends `subdomain` and `proxy_type` **flat on `content`**, not nested under a `proxy_config` object. Captured live:

```json
{"op":"NewProxy","content":{
  "user":{"metas":{"lease":"…"},"run_id":"…"},
  "proxy_name":"web","proxy_type":"http",
  "subdomain":"h-spikehost"}}
```

The mismatch made the plugin reject every legitimate proxy ("only http proxies permitted"). Fixed in `auth-plugin.ts` + its unit tests now mirror frp's real schema. Login's shape (`content.metas.lease`) was already correct.

## Gates

### GATE 1 — identity binding · PASS
An frpc holding a valid lease for `hostbbb` tried to claim subdomain `h-spikehost`. frps (via our plugin) rejected it: `start error: subdomain does not match leased hostId`. A host can claim **only** `h-<its own leased hostId>` — no cross-host squatting, no non-http proxy types.

### GATE 2 — replacement · PASS (real crash case); newest-wins is a documented follow-up
- **Two live frpc, same subdomain:** frp is **first-wins** — the second gets `proxy [web] already exists` and the original tunnel keeps serving. Deterministic, no flapping. (This is the behavior Sol flagged.)
- **Crash + reconnect (the real scenario):** SIGKILL the live frpc, immediately reconnect a fresh one for the same host → `start proxy success` in **~1 second**, tunnel serving again. The dead socket is reaped fast, so a crashed host is never locked out.
- **Assessment:** first-wins-among-live-connections is correct for crash/reconnect. The only case it doesn't cover — kicking a *genuinely live* old connection in favor of a newer one — arises only from a duplicate/stolen lease, which the short-lived per-host lease model already makes rare. **Follow-up (non-blocking):** for true newest-wins, the plugin can kick the old run via the frps admin API on a new Login. Not needed for v1.

### GATE 3 — WebSocket + HTTP fidelity · PASS
Through `frps` vhost → `frpc` → broker: plain HTTP (`GET /hello` → `broker-ok /hello`) and a WebSocket (`/ws`, `server.upgrade()` on the broker) both work; the WS echoed `echo:ping123` bidirectionally. Confirms the O1 premise — because frpc delivers ordinary local connections, Bun's native WS path needs no change and the transport-seam refactor stays deleted.

### GATE 4 — capacity · PASS
frps RSS: ~25.8 MB baseline (1 client) → ~27.8 MB with ~40 idle leased clients ≈ **~50 KB per idle client**. Extrapolated: **5,000 idle hosts ≈ 244 MB over baseline.** Memory is a non-issue on a small VPS; bandwidth (not compute) remains the only real constraint, as designed.

## Decision
All gates pass. Proceed with frp as the v1 relay data plane behind `RelayProvider` (spec §4 / D11). The rev-3 custom protocol (`git 8e63513`) stays the documented fallback but is not needed. Track the Gate-2 newest-wins refinement as a follow-up if concurrent-live duplicates ever surface in practice.

## Reproduce
Harness lives in `~/.cache/x/spike/` (not committed — throwaway): `plugin-server.ts` (wraps `handleAuthOp`), `fake-broker.ts` (HTTP+WS echo), `mint.ts` (real `mintLease`), and the frp TOML configs. Reference templates are in `frps.toml.example` + `Caddyfile.example` beside this file. The production configuration and runbook live in `deploy/connectivity-relay/`.

## Production result (2026-07-13)

The v1 relay is deployed at `*.relay.supermux.dev` with the control API at
`https://control.relay.supermux.dev`. A real host provider and a complete Supermux broker were
verified through the public edge for HTTP and WebSocket traffic. Graceful shutdown and SIGKILL both
released the FRP proxy, so a restarted broker can immediately reclaim its hostname.

The production pass also caught one post-spike incompatibility: current FRP client metadata must be
sent in TOML (`[metadatas]` and per-proxy `metadatas.lease`). The earlier INI output did not transmit
the lease metadata to the auth plugin. The shipped provider now writes `frpc.toml`.
