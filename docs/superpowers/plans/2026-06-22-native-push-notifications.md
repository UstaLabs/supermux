# Native Push Notifications — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the server-side core of native push — a content-blind **relay** (holds the APNs/FCM creds) plus the **broker** changes (device registration, encrypted payloads, fan-out wiring) — so the existing native-push scaffold becomes a working pipeline. The iOS and Android clients are scoped as follow-on plans (they need a Mac / emulator and resolve their own crypto details).

**Architecture:** Broker encrypts a notification preview to the device's public key and POSTs `{routingToken, ciphertext}` to a relay. The relay (a separate `src/relay/` bun process Ahmet runs once) maps the routing token → APNs/FCM push token and forwards the opaque blob. Registration delivers the routing token *through* a bootstrap push to the device (proof-of-possession). Spec: `docs/superpowers/specs/2026-06-22-native-push-notifications-design.md`.

**Tech Stack:** bun + TypeScript, `bun:sqlite`, `bun:test`. Crypto via **WebCrypto** (P-256 ECDH + HKDF-SHA256 + AES-256-GCM — resolves the spec's crypto open-Q; P-256 also lets the iOS client hold its private key in the Secure Enclave). APNs = ES256 provider JWT + HTTP/2 (`node:http2`); FCM = service-account OAuth2 + HTTP v1 (`fetch`). No new heavyweight deps; `web-push` already present for the web path.

**Scope of THIS plan:** Phases A (relay) + B (broker). Phases C (iOS) + D (Android) are outlined at the end and become their own plans. Phase E is the one-time ops/credential setup Ahmet does.

**Conventions to follow (observed in the codebase):**
- Tests: `import { expect, test } from "bun:test"`; in-memory `new Database(":memory:")` with the table created inline in a `freshDb()` helper; terse assertions (`toMatchObject`, `toEqual`).
- REST handlers (`src/channels/web/index.ts`): `if (method === "POST" && path === "/x") { const auth = this.requireAuth(req); if (!auth.ok) return new Response("unauthorized",{status:401}); ... body = await req.json(); validate types; return this.json({ ok:true }) }`. `auth.device.name` is the device id.
- Migrations: numbered SQL files in `src/core/storage/migrations/` (next is `012_`).
- Commit after every green step.

---

## Phase A — The Relay (`src/relay/`)

### Task A1: Relay routing-token store

**Files:**
- Create: `src/relay/store.ts`
- Create: `src/relay/store.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
// src/relay/store.test.ts
import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { RelayStore } from "./store"

function freshStore(): RelayStore {
  return new RelayStore(new Database(":memory:"))
}

test("register mints a unique routing token and maps it to the push token", () => {
  const s = freshStore()
  const a = s.register("ios", "apns-tok-1")
  const b = s.register("android", "fcm-tok-2")
  expect(a).not.toEqual(b)
  expect(s.lookup(a)).toMatchObject({ platform: "ios", pushToken: "apns-tok-1" })
  expect(s.lookup(b)).toMatchObject({ platform: "android", pushToken: "fcm-tok-2" })
})

test("lookup of an unknown token is null; unregister removes it", () => {
  const s = freshStore()
  const t = s.register("ios", "tok")
  s.unregister(t)
  expect(s.lookup(t)).toBeNull()
  expect(s.lookup("never")).toBeNull()
})
```

- [ ] **Step 2: Run it, verify it fails**

Run: `bun test src/relay/store.test.ts`
Expected: FAIL — `RelayStore` not found.

- [ ] **Step 3: Implement `RelayStore`**

```ts
// src/relay/store.ts
import type { Database } from "bun:sqlite"
import { randomBytes } from "node:crypto"

export interface RelayRoute { routing_token: string; platform: "ios" | "android"; push_token: string; created_at: string }

export class RelayStore {
  constructor(private readonly db: Database) {
    db.run(`CREATE TABLE IF NOT EXISTS relay_routes (
      routing_token TEXT PRIMARY KEY, platform TEXT NOT NULL CHECK (platform IN ('ios','android')),
      push_token TEXT NOT NULL, created_at TEXT NOT NULL)`)
  }
  register(platform: "ios" | "android", pushToken: string): string {
    const token = randomBytes(32).toString("base64url")
    this.db.prepare(`INSERT INTO relay_routes (routing_token, platform, push_token, created_at) VALUES (?,?,?,?)`)
      .run(token, platform, pushToken, new Date().toISOString())
    return token
  }
  lookup(routingToken: string): { platform: "ios" | "android"; pushToken: string } | null {
    const row = this.db.prepare(`SELECT platform, push_token FROM relay_routes WHERE routing_token = ?`).get(routingToken) as any
    return row ? { platform: row.platform, pushToken: row.push_token } : null
  }
  unregister(routingToken: string): void {
    this.db.prepare(`DELETE FROM relay_routes WHERE routing_token = ?`).run(routingToken)
  }
}
```

- [ ] **Step 4: Run it, verify PASS** — `bun test src/relay/store.test.ts`
- [ ] **Step 5: Commit** — `git add src/relay/store.ts src/relay/store.test.ts && git commit -m "feat(relay): routing-token store"`

---

### Task A2: APNs adapter (ES256 JWT + HTTP/2)

**Files:**
- Create: `src/relay/apns.ts` (implements the existing `PlatformPushAdapter` interface from `src/core/push/native-sender.ts`)
- Create: `src/relay/apns.test.ts`

**Interface to satisfy** (already defined in `src/core/push/native-sender.ts`):
```ts
interface PlatformPushAdapter { send(token: string, payload: PushPayload): Promise<{ ok: true } | { ok: false; gone: boolean }> }
```
For the relay, the "payload" carried is the already-encrypted blob; see Task A4 for how the relay calls this. The adapter is responsible only for the APNs transport + status mapping.

- [ ] **Step 1: Write the failing test** (inject the HTTP/2 request fn so no network is hit)

```ts
// src/relay/apns.test.ts
import { expect, test } from "bun:test"
import { createApnsAdapter } from "./apns"

const cfg = { keyP8: "-", keyId: "K", teamId: "T", bundleId: "dev.supermux.ios", sandbox: true }

test("maps 200 → ok", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 200, body: "" }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: true })
})

test("maps 410 (Unregistered) → gone", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 410, body: '{"reason":"Unregistered"}' }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: true })
})

test("maps other 4xx/5xx → not gone", async () => {
  const a = createApnsAdapter(cfg, async () => ({ status: 503, body: "" }))
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: false })
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/relay/apns.test.ts`

- [ ] **Step 3: Implement `createApnsAdapter`**

Key points (validate against Apple docs during execution — this is real-protocol code):
- Provider auth token = ES256-signed JWT, header `{alg:"ES256", kid:keyId}`, claims `{iss:teamId, iat:now}`, signed with the `.p8` private key via WebCrypto `crypto.subtle.sign({name:"ECDSA",hash:"SHA-256"}, key, …)`. Cache the JWT ~50 min.
- Request: HTTP/2 POST to `https://${sandbox?"api.sandbox.":"api."}push.apple.com/3/device/${token}`, headers `authorization: bearer <jwt>`, `apns-topic: bundleId`, `apns-push-type: alert`, `mutable-content` in the JSON body's `aps`. Body: `{ aps: { alert: { title: "supermux", body: "" }, "mutable-content": 1 }, data: payload.ciphertext }`.
- The HTTP/2 call is injected as `h2post(opts) => {status, body}` (default impl uses `node:http2`) so tests stay offline.
- Status map: `200 → {ok:true}`; `410` OR body reason `Unregistered`/`BadDeviceToken` → `{ok:false, gone:true}`; else `{ok:false, gone:false}`.

```ts
// src/relay/apns.ts  (signature + status mapping; fill the JWT/h2 bodies per notes above)
import type { PlatformPushAdapter } from "../core/push/native-sender"
export interface ApnsConfig { keyP8: string; keyId: string; teamId: string; bundleId: string; sandbox: boolean }
export type H2Post = (o: { host: string; path: string; headers: Record<string,string>; body: string }) => Promise<{ status: number; body: string }>
export function createApnsAdapter(cfg: ApnsConfig, h2post: H2Post = defaultH2Post): PlatformPushAdapter {
  return { async send(token, payload: any) {
    const jwt = await providerJwt(cfg)            // ES256, cached
    const host = cfg.sandbox ? "api.sandbox.push.apple.com" : "api.push.apple.com"
    const body = JSON.stringify({ aps: { alert: { title: "supermux", body: "" }, "mutable-content": 1 }, data: payload.ciphertext })
    const res = await h2post({ host, path: `/3/device/${token}`, headers: { authorization: `bearer ${jwt}`, "apns-topic": cfg.bundleId, "apns-push-type": "alert" }, body })
    if (res.status === 200) return { ok: true }
    const gone = res.status === 410 || /Unregistered|BadDeviceToken/.test(res.body)
    return { ok: false, gone }
  } }
}
// providerJwt(cfg) + defaultH2Post(node:http2) implemented here.
```

- [ ] **Step 4: Run it, verify PASS** — `bun test src/relay/apns.test.ts`
- [ ] **Step 5: Commit** — `git add src/relay/apns.* && git commit -m "feat(relay): APNs adapter (ES256 + HTTP/2)"`

---

### Task A3: FCM adapter (service-account OAuth2 + HTTP v1)

**Files:**
- Create: `src/relay/fcm.ts`
- Create: `src/relay/fcm.test.ts`

- [ ] **Step 1: Write the failing test** (inject the access-token getter + the fetch)

```ts
// src/relay/fcm.test.ts
import { expect, test } from "bun:test"
import { createFcmAdapter } from "./fcm"

const deps = { projectId: "p", getAccessToken: async () => "ya29", fetchImpl: async () => new Response("", { status: 200 }) }

test("maps 200 → ok", async () => {
  const a = createFcmAdapter({ ...deps })
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: true })
})

test("maps 404 (UNREGISTERED) → gone", async () => {
  const a = createFcmAdapter({ ...deps, fetchImpl: async () => new Response('{"error":{"status":"UNREGISTERED"}}', { status: 404 }) })
  expect(await a.send("tok", { ciphertext: "blob" } as any)).toEqual({ ok: false, gone: true })
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/relay/fcm.test.ts`

- [ ] **Step 3: Implement `createFcmAdapter`** (validate against FCM HTTP v1 docs during execution)
- `getAccessToken()` mints an OAuth2 token from the SA JSON (JWT grant signed RS256 with the SA private key → POST `https://oauth2.googleapis.com/token`; cache ~50 min). Inject it for tests.
- POST `https://fcm.googleapis.com/v1/projects/${projectId}/messages:send`, bearer token, body `{ message: { token, data: { d: payload.ciphertext } } }` (data-only so the client controls display).
- Status map: `200 → ok`; `404` or error status `UNREGISTERED`/`INVALID_ARGUMENT` (bad token) → `gone:true`; else `gone:false`.

```ts
// src/relay/fcm.ts
import type { PlatformPushAdapter } from "../core/push/native-sender"
export interface FcmDeps { projectId: string; getAccessToken: () => Promise<string>; fetchImpl?: typeof fetch }
export function createFcmAdapter(deps: FcmDeps): PlatformPushAdapter {
  const f = deps.fetchImpl ?? fetch
  return { async send(token, payload: any) {
    const at = await deps.getAccessToken()
    const res = await f(`https://fcm.googleapis.com/v1/projects/${deps.projectId}/messages:send`, {
      method: "POST", headers: { authorization: `Bearer ${at}`, "content-type": "application/json" },
      body: JSON.stringify({ message: { token, data: { d: payload.ciphertext } } }) })
    if (res.status === 200) return { ok: true }
    const body = await res.text()
    const gone = res.status === 404 || /UNREGISTERED|INVALID_ARGUMENT/.test(body)
    return { ok: false, gone }
  } }
}
```

- [ ] **Step 4: Run it, verify PASS** — `bun test src/relay/fcm.test.ts`
- [ ] **Step 5: Commit** — `git add src/relay/fcm.* && git commit -m "feat(relay): FCM adapter (OAuth2 + HTTP v1)"`

---

### Task A4: Relay core — register / push / unregister + bootstrap delivery + rate limit

**Files:**
- Create: `src/relay/core.ts`
- Create: `src/relay/core.test.ts`

The core ties the store + adapters together. `register` mints a token AND sends a **bootstrap push** carrying the routing token to the device (proof-of-possession). `push` looks up + forwards. A simple in-memory rate limiter caps sends per routing token.

- [ ] **Step 1: Write the failing test** (mock adapters capture sends)

```ts
// src/relay/core.test.ts
import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { RelayStore } from "./store"
import { createRelayCore } from "./core"

