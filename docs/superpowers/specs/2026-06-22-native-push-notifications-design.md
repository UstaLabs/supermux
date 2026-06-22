# Native Push Notifications — Design (2026-06-22)

## Goal

Native **background** push to the supermux iOS and Android apps — alert the user when an
agent **finishes a turn**, **needs input** (a permission/AskUserQuestion prompt), or
**errors** — even when the app is backgrounded or fully closed.

Hard requirements:

1. **Works out of the box for every self-hoster** — a stranger who installs supermux gets
   push with **no Apple/Google account of their own**.
2. **Privacy-preserving** — notification content (which can reveal your code) is **never
   visible** to Apple, Google, or any central relay.
3. **Doesn't regress** the existing self-hosted Web Push (PWA), and reuses the existing
   notify smarts (mute + "am I already looking at it" presence suppression).

This is the native-app equivalent of the already-shipped PWA Web Push. It is the "separate
follow-up project" anticipated by the Apple Watch spec; once built, **watchOS auto-mirrors
the iPhone's alerts to the wrist** for free.

## Current state (what exists)

- **Web Push — SHIPPED, self-hosting-pure.** Per-broker VAPID keypair; `core/push/{vapid,
  subscriptions,sender}.ts`, service worker `web-app/sw.ts`, REST `/push/vapid-public-key`
  + `/push/subscribe`. Works on iOS 16.4+ PWA (home-screen) + Android/desktop browsers. No
  Apple/Google account, no central server. Global all-or-nothing notify rule +
  viewing-tracker suppression already in place (`core/push/hook.ts` `firePushForReply`).
- **Native push — scaffold only, unwired.** `core/push/native-sender.ts`
  (`createNativePushSender({store, apns, fcm})` routes `ios→apns` / `android→fcm` to
  *injected* `PlatformPushAdapter`s), `device-tokens.ts` (`DevicePushTokenStore`, table
  `device_push_tokens`, migration 011). **No** concrete APNs/FCM adapter, `createNativePushSender`
  is **never called**, **no** device-token registration endpoint. The native iOS app
  (`apps/iosApp`) and native Android app (`apps/android`) have **no** push code.

## The core constraint (why a relay is unavoidable)

- **iOS:** APNs is the *only* OS path to wake a backgrounded/closed native app — there is no
  legal background socket. An APNs auth key is bound to the app's bundle id + an Apple
  Developer Team, so a third-party self-hoster **cannot** mint pushes for the published
  `dev.supermux.ios` app. Free 7-day sideloads can't even *receive* remote push (no
  `aps-environment` entitlement). ⇒ to reach **every** self-hoster's phone, exactly one party
  (the supermux project) must hold the APNs key and operate a relay.
- **Android:** FCM is analogous — the sender credentials are bound to one Firebase project.
  The same relay holds the FCM key.

This is the model Home Assistant, Nextcloud, and Matrix/Element all converged on. The
privacy-preserving ones (Nextcloud, Matrix `event_id_only`) ensure the relay only ever sees
an **encrypted blob + a routing token**, never content. We adopt that bar. (Full landscape:
`~/.mux/domains/claudemux.md` → "Push notifications: self-hosted native-app landscape".)

## Decisions (and why)

- **Approach A — one content-blind relay** (chosen over per-instance APNs keys, and over an
  ntfy/UnifiedPush bridge). It is the *only* model that delivers native, in-app, background
  push to every self-hoster with no Apple/Google account of their own.
- **Encrypted payload** (over a content-free "ping then fetch"). The broker encrypts the
  notification preview with the **device's public key**; the app decrypts on-device in a
  Notification Service Extension. The relay (and Apple/Google) see only ciphertext + a routing
  token + timing. More robust than ping-then-fetch (no network dependency at notify-time) and
  the Nextcloud-proven gold standard.
