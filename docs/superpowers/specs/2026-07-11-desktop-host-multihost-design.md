# Desktop-as-Host, Multi-Host Clients & Free Relay — Design

**Date:** 2026-07-11 (rev 2 — post Sol spec review)
**Status:** Approved direction by Ahmet (web chat brainstorm); rev 2 incorporates GPT-5.6-Sol's formal spec review (5 blocking clusters resolved)
**Evidence base:** joint Claude(Fable 5)×Codex(GPT-5.6-Sol) research — codebase audit (file:line-cited), 25/25 adversarially-verified web claims, 3 review rounds. Artifacts: `~/.cache/supermux-windows-research/`; verdict logged in `~/.mux/domains/claudemux.md` (2026-07-11).

## 1. Summary

Every native client gains a **multi-host** model: a list of paired brokers instead of today's single `(baseUrl, token)`. Desktop apps additionally **embed the broker** so the desktop machine is itself a host ("This computer"), paired to phones via QR in the first-run wizard. Reachability everywhere comes from a **free bundled relay** (host dials out; phones connect through `relay.supermux.dev`). Windows ships **client-first**; the **native Windows host** (no WSL) is the committed next initiative on a `mux-sessiond` + ConPTY architecture.

Goals, in order:
1. Onboarding collapses to: *install desktop app → QR appears → scan with phone → agents from anywhere.*
2. No regression for existing server-hosted users; a server is just another host in the list.
3. Per-host UI stays honest by scoping the *existing* per-host APIs (`/agents/status`, dynamic display frames, feature probes) to the selected host — no new capability layer.

Non-goals (v1): P2P hole-punching, E2E relay framing, Telegram/WhatsApp multi-host semantics, relay multi-region, Windows-native hosting (initiative 2).

## 2. Decisions locked (by Ahmet unless noted)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Reachability strategy | Own the tunnel (bundled relay), not LAN-only, not Tailscale-dependent |
| D2 | Relay pricing | **Free**, fair-use caps on heavy flows; natural paid tier later |
| D3 | Windows host approach | **Native, no WSL** (joint research verdict; WSL bridge rejected) |
| D4 | Sequencing | Initiative 1 = multi-host + relay + mac/Linux hosts + Windows client; Initiative 2 = Windows-native host (gated on 1 stable) |
| D5 | Session list across hosts | **B: merged all-hosts fleet list** with host badges (+ filter chip, host picker in new-session) |
| D6 | Desktop hosting eagerness | **A: host by default** — first-run wizard IS the QR screen; hosting + login keep-alive **checked by default, can be unchecked during setup**, toggleable in Settings |
| D7 | POSIX backend | Keep tmux indefinitely; bundle a static tmux in desktop apps; platform-neutral session-control protocol so sessiond convergence stays optional |
| D8 | tmux replacement scope | Only on Windows, as `mux-sessiond` (ConPTY + Job Objects + xterm-headless + named pipes), Codex backend first, Claude second |
| D9 | Web PWA scope | **Single-host in v1.** The PWA is served BY a host and authenticates with origin-bound HttpOnly cookies + relative URLs; a host selector there is a different auth model. Recorded as a locked scope decision (flagged to Ahmet at review; revisit post-v1). |

## 3. Host model

### 3.1 Host identity

On first run a broker generates an Ed25519 keypair at `~/.mux/state/host-key` (created atomically, file mode 0600, symlink-safe open, never logged or exported). `host_id` = lowercase base32, no padding, of the **first 128 bits** of SHA-256(pubkey) — 26 chars. Canonical encoding is defined once in shared code; relay registration always carries the full public key and the relay verifies `hash(publicKey)` matches the claimed `host_id` (§4.4). The relay hostname is `h-<host_id>.relay.supermux.dev`. The keypair authenticates relay registrations only; device auth stays token-based (existing per-host `devices.json` model).