function mk() {
  const sent: any[] = []
  const adapter = { send: (token: string, p: any) => { sent.push({ token, p }); return Promise.resolve({ ok: true as const }) } }
  const core = createRelayCore({ store: new RelayStore(new Database(":memory:")), apns: adapter, fcm: adapter, ratePerMin: 5 })
  return { core, sent }
}

test("register mints a token and bootstrap-pushes it to the device", async () => {
  const { core, sent } = mk()
  const { routingToken, status } = await core.register("ios", "apns-tok")
  expect(status).toBe("pending")
  expect(sent).toHaveLength(1)
  expect(sent[0].token).toBe("apns-tok")        // bootstrap went to the device's push token
  expect(JSON.stringify(sent[0].p)).toContain(routingToken) // …carrying the routing token
})

test("push forwards the ciphertext to the mapped device", async () => {
  const { core, sent } = mk()
  const { routingToken } = await core.register("android", "fcm-tok")
  sent.length = 0
  const r = await core.push(routingToken, "CIPHER")
  expect(r).toEqual({ ok: true })
  expect(sent[0]).toMatchObject({ token: "fcm-tok", p: { ciphertext: "CIPHER" } })
})

test("push to an unknown routing token returns gone", async () => {
  const { core } = mk()
  expect(await core.push("nope", "x")).toEqual({ ok: false, gone: true })
})

