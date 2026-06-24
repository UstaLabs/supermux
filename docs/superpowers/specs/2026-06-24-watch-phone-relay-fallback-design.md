# Watch Phone-Relay Fallback — Design (2026-06-24)

## Goal

Let the Apple Watch app reach the broker **through the paired iPhone** when the watch
cannot reach the broker on its own. Today the watch talks to the broker directly (REST
polling over its own Wi‑Fi/cellular). That breaks whenever the watch has no usable network
path to the broker — most importantly for **Tailscale-hosted brokers**: there is no
Tailscale client for watchOS, so the watch can never join the tailnet and can *never* reach
a tailnet-only broker directly, even on Wi‑Fi. The iPhone (which is on the tailnet) can.

This design makes the **phone the primary path** and keeps **direct as a fallback**, so the
watch works for Tailscale users, for plain "no Wi‑Fi" situations, and still works on its own
when the phone is away *and* the broker is publicly reachable.

## Background — what exists today

- **`SupermuxWatch/Watch/WatchBrokerSession.swift`** — the watch's broker client. Pure-Swift
  REST polling via `URLSession.shared` directly against `baseURL`. It does NOT use the KMP
  `Shared.framework` (SKIE/Kotlin-Native doesn't support the watch device arch `arm64_32`),
  so it is a thin hand-rolled client. Endpoints it touches:
  - `GET /sessions` (poll, every 3s)
  - `GET /sessions/{id}/messages` (poll for the open session)
  - `POST /sessions/{id}/message` (send; optimistic local echo, reconciled on next poll)
  - `POST /sessions/{id}/transcribe` (`{draft}` → cleaned text)
  - `GET /files/{id}` (inline photo bytes)
- **`SupermuxWatch/Watch/WatchProvisioning.swift`** — receives `{baseURL, token}` from the
  phone over `WCSession` (applicationContext/userInfo), stores them in the watch Keychain.
- **`Supermux/Watch/PhoneWatchProvisioner.swift`** — iOS side; the app's `WCSessionDelegate`.
  Pushes `{baseURL, token}` to the watch via `updateApplicationContext`. Activated in
  `Supermux/App/SupermuxApp.swift:22`. **No message handler today** → no relay.
- **`Supermux/Pairing/BrokerConfig.swift`** — phone's broker creds: `baseURL` (UserDefaults),
  `token` (Keychain, stored `kSecAttrAccessibleAfterFirstUnlock` → readable when the app is
  woken in the background).
- **Models** (`SupermuxWatch/Watch/WatchModels.swift`) — plain-Swift `Codable` DTOs
  (`SessionInfo`, `LogEntry`, `Attachment`); no KMP dependency.

**This amends the original watch spec** (`2026-06-22-apple-watch-app-design.md`), which chose
an *"independent connection, not relayed through the phone."* That assumption fails for
Tailscale brokers. The watch keeps its own direct client, but it is now the **fallback**, not
the only path.

## Decisions (and why)

- **Phone-first routing, keyed off `WCSession.isReachable`.** Reachability is an *instant*
  boolean (no network round-trip), so there is **no timeout penalty** in either direction —
  unlike a "try direct first, time out, then relay" scheme, which would waste ~5s on every
  request for Tailscale users.
  - Phone reachable → **relay through the phone** (works for Tailscale *and* public brokers).
  - Phone not reachable → **try direct** (keeps a cellular/Wi‑Fi watch working when the phone
    is left behind, *as long as* the broker is publicly reachable).
  - Neither → **Offline**.
- **Direct kept as a fallback** (not removed). It is essentially free (it's today's code) and
  preserves the phone-less-on-a-run case for publicly reachable brokers.
- **Relay everything through one generic envelope** (method + path + body → status + bytes),
  including photos. Simple, and every current/future watch endpoint works through it with no
  per-endpoint wiring.
- **Photo safety net.** Image responses (`GET /files/{id}`) are downscaled by the phone to a
  watch-sized thumbnail before crossing the (low-bandwidth) Watch link; anything still over a
  hard cap is **skipped** (the watch shows its normal placeholder and the image loads later on
  a working direct connection). Per the user: relay everything, accept occasional skips.
- **Relay handler hosted in `PhoneWatchProvisioner`** (already the `WCSessionDelegate`). It
  reads creds from `BrokerConfig`, runs the request, replies with the bytes. Works when the
  iOS app is **woken in the background** by the watch's `sendMessage` (token is
  `AfterFirstUnlock`, confirmed).