**Threat model (v1, accepted + disclosed):** the host private key is a *root host credential*. Theft permits relay takeover, denial of service, interception of relayed bearer tokens and content, and full host impersonation until clients re-pair or the identity is rotated — because v1 relay framing is not E2E (§4.6). v1 mitigations: 0600 key file; relay rate limits on registration replacement; host-side logging of replacement events; client-visible "host connection replaced/reconnected" diagnostics; a documented "Reset host identity & re-pair" recovery flow. The cryptographic fix is E2E framing (phase 2).

### 3.2 Client-side host records

```
PairedHost {
  recordId:    UUID        // client-generated at add time; the ONLY internal key
  hostId:      string?     // learned from GET /host; null against pre-upgrade brokers
  displayName: string      // user-editable; default = host-provided name
  directUrl:   string?     // loopback or LAN URL (transport policy: §3.5)
  relayUrl:    string?     // https://h-<id>.relay.supermux.dev (null for local-only)
  token:       string      // device bearer token minted by that host (secure store)
  platform:    string?     // last-seen, display/diagnostics ONLY — never a feature gate
  version:     string?     // last-seen broker version — display/diagnostics ONLY
  lastSeenAt:  epochMs
}
```

All internal keying (stores, WS registry, caches, push routing, `(recordId, sessionId)` identity) uses `recordId`, which always exists. `hostId` is backfilled when `GET /host` first succeeds; on backfill the client rekeys nothing (recordId stays the key) but records the mapping. If backfill discovers another record with the same `hostId` (same host added twice via different URLs), the records merge: prefer the one holding a currently-valid token, keep the user-assigned display name and list position, never silently discard either credential (the loser's token is revoked best-effort).

**Storage split (per Sol):** non-secret host metadata lives in the platform's normal versioned store (DataStore / UserDefaults / desktop equivalent); **one token per recordId** goes in the platform secure store. No single encrypted JSON blob whose corruption deletes the whole fleet. Secure-store requirements per target are an initiative-1 deliverable: macOS Keychain; Windows client DPAPI/Credential Manager; Linux desktop Secret Service (libsecret) with a documented encrypted-file fallback + warning; Android Keystore (exists, keeps the v0.10.1 self-heal); iOS production Keychain path confirmed. The current JVM `SecureTokenStore` is an in-memory stub and is NOT shippable.

**Migration:** existing single-host clients transparently become `PairedHost[0]` (new recordId, existing token + baseUrl as directUrl or relayUrl as appropriate). Old brokers that 404 on `/host` simply leave `hostId` null — everything works keyed by recordId until the broker upgrades.

### 3.3 `GET /host` (identity endpoint)

- **Unauthenticated** response: `{ hostId, name, protocolVersion }` — the minimum for pre-pairing verification and adoption probes. Rate-limited; `Cache-Control: no-store`.
- **Authenticated** response adds `{ platform, version }` (host-details screen only).
- `protocolVersion` versions the identity/pairing handshake — it is NOT a feature matrix.

**There is deliberately no capability schema.** Per-host feature knowledge comes from existing per-host APIs: `GET /agents/status` for installed/authed agent CLIs (broker + shared KMP `BrokerApi` already implement it); display panes render only from announced display streams (`stores/displays.ts` pattern); dictation degrades with a clear error; cross-version detection uses the established probe pattern, documented centrally as: 2xx = available · 404 = unavailable/older broker · 401/403 = auth problem · 5xx = exists-but-failing · transport error = host unreachable. Incidental fix riding along: the new-session launcher currently hardcodes all four agents; with the host picker it starts filtering by `/agents/status`.

### 3.4 Pairing protocol (canonical, v1)

QR / link payload:

```
{ v: 1, action: "pair", hostId, name, relayUrl, directUrl?, claimSecret }
```

Flow: `POST /pair/claim { claimSecret, deviceName }` → `{ host: { hostId, name, platform, version }, deviceToken }`.

Rules (these fix real gaps in today's flow, which is trust-on-first-connect and 403s once any device exists):
- The host stores only a **hash** of `claimSecret`; secrets have ≥128 bits entropy, a short expiry (default 10 min), and are **atomically consumed once**.
- Claims are permitted on already-configured hosts (that's the whole add-host flow); minting a claim requires an authenticated device or local wizard UI.
- The claim secret is not a device token and never becomes one; the response's `deviceToken` is the standard bearer.
- Client-side payload validation: `v` known; `action == "pair"`; relay origin on the allowlist (`*.relay.supermux.dev` or user-typed custom, confirmed); URL schemes https (or http for loopback/RFC1918 with the §3.5 policy); hostId format valid; max field lengths enforced; no navigation to arbitrary non-supermux URLs without explicit confirmation.
- Legacy `/pair` + `/pair.json` remain for the web PWA and old links; new native clients use only the claim flow.

### 3.5 Transport preference & direct URLs

Per host, in order: (1) loopback directUrl; (2) HTTPS directUrl; (3) relay. A **plain-HTTP LAN** directUrl is used only after an explicit, labeled opt-in ("unencrypted local connection") — never auto-preferred over relay TLS, because it would leak the bearer token to the LAN. Re-probe direct on network change; remember what worked; host details show the active path ("via relay").

## 4. Relay v1

### 4.1 Scope statement

The relay stores **no durable user content and no agent-session state**. It maintains ephemeral in-memory state: host connection registry (with generations), stream routing, registration challenges, rate counters, and backpressure accounting. One EU instance first (CPX21-class, 20 TB/mo); can co-locate with the push relay.

### 4.2 Tunnel & framing (normative frame set)

Host keeps ONE persistent outbound WSS to `wss://relay.supermux.dev/register`. Client traffic to `https://h-<id>.relay.supermux.dev` is carried over that tunnel as multiplexed streams. Binary protocol, versioned frame header; frame types:

```
request_open   (streamId, method, path, headers)     relay→host
request_data   (streamId, bytes)                     relay→host   [streamed]
request_end    (streamId)                            relay→host   [half-close]
response_open  (streamId, status, headers)           host→relay
response_data  (streamId, bytes)                     host→relay   [streamed]
response_end   (streamId)                            host→relay
duplex_open    (streamId, path, headers)             relay→host   [WebSocket upgrade]
duplex_data    (streamId, bytes, direction implicit) both
duplex_end     (streamId)                            both
reset          (streamId, code)                      both         [abort, incl. slow-consumer]
window_update  (streamId | 0 = connection, credit)   both         [flow control]
ping/pong      ()                                    both
```

Stream IDs: relay allocates odd, host even (parity avoids collisions). Half-close is explicit (`*_end`); either side may `reset` with a code; per-stream deadlines (idle + total) and connection-level fatal errors are defined. Hard limits are protocol constants (configurable): max frame size 1 MiB, max header block 64 KiB, max concurrent streams per tunnel, max buffered bytes per stream and per connection.

### 4.3 Flow control & fairness

Credit-based windows (`window_update`) per stream plus a connection-wide ceiling; fair round-robin scheduling across streams with a small reserved budget for control traffic, so a saturated upload or display stream cannot head-of-line-block chat. Slow consumers get `reset(SLOW_CONSUMER)` on queue overflow. Display/upload streams are throttleable at stream granularity (this is where fair-use caps attach). The soak test asserts: with one saturated upload stream, control-frame round-trip stays under a defined threshold.

### 4.4 Registration (canonical transcript + generations)

Relay issues a one-time nonce; host signs the canonical transcript:

```
{ protocolVersion, relayOrigin, hostId, publicKey, nonce, nonceExpiry, clientRegistrationId }
```

Relay verifies: Ed25519 signature; `hostId == base32(sha256(publicKey)[0..16))`; nonce was issued by this relay, unexpired, consumed exactly once. Each accepted registration gets a **monotonic generation**; the registry deletes entries only by compare-and-delete on `(hostId, generation)` so a stale close handler can never remove a successor. A displaced tunnel receives close reason `replaced` and must obtain a fresh nonce before reconnecting (no tight relogin loops). Registration attempts are rate-limited per IP and per hostId.

### 4.5 Broker integration (transport-neutral seams)

Relayed **HTTP** cannot simply "feed the same handler," and relayed **WebSockets** cannot use Bun's `server.upgrade()` at all. The broker therefore grows two transport-neutral seams, extracted from today's route/upgrade path:

```
handleHttp(request: Request, ctx: ConnectionContext): Promise<Response>
acceptDuplex(request: Request, duplex: DuplexStream, ctx: ConnectionContext): CloseHandle
```

Direct Bun sockets adapt Bun callbacks to `DuplexStream`; relay streams adapt §4.2 frames to the same interface. All WS routes pass through the seam: control `/ws`, terminal, display/VNC, scrcpy, LSP, exposed-port proxy. `ConnectionContext` carries **trusted out-of-band metadata**: `viaRelay`, relay connection id, verified client address. Relay-reserved headers (e.g. `X-Mux-Via`) are stripped from client-supplied frames — security decisions never read forwardable headers. This seam extraction is a meaningful refactor and gets its own implementation-plan phase.

### 4.6 Privacy & abuse posture

Security-model statement (unambiguous): *traffic is encrypted in transit to and from the relay, but v1 is not end-to-end encrypted; the relay infrastructure can technically access forwarded request metadata and content, including device bearer tokens.* Marketing copy phrases this honestly ("encrypted to our relay"). E2E framing + P2P hole-punching are phase 2.

Hard safety ceilings ship in v1 (conservative, configurable): registrations/IP/min; replacement rate/hostId; concurrent streams per tunnel and per client IP; stream-open rate; frame + header sizes; buffered bytes per stream/connection; body size (aligned with `MUX_WEB_UPLOAD_MAX_MB`); handshake/idle/total timeouts. Soft fair-use caps (the D2 "generous free tier") are tuned from dogfood telemetry later — but the hard ceilings above are not deferred.

Relay outage degrades to direct/LAN; clients distinguish "host unreachable via relay" from "host offline" when a direct path works.

## 5. Client UX (iOS, Android, desktop; web PWA per D9)

**Merged fleet list (D5).** One list, every session from every paired host; compact host badge per row (short name + per-host color dot); project groups become per host+project. Filter chip row: `All · <host…> · +` (persisted selection). **One persistent control WS per online host; feature streams (terminal, display, LSP, proxy) open additional host-scoped connections as needed.**

**Offline hosts & the snapshot cache.** Rendering dimmed rows after cold launch requires persistence:

```
HostSnapshot {
  recordId, sessions: SessionSummary[], fetchedAt, brokerVersion?
}
```

Stored OUTSIDE the secure store (normal app storage, schema-versioned). Replaced wholesale on each successful full snapshot; updated incrementally by WS frames while connected; archived sessions are not cached. Unreachable host → greyed group header "MacBook — last seen 2h ago", rows dimmed/non-interactive except retry; snapshots older than 7 days label "stale"; cache is deleted when a host is forgotten.

**Push routing.** Every push payload carries `hostId` (equivalently the recordId mapping) **inside the E2E-encrypted envelope** — the relay stays blind. Notification identity, grouping (threadIdentifier/group), clear-on-open, and tap deep-links key on `(recordId, sessionId)`. Requirements: device push token registered/revoked per host; token rotation propagated to all paired hosts (queued retry for offline ones); forgetting a host best-effort unregisters; a tap for a currently-unreachable host opens the fleet list with that host's group + an unreachable banner; notifications show the host badge when session names collide across hosts.

**Add host flow.** "Add host" → QR scanner + paste-link fallback → §3.4 claim. Host details sheet: rename, forget (local delete + best-effort revoke), transport indicator, "prefer direct" per §3.5.

**New-session launcher** gains a host picker pill (defaults to last-used; hidden with one host) and filters agents by the selected host's `/agents/status`.

## 6. Desktop-as-host (macOS + Linux in initiative 1)

**Broker embedding.** The desktop app supervises a broker sidecar (bundled Bun runtime + broker build + bundled static tmux). State in the standard `~/.mux`.

**Adopt-don't-duplicate (ownership semantics, per Sol):**
1. Acquire a per-user broker-start lock (file lock in `~/.mux/state`); the keep-alive unit and the desktop launcher use the same lock.
2. Probe `:9898` `GET /host`: valid → adopt as `external`.
3. 404 but legacy supermux fingerprint (`/me`) → "This computer already runs an older supermux — upgrade it to manage from the app" (no silent takeover).
4. Foreign process on the port → actionable conflict UI; offer a persisted alternate local port for the sidecar.
5. Ownership recorded as `managed-by-desktop` | `external`. The app never stops/updates/reconfigures an `external` broker without explicit confirmation.

**First-run wizard (D6 choice A, copy per Sol).** Headline: "**This computer is ready to host your agents.** Scan the QR with your phone to connect securely." Checked-by-default control (can be unchecked during setup): "**Keep this computer available when the app is closed and after I sign in**" → installs launchd LaunchAgent (macOS) / systemd `--user` unit with XDG-autostart fallback (Linux). Supporting line: "Remote connections use relay.supermux.dev. Traffic is encrypted in transit; end-to-end relay encryption is not yet available. Learn more." Unchecking means: broker runs while the app is open (QR still works); quitting the app stops an app-managed broker; no keep-alive unit is installed; adopted external brokers are never touched. Settings expose "Host from this computer" and "Keep available in background" as separate toggles. After the first successful claim the wizard shows durable status: "Paired with <device> · Available remotely."

Agent detection: wizard lists detected CLIs from `/agents/status` with install nudges (existing `agents/install.ts` recipes); an agent-less host gets "Install Claude Code" as the primary action. The desktop app auto-pairs to its own local host (loopback + self-minted token) → "This computer" appears in the fleet.

**Windows desktop (initiative 1 scope).** Client-only. Fleet list shows an enabled card: "Host from this PC — native hosting is coming next. Join the preview." → explainer + preview signup (demand measurement) + documented advanced path: Linux broker in WSL pairs as a **separate** host "This PC — Ubuntu (WSL)" (own state/pairing; no cross-boundary path translation, ever).

## 7. Windows-native host (initiative 2 — summary; own spec when scheduled)

Committed architecture (joint verdict): per-user **`mux-sessiond`** daemon launched by a logon Scheduled Task (never a session-0 Windows Service), owning all ConPTYs, per-session Job Objects (kill-on-close teardown), and `@xterm/headless` canonical screens; capability-scoped named pipes `\\.\pipe\supermux-…` with logon-SID DACLs; broker restarts freely, sessiond survives. PowerShell = default shell; Git Bash = optional detected dependency (Claude Code ≥2.1.120 no longer requires it). Agent order: **Codex first** (structured app-server, zero TUI work) → **Claude** (3–4 eng-wks, gated on VT-replay test infra: deterministic recorded-stream fixtures + one credentialed real-Claude release gate) → OpenCode preview; Cursor "native support unverified" until a Win32 CLI ships. **Release gates:** real-Windows probe of Bun's `terminal` (ConPTY) spawn option + pinned Bun version; Win10 1809+ baseline. Start trigger: relay reliable + multi-host shipped + mac/Linux pairing proven + preview-signup demand data.

## 8. Phase-0 portability seam (inside initiative 1, ~1–2 wks)

Narrow, concrete, nothing speculative:
- Terminal/session targets become opaque IDs in wire formats and client code (no tmux-shaped names leaking).
- `GET /host` added (identity handshake: §3.3).
- **tmux absence is no longer broker-fatal**: it disables tmux-backed Claude sessions and persistent-terminal features with clear status; codex/cursor/opencode hosts run without it (`src/shared/preflight.ts` currently makes tmux globally fatal).
- Define (not fully implement) the platform-neutral session-control interface: `create/list/inspect/write/sendKey/resize/captureText/captureStyled/attach/detach/interrupt/terminate/subscribe/snapshot` — tmux adapter is the only implementation for now.
- `LocalEndpoint` type introduced (`unix` today, `windows-pipe` reserved).
- NOT in scope: porting installers/updater/credential helpers/shell spawns (deferred to initiative 2 when requirements are concrete).

## 9. Error handling & edge cases

- **Relay down:** hosts keep serving direct/LAN; clients badge "via relay unavailable"; fleet list serves cached snapshots with last-seen.
- **Host offline:** greyed group (§5); sends disabled with explanation; push silent by nature.
- **Pairing:** expired/used claimSecret → explicit error + QR regenerate; claim on configured hosts is the normal path (§3.4); wizard claim-mint requires local UI.
- **Migration/rekey:** old broker (no `/host`) → recordId-keyed operation indefinitely; hostId backfill merges duplicate records per §3.2 without credential loss.
- **Same repo/session names on two hosts:** identity is `(recordId, sessionId)`; host badge disambiguates (D5 forces this).
- **Duplicate broker on desktop:** ownership semantics per §6; external brokers never killed.
- **Registration races:** generation compare-and-delete (§4.4); `replaced` close reason; fresh-nonce reconnect.
- **Clock skew:** claim expiry validated server-side only.

## 10. Testing

- **Relay protocol:** framing codec round-trip; flow-control window accounting; fairness soak (saturated upload must not delay control frames beyond threshold); generation replacement races; transcript verification (bad sig / wrong hash-binding / replayed nonce / expired nonce all rejected); limit enforcement (oversize frame/header, stream floods).
- **Broker seams:** `handleHttp`/`acceptDuplex` conformance for every WS route (control, terminal, display, scrcpy, LSP, proxy) over both direct and simulated-relay transports; relay-reserved header stripping; `ConnectionContext.viaRelay` correctness.
- **Pairing:** hashed one-time claim (reuse → 4xx), expiry, configured-host claims, payload validation matrix.
- **Migration:** single-host → PairedHost[0]; hostId backfill; duplicate-record merge preserving both credentials; snapshot-cache schema migration.
- **Clients:** PairedHost + secure-store per platform (incl. Linux fallback policy); fleet list states (online/offline/stale/forgotten); per-host push routing incl. token rotation and collision badges; launcher `/agents/status` filtering; wizard flow (QR < 5 s cold start, mocked relay).
- **End-to-end pre-ship:** phone on cellular ↔ NATed desktop host via real relay — pair, chat, terminal, upload (cap check), host reboot recovery, relay-restart recovery.

## 11. Rollout order

1. Phase-0 seam + `GET /host` identity endpoint (broker, no behavior change).
2. Broker transport seams (`handleHttp`/`acceptDuplex`) — refactor with conformance tests, still direct-only.
3. Relay server + broker relay-client; dogfood with THIS server as the first relayed host.
4. Multi-host storage + fleet list + add-host QR on Android (fastest iteration), then iOS, then desktop clients; per-host push routing.
5. macOS/Linux desktop host embedding + first-run wizard; bundled tmux; adopt/ownership semantics.
6. Windows desktop client + preview card; preview-signup counter live.
7. Initiative 2 (Windows-native host) per §7 trigger.

## 12. Open items (non-blocking)

- Soft fair-use cap values (tuned from dogfood telemetry; hard safety ceilings are in §4.6 and NOT deferred).
- Merged-view pagination once fleets exceed ~200 sessions (defer until real).
- E2E pairing-payload upgrade path (payload already versioned per §3.4).
