# WhatsApp channel (via GOWA / whatsmeow) — Design (2026-06-25)

## Goal

Add **WhatsApp** as a third inbound/outbound channel alongside Telegram and the web
PWA, so the user can DM their agent sessions over WhatsApp exactly like they do on
Telegram today: send a message → it routes to a session → replies come back, with
images, documents, and voice notes supported.

**v1 scope = "tier B"**: text + media (images/documents, both directions) + **inbound
voice notes** (delivered as `kind:"voice"` attachments, exactly as Telegram does today).
No outbound TTS/voice replies (the broker has no TTS today), no reactions/edits
(fast-follow), DM-only (no groups in v1).

## Context

Channels implement a single interface — `src/channels/channel.ts`:

```ts
interface Channel {
  readonly name: string
  readonly capabilities: ChannelCapabilities  // multiplexesSessions, supportsReactions, supportsEdit, supportsAttachments
  start(): Promise<void>
  stop(): Promise<void>
  send(action: OutboundAction): Promise<OutboundResult>
  on(event: "inbound", handler: (msg: InboundMessage) => void): void
}
```

Two existing implementations bracket the design space:
- **Telegram** (`src/channels/telegram/`) — `multiplexesSessions: true`, long-lived
  connection (grammy polling), eager attachment download into `FileStore`, access
  control via a deny-by-default allowlist file. WhatsApp mirrors this channel almost
  exactly.
- **Web** (`src/channels/web/`) — `multiplexesSessions: false`, owns a `Bun.serve`
  HTTP/WebSocket server. Relevant because WhatsApp also owns an HTTP listener (for the
  GOWA webhook).

Downstream of `on("inbound")`, the broker's existing classify/deliver path
(`src/main.ts`) handles slash commands, reply-to routing, session creation, dedupe by
`message_id`, and delivery — all keyed off the channel-agnostic `InboundMessage`. A new
channel reuses this unchanged.

**Chat-id dispatch is already data-driven.** Outbound replies resolve their channel by
splitting the `chat_id` prefix (`"web"` → web; `"<x>:..."` → channel `<x>`; bare → legacy
telegram). A `whatsapp:` prefix slots in with no dispatch rework.

**Why GOWA (decided in brainstorming).** WhatsApp has no official bot API we want to
use here (the Cloud API imposes a 24-hour messaging window awkward for async agent
replies). Among unofficial options, `whatsmeow` (Go) is the most battle-tested core — it
powers the WhatsApp bridges Beeper and Element run in production. `whatsmeow` is a Go
library and supermux is TypeScript, so any path runs a Go process; **GOWA**
(`github.com/aldinokemal/go-whatsapp-web-multidevice`) is the actively-maintained REST +
webhook packaging of `whatsmeow` (v8.x, releases within days of this spec). We run it as
a **sidecar binary** and talk HTTP. This isolates WhatsApp's protocol flakiness from the
broker and inherits whatsmeow's stability. Ban risk is low for this usage pattern: a
responder bot on the user's own (secondary) number that never does cold outreach sits
well under the headline ban rates, which target bulk/spam senders.

## Decisions

- **GOWA runs as a sidecar Go binary** on the same host (a `systemd` unit, e.g.
  `mux-gowa.service`), bound to localhost, linked to a **secondary** WhatsApp number as a
  companion device, with its session persisted to its own SQLite store. The broker never
  speaks the WhatsApp protocol — only HTTP to GOWA, and GOWA → broker via webhook.
- **`chat_id` scheme: `whatsapp:<jid>`** (e.g. `whatsapp:1234567890@s.whatsapp.net`),
  following the existing prefix convention. `user_id` = the sender JID.
- **`multiplexesSessions: true`** — one WhatsApp chat routes across the user's sessions,
  identical to Telegram. (No `target_session_id`; the shared classifier picks the session.)
- **Capabilities (v1):**
  `{ multiplexesSessions: true, supportsReactions: false, supportsEdit: false, supportsAttachments: true }`.
  Reactions/edits flip to `true` in the tier-C fast-follow (GOWA supports both).
- **Inbound transport: the channel owns a minimal `Bun.serve` HTTP listener** on a
  localhost port. GOWA POSTs a webhook per received message; the listener verifies an
  **HMAC-SHA256 signature** (shared secret) before processing. Localhost-only → no public
  exposure, no TLS. (We deliberately do **not** reuse the web channel's server — the web
  channel may be disabled, and "each channel owns its transport" keeps boundaries clean.)