- **Real broker HTTP errors pass through untouched.** A `404`/`500` from the broker is a
  valid response carried back as `(body, status)` — it is NOT treated as a transport failure,
  so it does not trigger a pointless fallback/retry. Only *delivery* failures (WCSession
  errorHandler, or the phone reporting it couldn't reach the broker) trigger the backstop.

## Architecture

### Watch side — transport abstraction

Introduce a small transport layer so *what* the watch wants is separated from *how* it
travels. `WatchBrokerSession` stops building `URLRequest`s inline and calls a transport.

```swift
protocol BrokerTransport {
    /// Returns (responseBody, httpStatus). Throws ONLY on transport/delivery failure
    /// (not on a non-2xx broker response — that comes back as (body, status)).
    func request(method: String, path: String, body: Data?, contentType: String?) async throws -> (Data, Int)
}
```

- **`DirectTransport`** — today's `URLSession.shared` logic, lifted verbatim (Bearer token,
  15s timeout). One implementation, used by both the watch's direct path and as the failover.
- **`PhoneRelayTransport`** — packages the request into a WCSession message and awaits the
  reply (see envelope below). Throws `RelayError.unreachable` if `!isReachable`, and
  `RelayError.phoneFailed` if the phone reports a delivery/fetch failure or the WCSession
  errorHandler fires.
- **`RoutingTransport`** — the failover policy:
  ```
  if relay.isReachable {
      do  { return try await relay.request(...) }          // phone-first
      catch is RelayError { return try await direct.request(...) }  // backstop
  } else {
      return try await direct.request(...)
  }
  ```
  It also publishes the current `route` (`.phone` / `.direct` / `.offline`) for the UI,
  updated from the outcome of each request.

`WatchBrokerSession` gets a `BrokerTransport` injected (default: `RoutingTransport` built from
the existing `{baseURL, token}`; a fake is injected in tests). Its `get(...)`, `send(...)`,
`transcribeDraft(...)`, and `loadFile(...)` all route through `transport.request(...)` and
keep their existing JSON-decoding / optimistic-echo / merge logic.

### Relay envelope (wire format over WCSession)

`WCSession` interactive messages carry property-list types, so `Data` values pass directly
(no base64). Watch → phone request, sent via `sendMessage(_:replyHandler:errorHandler:)`:

```
["m": String,            // HTTP method: "GET" | "POST" | "PUT"
 "p": String,            // path, e.g. "/sessions/abc/messages"
 "b": Data?,             // request body bytes (omitted for GET)
 "ct": String?]          // content-type for the body, if any
```

Phone → watch reply (`replyHandler`):

```
["status": Int,          // broker HTTP status (200, 404, 500, …); 0 = phone-side failure
 "body": Data,           // response bytes (may be empty)
 "err": String?]         // human-readable reason when status == 0
```

`status == 0` (or the `errorHandler` firing) → `PhoneRelayTransport` throws `RelayError`,
which `RoutingTransport` catches to attempt the direct backstop.

### Phone side — relay handler

Add to `PhoneWatchProvisioner`:

```swift
func session(_ session: WCSession,
             didReceiveMessage message: [String: Any],
             replyHandler: @escaping ([String: Any]) -> Void) {
    Task { replyHandler(await BrokerRelay.handle(message)) }
}
```

`BrokerRelay` (new, iOS-target, unit-testable):