- **Android = FCM** (out-of-the-box default). One Firebase project held by the publisher; the
  Android app embeds its `google-services.json`; the **relay** holds the FCM service-account
  key. (UnifiedPush — no-Google, reuses the web-push send path — is a noted **future** add-on,
  out of v1 scope.)
- **iOS = APNs + a Notification Service Extension**, distributed via **App Store/TestFlight**
  (required for the push entitlement; free sideload can't receive push). Already on TestFlight.
- **The relay is a mode of the existing supermux binary** (`supermux relay`, entrypoint
  `src/relay/`), bun/TS, reusing the monorepo's types + build + Docker image. Ahmet runs **one**
  instance (e.g. on Coolify). Brokers reach it via a baked-in default URL, overridable with
  `MUX_PUSH_RELAY_URL`.
- **Capability-token model for relay auth/abuse-prevention** (below) — keeps the relay
  zero-config for self-hosters (no per-broker credential to distribute) while making it
  impractical to abuse the APNs/FCM keys, and keeps the relay's knowledge minimal.

## Architecture

Three components: **the relay**, **broker additions**, **native-app additions**.

```
agent finishes ─► broker firePushForReply (mute + presence checks, already exists)
                        │  encrypt(preview, devicePubKey) ─► ciphertext
                        ▼
                  POST {relay}/push {routingToken, ciphertext}
                        │  relay looks up routingToken ─► {platform, pushToken}
                        ▼
                  APNs (.p8/HTTP2)  ──►  iPhone  ─► NSE decrypts ─► notification ─► tap ─► open session
                  FCM  (HTTP v1)    ──►  Android ─► service decrypts ─► notification ─► tap ─► open session
```

### 1. The relay (`supermux relay`, new — Ahmet hosts one)

- Holds the **APNs `.p8`** auth key (+ key id, team id, bundle id) and the **FCM
  service-account JSON**. Supplied via env/secrets; never shipped to brokers.
- Implements the existing `PlatformPushAdapter` interface (from `native-sender.ts`) with two
  **real** adapters: APNs HTTP/2 (ES256 JWT from the `.p8`) and FCM HTTP v1 (OAuth2 from the
  service account). The relay reuses `createNativePushSender`'s `ios→apns / android→fcm`
  routing — i.e. the scaffold's routing logic lives here, where the credentials are.
- **State:** a small store `routingToken → {platform, pushToken}` + rate-limit counters
  (SQLite for v1, single instance). **No** content, **no** broker URL/identity, **no** user
  identity, **no** private keys.
- **Endpoints:**
  - `POST /register {platform, pushToken}` → mints a high-entropy `routingToken`, stores
    `routingToken → {platform, pushToken}`, returns `{routingToken}`. Rate-limited per IP.
  - `POST /push {routingToken, ciphertext}` → looks up the push token, wraps `ciphertext` in
    an APNs/FCM envelope (`mutable-content:1`, opaque `data`), sends. Returns
    `{ok}` / `{ok:false, gone}` (gone ⇒ caller should drop the registration). Rate-limited
    per routingToken + globally (protects the keys from being flagged by Apple/Google).
  - `POST /unregister {routingToken}` → drops the mapping.

### 2. Broker additions (every self-hoster — small)

- **Migration 012:** extend `device_push_tokens` with `routing_token TEXT` and
  `device_pubkey TEXT`. (`DevicePushTokenStore` gains these fields.)
- **`POST /push/device {platform, token, pubkey}`** (authed by the app's existing device
  bearer): the broker calls `{relay}/register`, gets a `routingToken`, stores the row.
  **`DELETE /push/device`** → calls `{relay}/unregister` + removes the row. Add to the web
  channel's `/push` route group (alongside `/push/subscribe`).
- **`core/push/relay-adapter.ts`** — a `RelayPushAdapter`: given a stored device row + a
  `PushPayload`, it ECIES-encrypts `JSON(payload)` with `device_pubkey` and `POST`s
  `{routingToken, ciphertext}` to the relay (no `platform` field needed — the relay already
  knows it from registration). (Encryption: X25519 ECDH + HKDF-SHA256 + AES-256-GCM, or reuse
  the RFC 8291 primitives the web-push lib already brings — resolved in the plan.) Platform
  routing is the relay's job, so the broker uses this adapter **directly** in the fan-out;
  `createNativePushSender`'s `ios/android` split is unused on the broker — it lives on the relay.
- **Wire native into the existing fan-out.** `firePushForReply` already does the mute +
  presence suppression and fans out `sender.sendToDevice(device, payload)`. Extend the fan-out
  to also cover native registrations (`DevicePushTokenStore`) via the `RelayPushAdapter`, so
  **native push inherits the existing suppression for free**. Mirror this on the error-notify
  path (`main.ts:656`) and the broadcast paths (`main.ts:760`, `:3161`) for parity.
- **Payload:** reuse `PushPayload {session, sessionId, text(120-char preview via
  `extractPreview`), kind, ts}`. For native, the broker encrypts the whole payload; the app
  reconstructs title/body/sessionId after decrypt.
- **Gone handling** already exists: `native-sender.ts` removes the device on `{gone:true}`;
  the relay returns `gone` on APNs `410` / FCM `UNREGISTERED`.

### 3. Native apps

**iOS (`apps/iosApp`)** — App Store/TestFlight build:

- Add the **Push Notifications** capability + `aps-environment` entitlement. On launch:
  `UNUserNotificationCenter` authorization → `registerForRemoteNotifications` → APNs token.
- Generate an encryption keypair (`CryptoKit` Curve25519), store the **private** key in the
  **Keychain**, send `{platform:"ios", token, pubkey}` to broker `POST /push/device`. Re-send
  on APNs token change.
- Add a **Notification Service Extension** (`mutable-content:1`): on receipt, decrypt the
  ciphertext with the Keychain private key → set title/body → show. On decrypt failure, show a
  generic fallback ("New activity in supermux").
- Tap handler: read `sessionId` from the decrypted payload → deep-link to the session (reuse
  the existing `SM_OPEN_SESSION`-style routing).
- **watchOS** auto-mirrors iPhone notifications → free wrist alerts (per the watch spec).

**Android (`apps/android`)** — Play/APK build embedding the publisher's `google-services.json`:

- Add the Firebase Messaging SDK. `FirebaseMessagingService` → FCM token (+ `onNewToken`
  refresh → re-register).
- Generate an encryption keypair, store the **private** key in the Keystore /
  EncryptedSharedPreferences, send `{platform:"android", token, pubkey}` to broker
  `POST /push/device`.
- On FCM **data** message: decrypt the ciphertext → post a notification (NotificationManager +
  a "Sessions" channel). Tap → open the session.

**Shared (KMP `apps/shared`):** the registration call + keypair management can live in
commonMain (crypto via a KMP-friendly lib or `expect/actual` platform crypto). DTOs reuse
existing patterns.

## Privacy posture (explicit)

- **The relay sees:** a routing token, an opaque ciphertext, a platform push token,
  timing/volume. **Not:** content, your code, session names, broker URL/identity, or user
  identity.
- **Apple/Google see:** the encrypted blob + the device push token + timing (their normal
  metadata). **Not** content.
- **The relay stores:** `routingToken → {platform, pushToken}` + rate-limit counters. Minimal;
  tokens are rotatable/revocable.
- **Self-hostable:** the relay is open-source; a user who ships their own app build with their
  own APNs/FCM creds can run their own relay and point their broker at it via
  `MUX_PUSH_RELAY_URL`. The default relay is a convenience, not a lock-in.
- This meets the Nextcloud / Matrix-`event_id_only` privacy bar — **stronger** than Home
  Assistant (whose FCM payloads are cleartext).

### Abuse-prevention reasoning

- A `routingToken` is a **capability**: holding it lets you push to exactly one device. It is
  minted only via `/register` and held only by that device's broker.
- `/register` returns a token only for the `pushToken` you supply — and you only possess your
  **own** device's push token. So an attacker can, at worst, spam **themselves**. To target a
  victim they'd need the victim's push token (not public) **and** the victim's public key (held
  only by the victim's broker) to produce a *decryptable* notification — without it the NSE/
  service fails to decrypt and shows nothing or the generic fallback.
- Per-routingToken + global **rate limits** protect the APNs/FCM keys from being flagged.
  Optional defense-in-depth (deferred): the device also registers a verify-key and the broker
  signs payloads.

## Distribution

- **iOS:** App Store or TestFlight (the push entitlement needs a provisioned, non-free build).
- **Android:** Play Store build (or any APK) embedding the publisher's `google-services.json`.
  (F-Droid / no-Google users → future UnifiedPush path.)
- **Relay:** the existing supermux Docker image with the `supermux relay` entrypoint, deployed
  on Coolify. Secrets: APNs `.p8` (+ key/team/bundle ids), FCM service-account JSON.

## Error handling & fallbacks

- **Push token gone** (APNs `410` / FCM `UNREGISTERED`): relay returns `{gone:true}` → broker
  removes the device row (the `native-sender.ts` path already does this).
- **Relay unreachable / not configured:** broker logs + skips native; **web push still fires**;
  retried on the next event. Native is best-effort, exactly like web push today. No crash.
- **NSE / service decrypt failure:** generic fallback notification.
- **Rate-limit hit:** relay returns `429`; broker backs off.
- **No relay at all** (a self-hoster who doesn't want it): feature degrades cleanly to
  web-push-only (the PWA).

## Testing

- **Broker unit:** `/push/device` register/unregister (store row + mock relay), `relay-adapter`
  encryption (ciphertext opaque, decrypts with the test private key), hook fan-out includes
  native devices, `gone → remove`. Extend `native-sender.test.ts` / `device-tokens.test.ts`;
  add `relay-adapter.test.ts`, `push-device-endpoint.test.ts`.
- **Relay unit:** `register → routingToken`; `push →` mocked APNs/FCM `PlatformPushAdapter`;
  rate-limit; `gone` propagation.
- **Integration:** broker → mock relay → assert ciphertext is opaque and routing is correct;
  **mute + presence suppression still applies to native** (extend
  `tests/push-viewing-suppression.test.ts`).
- **Device/manual:** real APNs **sandbox** + FCM test send; NSE/service decrypt on device; tap
  deep-link; watchOS mirror. (Linux host can't build iOS and Android needs the emulator —
  app-side code is static-reviewed here; compile/run verified on Mac/emulator.)

## Non-goals (v1)

- UnifiedPush / no-Google Android path (easy follow-on; reuses the web-push send path).
- iOS Live Activities / progress indicators.
- Rich notification actions (reply-from-notification).
- Per-device notification preferences beyond the existing global mute + presence rule.
- A documented "run your own relay" happy-path (it's *supported* via `MUX_PUSH_RELAY_URL`, but
  the default relay is the documented route for v1).

## Open questions (resolve in the plan)

- **KMP crypto:** which X25519/ECIES approach — a KMP-friendly crypto lib in commonMain, vs
  `expect/actual` over `CryptoKit` (iOS) + Tink/BouncyCastle (Android)? And: reuse RFC 8291
  primitives (already pulled by `web-push`) for symmetry, or a clean ECIES?
- **`device_push_tokens` migration shape:** add columns (preferred) vs a new table.
- **Relay store:** SQLite (v1) — confirm single-instance is fine for scale; Redis only if it
  ever needs to scale out.
- **Fan-out breadth:** wire native into the reply path only, or also the error + broadcast
  paths in v1? (Recommend: all, for parity with web push.)
