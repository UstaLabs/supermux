# Desktop-as-Host, Multi-Host Clients & Free Relay — Design

**Date:** 2026-07-11
**Status:** Approved by Ahmet (web chat brainstorm, session `desktop-as-host-brainstorm`)
**Evidence base:** joint Claude(Fable 5)×Codex(GPT-5.6-Sol) research — codebase audit (file:line-cited), 25/25 adversarially-verified web claims, 2 debate rounds. Artifacts: `~/.cache/supermux-windows-research/`; verdict logged in `~/.mux/domains/claudemux.md` (2026-07-11).

## 1. Summary

Every supermux client gains a **multi-host** model: a list of paired brokers instead of today's single `(baseUrl, token)`. Desktop apps additionally **embed the broker** so the desktop machine is itself a host ("This computer"), paired to phones via QR in the first-run wizard. Reachability everywhere comes from a **free bundled relay** (host dials out; phones connect through `relay.supermux.dev`). Windows ships **client-first**; the **native Windows host** (no WSL) is the committed next initiative on a `mux-sessiond` + ConPTY architecture.

Goals, in order:
1. Onboarding collapses to: *install desktop app → QR appears → scan with phone → agents from anywhere.*
2. No regression for existing server-hosted users; a server is just another host in the list.
3. Honest per-host capability display (agents present, display support, host-preview state).

Non-goals (v1): P2P hole-punching, E2E relay framing, Telegram/WhatsApp multi-host semantics, relay multi-region, Windows-native hosting (initiative 2), web-PWA host selector.

## 2. Decisions locked (by Ahmet unless noted)

| # | Decision | Choice |
|---|----------|--------|
| D1 | Reachability strategy | Own the tunnel (bundled relay), not LAN-only, not Tailscale-dependent |
| D2 | Relay pricing | **Free**, fair-use caps on heavy flows; natural paid tier later |
| D3 | Windows host approach | **Native, no WSL** (joint research verdict; WSL bridge rejected) |
| D4 | Sequencing | Initiative 1 = multi-host + relay + mac/Linux hosts + Windows client; Initiative 2 = Windows-native host (gated on 1 stable) |
| D5 | Session list across hosts | **B: merged all-hosts fleet list** with host badges (+ filter chip, host picker in new-session) |
| D6 | Desktop hosting eagerness | **A: host by default** — first-run wizard IS the QR screen; hosting + login keep-alive default-on, untickable at setup, toggleable in Settings |
| D7 | POSIX backend | Keep tmux indefinitely; bundle a static tmux in desktop apps; platform-neutral session-control protocol so sessiond convergence stays optional |
| D8 | tmux replacement scope | Only on Windows, as `mux-sessiond` (ConPTY + Job Objects + xterm-headless + named pipes), Codex backend first, Claude second |

## 3. Host model

**Host identity.** On first run a broker generates an Ed25519 keypair under `~/.mux/state/host-key`. `host_id` = base32(truncated SHA-256(pubkey)) (~13 chars, e.g. `h7k2m9qfp3xw4`). The relay hostname is `h-<host_id>.relay.supermux.dev`. The keypair signs relay registrations only; device auth stays token-based (existing `devices.json` model, per host).

**Client-side host record** (stored per client, replacing the single url+token):

```
PairedHost {
  hostId:      string      // from pairing payload
  displayName: string      // user-editable; default = host's os hostname
  directUrl:   string?     // LAN/private URL if known (e.g. http://192.168.1.20:9898)
  relayUrl:    string      // https://h-<id>.relay.supermux.dev
  token:       string      // device bearer token minted by that host
  capabilities: HostCapabilities   // last-seen snapshot, refreshed on connect
  lastSeenAt:  epochMs
}
```

`SecureTokenStore` (KMP `expect/actual`) grows from one `(token, baseUrl)` to an ordered list of `PairedHost` (JSON blob in the same secure store; Android self-heal behavior from v0.10.1 carries over). Migration: existing single pair becomes `PairedHost[0]` with `hostId` back-filled on first connect (broker exposes `GET /host` → `{hostId, name, capabilities}`).

**Capabilities** (server-computed, in `GET /host` and the WS hello):

```
HostCapabilities {
  agents:        AgentKind[]   // detected CLIs
  display:       boolean       // Xvfb/mac provider present
  whisper:       boolean       // server-side STT available
  platform:      "linux" | "macos" | "windows" | "wsl"
  version:       string
}
```

Clients render per-host affordances from this — never from platform assumptions. (The Windows "Host from this PC — preview" card is a *client-side* affordance of the Windows desktop build, not a capability: a Windows machine without a broker reports nothing.)