1. Parse `m`/`p`/`b`/`ct`. Validate the path (must start with `/`; reject anything that isn't
   a relative broker path — defense against a malformed message).
2. Read `BrokerConfig.baseURL` + `BrokerConfig.token`. If unpaired → reply `status: 0`.
3. Build `URLRequest(baseURL + path)`, set `Authorization: Bearer <token>`, method, body,
   content-type. Run via `URLSession.shared`.
4. On transport failure (offline phone, DNS, timeout) → reply `status: 0, err: <reason>`.
5. On success → if `path` starts with `/files/` and the body is an image over the size
   threshold, **downscale** (`UIImage` → max dimension ~640px → JPEG q≈0.6). If the result is
   still over the hard cap (~256 KB) — or it isn't a decodable image — reply `status: 0`
   (skip). Otherwise reply `status: <code>, body: <bytes>`.

Constants (`thumbMaxDimension`, `jpegQuality`, `hardCapBytes`) are tunable and documented in
the file. **Background wake:** when `isReachable`, the watch's `sendMessage` launches the iOS
app in the background to answer; the handler needs only `BrokerConfig` + `URLSession`, both
available in the background.

### UX indicator

`WatchBrokerSession` exposes `route` (`.phone` / `.direct` / `.offline`). In
`SessionsListView`:

- `.phone` → a subtle "via iPhone" glyph (e.g. SF Symbol `iphone`), small, in the header.
- `.direct` → nothing (normal).
- `.offline` → a clear "Offline" state, reusing the existing `status` surface.

The glyph is intentionally low-key (phone-relay is the *normal* path now) and trivial to drop
if it reads as noise.

## End-to-end flows

- **Tailscale broker, phone in pocket:** `isReachable == true` → every call relays through the
  phone → works. Route shows "via iPhone". (Direct is never even attempted.)
- **Public broker, phone left at home, cellular/Wi‑Fi watch:** `isReachable == false` →
  direct → works. Route shows normal.
- **Phone reachable but its broker call fails** (broker down / phone lost tailnet): relay
  throws → backstop to direct → if direct also fails → Offline.
- **Tailscale broker, phone away:** relay unreachable, direct can't reach the tailnet →
  Offline (unavoidable; nothing on the watch can reach a tailnet-only broker without the
  phone).
- **Send a message:** unchanged UX — optimistic local echo appears instantly, reconciled on
  the next poll, regardless of which transport carried the POST.

## Files

**New**
- `apps/iosApp/SupermuxWatch/Watch/BrokerTransport.swift` — protocol, `DirectTransport`,
  `PhoneRelayTransport`, `RoutingTransport`, `RelayError`, `RelayEnvelope` (key constants +
  encode/decode). The envelope + `RoutingTransport` decision logic are pure Swift behind the
  `BrokerTransport`/reachability protocols.
- `apps/iosApp/Supermux/Watch/BrokerRelay.swift` — phone-side fetch + photo-shrink (iOS
  target; unit-testable). Shares the envelope key constants with the watch file (see
  target-membership note under Open Items).

**Modified**
- `apps/iosApp/SupermuxWatch/Watch/WatchBrokerSession.swift` — route all calls through the
  injected `BrokerTransport`; add `route`.
- `apps/iosApp/Supermux/Watch/PhoneWatchProvisioner.swift` — add `didReceiveMessage:replyHandler:`.
- `apps/iosApp/SupermuxWatch/Watch/SessionsListView.swift` — route indicator.
- `apps/iosApp/project.yml` — any new-file target membership (XcodeGen).
- `apps/iosApp/SupermuxTests/` — new unit tests (below).

## Error handling & edge cases

- **Unpaired phone / no creds** → relay replies `status: 0` → backstop to direct.
- **WCSession not supported / not activated on the watch** → `PhoneRelayTransport` reports
  unreachable → direct only.
- **Reply timeout** (phone woke too slowly / went away mid-flight) → `errorHandler` →
  `RelayError` → backstop to direct.