test("rate limit blocks the N+1th push in a window", async () => {
  const { core } = mk()
  const { routingToken } = await core.register("ios", "t")
  for (let i = 0; i < 5; i++) expect((await core.push(routingToken, "x")).ok).toBe(true)
  expect(await core.push(routingToken, "x")).toEqual({ ok: false, gone: false }) // 6th blocked
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/relay/core.test.ts`

- [ ] **Step 3: Implement `createRelayCore`**

```ts
// src/relay/core.ts
import type { RelayStore } from "./store"
import type { PlatformPushAdapter } from "../core/push/native-sender"

export interface RelayCore {
  register(platform: "ios" | "android", pushToken: string): Promise<{ routingToken: string; status: "pending" }>
  push(routingToken: string, ciphertext: string): Promise<{ ok: true } | { ok: false; gone: boolean }>
  unregister(routingToken: string): void
}
export function createRelayCore(o: { store: RelayStore; apns: PlatformPushAdapter; fcm: PlatformPushAdapter; ratePerMin: number }): RelayCore {
  const hits = new Map<string, number[]>()
  const adapterFor = (p: "ios" | "android") => (p === "ios" ? o.apns : o.fcm)
  function allowed(rt: string): boolean {
    const now = Date.now(), win = hits.get(rt)?.filter((t) => now - t < 60_000) ?? []
    if (win.length >= o.ratePerMin) { hits.set(rt, win); return false }
    win.push(now); hits.set(rt, win); return true
  }
  return {
    async register(platform, pushToken) {
      const routingToken = o.store.register(platform, pushToken)
      // bootstrap push: deliver the routing token THROUGH the device's push channel
      await adapterFor(platform).send(pushToken, { ciphertext: JSON.stringify({ kind: "bootstrap", routingToken }) } as any)
      return { routingToken, status: "pending" }
    },
    async push(routingToken, ciphertext) {
      const route = o.store.lookup(routingToken)
      if (!route) return { ok: false, gone: true }
      if (!allowed(routingToken)) return { ok: false, gone: false }
      const res = await adapterFor(route.platform).send(route.pushToken, { ciphertext } as any)
      if (res.ok === false && res.gone) o.store.unregister(routingToken)
      return res
    },
    unregister(routingToken) { o.store.unregister(routingToken) },
  }
}
```

- [ ] **Step 4: Run it, verify PASS** — `bun test src/relay/core.test.ts`
- [ ] **Step 5: Commit** — `git add src/relay/core.* && git commit -m "feat(relay): core register/push/unregister + bootstrap + rate limit"`

---

### Task A5: Relay HTTP server + config + entrypoint

**Files:**
- Create: `src/relay/server.ts` (request router → core; testable with `Bun.serve({ port: 0 })`)
- Create: `src/relay/server.test.ts`
- Create: `src/relay/main.ts` (reads env, wires real adapters + SQLite, starts the server)
- Modify: `package.json` (add `"relay": "bun src/relay/main.ts"` to `scripts`)

- [ ] **Step 1: Write the failing test**

```ts
// src/relay/server.test.ts
import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { RelayStore } from "./store"
import { createRelayCore } from "./core"
import { makeRelayHandler } from "./server"

function handler() {
  const a = { send: () => Promise.resolve({ ok: true as const }) }
  const core = createRelayCore({ store: new RelayStore(new Database(":memory:")), apns: a, fcm: a, ratePerMin: 100 })
  return makeRelayHandler(core)
}
const req = (path: string, body: any) => new Request("http://x" + path, { method: "POST", body: JSON.stringify(body) })

test("POST /register returns 202 pending (token not in the response)", async () => {
  const res = await handler()(req("/register", { platform: "ios", pushToken: "t" }))
  expect(res.status).toBe(202)
  expect(await res.json()).toEqual({ status: "pending" })
})

test("POST /push with an unknown token returns gone", async () => {
  const res = await handler()(req("/push", { routingToken: "nope", ciphertext: "x" }))
  expect(await res.json()).toMatchObject({ ok: false, gone: true })
})

test("rejects malformed bodies with 400", async () => {
  const res = await handler()(req("/register", { platform: "windows", pushToken: "t" }))
  expect(res.status).toBe(400)
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/relay/server.test.ts`

- [ ] **Step 3: Implement `makeRelayHandler` + `main.ts`**

```ts
// src/relay/server.ts
import type { RelayCore } from "./core"
const json = (b: any, status = 200) => new Response(JSON.stringify(b), { status, headers: { "content-type": "application/json" } })
export function makeRelayHandler(core: RelayCore) {
  return async (req: Request): Promise<Response> => {
    const { pathname } = new URL(req.url)
    if (req.method !== "POST") return new Response("method", { status: 405 })
    let b: any; try { b = await req.json() } catch { return json({ error: "bad json" }, 400) }
    if (pathname === "/register") {
      if (b?.platform !== "ios" && b?.platform !== "android") return json({ error: "platform" }, 400)
      if (typeof b?.pushToken !== "string") return json({ error: "pushToken" }, 400)
      await core.register(b.platform, b.pushToken)
      return json({ status: "pending" }, 202)        // routing token is delivered via the bootstrap push, never here
    }
    if (pathname === "/push") {
      if (typeof b?.routingToken !== "string" || typeof b?.ciphertext !== "string") return json({ error: "fields" }, 400)
      return json(await core.push(b.routingToken, b.ciphertext))
    }
    if (pathname === "/unregister") {
      if (typeof b?.routingToken !== "string") return json({ error: "routingToken" }, 400)
      core.unregister(b.routingToken); return json({ ok: true })
    }
    return new Response("not found", { status: 404 })
  }
}
```
`main.ts`: read env (`MUX_RELAY_PORT`, `MUX_RELAY_DB`, APNs `MUX_APNS_KEY_P8`/`_KEY_ID`/`_TEAM_ID`/`_BUNDLE_ID`/`_SANDBOX`, FCM `MUX_FCM_SA_JSON`/`_PROJECT_ID`, `MUX_RELAY_RATE_PER_MIN`) → build `createApnsAdapter` + `createFcmAdapter` + `RelayStore(new Database(dbPath))` → `createRelayCore` → `Bun.serve({ port, fetch: makeRelayHandler(core) })`. Add a per-IP `/register` limiter (reuse the `allowed()` shape keyed by `req` IP). Log readiness like the broker's `log.info("push_ready", …)`.

- [ ] **Step 4: Run it, verify PASS** — `bun test src/relay/server.test.ts`
- [ ] **Step 5: Commit** — `git add src/relay package.json && git commit -m "feat(relay): HTTP server + entrypoint + relay npm script"`

---

## Phase B — Broker changes

### Task B1: Migration 012 + store fields

**Files:**
- Create: `src/core/storage/migrations/012_device_push_routing.sql`
- Modify: `src/core/push/device-tokens.ts` (add `routing_token`, `device_pubkey`; new `putNative(...)`)
- Modify: `src/core/push/device-tokens.test.ts` (extend `freshDb` columns + a new test)

- [ ] **Step 1: Write the failing test** (add to `device-tokens.test.ts`; update its inline `CREATE TABLE` to include the two new columns)

```ts
test("putNative stores routingToken + pubkey and get returns them", () => {
  const s = new DevicePushTokenStore(freshDb())
  s.putNative("phone", "ios", "rt-123", "PUBKEY")
  expect(s.get("phone")).toMatchObject({ platform: "ios", routing_token: "rt-123", device_pubkey: "PUBKEY" })
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/core/push/device-tokens.test.ts`

- [ ] **Step 3: Implement**

```sql
-- src/core/storage/migrations/012_device_push_routing.sql
ALTER TABLE device_push_tokens ADD COLUMN routing_token TEXT;
ALTER TABLE device_push_tokens ADD COLUMN device_pubkey TEXT;
```
Add to `DevicePushTokenStore` (`device-tokens.ts`): extend `DevicePushTokenRecord` with `routing_token: string | null; device_pubkey: string | null`, and add:
```ts
putNative(device: string, platform: "ios" | "android", routingToken: string, pubkey: string): void {
  this.db.prepare(`INSERT INTO device_push_tokens (device, platform, token, routing_token, device_pubkey, created_at)
    VALUES (?, ?, '', ?, ?, ?)
    ON CONFLICT(device) DO UPDATE SET platform=excluded.platform, routing_token=excluded.routing_token, device_pubkey=excluded.device_pubkey`)
    .run(device, platform, routingToken, pubkey, new Date().toISOString())
}
```
(The legacy `token` column stays NOT NULL → insert `''`; the broker keys off `routing_token`.)

- [ ] **Step 4: Run it, verify PASS** — `bun test src/core/push/device-tokens.test.ts`
- [ ] **Step 5: Commit** — `git add src/core/push/device-tokens.* src/core/storage/migrations/012_device_push_routing.sql && git commit -m "feat(push): migration 012 + native routing fields"`

---

### Task B2: Payload encryption (P-256 ECDH + HKDF + AES-256-GCM)

**Files:**
- Create: `src/core/push/encrypt.ts`
- Create: `src/core/push/encrypt.test.ts`

Resolves the spec crypto open-Q: **P-256 ECDH → HKDF-SHA256 → AES-256-GCM**, all via WebCrypto (available in bun; mirrors the RFC 8291 family; P-256 enables Secure Enclave on iOS). The device public key is an uncompressed P-256 point, base64url.

- [ ] **Step 1: Write the failing test** (round-trip with an ephemeral recipient keypair generated in-test)

```ts
// src/core/push/encrypt.test.ts
import { expect, test } from "bun:test"
import { sealForDevice } from "./encrypt"

test("sealForDevice produces a blob a holder of the private key can open", async () => {
  const kp = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])
  const rawPub = Buffer.from(await crypto.subtle.exportKey("raw", kp.publicKey)).toString("base64url")
  const sealed = await sealForDevice(rawPub, JSON.stringify({ session: "s", text: "hi" }))
  // decrypt with the test private key using the helper's documented format:
  const plain = await openForTest(sealed, kp.privateKey)   // openForTest defined in the test, mirrors the client
  expect(JSON.parse(plain)).toMatchObject({ session: "s", text: "hi" })
})
```
(Include `openForTest` in the test file — it does the receiver half: import the ephemeral pub from `sealed`, ECDH, HKDF, AES-GCM open. This both tests and *documents the exact wire format the iOS/Android clients must implement.*)

- [ ] **Step 2: Run it, verify it fails** — `bun test src/core/push/encrypt.test.ts`

- [ ] **Step 3: Implement `sealForDevice`** — generate an ephemeral P-256 keypair; `deriveBits` against the device pub; HKDF-SHA256 (salt=16 random bytes, info=`"supermux-push"`) → 32-byte AES key; AES-256-GCM (12-byte iv); output `base64url(ephemeralRawPub) + "." + base64url(salt) + "." + base64url(iv) + "." + base64url(ciphertext+tag)`. Document this 4-part format in a comment (the clients depend on it).

- [ ] **Step 4: Run it, verify PASS** — `bun test src/core/push/encrypt.test.ts`
- [ ] **Step 5: Commit** — `git add src/core/push/encrypt.* && git commit -m "feat(push): P-256 ECIES sealForDevice + documented wire format"`

---

### Task B3: Relay client adapter

**Files:**
- Create: `src/core/push/relay-adapter.ts`
- Create: `src/core/push/relay-adapter.test.ts`

A `NativePushSender`-shaped object (matches `src/core/push/native-sender.ts`'s `sendToDevice`) that, given a device id, reads the row, seals the payload, and POSTs `{routingToken, ciphertext}` to the relay.

- [ ] **Step 1: Write the failing test** (inject fetch + a store with a real device pubkey)

```ts
// src/core/push/relay-adapter.test.ts
import { expect, test } from "bun:test"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "./device-tokens"
import { createRelayClient } from "./relay-adapter"

async function deviceKey() {
  const kp = await crypto.subtle.generateKey({ name: "ECDH", namedCurve: "P-256" }, true, ["deriveBits"])
  return Buffer.from(await crypto.subtle.exportKey("raw", kp.publicKey)).toString("base64url")
}
function store() {
  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  return new DevicePushTokenStore(db)
}

test("seals the payload and POSTs {routingToken, ciphertext} to the relay", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  let captured: any
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", fetchImpl: async (_u, init) => { captured = JSON.parse((init as any).body); return new Response("{\"ok\":true}") } })
  const r = await client.sendToDevice("phone", { session: "s", text: "hi", ts: "t" })
  expect(r).toEqual({ ok: true })
  expect(captured.routingToken).toBe("rt-1")
  expect(typeof captured.ciphertext).toBe("string")     // opaque, sealed
  expect(captured.ciphertext).not.toContain("hi")       // plaintext never leaves
})