**Connection preference.** Per host: try `directUrl` (if set) with a short timeout, else `relayUrl`; remember what worked, re-probe direct on network change. The phone never needs to know which path is active beyond a small "via relay" indicator in host details.

## 4. Relay v1

**Topology.** Host keeps ONE persistent outbound WSS to `relay.supermux.dev` (`/register`, authenticated by a signature over a server nonce with the host key). Phone traffic to `h-<id>.relay.supermux.dev` (TLS terminated at relay) is piped into that tunnel. Multiplexing: yamux-style framing — `open(streamId, method+path+headers)` / `data` / `end` / `reset` — implemented in the broker as a `RelayTransport` that feeds the same request handler as direct HTTP/WS (the broker cannot tell relayed from direct requests apart, aside from a `X-Mux-Via: relay` marker for logging).

**Properties & policy:**
- Stateless relay; no session state, no storage. One EU box first (CPX21-class, 20 TB/mo); geo expansion later.
- Fair-use: per-host soft caps on VNC/display streams and uploads (config-driven; generous defaults; enforcement = throttle, not cut).
- Privacy copy (marketing-accuracy rule): "encrypted to our relay" — NOT "end-to-end". E2E framing + P2P are explicitly phase 2.
- Relay outage degrades to LAN/direct only; clients show "host unreachable via relay" distinctly from "host offline" when a direct path still works.
- Keepalive ping 30s; host reconnects with jittered backoff; registration is last-writer-wins per host_id (a second broker with a stolen key kicks the first — acceptable v1, revisit with E2E).

**Broker changes:** `src/core/relay-client/` (new): dial, register, stream demux → existing `fetch`-style handler + WS upgrade path. Reuses the push relay's operational profile; can co-locate with it.

## 5. Client UX (iOS, Android, desktop; web PWA excluded)