- **Oversized / non-image file** → skipped (`status: 0`); watch shows placeholder, retries on
  a later working connection. Non-`/files/` bodies are never size-capped.
- **Broker non-2xx** → returned as `(body, status)`; `WatchBrokerSession` treats non-2xx the
  same as today (throws → poll shows the diagnostic), no transport fallback.
- **Path validation** on the phone (must be a relative broker path) so a malformed message
  can't redirect the bearer-authed request elsewhere.

## Security

No new secret exposure. The bearer token already lives on *both* devices (watch Keychain +
phone Keychain). WCSession traffic is transported over Apple's encrypted Bluetooth/Wi‑Fi link
between the paired devices. The relay only ever forwards to `BrokerConfig.baseURL` with the
phone's own token; the phone validates the path is broker-relative. Background Keychain read is
already permitted (`AfterFirstUnlock`).

## Testing

**Unit (no devices; via the existing `SupermuxTests` XCTest target):**
- `RoutingTransport` decision logic with fake `BrokerTransport`s + a fake reachability flag:
  - phone reachable → relay used; direct not called
  - relay throws `RelayError` → direct backstop used
  - relay returns `(body, 500)` → returned as-is, NO fallback
  - phone unreachable → direct used directly
  - both fail → error surfaced; route == `.offline`
- `RelayEnvelope` encode/decode round-trips (method/path/body/ct; status/body/err).
- `BrokerRelay` photo-shrink: large image → downscaled under cap; tiny image → untouched;
  non-image over cap → skipped (`status: 0`); path validation rejects non-relative paths.

**Device (real watch + phone; WCSession is unreliable in the simulator):**
1. Tailscale broker, phone near → watch works (relay), indicator shows "via iPhone".
2. Phone off / out of range, public broker, watch on Wi‑Fi → works (direct).
3. Tailscale broker, phone away → Offline.
4. Photo loads over relay; an oversized photo is skipped gracefully (no hang).
5. Send a voice-dictated message over the relay path; cleanup + echo reconcile correctly.

## Non-goals (this iteration)

- **Proactive `NWPathMonitor` switching** — reachability-based routing is enough; a
  network-change nudge can be layered later if the first relay wake ever feels slow.
- **Preferring direct for public brokers to save phone battery** — possible later
  optimization (learn/remember whether direct works per broker URL); not needed for v1.
- Making a tailnet-only broker reachable from a phone-less watch — impossible by definition.

## Risks & mitigations

- **WCSession message size for photos.** Interactive messages are meant to be small.
  *Mitigation:* downscale to a watch thumbnail and hard-cap with skip; non-photo payloads are
  tiny JSON. If photo reliability proves poor, a follow-up can move file transfer to
  `transferFile` (async, no reply handler).
- **Background wake latency.** The first relay call after the iOS app has been suspended may
  add ~1–2s while the system wakes it; subsequent calls are fast. Acceptable; not user-blocking.
- **Test target membership.** The watch-side pure logic must be reachable from the iOS
  `SupermuxTests` target (see Open Items).
- **Build/verify needs a Mac.** watchOS + iOS can't be compiled on this Linux host; the build
  + simulator/device verify run on the remote Mac (recipe located in the plan step).

## Open items for the plan step

- **Testability wiring:** decide how `SupermuxTests` (iOS) exercises the watch-side
  `RoutingTransport`/`RelayEnvelope`. Preferred: give those pure-Swift source files iOS-target
  membership too (they only depend on the `BrokerTransport` protocol + an injectable
  reachability flag, not on watchOS-only APIs), so `@testable import Supermux` can reach them.
  Confirm in `project.yml`.
- **Locate the established remote-Mac SSH host + iOS/watchOS build/sign recipe** (memory:
  `infra` / `claudemux`; prior iOS sessions) for the verify gate.
- **Tune** `thumbMaxDimension` / `jpegQuality` / `hardCapBytes` against real watch thumbnails.
