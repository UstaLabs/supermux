# WhatsApp Channel (via GOWA) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `whatsapp` channel to the supermux broker that mirrors the Telegram channel, talking to a co-located GOWA (whatsmeow) sidecar over HTTP — outbound via GOWA's REST send endpoints, inbound via an HMAC-verified webhook the channel hosts on localhost.

**Architecture:** A new `WhatsAppChannel implements Channel` in `src/channels/whatsapp/`, decomposed into small units: a GOWA REST client (`gowa-api.ts`), an inbound normalizer (`inbound.ts`), an HMAC verifier (`webhook-verify.ts`), a localhost `Bun.serve` webhook listener (`webhook.ts`), and a deny-by-default phone allowlist (`access.ts`). It registers in `src/main.ts` beside Telegram; the existing Telegram inbound handler is refactored into a shared `wireInbound(ch)` closure both channels call (DRY). v1 = text + media (image/document) + inbound voice notes delivered as `kind:"voice"` attachments (Telegram parity); DM-only; no reactions/edits/TTS.

**Tech Stack:** TypeScript, Bun (`bun test`, `Bun.serve`), node `crypto` (HMAC), GOWA REST API v8.x. Follows existing patterns in `src/channels/telegram/`.

---

## Reference: verified facts this plan relies on

**Channel interface** (`src/channels/channel.ts`): `Channel = { name, capabilities, start(), stop(), send(action), on("inbound", handler) }`. `InboundMessage = { channel, chat_id, message_id, user, user_id, ts, text?, reply_to_message_id?, target_session_id?, attachments? }`. `InboundAttachment = { kind, file_id, mime?, size?, name? }`. `OutboundAction` reply variant = `{ op:"reply", chat_id, text, reply_to?, files?, ... }`.

**AttachmentKind** (`src/core/files/kinds.ts:2`): `"photo" | "document" | "voice" | "audio" | "video_note"`.

**FileStore.put** (`src/core/files/store.ts`): `put(input: { kind: AttachmentKind; mime?; name?; session?; device?; origin: AttachmentOrigin; bytes: Uint8Array|Buffer }): Promise<{ file_id: string; size: number }>`. `AttachmentOrigin` (`store.ts:16`) = `"web-upload" | "telegram-dl" | "session-outbound"` — **add `"whatsapp-dl"`**.

**GOWA REST (v8.x, base `http://host:3000`, Basic-Auth, optional `X-Device-Id` header):**
- `POST /send/message` (JSON `{ phone, message, reply_message_id? }`) → `{ results: { message_id } }`.
- `POST /send/image` (multipart `phone`, `image` file, `caption?`, `reply_message_id?`).
- `POST /send/file` (multipart `phone`, `file` binary, `caption?`, `reply_message_id?`).
- `POST /send/audio` (multipart `phone`, `audio` file, `reply_message_id?`).
- `phone` = full JID `<number>@s.whatsapp.net`. Send response message id is at `results.message_id`.
- `GET /app/status` → `{ results: { is_connected, is_logged_in } }`.
- `GET /message/{id}/download?phone=<jid>` → `{ results: { file_url } }` (only needed when GOWA auto-download is OFF).
- Served media path from webhook is under `statics/…`, fetchable at `http://host:3000/<path>`.

**GOWA webhook** (configured GOWA-side with `--webhook`, `--webhook-secret`): POSTs JSON
`{ event, device_id, session_id?, payload: { id, chat_id, from, from_name, timestamp(RFC3339), is_from_me, body, image?|audio?|document?|video?|sticker?, replied_to_id?, quoted_body? } }`.
Media value is a **bare string path** (auto-download on, no caption), or `{ path, caption }` (with caption), or `{ url, filename }` (auto-download off). Voice notes arrive under `audio` as `.ogg`. HMAC: header `X-Hub-Signature-256: sha256=<hex>` = HMAC-SHA256 of the **raw body** keyed by the secret. The webhook also fires for our own sends with `is_from_me: true` — filter those out.

**Broker wiring points** (`src/main.ts`): env object lines 229-235; Telegram construct lines 475-477; `channels` record line 478; `requireAtLeastOneChannel(hasTelegram, webEnv.enabled)` lines 852-853; Telegram inbound handler `if (telegram) { … } // end if (telegram)` lines 2627-2839; start `if (telegram) await telegram.start()` line 3245; stop lines 3270-3272. App config: `AppConfigEnv` (`app-config.ts:103-109`), `AppConfig` (17-39), `resolveAppConfig` `firstNonEmpty` (123-146), `SECRET_FIELDS` (220). Tests: `bun test`, `import { describe, expect, test } from "bun:test"`.

---

## File structure

**Create:**
- `src/channels/whatsapp/access.ts` — phone allowlist (deny-by-default).
- `src/channels/whatsapp/access.test.ts`
- `src/channels/whatsapp/webhook-verify.ts` — HMAC-SHA256 verify.
- `src/channels/whatsapp/webhook-verify.test.ts`
- `src/channels/whatsapp/gowa-api.ts` — typed GOWA REST client.
- `src/channels/whatsapp/gowa-api.test.ts`
- `src/channels/whatsapp/inbound.ts` — webhook payload → `InboundMessage`.
- `src/channels/whatsapp/inbound.test.ts`
- `src/channels/whatsapp/webhook.ts` — request handler + `Bun.serve` listener.
- `src/channels/whatsapp/webhook.test.ts`
- `src/channels/whatsapp/index.ts` — `WhatsAppChannel implements Channel`.
- `src/channels/whatsapp/index.test.ts`
- `src/shared/channels.test.ts` — truth-table test (none exists today).

**Modify:**
- `src/core/files/store.ts` — add `"whatsapp-dl"` to `AttachmentOrigin`.
- `src/shared/channels.ts` — add `hasWhatsapp` param.
- `src/core/settings/app-config.ts` — add 5 WhatsApp config fields + env + secrets.
- `src/main.ts` — env, construct, register, start/stop, `wireInbound` refactor.

---

### Task 1: Add `whatsapp-dl` attachment origin

**Files:**
- Modify: `src/core/files/store.ts:16`

- [ ] **Step 1: Edit the `AttachmentOrigin` union**

Change:
```ts
export type AttachmentOrigin = "web-upload" | "telegram-dl" | "session-outbound"
```
to:
```ts
export type AttachmentOrigin = "web-upload" | "telegram-dl" | "whatsapp-dl" | "session-outbound"
```

- [ ] **Step 2: Typecheck**

Run: `cd /home/ahmet/.mux/worktrees/supermux-3962b5bf/3dfa2584-c1da-46c9-856a-851e123801ab && bunx tsc --noEmit`
Expected: no new errors (a string literal was added to a union).

- [ ] **Step 3: Commit**

```bash
git add src/core/files/store.ts
git commit -m "feat(whatsapp): add whatsapp-dl attachment origin"
```