test("relay 'gone' prunes the device row", async () => {
  const s = store(); s.putNative("phone", "ios", "rt-1", await deviceKey())
  const client = createRelayClient({ store: s, relayUrl: "https://relay.test", fetchImpl: async () => new Response('{"ok":false,"gone":true}') })
  expect(await client.sendToDevice("phone", { session: "s", ts: "t" })).toEqual({ ok: false, gone: true })
  expect(s.get("phone")).toBeNull()
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/core/push/relay-adapter.test.ts`

- [ ] **Step 3: Implement `createRelayClient`** — `sendToDevice(device, payload)`: `row = store.get(device)`; if `!row?.routing_token || !row.device_pubkey` return `{ok:false, gone:true}`; `ciphertext = await sealForDevice(row.device_pubkey, JSON.stringify(payload))`; `POST ${relayUrl}/push {routingToken: row.routing_token, ciphertext}`; parse `{ok}`/`{ok,gone}`; on `gone` call `store.remove(device)`; return the result.

- [ ] **Step 4: Run it, verify PASS** — `bun test src/core/push/relay-adapter.test.ts`
- [ ] **Step 5: Commit** — `git add src/core/push/relay-adapter.* && git commit -m "feat(push): broker relay client (seal + forward)"`

---

### Task B4: `POST` / `DELETE /push/device` endpoints

**Files:**
- Modify: `src/channels/web/index.ts` (add handlers next to `/push/subscribe` ~line 1140; add `deviceTokenStore?` + `relayUrl?` to opts/fields like `pushStore`)
- Create: `tests/push-device-endpoint.test.ts` (mirror `tests/push-endpoints.test.ts` — use `port: 0` + `channel.boundPort`, mint a device token via a sibling `DeviceStore`, see existing `update-routes.test.ts`)

- [ ] **Step 1: Write the failing test** — POST `/push/device {platform, routingToken, pubkey}` with a valid bearer → 200 + row stored; POST without auth → 401; DELETE removes the row. (Construct the channel with a `DevicePushTokenStore` over a temp DB; assert via that store.)

- [ ] **Step 2: Run it, verify it fails** — `bun test tests/push-device-endpoint.test.ts`

- [ ] **Step 3: Implement the handlers** (mirror the `/push/subscribe` block exactly):

```ts
if (method === "POST" && path === "/push/device") {
  const auth = this.requireAuth(req); if (!auth.ok) return new Response("unauthorized", { status: 401 })
  if (!this.deviceTokenStore) return new Response("push not configured", { status: 503 })
  let body: any; try { body = await req.json() } catch { return new Response("bad json", { status: 400 }) }
  const platform = body?.platform, rt = body?.routingToken, pubkey = body?.pubkey
  if ((platform !== "ios" && platform !== "android") || typeof rt !== "string" || typeof pubkey !== "string")
    return new Response("platform + routingToken + pubkey required", { status: 400 })
  this.deviceTokenStore.putNative(auth.device.name, platform, rt, pubkey)
  return this.json({ ok: true })
}
if (method === "DELETE" && path === "/push/device") {
  const auth = this.requireAuth(req); if (!auth.ok) return new Response("unauthorized", { status: 401 })
  if (!this.deviceTokenStore) return new Response("push not configured", { status: 503 })
  this.deviceTokenStore.remove(auth.device.name)
  return this.json({ ok: true })
}
```
Add `/push/device` is already covered by the `"/push"` entry in `API_PREFIXES`. Add `deviceTokenStore?: DevicePushTokenStore` to `WebChannelOpts` + the private field + constructor assignment (mirror `pushStore`).

- [ ] **Step 4: Run it, verify PASS** — `bun test tests/push-device-endpoint.test.ts`
- [ ] **Step 5: Commit** — `git add src/channels/web/index.ts tests/push-device-endpoint.test.ts && git commit -m "feat(push): POST/DELETE /push/device registration endpoints"`

---

### Task B5: Fan native push into `firePushForReply`

**Files:**
- Modify: `src/core/push/hook.ts` (extend `FirePushArgs` with an optional `nativeSender` + `nativeDevices()`; fan out to both after the existing suppression checks)
- Modify: `src/core/push/hook.test.ts` (or the suppression test) — assert native sender is called when not muted/not present, and NOT called when muted or present
- Modify: `tests/push-viewing-suppression.test.ts` — extend to cover native

- [ ] **Step 1: Write the failing test** — with a mock `nativeSender`, a reply when not muted + not present calls `nativeSender.sendToDevice` for each native device; muted → zero calls; present-on-any-device → zero calls (reuse the existing suppression scaffolding).

- [ ] **Step 2: Run it, verify it fails** — `bun test src/core/push/hook.test.ts`

- [ ] **Step 3: Implement** — in `firePushForReply`, after the existing `isMuted`/`anyPresent` guards build the `payload`, add (keeping the web fan-out unchanged):
```ts
if (args.nativeSender && args.nativeDevices) {
  for (const device of args.nativeDevices()) await args.nativeSender.sendToDevice(device, payload)
}
```
Add `nativeSender?: NativePushSender` and `nativeDevices?: () => string[]` to `FirePushArgs`. (The suppression lives above this block, so native inherits mute + presence automatically.)

- [ ] **Step 4: Run it, verify PASS** — `bun test src/core/push/hook.test.ts tests/push-viewing-suppression.test.ts`
- [ ] **Step 5: Commit** — `git add src/core/push/hook.* tests/push-viewing-suppression.test.ts && git commit -m "feat(push): fan native push into the notify hook (inherits suppression)"`

---

### Task B6: Wire it together in `main.ts`

**Files:**
- Modify: `src/main.ts` (construct `DevicePushTokenStore` + `createRelayClient`; pass to the `WebChannel` opts; pass `nativeSender`/`nativeDevices` into the `firePushForReply` call ~line 613; mirror into the error path ~656 and broadcast paths ~760/3161 — resolves the spec "fan-out breadth" open-Q: **all paths**, for parity)

- [ ] **Step 1: Implement the wiring** (no new behavior beyond construction; covered by the integration tests below)

```ts
// near line 405, after pushSender:
const deviceTokenStore = new DevicePushTokenStore(db)
const relayUrl = process.env.MUX_PUSH_RELAY_URL ?? "https://push.supermux.dev"
const nativeSender = createRelayClient({ store: deviceTokenStore, relayUrl })
const nativeDevices = () => deviceTokenStore.all().filter((r) => r.routing_token).map((r) => r.device)
```
- Pass `deviceTokenStore` into the `WebChannel` opts.
- At the `firePushForReply({...})` call (~613) add `nativeSender, nativeDevices`.
- At the error path (~656) and broadcast loops (~760, ~3161) add a parallel `for (const r of deviceTokenStore.all()) if (r.routing_token) void nativeSender.sendToDevice(r.device, payload)`.

- [ ] **Step 2: Add an integration test** — `tests/native-push-e2e.test.ts`: stand up the broker pieces with a mock relay (capture POSTs), register a device via `/push/device`, fire a reply through the hook, assert the mock relay received a `{routingToken, ciphertext}` whose ciphertext does not contain the plaintext; assert a muted/present session sends nothing.

- [ ] **Step 3: Run the full suite** — `bun test` — Expected: all green (note the 4–7 pre-existing display/layout failures called out in the repo notes are unrelated; confirm against a clean tree).

- [ ] **Step 4: Commit** — `git add src/main.ts tests/native-push-e2e.test.ts && git commit -m "feat(push): wire native sender into broker notify paths"`

---

## Phase C — iOS client *(outline → own plan; build on the Mac)*

Becomes `docs/superpowers/plans/2026-06-2x-native-push-ios.md`. Outline:
- Add Push Notifications capability + `aps-environment`; `UNUserNotificationCenter` auth → APNs token.
- Generate a **P-256** keypair (CryptoKit; consider Secure Enclave for the private key); export the raw public key (base64url) matching `encrypt.ts`'s format.
- `POST {relay}/register {platform:"ios", pushToken}`; receive the routing token via the **bootstrap push** (a `bootstrap` payload handled in the app/NSE); then `POST /push/device {platform:"ios", routingToken, pubkey}` to the broker (relay URL learned from the broker config).
- **Notification Service Extension**: parse the 4-part sealed blob from `aps`/`data`, do the receiver half (ECDH→HKDF→AES-GCM, mirroring `encrypt.test.ts`'s `openForTest`), set title/body; generic fallback on failure.
- Tap → deep-link to the session (reuse `SM_OPEN_SESSION` routing). watchOS mirrors automatically.
- Verify on the remote Mac + a real device (sandbox APNs). Static-review only on this Linux host.

## Phase D — Android client *(outline → own plan; build on the emulator/device)*

Becomes `docs/superpowers/plans/2026-06-2x-native-push-android.md`. Outline:
- Add Firebase Messaging SDK + the publisher's `google-services.json`; `FirebaseMessagingService` → FCM token (+ `onNewToken`).
- Generate a **P-256** keypair (Keystore / Tink); export raw public key (base64url) matching `encrypt.ts`.
- `POST {relay}/register`; receive routing token via bootstrap data message; `POST /push/device` to the broker.
- On data message: open the sealed blob (ECDH→HKDF→AES-GCM), post a notification on a "Sessions" channel; tap → open session.
- Shared KMP (`apps/shared`): the register call + keypair/seal-open can live in commonMain (`expect/actual` over CryptoKit / Java EC).
- Verify on the emulator (FCM works there with Play services) + a real device.

## Phase E — One-time ops / credentials *(Ahmet)*

- Apple Developer: create an **APNs Auth Key (.p8)**; note Key ID, Team ID, bundle id `dev.supermux.ios`. Ship the iOS app via **App Store/TestFlight** (push entitlement).
- Firebase: create a project; download the **service-account JSON**; embed the matching `google-services.json` in the Android build.
- Deploy the relay (the existing Docker image, `relay` entrypoint) on Coolify with the secrets as env; point DNS at it (e.g. `push.supermux.dev`); set `MUX_PUSH_RELAY_URL` default to it.

---

## Self-Review

**Spec coverage:** relay (A1–A5) ✓ · broker registration endpoint (B4) ✓ · relay adapter + encryption (B2/B3) ✓ · migration 012 (B1) ✓ · fan-out into firePushForReply + error/broadcast paths (B5/B6) ✓ · gone-handling/pruning (A4/B3) ✓ · rate-limit/abuse (A4 + A5 per-IP) ✓ · proof-of-possession bootstrap (A4) ✓ · privacy/encryption (B2/B3) ✓ · iOS/Android/distribution (C/D/E outlines) ✓. Spec open-Qs resolved here: crypto = P-256 ECIES (B2); fan-out breadth = all paths (B6); migration = add columns (B1); relay store = SQLite (A1).

**Placeholder scan:** Protocol-specific bodies in A2 (APNs JWT/HTTP2) and A3 (FCM OAuth2) are intentionally described with the exact approach + status-mapping code + injected-transport tests rather than fabricated wire code I can't verify on this host; everything else is complete code. These are flagged "validate against the live API during execution," not "implement later."

**Type consistency:** `PlatformPushAdapter.send(token, payload)` (native-sender.ts) is reused by A2/A3/A4. `NativePushSender.sendToDevice(device, payload)` shape is matched by B3's `createRelayClient` and consumed by B5. `sealForDevice(pubB64url, plaintext) → string` (B2) is the exact producer the relay-client (B3) and the C/D clients consume; the 4-part wire format is pinned by `encrypt.test.ts`.