**Merged fleet list (D5).**
- One list, every session from every paired host. Each row gains a compact host badge (short name, colored dot per host). Existing project-grouping stays; groups are per host+project.
- Host filter chip row at top: `All · <host1> · <host2> · +` (the `+` = Add host). Selecting a chip filters; "All" is default and persisted.
- Offline/unreachable host: its sessions collapse into a greyed group header "MacBook — unreachable" (rows visible but dimmed, non-tappable except a retry affordance). No stale-forever spinners: last-seen timestamp shown.
- Connections: one WS per paired host. Phone-side store keys everything by `(hostId, sessionId)`. Push registration happens per host (each host's broker registers the device token with its own relay config as today).
- New-session launcher gains a host picker pill (defaults to last-used host; hidden when only one host).

**Add host flow.** "Add host" → QR scanner (+ paste-link fallback). Pairing payload (QR/link) = existing one-time pairing link format, extended: `{hostId, name, relayUrl, directUrl?, oneTimeToken}`. Scanning calls the host's `/pair/claim` (direct if reachable, else relay) exactly like today's add-device flow — the trust model is unchanged, only transport differs.

**Renames/removal.** Host details sheet: rename display name, forget host (drops tokens locally + best-effort revoke call), toggle "prefer direct".

## 6. Desktop-as-host (macOS + Linux in initiative 1)

**Broker embedding.** The desktop app manages a broker as a supervised sidecar (own process, not in-proc): bundled Bun runtime + broker build + **bundled static tmux** (invisible dependency, D7). State in the standard `~/.mux`. If a broker is already running on :9898 (existing power users), the app detects and adopts it instead of spawning a second (port probe + `GET /host` handshake).

**First-run wizard (D6, choice A).**
1. Launch → sidecar broker starts → host key + relay registration → wizard shows the pairing QR immediately. Copy: "This computer is ready. Scan with your phone." Sub-line: "Hosting stays on in the background and starts at login" with an untick control.
2. Keep-alive install: macOS `launchd` LaunchAgent; Linux systemd `--user` unit (falls back to XDG autostart). Quitting the app leaves the host running (menu-bar/tray affordance mirrors this honestly).
3. Agent detection: wizard lists detected CLIs (claude/codex/cursor/opencode) with install nudges for missing ones (existing `agents/install.ts` recipes). An empty machine gets a "install Claude Code" primary action — a host with zero agents is an empty room.
4. The desktop app auto-pairs to its own local host (loopback direct URL + self-minted token); it appears as "This computer" in the fleet.

**Windows desktop (initiative 1 scope).** Client-only. The fleet list shows an enabled card: "Host from this PC — native hosting is coming next. Join the preview / Use a WSL host today." Opens an explainer + preview signup (demand measurement) + documented advanced path: install the Linux broker in WSL, which pairs as a **separate** host labeled "This PC — Ubuntu (WSL)" (own state/pairing; no path translation between worlds, ever).

## 7. Windows-native host (initiative 2 — summary; own spec when scheduled)

Committed architecture (joint verdict): per-user **`mux-sessiond`** daemon launched by a logon Scheduled Task (never a session-0 Windows Service), owning all ConPTYs, per-session Job Objects (kill-on-close teardown), and `@xterm/headless` canonical screens; capability-scoped named pipes `\\.\pipe\supermux-…` with logon-SID DACLs; broker restarts freely, sessiond survives. PowerShell = default shell; Git Bash = optional detected capability (Claude Code ≥2.1.120 no longer requires it). Agent order: **Codex first** (structured app-server, zero TUI work) → **Claude** (3–4 eng-wks, gated on VT-replay test infra: deterministic recorded-stream fixtures + one credentialed real-Claude release gate) → OpenCode preview; Cursor "native support unverified" until a Win32 CLI ships. **Release gates:** real-Windows probe of Bun's `terminal` (ConPTY) spawn option + pinned Bun version; Win10 1809+ baseline. Start trigger: relay reliable + multi-host shipped + mac/Linux pairing proven + preview-signup demand data.

## 8. Phase-0 portability seam (inside initiative 1, ~1–2 wks)

Narrow, concrete, nothing speculative:
- Terminal/session targets become opaque IDs in wire formats and client code (no tmux-shaped names leaking).
- `HostCapabilities` added to `GET /host` + WS hello; clients render from it.
- tmux preflight scoped to the Claude backend only (`src/shared/preflight.ts` currently makes tmux globally fatal; codex/cursor/opencode hosts must not require it).
- Define (not fully implement) the platform-neutral session-control interface: `create/list/inspect/write/sendKey/resize/captureText/captureStyled/attach/detach/interrupt/terminate/subscribe/snapshot` — tmux adapter is the only implementation for now.
- `LocalEndpoint` type introduced (`unix` today, `windows-pipe` reserved).
- NOT in scope: porting installers/updater/credential helpers/shell spawns (deferred to initiative 2 when requirements are concrete).

## 9. Error handling & edge cases

- **Relay down:** hosts keep serving direct/LAN; clients badge "via relay unavailable"; fleet list keeps cached rows with last-seen.
- **Host offline:** greyed group; inbound send disabled with explanation; push obviously silent.
- **Pairing failures:** one-time token expiry/reuse → same errors as the existing add-device flow; QR regenerates.
- **Two hosts, same repo, same session names:** all identity is `(hostId, sessionId)`; display disambiguation = host badge (D5 forces this).
- **Existing single-host users:** transparent migration to `PairedHost[0]`; zero re-pairing.
- **Duplicate broker on desktop:** adopt-don't-duplicate rule (port 9898 probe) — protects the current live-server setup on this very machine.
- **Clock skew:** pairing tokens validated server-side only (no client-time trust), as today.

## 10. Testing

- Broker: unit tests for relay framing codec + `RelayTransport` demux against the real request handler (loopback fake relay in-process); host-key/registration signature tests; capability computation tests.
- Relay server: protocol tests + a soak test with a slow-reader phone (backpressure must propagate to the host tunnel, reusing the existing `getBufferedAmount` discipline).
- Clients: KMP shared tests for `PairedHost` store + migration; UI tests for fleet-list badge/filter/offline states and the new-session host picker; wizard flow test (QR shown < 5s from cold start, mocked relay).
- End-to-end (pre-ship): phone on cellular ↔ desktop host behind NAT via the real relay — pair, chat, terminal, upload, VNC-off (cap check), host reboot recovery.
- Windows initiative gates are in §7 (VT-replay infra is part of that initiative, not this one).

## 11. Rollout order

1. Phase-0 seam + `GET /host`/capabilities (broker, no behavior change).
2. Relay server + broker relay-client; dogfood with THIS server as the first relayed host.
3. Multi-host storage + fleet list + add-host QR on Android (fastest iteration), then iOS, then desktop clients.
4. macOS/Linux desktop host embedding + first-run wizard; bundled tmux.
5. Windows desktop client + preview card; preview-signup counter live.
6. Initiative 2 (Windows-native host) per §7 trigger.

## 12. Open items (non-blocking)

- Fair-use cap numbers (decide from dogfood telemetry; start uncapped with logging).
- QR payload versioning field for future E2E pairing upgrade.
- Relay abuse controls (per-host token-bucket on `open` frames) — sized during relay implementation.
- Whether "All hosts" merged view needs pagination once fleets exceed ~200 sessions (defer until real).