---

### Task 2: Extend `requireAtLeastOneChannel` for WhatsApp (TDD)

**Files:**
- Modify: `src/shared/channels.ts`
- Test: `src/shared/channels.test.ts` (create)

- [ ] **Step 1: Write the failing test**

Create `src/shared/channels.test.ts`:
```ts
import { describe, expect, test } from "bun:test"
import { requireAtLeastOneChannel } from "./channels"

describe("requireAtLeastOneChannel", () => {
  test("ok when any single channel is enabled", () => {
    expect(requireAtLeastOneChannel(true, false, false)).toEqual({})
    expect(requireAtLeastOneChannel(false, true, false)).toEqual({})
    expect(requireAtLeastOneChannel(false, false, true)).toEqual({})
  })
  test("error when none enabled, and the message mentions WhatsApp", () => {
    const r = requireAtLeastOneChannel(false, false, false)
    expect(r.error).toBeTruthy()
    expect(r.error).toContain("WhatsApp")
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bun test src/shared/channels.test.ts`
Expected: FAIL — `requireAtLeastOneChannel` currently takes 2 args / no WhatsApp in message.

- [ ] **Step 3: Implement**

Replace the body of `src/shared/channels.ts`:
```ts
/** Pure helper — no side-effects, easy to unit-test. */
export function requireAtLeastOneChannel(hasTelegram: boolean, hasWeb: boolean, hasWhatsapp: boolean): { error?: string } {
  if (!hasTelegram && !hasWeb && !hasWhatsapp) {
    return {
      error: "supermux needs at least one channel: set MUX_TELEGRAM_BOT_TOKEN (Telegram), MUX_WEB_PORT + MUX_WEB_PUBLIC_URL (web PWA), or MUX_WHATSAPP_GOWA_URL (WhatsApp via GOWA).",
    }
  }
  return {}
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/shared/channels.test.ts`
Expected: PASS. (The `main.ts` call site is updated in Task 9 — a 2-arg call is a compile error until then, which is fine; we don't typecheck the whole tree until Task 10.)

- [ ] **Step 5: Commit**

```bash
git add src/shared/channels.ts src/shared/channels.test.ts
git commit -m "feat(whatsapp): requireAtLeastOneChannel accepts hasWhatsapp"
```

---

### Task 3: Add WhatsApp app-config fields

**Files:**
- Modify: `src/core/settings/app-config.ts`

- [ ] **Step 1: Extend `AppConfigEnv`** (the env-shaped interface, ~lines 103-109) — add:
```ts
  MUX_WHATSAPP_GOWA_URL?: string
  MUX_WHATSAPP_GOWA_BASIC_AUTH?: string
  MUX_WHATSAPP_GOWA_DEVICE_ID?: string
  MUX_WHATSAPP_WEBHOOK_PORT?: string
  MUX_WHATSAPP_WEBHOOK_SECRET?: string
```

- [ ] **Step 2: Extend `AppConfig`** (the resolved interface, ~lines 17-39) — add:
```ts
  whatsappGowaUrl: string         // "" when unset
  whatsappGowaBasicAuth: string   // "" when unset (secret)
  whatsappGowaDeviceId: string    // "" when unset
  whatsappWebhookPort: string     // "" when unset
  whatsappWebhookSecret: string   // "" when unset (secret)
```

- [ ] **Step 3: Resolve them in `resolveAppConfig`** (the `firstNonEmpty(...)` block, ~lines 123-146) — add:
```ts
  whatsappGowaUrl: firstNonEmpty(stored.whatsappGowaUrl, env.MUX_WHATSAPP_GOWA_URL),
  whatsappGowaBasicAuth: firstNonEmpty(stored.whatsappGowaBasicAuth, env.MUX_WHATSAPP_GOWA_BASIC_AUTH),
  whatsappGowaDeviceId: firstNonEmpty(stored.whatsappGowaDeviceId, env.MUX_WHATSAPP_GOWA_DEVICE_ID),
  whatsappWebhookPort: firstNonEmpty(stored.whatsappWebhookPort, env.MUX_WHATSAPP_WEBHOOK_PORT),
  whatsappWebhookSecret: firstNonEmpty(stored.whatsappWebhookSecret, env.MUX_WHATSAPP_WEBHOOK_SECRET),
```
(Match the existing call signature of `firstNonEmpty` used by `telegramBotToken` directly above; if it takes a trailing default arg, pass `""` like its neighbors.)

- [ ] **Step 4: Mark secrets** — add to `SECRET_FIELDS` (~line 220):
```ts
export const SECRET_FIELDS = ["telegramBotToken", "claudeOauthToken", "anthropicApiKey", "codexApiKey", "cursorApiKey", "whatsappGowaBasicAuth", "whatsappWebhookSecret"] as const
```

- [ ] **Step 5: Typecheck the file**

Run: `bunx tsc --noEmit`
Expected: errors ONLY at the as-yet-unmodified `main.ts` (it doesn't read these env keys yet / `requireAtLeastOneChannel` arity) — those are fixed in Tasks 9-10. No errors inside `app-config.ts` itself.

- [ ] **Step 6: Commit**

```bash
git add src/core/settings/app-config.ts
git commit -m "feat(whatsapp): app-config fields for GOWA url/auth/webhook"
```

---

### Task 4: Phone allowlist `access.ts` (TDD)

**Files:**
- Create: `src/channels/whatsapp/access.ts`
- Test: `src/channels/whatsapp/access.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/channels/whatsapp/access.test.ts`:
```ts
import { describe, expect, test } from "bun:test"
import { mkdtempSync, writeFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { loadWhatsAppAccess, isWhatsAppAllowed } from "./access"

function withFile(json: string): string {
  const dir = mkdtempSync(join(tmpdir(), "wa-access-"))
  const p = join(dir, "access.json")
  writeFileSync(p, json)
  return p
}

describe("whatsapp access", () => {
  test("allows a listed number, matching despite JID suffix", () => {
    const p = withFile(JSON.stringify({ whatsapp: { allowFrom: ["628123456789"] } }))
    const acc = loadWhatsAppAccess(p)
    expect(isWhatsAppAllowed(acc, "628123456789@s.whatsapp.net")).toBe(true)
    expect(isWhatsAppAllowed(acc, "628123456789:12@s.whatsapp.net")).toBe(true)
    rmSync(p, { force: true })
  })
  test("denies unlisted numbers", () => {
    const p = withFile(JSON.stringify({ whatsapp: { allowFrom: ["628123456789"] } }))
    expect(isWhatsAppAllowed(loadWhatsAppAccess(p), "447700900000@s.whatsapp.net")).toBe(false)
    rmSync(p, { force: true })
  })
  test("deny-by-default on missing/empty file", () => {
    expect(loadWhatsAppAccess("/no/such/file.json")).toEqual({ allowFrom: [] })
    expect(isWhatsAppAllowed({ allowFrom: [] }, "628123456789@s.whatsapp.net")).toBe(false)
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bun test src/channels/whatsapp/access.test.ts`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Implement**

Create `src/channels/whatsapp/access.ts`:
```ts
import { readFileSync } from "fs"

// Deny-by-default WhatsApp DM allowlist. Reads the `whatsapp` section of the
// shared access.json: { "whatsapp": { "allowFrom": ["628123456789", ...] } }.
// Entries are bare phone numbers; we compare the numeric local-part of the
// sender JID so "628..." matches "628...@s.whatsapp.net" and device-suffixed
// JIDs like "628...:12@s.whatsapp.net".
export type WhatsAppAccess = { allowFrom: string[] }

export function loadWhatsAppAccess(path: string): WhatsAppAccess {
  try {
    const parsed = JSON.parse(readFileSync(path, "utf8")) as { whatsapp?: { allowFrom?: unknown } }
    const wa = parsed.whatsapp ?? {}
    return { allowFrom: Array.isArray(wa.allowFrom) ? wa.allowFrom.map(String) : [] }
  } catch {
    return { allowFrom: [] }
  }
}

function numberOf(jid: string): string {
  return (jid.split("@")[0] ?? "").split(":")[0] ?? ""
}

export function isWhatsAppAllowed(access: WhatsAppAccess, fromJid: string): boolean {
  if (access.allowFrom.length === 0) return false
  const num = numberOf(fromJid)
  return num.length > 0 && access.allowFrom.some((a) => numberOf(a) === num)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/channels/whatsapp/access.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/whatsapp/access.ts src/channels/whatsapp/access.test.ts
git commit -m "feat(whatsapp): deny-by-default phone allowlist"
```

---

### Task 5: HMAC webhook verification `webhook-verify.ts` (TDD)

**Files:**
- Create: `src/channels/whatsapp/webhook-verify.ts`
- Test: `src/channels/whatsapp/webhook-verify.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/channels/whatsapp/webhook-verify.test.ts`:
```ts
import { describe, expect, test } from "bun:test"
import { createHmac } from "crypto"
import { verifyGowaSignature } from "./webhook-verify"

const SECRET = "topsecret"
const BODY = JSON.stringify({ event: "message", payload: { id: "X" } })
const goodSig = "sha256=" + createHmac("sha256", SECRET).update(BODY, "utf8").digest("hex")

describe("verifyGowaSignature", () => {
  test("accepts a correctly-signed body", () => {
    expect(verifyGowaSignature(BODY, goodSig, SECRET)).toBe(true)
  })
  test("rejects a tampered body", () => {
    expect(verifyGowaSignature(BODY + " ", goodSig, SECRET)).toBe(false)
  })
  test("rejects wrong secret and missing header", () => {
    expect(verifyGowaSignature(BODY, goodSig, "other")).toBe(false)
    expect(verifyGowaSignature(BODY, null, SECRET)).toBe(false)
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bun test src/channels/whatsapp/webhook-verify.test.ts`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Implement**

Create `src/channels/whatsapp/webhook-verify.ts`:
```ts
import { createHmac, timingSafeEqual } from "crypto"

// GOWA signs each webhook with HMAC-SHA256 over the raw request body, sent as
// `X-Hub-Signature-256: sha256=<hexdigest>`. Verify over the EXACT received
// bytes (never a re-serialized JSON) or the signature won't match.
export function verifyGowaSignature(rawBody: string, header: string | null | undefined, secret: string): boolean {
  if (!header) return false
  const expected = createHmac("sha256", secret).update(rawBody, "utf8").digest("hex")
  const received = header.startsWith("sha256=") ? header.slice("sha256=".length) : header
  let a: Buffer
  let b: Buffer
  try {
    a = Buffer.from(expected, "hex")
    b = Buffer.from(received, "hex")
  } catch {
    return false
  }
  if (a.length === 0 || a.length !== b.length) return false
  return timingSafeEqual(a, b)
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/channels/whatsapp/webhook-verify.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/whatsapp/webhook-verify.ts src/channels/whatsapp/webhook-verify.test.ts
git commit -m "feat(whatsapp): HMAC-SHA256 webhook signature verify"
```

---

### Task 6: GOWA REST client `gowa-api.ts` (TDD)

**Files:**
- Create: `src/channels/whatsapp/gowa-api.ts`
- Test: `src/channels/whatsapp/gowa-api.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/channels/whatsapp/gowa-api.test.ts`:
```ts
import { describe, expect, test } from "bun:test"
import { GowaClient } from "./gowa-api"

function fakeFetch(captured: any[], response: any): typeof fetch {
  return (async (url: any, init?: any) => {
    captured.push({ url: String(url), init })
    return new Response(JSON.stringify(response), { status: 200, headers: { "content-type": "application/json" } })
  }) as unknown as typeof fetch
}

describe("GowaClient.sendText", () => {
  test("POSTs JSON to /send/message with basic auth + device header and returns message_id", async () => {
    const cap: any[] = []
    const c = new GowaClient({ baseUrl: "http://127.0.0.1:3000", basicAuth: "u:p", deviceId: "dev1", fetchImpl: fakeFetch(cap, { results: { message_id: "ABC123" } }) })
    const r = await c.sendText("628000@s.whatsapp.net", "hi", "REPLY1")
    expect(r.message_id).toBe("ABC123")
    expect(cap[0].url).toBe("http://127.0.0.1:3000/send/message")
    expect(cap[0].init.method).toBe("POST")
    expect(cap[0].init.headers["Authorization"]).toBe("Basic " + Buffer.from("u:p").toString("base64"))
    expect(cap[0].init.headers["X-Device-Id"]).toBe("dev1")
    expect(JSON.parse(cap[0].init.body)).toEqual({ phone: "628000@s.whatsapp.net", message: "hi", reply_message_id: "REPLY1" })
  })
})

describe("GowaClient.status / fetchMedia", () => {
  test("status maps results flags", async () => {
    const c = new GowaClient({ baseUrl: "http://h:3000", fetchImpl: fakeFetch([], { results: { is_connected: true, is_logged_in: true } }) })
    expect(await c.status()).toEqual({ is_connected: true, is_logged_in: true })
  })
  test("fetchMedia resolves a relative statics path against baseUrl", async () => {
    const cap: any[] = []
    const f = ((async (url: any) => { cap.push(String(url)); return new Response(new Uint8Array([1, 2, 3])) }) as unknown) as typeof fetch
    const c = new GowaClient({ baseUrl: "http://h:3000", fetchImpl: f })
    const bytes = await c.fetchMedia("statics/media/x.ogg")
    expect(Array.from(bytes)).toEqual([1, 2, 3])
    expect(cap[0]).toBe("http://h:3000/statics/media/x.ogg")
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bun test src/channels/whatsapp/gowa-api.test.ts`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Implement**

Create `src/channels/whatsapp/gowa-api.ts`:
```ts
import { readFile } from "fs/promises"
import { basename } from "path"

export interface GowaClientOpts {
  baseUrl: string
  basicAuth?: string         // "user:pass"
  deviceId?: string          // X-Device-Id (GOWA v8 multi-device); optional
  fetchImpl?: typeof fetch   // injectable for tests
}

export interface GowaSendResult { message_id: string }
export type GowaMediaKind = "image" | "file" | "audio"

export class GowaClient {
  constructor(private readonly opts: GowaClientOpts) {}

  private get f(): typeof fetch { return this.opts.fetchImpl ?? fetch }

  private headers(extra?: Record<string, string>): Record<string, string> {
    const h: Record<string, string> = { ...(extra ?? {}) }
    if (this.opts.basicAuth) h["Authorization"] = "Basic " + Buffer.from(this.opts.basicAuth).toString("base64")
    if (this.opts.deviceId) h["X-Device-Id"] = this.opts.deviceId
    return h
  }

  async sendText(phone: string, message: string, replyTo?: string): Promise<GowaSendResult> {
    const res = await this.f(`${this.opts.baseUrl}/send/message`, {
      method: "POST",
      headers: this.headers({ "Content-Type": "application/json" }),
      body: JSON.stringify({ phone, message, ...(replyTo ? { reply_message_id: replyTo } : {}) }),
    })
    return this.parseSend(res)
  }

  async sendMedia(kind: GowaMediaKind, phone: string, filePath: string, opts?: { caption?: string; replyTo?: string }): Promise<GowaSendResult> {
    const bytes = await readFile(filePath)
    const form = new FormData()
    form.set("phone", phone)
    const field = kind === "image" ? "image" : kind === "audio" ? "audio" : "file"
    form.set(field, new Blob([bytes]), basename(filePath))
    if (opts?.caption) form.set("caption", opts.caption)
    if (opts?.replyTo) form.set("reply_message_id", opts.replyTo)
    const res = await this.f(`${this.opts.baseUrl}/send/${kind}`, { method: "POST", headers: this.headers(), body: form })
    return this.parseSend(res)
  }

  private async parseSend(res: Response): Promise<GowaSendResult> {
    if (!res.ok) throw new Error(`gowa send failed: ${res.status} ${await res.text().catch(() => "")}`)
    const j: any = await res.json()
    const id = j?.results?.message_id
    if (!id) throw new Error("gowa send: no results.message_id in response")
    return { message_id: String(id) }
  }

  async status(): Promise<{ is_connected: boolean; is_logged_in: boolean }> {
    const res = await this.f(`${this.opts.baseUrl}/app/status`, { headers: this.headers() })
    const j: any = await res.json().catch(() => ({}))
    const r = j?.results ?? {}
    return { is_connected: !!r.is_connected, is_logged_in: !!r.is_logged_in }
  }

  async fetchMedia(pathOrUrl: string): Promise<Uint8Array> {
    const url = /^https?:\/\//.test(pathOrUrl) ? pathOrUrl : `${this.opts.baseUrl}/${pathOrUrl.replace(/^\//, "")}`
    const res = await this.f(url, { headers: this.headers() })
    if (!res.ok) throw new Error(`gowa media fetch failed: ${res.status}`)
    return new Uint8Array(await res.arrayBuffer())
  }

  async downloadMedia(messageId: string, phone: string): Promise<string> {
    const res = await this.f(`${this.opts.baseUrl}/message/${encodeURIComponent(messageId)}/download?phone=${encodeURIComponent(phone)}`, { headers: this.headers() })
    const j: any = await res.json().catch(() => ({}))
    const fileUrl = j?.results?.file_url
    if (!fileUrl) throw new Error("gowa download: no results.file_url")
    return String(fileUrl)
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/channels/whatsapp/gowa-api.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/whatsapp/gowa-api.ts src/channels/whatsapp/gowa-api.test.ts
git commit -m "feat(whatsapp): GOWA REST client (send/status/media)"
```

---

### Task 7: Inbound normalizer `inbound.ts` (TDD)

**Files:**
- Create: `src/channels/whatsapp/inbound.ts`
- Test: `src/channels/whatsapp/inbound.test.ts`

- [ ] **Step 1: Write the failing test**

Create `src/channels/whatsapp/inbound.test.ts`:
```ts
import { describe, expect, test } from "bun:test"
import { normalizeWhatsAppInbound } from "./inbound"

function deps(opts?: { media?: Uint8Array; putKindOut?: (k: string) => void }) {
  return {
    gowa: {
      fetchMedia: async (_p: string) => opts?.media ?? new Uint8Array([9, 9]),
      downloadMedia: async (_id: string, _phone: string) => "http://h:3000/statics/media/dl.ogg",
    },
    fileStore: {
      put: async (input: any) => { opts?.putKindOut?.(input.kind); return { file_id: "fid-" + input.kind, size: (input.bytes as Uint8Array).length } },
    } as any,
  }
}

describe("normalizeWhatsAppInbound", () => {
  test("text message", async () => {
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M1", chat_id: "628@s.whatsapp.net", from: "628@s.whatsapp.net", from_name: "Ada", timestamp: "2026-06-25T10:00:00Z", body: "hello", is_from_me: false } }, deps())
    expect(msg).toMatchObject({ channel: "whatsapp", chat_id: "whatsapp:628@s.whatsapp.net", message_id: "M1", user: "Ada", user_id: "628@s.whatsapp.net", ts: "2026-06-25T10:00:00Z", text: "hello" })
    expect(msg.attachments).toBeUndefined()
  })

  test("image as bare-string path → photo attachment", async () => {
    let kind = ""
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M2", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "2026-06-25T10:00:00Z", image: "statics/media/x.jpeg" } }, deps({ putKindOut: (k) => (kind = k) }))
    expect(kind).toBe("photo")
    expect(msg.attachments?.[0]).toMatchObject({ kind: "photo", file_id: "fid-photo" })
  })

  test("audio .ogg → voice attachment (Telegram parity)", async () => {
    let kind = ""
    await normalizeWhatsAppInbound({ payload: { id: "M3", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", audio: "statics/media/v.ogg" } }, deps({ putKindOut: (k) => (kind = k) }))
    expect(kind).toBe("voice")
  })

  test("document {url,filename} (auto-download off) → resolves via downloadMedia, kind document, carries name", async () => {
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M4", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", document: { url: "https://mmg.whatsapp.net/enc", filename: "report.pdf" } } }, deps())
    expect(msg.attachments?.[0]).toMatchObject({ kind: "document", name: "report.pdf" })
  })

  test("quoted reply maps replied_to_id", async () => {
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M5", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", body: "re", replied_to_id: "M1" } }, deps())
    expect(msg.reply_to_message_id).toBe("M1")
  })

  test("media fetch failure drops attachment but keeps the message", async () => {
    const badDeps = { gowa: { fetchMedia: async () => { throw new Error("boom") }, downloadMedia: async () => "x" }, fileStore: { put: async () => ({ file_id: "x", size: 0 }) } as any }
    const msg = await normalizeWhatsAppInbound({ payload: { id: "M6", chat_id: "c@s.whatsapp.net", from: "c@s.whatsapp.net", timestamp: "t", body: "cap", image: "statics/media/x.jpeg" } }, badDeps)
    expect(msg.attachments).toBeUndefined()
    expect(msg.text).toBe("cap")
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bun test src/channels/whatsapp/inbound.test.ts`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Implement**

Create `src/channels/whatsapp/inbound.ts`:
```ts
import type { FileStore } from "../../core/files/store"
import type { AttachmentKind } from "../../core/files/kinds"
import type { InboundAttachment, InboundMessage } from "../channel"
import { makeLogger } from "../../shared/log"

const log = makeLogger("channels/whatsapp/inbound")

export interface WhatsAppNormalizeDeps {
  gowa: {
    fetchMedia(pathOrUrl: string): Promise<Uint8Array>
    downloadMedia(messageId: string, phone: string): Promise<string>
  }
  fileStore: Pick<FileStore, "put">
}

const MEDIA_FIELDS = ["image", "audio", "document", "video", "sticker"] as const
type MediaField = (typeof MEDIA_FIELDS)[number]

function pickMedia(p: any): { field: MediaField; raw: any } | null {
  for (const field of MEDIA_FIELDS) if (p[field] != null) return { field, raw: p[field] }
  return null
}

// WhatsApp lumps voice notes and audio under `audio`; a `.ogg` is a voice note
// (parity with Telegram's `voice`). image→photo, document→document; video and
// sticker fall back to document for v1 (tier B is text+image+document+voice).
function mediaKind(field: MediaField, pathOrUrl: string): AttachmentKind {
  if (field === "image") return "photo"
  if (field === "audio") return pathOrUrl.toLowerCase().endsWith(".ogg") ? "voice" : "audio"
  return "document"
}

export async function normalizeWhatsAppInbound(payload: any, deps: WhatsAppNormalizeDeps): Promise<InboundMessage> {
  const p = payload?.payload ?? {}
  const chatId = String(p.chat_id ?? p.from ?? "")
  let attachments: InboundAttachment[] | undefined

  const media = pickMedia(p)
  if (media) {
    try {
      const raw = media.raw
      let ref: { pathOrUrl: string; name?: string } | null = null
      if (typeof raw === "string") ref = { pathOrUrl: raw }
      else if (raw?.path) ref = { pathOrUrl: String(raw.path), name: raw.filename ? String(raw.filename) : undefined }
      else if (raw?.url) {
        // auto-download OFF: ask GOWA to decrypt+save, then fetch the served file
        const fileUrl = await deps.gowa.downloadMedia(String(p.id ?? ""), chatId)
        ref = { pathOrUrl: fileUrl, name: raw.filename ? String(raw.filename) : undefined }
      }
      if (ref) {
        const bytes = await deps.gowa.fetchMedia(ref.pathOrUrl)
        const kind = mediaKind(media.field, ref.pathOrUrl)
        const stored = await deps.fileStore.put({ kind, name: ref.name, origin: "whatsapp-dl", bytes })
        attachments = [{ kind, file_id: stored.file_id, size: bytes.length, name: ref.name }]
      }
    } catch (err: any) {
      log.warn("eager_download_failed_dropping_attachment", { err: err?.message ?? String(err), id: String(p.id ?? ""), field: media.field })
      // attachments stays undefined → message still flows
    }
  }

  return {
    channel: "whatsapp",
    chat_id: `whatsapp:${chatId}`,
    message_id: String(p.id ?? ""),
    user: String(p.from_name ?? p.from ?? ""),
    user_id: String(p.from ?? ""),
    ts: typeof p.timestamp === "string" && p.timestamp ? p.timestamp : new Date().toISOString(),
    text: typeof p.body === "string" ? p.body : "",
    reply_to_message_id: p.replied_to_id ? String(p.replied_to_id) : undefined,
    attachments,
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/channels/whatsapp/inbound.test.ts`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/whatsapp/inbound.ts src/channels/whatsapp/inbound.test.ts
git commit -m "feat(whatsapp): inbound webhook payload normalizer"
```

---

### Task 8: Webhook handler + listener `webhook.ts` (TDD)

**Files:**
- Create: `src/channels/whatsapp/webhook.ts`
- Test: `src/channels/whatsapp/webhook.test.ts`

- [ ] **Step 1: Write the failing test** (tests the pure request handler — no socket needed)

Create `src/channels/whatsapp/webhook.test.ts`:
```ts
import { describe, expect, test } from "bun:test"
import { createHmac } from "crypto"
import { createWebhookHandler } from "./webhook"

const SECRET = "s3cr3t"
function signed(bodyObj: any): Request {
  const body = JSON.stringify(bodyObj)
  const sig = "sha256=" + createHmac("sha256", SECRET).update(body, "utf8").digest("hex")
  return new Request("http://127.0.0.1/webhook", { method: "POST", body, headers: { "X-Hub-Signature-256": sig } })
}

describe("createWebhookHandler", () => {
  test("invokes onMessage for a signed inbound message event", async () => {
    const seen: any[] = []
    const h = createWebhookHandler({ secret: SECRET, onMessage: (p) => seen.push(p) })
    const res = await h(signed({ event: "message", payload: { id: "M1", is_from_me: false, body: "hi" } }))
    expect(res.status).toBe(200)
    expect(seen).toHaveLength(1)
    expect(seen[0].id).toBe("M1")
  })
  test("rejects a bad signature with 401 and does not call onMessage", async () => {
    const seen: any[] = []
    const h = createWebhookHandler({ secret: SECRET, onMessage: (p) => seen.push(p) })
    const bad = new Request("http://127.0.0.1/webhook", { method: "POST", body: JSON.stringify({ event: "message", payload: {} }), headers: { "X-Hub-Signature-256": "sha256=deadbeef" } })
    const res = await h(bad)
    expect(res.status).toBe(401)
    expect(seen).toHaveLength(0)
  })
  test("ignores our own outbound (is_from_me) and non-message events", async () => {
    const seen: any[] = []
    const h = createWebhookHandler({ secret: SECRET, onMessage: (p) => seen.push(p) })
    await h(signed({ event: "message", payload: { id: "M2", is_from_me: true } }))
    await h(signed({ event: "message.ack", payload: { id: "M3" } }))
    expect(seen).toHaveLength(0)
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bun test src/channels/whatsapp/webhook.test.ts`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Implement**

Create `src/channels/whatsapp/webhook.ts`:
```ts
import { verifyGowaSignature } from "./webhook-verify"
import { makeLogger } from "../../shared/log"

const log = makeLogger("channels/whatsapp/webhook")

export interface WebhookHandlerOpts {
  secret: string
  onMessage: (payload: any) => void
}

// Pure request handler — verifies HMAC, parses, filters to inbound message
// events, and fires onMessage with the inner `payload` object.
export function createWebhookHandler(opts: WebhookHandlerOpts): (req: Request) => Promise<Response> {
  return async (req) => {
    if (req.method !== "POST") return new Response("method not allowed", { status: 405 })
    const raw = await req.text()
    const sig = req.headers.get("X-Hub-Signature-256")
    if (!verifyGowaSignature(raw, sig, opts.secret)) {
      log.warn("webhook_bad_signature")
      return new Response("invalid signature", { status: 401 })
    }
    let body: any
    try {
      body = JSON.parse(raw)
    } catch {
      return new Response("bad json", { status: 400 })
    }
    if (body?.event === "message" && body?.payload && body.payload.is_from_me !== true) {
      try {
        opts.onMessage(body.payload)
      } catch (err: any) {
        log.error("webhook_onmessage_threw", { err: err?.message ?? String(err) })
      }
    }
    return new Response("ok")
  }
}

// Thin localhost Bun.serve wrapper around the handler.
export class WhatsAppWebhookServer {
  private server?: ReturnType<typeof Bun.serve>
  constructor(private readonly port: number, private readonly handler: (req: Request) => Promise<Response>) {}
  start(): void {
    this.server = Bun.serve({ port: this.port, hostname: "127.0.0.1", fetch: this.handler })
  }
  async stop(): Promise<void> {
    this.server?.stop(true)
  }
  get boundPort(): number {
    return this.server?.port ?? this.port
  }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/channels/whatsapp/webhook.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/whatsapp/webhook.ts src/channels/whatsapp/webhook.test.ts
git commit -m "feat(whatsapp): HMAC-verified webhook handler + localhost listener"
```

---

### Task 9: `WhatsAppChannel` class `index.ts` (TDD)

**Files:**
- Create: `src/channels/whatsapp/index.ts`
- Test: `src/channels/whatsapp/index.test.ts`

- [ ] **Step 1: Write the failing test** (exercises `send()` with an injected fake GOWA via a subclass seam)

Create `src/channels/whatsapp/index.test.ts`:
```ts
import { describe, expect, test } from "bun:test"
import { WhatsAppChannel } from "./index"

function makeChannel(captured: any[]) {
  const ch = new WhatsAppChannel({ gowaUrl: "http://127.0.0.1:3000", webhookPort: 0, webhookSecret: "x", fileStore: {} as any })
  // swap the gowa client for a capturing fake (private field, test seam)
  ;(ch as any).gowa = {
    sendText: async (phone: string, message: string, replyTo?: string) => { captured.push({ kind: "text", phone, message, replyTo }); return { message_id: "T1" } },
    sendMedia: async (kind: string, phone: string, path: string, o?: any) => { captured.push({ kind, phone, path, o }); return { message_id: "M1" } },
  }
  return ch
}

describe("WhatsAppChannel.send", () => {
  test("text reply → sendText with JID derived from chat_id; returns message_id", async () => {
    const cap: any[] = []
    const r = await makeChannel(cap).send({ op: "reply", chat_id: "whatsapp:628@s.whatsapp.net", text: "hi" } as any)
    expect(r).toEqual({ ok: true, value: { message_id: "T1" } })
    expect(cap[0]).toMatchObject({ kind: "text", phone: "628@s.whatsapp.net", message: "hi" })
  })
  test("bare number chat_id gets @s.whatsapp.net suffix", async () => {
    const cap: any[] = []
    await makeChannel(cap).send({ op: "reply", chat_id: "whatsapp:628999", text: "x" } as any)
    expect(cap[0].phone).toBe("628999@s.whatsapp.net")
  })
  test("image file → sendMedia('image', ...) with caption from text", async () => {
    const cap: any[] = []
    await makeChannel(cap).send({ op: "reply", chat_id: "whatsapp:c@s.whatsapp.net", text: "cap", files: ["/tmp/p.jpg"] } as any)
    expect(cap[0]).toMatchObject({ kind: "image", path: "/tmp/p.jpg", o: { caption: "cap", replyTo: undefined } })
  })
  test("non-reply op is rejected", async () => {
    const r = await makeChannel([]).send({ op: "react", chat_id: "whatsapp:c", message_id: "1", emoji: "👍" } as any)
    expect(r.ok).toBe(false)
  })
})
```

- [ ] **Step 2: Run it to verify it fails**

Run: `bun test src/channels/whatsapp/index.test.ts`
Expected: FAIL — module does not exist.

- [ ] **Step 3: Implement**

Create `src/channels/whatsapp/index.ts`:
```ts
import { extname } from "path"
import type { Channel, ChannelCapabilities, InboundMessage, OutboundAction, OutboundResult } from "../channel"
import type { FileStore } from "../../core/files/store"
import { GowaClient, type GowaMediaKind } from "./gowa-api"
import { normalizeWhatsAppInbound } from "./inbound"
import { createWebhookHandler, WhatsAppWebhookServer } from "./webhook"
import { loadWhatsAppAccess, isWhatsAppAllowed } from "./access"
import { makeLogger } from "../../shared/log"
import { ACCESS_FILE } from "../../shared/paths"

const log = makeLogger("channels/whatsapp")
const IMAGE_EXT = new Set([".png", ".jpg", ".jpeg", ".webp", ".gif"])
const AUDIO_EXT = new Set([".ogg", ".opus", ".mp3", ".m4a", ".wav", ".aac"])

export interface WhatsAppChannelOpts {
  gowaUrl: string
  gowaBasicAuth?: string
  gowaDeviceId?: string
  webhookPort: number
  webhookSecret: string
  fileStore: FileStore
}

export class WhatsAppChannel implements Channel {
  readonly name = "whatsapp"
  readonly capabilities: ChannelCapabilities = {
    multiplexesSessions: true,
    supportsReactions: false,
    supportsEdit: false,
    supportsAttachments: true,
  }
  private readonly gowa: GowaClient
  private readonly fileStore: FileStore
  private readonly server: WhatsAppWebhookServer
  private inboundHandlers: Array<(m: InboundMessage) => void> = []

  constructor(opts: WhatsAppChannelOpts) {
    this.fileStore = opts.fileStore
    this.gowa = new GowaClient({ baseUrl: opts.gowaUrl, basicAuth: opts.gowaBasicAuth, deviceId: opts.gowaDeviceId })
    const handler = createWebhookHandler({ secret: opts.webhookSecret, onMessage: (p) => { void this.onPayload(p) } })
    this.server = new WhatsAppWebhookServer(opts.webhookPort, handler)
  }

  on(event: "inbound", handler: (m: InboundMessage) => void): void {
    if (event === "inbound") this.inboundHandlers.push(handler)
  }

  async start(): Promise<void> {
    this.server.start()
    try {
      const st = await this.gowa.status()
      if (!st.is_logged_in) log.warn("whatsapp_not_logged_in", { hint: "pair the secondary number via GOWA GET /app/login (QR) or /app/login-with-code" })
      else log.info("whatsapp_ready", {})
    } catch (err: any) {
      log.warn("whatsapp_status_probe_failed", { err: err?.message ?? String(err) })
    }
    log.info("whatsapp channel listening", { port: this.server.boundPort })
  }

  async stop(): Promise<void> {
    await this.server.stop()
  }

  async send(action: OutboundAction): Promise<OutboundResult> {
    try {
      if (action.op !== "reply") return { ok: false, error: `whatsapp: unsupported op "${action.op}"` }
      const phone = toJid(action.chat_id)
      if (action.files && action.files.length > 0) {
        let firstId = ""
        for (let i = 0; i < action.files.length; i++) {
          const path = action.files[i]!
          const caption = i === 0 ? action.text : undefined
          const ext = extname(path).toLowerCase()
          const kind: GowaMediaKind = IMAGE_EXT.has(ext) ? "image" : AUDIO_EXT.has(ext) ? "audio" : "file"
          const r = await this.gowa.sendMedia(kind, phone, path, { caption, replyTo: action.reply_to })
          if (i === 0) firstId = r.message_id
        }
        return { ok: true, value: { message_id: firstId } }
      }
      const r = await this.gowa.sendText(phone, action.text, action.reply_to)
      return { ok: true, value: { message_id: r.message_id } }
    } catch (err: any) {
      return { ok: false, error: String(err?.message ?? err) }
    }
  }

  private async onPayload(payload: any): Promise<void> {
    const fromJid = String(payload?.from ?? "")
    if (!isWhatsAppAllowed(loadWhatsAppAccess(ACCESS_FILE), fromJid)) {
      log.warn("access_dropped_inbound", { from: fromJid })
      return
    }
    let msg: InboundMessage
    try {
      msg = await normalizeWhatsAppInbound(payload, { gowa: this.gowa, fileStore: this.fileStore })
    } catch (err: any) {
      log.error("whatsapp_inbound_normalize_failed", { err: err?.message ?? String(err) })
      return
    }
    for (const h of this.inboundHandlers) {
      try { h(msg) } catch (err: any) { log.error("whatsapp inbound handler threw", { err: err?.message ?? String(err) }) }
    }
  }
}

// "whatsapp:<jid|number>" → a GOWA `phone` JID.
function toJid(chatId: string): string {
  const raw = chatId.startsWith("whatsapp:") ? chatId.slice("whatsapp:".length) : chatId
  return raw.includes("@") ? raw : `${raw}@s.whatsapp.net`
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `bun test src/channels/whatsapp/index.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/whatsapp/index.ts src/channels/whatsapp/index.test.ts
git commit -m "feat(whatsapp): WhatsAppChannel implementing the Channel interface"
```

---

### Task 10: Wire the channel into the broker (`src/main.ts`)

**Files:**
- Modify: `src/main.ts` (import, env, construct, register, start/stop, `requireAtLeastOneChannel`, `wireInbound` refactor)

> This task changes existing code. Read each anchor region first (line numbers from the Reference section), then apply the edit. No new tests here — Task 11 typechecks + runs the suite.

- [ ] **Step 1: Import the channel** — near the other channel imports (e.g. beside the Telegram import):

```ts
import { WhatsAppChannel } from "./channels/whatsapp"
```

- [ ] **Step 2: Read the 4 WhatsApp env vars** — in the `appConfigEnv` object literal (~lines 229-235), add:

```ts
  MUX_WHATSAPP_GOWA_URL: process.env.MUX_WHATSAPP_GOWA_URL,
  MUX_WHATSAPP_GOWA_BASIC_AUTH: process.env.MUX_WHATSAPP_GOWA_BASIC_AUTH,
  MUX_WHATSAPP_GOWA_DEVICE_ID: process.env.MUX_WHATSAPP_GOWA_DEVICE_ID,
  MUX_WHATSAPP_WEBHOOK_PORT: process.env.MUX_WHATSAPP_WEBHOOK_PORT,
  MUX_WHATSAPP_WEBHOOK_SECRET: process.env.MUX_WHATSAPP_WEBHOOK_SECRET,
```

- [ ] **Step 3: Construct the channel** — immediately after the Telegram construction (~lines 475-477), add:

```ts
const WA_GOWA_URL = appConfig.whatsappGowaUrl || undefined
const hasWhatsApp = !!WA_GOWA_URL
const whatsapp: WhatsAppChannel | undefined = hasWhatsApp
  ? new WhatsAppChannel({
      gowaUrl: WA_GOWA_URL!,
      gowaBasicAuth: appConfig.whatsappGowaBasicAuth || undefined,
      gowaDeviceId: appConfig.whatsappGowaDeviceId || undefined,
      webhookPort: Number(appConfig.whatsappWebhookPort) || 3001,
      webhookSecret: appConfig.whatsappWebhookSecret || "secret",
      fileStore,
    })
  : undefined
```

- [ ] **Step 4: Register in the `channels` record** — change line 478 from:

```ts
const channels: Record<string, Channel> = telegram ? { telegram } : {}
```
to:
```ts
const channels: Record<string, Channel> = {
  ...(telegram ? { telegram } : {}),
  ...(whatsapp ? { whatsapp } : {}),
}
```

- [ ] **Step 5: Update the channel-required check** — at ~lines 852-853, change:

```ts
const channelCheck = requireAtLeastOneChannel(hasTelegram, webEnv.enabled)
```
to:
```ts
const channelCheck = requireAtLeastOneChannel(hasTelegram, webEnv.enabled, hasWhatsApp)
```

- [ ] **Step 6: Refactor the Telegram inbound handler into a shared `wireInbound(ch)` and call it for both channels.**

The current block (lines 2627-2839) is:
```ts
if (telegram) {
const _tg = telegram
_tg.on("inbound", async (msg: InboundMessage) => {
  /* … ~210 lines … */
})
} // end if (telegram)
```
Transform it to a channel-agnostic closure (it already only uses `Channel`-interface methods plus in-scope closures like `classifyInbound`, `handleSlash`, `deliverInbound`, `registry`, `messageLog`, `waitForSessionConnected`):

```ts
const wireInbound = (ch: Channel) => {
  ch.on("inbound", async (msg: InboundMessage) => {
    /* … the SAME body, with exactly two substitutions … */
  })
}
if (telegram) wireInbound(telegram)
if (whatsapp) wireInbound(whatsapp)
```
The two substitutions inside the body:
1. Replace every `_tg.send(` with `ch.send(` (the error-reply / slash-reply sends).
2. Replace the hard-coded `channel: "telegram"` in the `messageLog.append({ … })` call (line ~2800) with `channel: ch.name`.
Leave everything else identical (classify/slash/deliver logic is channel-agnostic). Remove the now-unused `const _tg = telegram` line.

- [ ] **Step 7: Start the channel** — after `if (telegram) await telegram.start()` (~line 3245), add:

```ts
if (whatsapp) await whatsapp.start()
```

- [ ] **Step 8: Stop the channel** — after the Telegram stop block (~lines 3270-3272), add:

```ts
if (whatsapp) try {
  await whatsapp.stop()
} catch (err: any) { log.warn("whatsapp_stop_failed", { err: err?.message ?? String(err) }) }
```

- [ ] **Step 9: Typecheck**

Run: `bunx tsc --noEmit`
Expected: PASS (no errors). If `Channel` isn't imported in `main.ts`, add it to the existing `./channels/channel` import.

- [ ] **Step 10: Commit**

```bash
git add src/main.ts
git commit -m "feat(whatsapp): wire WhatsAppChannel into the broker (register + shared inbound)"
```

---

### Task 11: Full verification + ops runbook

**Files:**
- Create: `docs/whatsapp-gowa-setup.md` (ops runbook; force-add past the `docs/` gitignore like the spec/plan)

- [ ] **Step 1: Run the whole test suite**

Run: `bun test`
Expected: PASS — all existing tests plus the 6 new WhatsApp test files (access, webhook-verify, gowa-api, inbound, webhook, index) and `shared/channels.test.ts`. Fix any regressions before continuing.

- [ ] **Step 2: Full typecheck**

Run: `bunx tsc --noEmit`
Expected: no errors.

- [ ] **Step 3: Lint/build if the repo defines one** (only if present in `package.json` scripts)

Run: `bun run build` (skip if there is no `build` script)
Expected: success.

- [ ] **Step 4: Write the ops runbook**

Create `docs/whatsapp-gowa-setup.md` documenting:
- Install/run GOWA (single Go binary or Docker), bound to `127.0.0.1:3000`, `--basic-auth=…`.
- Configure its webhook: `--webhook="http://127.0.0.1:3001/webhook"` (matching `MUX_WHATSAPP_WEBHOOK_PORT`) and `--webhook-secret=…` (matching `MUX_WHATSAPP_WEBHOOK_SECRET`); enable `WHATSAPP_AUTO_DOWNLOAD_MEDIA=true`.
- Broker env to set: `MUX_WHATSAPP_GOWA_URL=http://127.0.0.1:3000`, `MUX_WHATSAPP_GOWA_BASIC_AUTH=user:pass`, `MUX_WHATSAPP_WEBHOOK_PORT=3001`, `MUX_WHATSAPP_WEBHOOK_SECRET=…`, optional `MUX_WHATSAPP_GOWA_DEVICE_ID=…` (GOWA v8).
- One-time pairing of the **secondary** number: `GET /app/login` (scan QR) or `GET /app/login-with-code?phone=…`; confirm via `GET /app/status` (`is_logged_in: true`).
- Add the sender to the allowlist: `~/.mux/.claude/access.json` (the `ACCESS_FILE`) → `{ "whatsapp": { "allowFrom": ["<your-number>"] } }`.
- A sample `systemd` unit (`mux-gowa.service`) for the binary.

- [ ] **Step 5: Commit**

```bash
git add -f docs/whatsapp-gowa-setup.md
git commit -m "docs(whatsapp): GOWA sidecar setup + pairing runbook"
```

- [ ] **Step 6: Manual acceptance** (requires a paired secondary number + a running GOWA; do interactively, not in CI)

With GOWA running and paired, and the broker restarted with the WhatsApp env set:
1. From an allow-listed number, DM the GOWA number "hello" → a session is created/routed and replies come back to WhatsApp.
2. Send an image with a caption → it reaches the session as a `photo` attachment.
3. Send a PDF → reaches the session as a `document` attachment.
4. Send a voice note → reaches the session as a `kind:"voice"` attachment (Telegram parity).
5. Have an agent reply with text and with an image file → both arrive on WhatsApp.

---

## Self-Review

**1. Spec coverage** (each spec decision → task):
- GOWA sidecar over HTTP → Tasks 6 (client), 11 (runbook). ✓
- `chat_id: whatsapp:<jid>` → Task 7 (inbound), Task 9 (`toJid`). ✓
- `multiplexesSessions:true`, caps reactions/edit=false → Task 9. ✓
- Channel-owned HMAC webhook listener on localhost → Tasks 5, 8. ✓
- Voice = `kind:"voice"` attachment, no channel transcription → Task 7 (`mediaKind` `.ogg`→voice). ✓
- Dedicated `whatsapp` allowlist, deny-by-default, DM-only → Task 4. ✓
- Outbound media classification by extension → Task 9. ✓
- `requireAtLeastOneChannel` extended → Task 2. ✓
- Config env vars → Task 3. ✓
- Broker registration + shared inbound (extract preferred) → Task 10. ✓
- Error handling (GOWA down → send error; not-logged-in → degraded start; HMAC 401; media-fail drops attachment; dedupe via existing message_id path) → Tasks 6/8/9 + inherited deliver path. ✓
- Testing (unit per unit + fake-GOWA via injected fetch/seams) → Tasks 2,4,5,6,7,8,9. ✓
- Out of scope (TTS, reactions/edits, groups) → not implemented, capabilities false. ✓

**2. Placeholder scan:** No "TBD/TODO/handle appropriately". Every code step has complete code; the one refactor step (Task 10 Step 6) gives an exact two-substitution recipe against named line anchors. ✓

**3. Type consistency:** `GowaSendResult.message_id` (string) used consistently; `GowaMediaKind = "image"|"file"|"audio"` defined in Task 6 and imported in Task 9; `WhatsAppNormalizeDeps` in Task 7 matches what Task 9 passes (`{ gowa: this.gowa, fileStore: this.fileStore }` — `GowaClient` has `fetchMedia`/`downloadMedia`, `FileStore` has `put`); `AttachmentOrigin` gains `"whatsapp-dl"` (Task 1) before `inbound.ts` uses it (Task 7); `requireAtLeastOneChannel` 3-arg signature (Task 2) matches the call site update (Task 10 Step 5). ✓

---

## Execution Handoff

Per the user's directive ("use subagents"), execute with **superpowers:subagent-driven-development** — a fresh subagent per task (Tasks 1-11 in order), with a two-stage review between tasks. Tasks 1-9 are independent of `main.ts` and each ends green + committed; Task 10 integrates; Task 11 verifies the whole suite and writes the runbook.