- **Voice = a `kind:"voice"` attachment, identical to Telegram.** Neither channel
  transcribes on the inbound path: a voice note is stored in `FileStore` and delivered to
  the session as a voice **attachment** (surfaced as `attachment_kind:"voice"` +
  `attachment_file_id` in the deliver meta — `src/main.ts:2816`), and the agent handles
  the audio. The broker's whisper STT (`src/main.ts:1468`) is a **separate** feature used
  only by the web PWA's voice recorder via a `transcribe` RPC — it is not on any channel's
  inbound path. So WhatsApp voice support = deliver inbound voice as a `kind:"voice"`
  attachment; behavior then matches Telegram exactly, with **no whisper wiring in the
  channel**.
- **Access control: a dedicated `whatsapp` allowlist**, deny-by-default, mirroring
  `telegram/access.ts`. DM sender JID must be on the allowlist. v1 is DM-only; groups are
  out of scope. (Kept separate from the Telegram allowlist so phone numbers and Telegram
  user IDs don't conflate.)
- **Outbound media classification by file extension**, mirroring `telegram/bot-api.ts`
  (image vs document vs audio) — maps to GOWA's `/send/image`, `/send/file`, etc.
- **At-least-one-channel** validation (`src/shared/channels.ts`) extends to count WhatsApp.

## Architecture

```
WhatsApp user ⇄ (secondary number)
      │
      ▼
  GOWA binary  ── whatsmeow ──▶ WhatsApp servers
   (sidecar, localhost:GOWA_PORT, own SQLite session)
      │  ▲
 webhook│  │REST (send text/image/file, fetch media, login status)
 (HMAC) │  │
      ▼  │
  ┌─────────────────────────────────────────────┐
  │  supermux broker (Bun/TS)                    │
  │   WhatsAppChannel                            │
  │    • Bun.serve webhook listener (localhost)  │
  │    • gowa-api client                         │
  │    • inbound normalizer → FileStore          │
  │    • access allowlist                        │
  │    on("inbound") ─▶ shared classify/deliver ─▶ sessions
  └─────────────────────────────────────────────┘
```

## Components

New directory `src/channels/whatsapp/`, mirroring `src/channels/telegram/`. Each unit is
small, single-purpose, and independently testable.

- **`index.ts` — `WhatsAppChannel implements Channel`.**
  - *Does:* owns lifecycle. `start()` boots the webhook listener and probes GOWA login
    status (logs a re-pair hint if logged out, but does **not** block boot — degraded
    start). `stop()` closes the listener. `send()` dispatches `op:"reply"` to the GOWA
    client (text + per-file media). `on("inbound")` registers handlers; the webhook
    listener fires them after normalization + access check.
  - *Depends on:* `gowa-api.ts`, `webhook.ts`, `inbound.ts`, `access.ts`, `FileStore`,
    `shared/log`.
  - *Construction:* `new WhatsAppChannel({ gowaUrl, gowaBasicAuth?, webhookPort, webhookSecret, fileStore })`.

- **`gowa-api.ts` — typed REST client for GOWA.**
  - *Does:* `sendText`, `sendImage`, `sendFile` (and `sendAudio` for completeness),
    `loginStatus`/`devices`, and `fetchMedia(url|id) → bytes`. Pure HTTP (`fetch`),
    optional Basic-Auth header. Classifies outbound files by extension → the right
    endpoint, mirroring `telegram/bot-api.ts`'s `IMAGE_EXT`/`VOICE_EXT` sets.
  - *Depends on:* nothing but `fetch` + config. Fully mockable (inject `fetch`).
  - *Note:* exact GOWA endpoint paths/payload shapes are pulled from GOWA's OpenAPI
    (`docs/openapi.yaml`) during implementation — this unit is the single place that
    knows them.

- **`inbound.ts` — `normalizeWhatsAppInbound(payload, { gowa, fileStore }) → InboundMessage`.**
  - *Does:* maps a GOWA webhook payload to `InboundMessage`: `channel:"whatsapp"`,
    `chat_id:"whatsapp:<jid>"`, `message_id`, `user`/`user_id` (JID/pushname), `ts`,
    `text` (body or media caption), `reply_to_message_id` if quoted. For media: fetches
    bytes via `gowa.fetchMedia(...)`, `fileStore.put({ kind, mime, name, origin:"whatsapp-dl", bytes })`,
    emits one `InboundAttachment` with the synthetic `file_id`. Maps WhatsApp media types
    → `AttachmentKind` (`image→photo`, `audio/ptt→voice`, `document→document`, etc.).
    Best-effort: a media-fetch failure logs and drops the attachment but still delivers
    the message (matches Telegram).
  - *Depends on:* `gowa-api.ts`, `FileStore`. Pure given an injected `gowa` + `fileStore`
    → unit-testable with sample payloads.

- **`webhook.ts` — the localhost HTTP listener.**
  - *Does:* `Bun.serve` on `webhookPort` (127.0.0.1). On POST: read raw body, verify
    `HMAC-SHA256(body, secret)` against GOWA's signature header (constant-time compare);
    on mismatch → `401` + warn. On success → JSON-parse, hand to a callback
    (`(payload) => void`) that the channel wires to `normalizeWhatsAppInbound` + access +
    inbound-handlers. Ignores non-message events (presence/receipts) for v1.
  - *Depends on:* node `crypto` for HMAC. The verify function is a pure, testable helper.

- **`access.ts` — WhatsApp allowlist (deny-by-default).**
  - *Does:* `loadWhatsAppAccess(path) → { allowFrom: string[] }` (reads the `whatsapp`
    section of the access file; missing/malformed → empty list) and
    `isWhatsAppAllowed(access, senderJid) → boolean`. v1 DM-only. Mirrors the philosophy
    of `telegram/access.ts`.
  - *Depends on:* `fs`. Pure → trivially unit-testable.

**Broker wiring (`src/main.ts`, `src/shared/channels.ts`):**
- Read config: `MUX_WHATSAPP_GOWA_URL` (enable flag = presence of this), optional
  `MUX_WHATSAPP_GOWA_BASIC_AUTH`, `MUX_WHATSAPP_WEBHOOK_PORT`, `MUX_WHATSAPP_WEBHOOK_SECRET`.
- Construct `WhatsAppChannel` when enabled; register it in the `channels` record under
  key `"whatsapp"` (the prefix-dispatch already routes `whatsapp:` replies to it).
- `requireAtLeastOneChannel(hasTelegram, hasWeb, hasWhatsApp)` — add the third arg + update
  the error string.
- Wire `whatsapp.on("inbound", …)` to the **same** classify/deliver handler the Telegram
  block uses. Prefer extracting that handler into a shared function both channels call
  (it already operates on channel-agnostic `InboundMessage`); duplication is the fallback
  if extraction proves invasive.

## Data flow

**Inbound** (WhatsApp → session):
1. User sends a WhatsApp message → GOWA (whatsmeow) receives it, auto-downloads media.
2. GOWA POSTs a webhook to `127.0.0.1:WEBHOOK_PORT` with an HMAC signature header.
3. `webhook.ts` verifies HMAC → parses JSON → callback.
4. Access check: `isWhatsAppAllowed(access, senderJid)`; not allowed → drop + warn.
5. `normalizeWhatsAppInbound` → `InboundMessage` (media → `FileStore`; voice arrives as
   `kind:"voice"`).
6. Channel fires `on("inbound")` handlers.
7. Broker's shared classify/deliver: slash command? reply-to an agent message? new
   session? → dedupe by `message_id` → deliver to the session (multiplexed like Telegram).
   A voice note rides through as a `kind:"voice"` attachment in the deliver meta — exactly
   as a Telegram voice note does (no inbound transcription in either channel).

**Outbound** (session → WhatsApp):
1. Agent calls the `reply` shim tool with `chat_id:"whatsapp:<jid>"`, text, optional files.
2. Broker resolves channel `"whatsapp"` from the prefix → `WhatsAppChannel.send(action)`.
3. `send()` strips the prefix; sends text via `gowa.sendText`; for each file, classifies
   by extension and calls `gowa.sendImage`/`sendFile`; returns the GOWA message id.
4. Broker logs the outbound to `MessageStore` (same as every channel).

## Config & ops

**Broker env (per-session `--settings`/app-config):**
- `MUX_WHATSAPP_GOWA_URL` — e.g. `http://127.0.0.1:3000` (enable flag).
- `MUX_WHATSAPP_GOWA_BASIC_AUTH` — optional `user:pass` if GOWA's REST is protected.
- `MUX_WHATSAPP_WEBHOOK_PORT` — localhost port for the inbound listener.
- `MUX_WHATSAPP_WEBHOOK_SECRET` — shared HMAC secret (also set on the GOWA side).

**GOWA side (documented as an ops step, not code in this repo):**
- Run the binary/`systemd` unit with `--webhook=http://127.0.0.1:WEBHOOK_PORT/...`,
  `--webhook-secret=…`, auto-download media enabled, bound to localhost.
- One-time pairing: scan the QR (GOWA UI/endpoint) or use a pairing code with the
  secondary number. Session persists across restarts.

These ops steps go in a short `docs/` runbook produced alongside the implementation.

## Error handling

- **GOWA unreachable** → `send()` returns `{ ok:false, error }`; the broker logs and does
  not crash (same contract as Telegram's `send`).
- **WhatsApp logged out / session expired** → `start()` logs a re-pair hint and starts
  **degraded** (no boot block); sends fail with a clear error until re-paired. Optional:
  surface a notice to the owner.
- **Webhook HMAC mismatch** → `401`, warn, drop. Guards the localhost listener.
- **Webhook retries / duplicates** → rely on the broker's existing `message_id` dedupe in
  the deliver path (GOWA may re-POST on non-2xx; the listener returns `200` promptly after
  enqueueing to minimize retries).
- **Media fetch failure** → log, drop the attachment, still deliver the text (best-effort,
  matches Telegram's `eager_download_failed_dropping_attachment`).
- **Reconnect storms** (a ban trigger) are handled inside whatsmeow/GOWA; the broker never
  hammers GOWA — it only reacts to webhooks and sends on demand.

## Testing

- **`access.ts`** — allow/deny by JID, deny-by-default on missing/malformed file.
- **`webhook.ts`** — HMAC verify accepts a correctly-signed body, rejects a tampered body
  / wrong secret; non-message events ignored.
- **`inbound.ts`** — sample GOWA payloads (text, image, document, voice, quoted-reply) →
  expected `InboundMessage`; media path calls `fileStore.put` with the right `kind`;
  fetch-failure drops the attachment but keeps the message. (Mirrors
  `telegram/inbound`-style tests with an injected fake `gowa` + `fileStore`.)
- **`gowa-api.ts`** — request shaping (URL, headers incl. Basic-Auth, body) per endpoint
  and outbound extension→endpoint classification, with an injected mock `fetch`.
- **Fake-GOWA stub** — a tiny local HTTP server to exercise `send()` end-to-end and to
  drive the webhook listener (sign a payload, POST it, assert an `InboundMessage` reaches a
  registered handler) — no real WhatsApp needed. Mirrors the repo's existing
  channel/transcription test style.
- **`requireAtLeastOneChannel`** — extended truth table incl. WhatsApp-only.
- **Typecheck/build** the broker (`bun run` build/test as the repo defines).
- **Manual acceptance** (post-merge, with the secondary number paired): send/receive
  text, an image, a document, and a voice note; confirm routing to a session, the reply
  path back, and that the voice note arrives as a `kind:"voice"` attachment (Telegram parity).

## Out of scope (fast-follows)

- **TTS / outbound voice replies** — the broker has no TTS today; a separate project.
- **Reactions + edits (tier C)** — flip the two capability flags and add the
  corresponding `gowa-api` calls + outbound `op` handling.
- **Groups** — v1 is DM-only; group allow policy + `requireMention` parity with Telegram
  is later work.
- **A WhatsApp command menu** — WhatsApp has no Telegram-style command-menu UI; slash
  commands still work as plain text via the shared classifier.
- **Provisioning GOWA from the broker** — GOWA is operated as an independent service; the
  broker assumes it is running and reachable.

## Open questions

- **Exact GOWA endpoint paths & webhook payload shape** — resolved against GOWA's
  `docs/openapi.yaml` + `docs/webhook-payload.md` during implementation; isolated to
  `gowa-api.ts` + `inbound.ts` so nothing else depends on them.
- **Shared-handler extraction vs duplication** for the inbound wiring — decided during
  implementation based on how cleanly the Telegram handler factors out (preference:
  extract).
