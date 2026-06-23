# Stateless Relay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the push relay hold zero state at rest — replace its SQLite routing table with a sealed self-describing token, and put rate-limiting behind a pluggable interface (in-memory default, optional Redis) — as a pure drop-in (broker + apps + the external relay API unchanged).

**Architecture:** A `TokenCodec` AES-256-GCM-seals `{platform, pushToken, exp}` into the routing token (versioned `r1.<keyId>.<payload>`, AAD-bound, keyset for rotation); the relay `open`s it instead of a DB lookup. A `RateLimiter` interface has an `InMemory` impl (extracted from today's logic) and an optional injected-client `Redis` impl with in-memory fallback. `core.ts`/`main.ts` rewire to these; `RelayStore`/`bun:sqlite` are deleted.

**Tech Stack:** bun + TypeScript, `bun:test`, Node `crypto` (AES-256-GCM — no new dep). Spec: `docs/superpowers/specs/2026-06-23-stateless-relay-design.md`.

**Conventions:** tests use `bun:test` (`import { expect, test } from "bun:test"`); inject `now()` for deterministic time; commit after every green step; end commit bodies with `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

---

### Task 1: TokenCodec (sealed self-describing token)

**Files:**
- Create: `src/relay/token-codec.ts`
- Create: `src/relay/token-codec.test.ts`

- [ ] **Step 1: Write the failing test** `src/relay/token-codec.test.ts`:

```ts
import { expect, test } from "bun:test"
import { randomBytes } from "node:crypto"
import { createTokenCodec, type RelayKeyset } from "./token-codec"

function keyset(ids: string[]): RelayKeyset {
  const keys = new Map(ids.map((id) => [id, randomBytes(32)]))
  return { currentKeyId: ids[0]!, keys }
}
const seed = { platform: "ios" as const, pushToken: "apns-tok-xyz", ttlSeconds: 3600 }

test("seal then open round-trips platform + pushToken", () => {
  const c = createTokenCodec(keyset(["k1"]))
  const tok = c.seal(seed)
  expect(tok.startsWith("r1.k1.")).toBe(true)
  expect(c.open(tok)).toEqual({ ok: true, platform: "ios", pushToken: "apns-tok-xyz" })
})

test("an expired token opens as expired", () => {
  let t = 1000
  const c = createTokenCodec(keyset(["k1"]), () => t)
  const tok = c.seal({ ...seed, ttlSeconds: 10 })
  t = 2000 // now past exp
  expect(c.open(tok)).toEqual({ ok: false, reason: "expired" })
})

test("a tampered token is invalid", () => {
  const c = createTokenCodec(keyset(["k1"]))
  const tok = c.seal(seed)
  const bad = tok.slice(0, -2) + (tok.endsWith("A") ? "B" : "A")
  expect(c.open(bad)).toMatchObject({ ok: false, reason: "invalid" })
})

test("an unknown keyId is invalid; garbage is invalid", () => {
  const c = createTokenCodec(keyset(["k1"]))
  expect(c.open("r1.kZ.abc")).toMatchObject({ ok: false, reason: "invalid" })
  expect(c.open("not-a-token")).toMatchObject({ ok: false, reason: "invalid" })
})

test("rotation: a token sealed under an old key still opens while that key is in the set", () => {
  const ks = keyset(["k2", "k1"]) // current k2, but k1 still present
  const oldOnly = { currentKeyId: "k1", keys: new Map([["k1", ks.keys.get("k1")!]]) }
  const tokFromOld = createTokenCodec(oldOnly).seal(seed)
  expect(createTokenCodec(ks).open(tokFromOld)).toMatchObject({ ok: true, pushToken: "apns-tok-xyz" })
})

test("editing the keyId in the string fails (AAD-bound)", () => {
  const ks = keyset(["k1", "k9"])
  const tok = createTokenCodec(ks).seal(seed) // sealed under k1
  const swapped = tok.replace("r1.k1.", "r1.k9.") // points at a real-but-different key
  expect(createTokenCodec(ks).open(swapped)).toMatchObject({ ok: false, reason: "invalid" })
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/relay/token-codec.test.ts` (Cannot find module).

- [ ] **Step 3: Implement `src/relay/token-codec.ts`:**

```ts
import { randomBytes, createCipheriv, createDecipheriv } from "node:crypto"

export interface RelayKeyset { currentKeyId: string; keys: Map<string, Buffer> } // each key = 32 bytes
export type OpenResult =
  | { ok: true; platform: "ios" | "android"; pushToken: string }
  | { ok: false; reason: "expired" | "invalid" }
export interface TokenCodec {
  seal(input: { platform: "ios" | "android"; pushToken: string; ttlSeconds: number }): string
  open(token: string): OpenResult
}

export function createTokenCodec(keyset: RelayKeyset, now: () => number = () => Math.floor(Date.now() / 1000)): TokenCodec {
  return {
    seal({ platform, pushToken, ttlSeconds }) {
      const keyId = keyset.currentKeyId
      const key = keyset.keys.get(keyId)
      if (!key) throw new Error(`relay token key '${keyId}' missing`)
      const iv = randomBytes(12)
      const aad = Buffer.from(`r1.${keyId}`, "utf8")
      const pt = Buffer.from(JSON.stringify({ p: platform === "ios" ? "i" : "a", t: pushToken, e: now() + ttlSeconds }), "utf8")
      const cipher = createCipheriv("aes-256-gcm", key, iv)
      cipher.setAAD(aad)
      const ct = Buffer.concat([cipher.update(pt), cipher.final()])
      const tag = cipher.getAuthTag()
      return `r1.${keyId}.${Buffer.concat([iv, ct, tag]).toString("base64url")}`
    },
    open(token) {
      try {
        const parts = token.split(".")
        if (parts.length !== 3 || parts[0] !== "r1") return { ok: false, reason: "invalid" }
        const keyId = parts[1]!
        const key = keyset.keys.get(keyId)
        if (!key) return { ok: false, reason: "invalid" }
        const raw = Buffer.from(parts[2]!, "base64url")
        if (raw.length < 12 + 16 + 1) return { ok: false, reason: "invalid" }
        const iv = raw.subarray(0, 12)
        const tag = raw.subarray(raw.length - 16)
        const ct = raw.subarray(12, raw.length - 16)
        const decipher = createDecipheriv("aes-256-gcm", key, iv)
        decipher.setAAD(Buffer.from(`r1.${keyId}`, "utf8"))
        decipher.setAuthTag(tag)
        const pt = Buffer.concat([decipher.update(ct), decipher.final()])
        const o = JSON.parse(pt.toString("utf8")) as { p: "i" | "a"; t: string; e: number }
        if (typeof o.e !== "number" || o.e <= now()) return { ok: false, reason: "expired" }
        return { ok: true, platform: o.p === "i" ? "ios" : "android", pushToken: o.t }
      } catch {
        return { ok: false, reason: "invalid" }
      }
    },
  }
}
```

- [ ] **Step 4: Run it, verify PASS** — `bun test src/relay/token-codec.test.ts`.
- [ ] **Step 5: Commit** — `git add src/relay/token-codec.ts src/relay/token-codec.test.ts && git commit -m "feat(relay): sealed self-describing token codec (AES-256-GCM, keyset rotation)"`.

---

### Task 2: RateLimiter (interface + in-memory + optional Redis)

**Files:**
- Create: `src/relay/rate-limiter.ts`
- Create: `src/relay/rate-limiter.test.ts`

- [ ] **Step 1: Write the failing test** `src/relay/rate-limiter.test.ts`:

```ts
import { expect, test } from "bun:test"
import { createInMemoryRateLimiter, createRedisRateLimiter, type RedisLike } from "./rate-limiter"

test("in-memory: allows up to the limit per minute, then blocks", async () => {
  let t = 0
  const rl = createInMemoryRateLimiter(() => t)
  for (let i = 0; i < 3; i++) expect(await rl.allow("k", 3)).toBe(true)
  expect(await rl.allow("k", 3)).toBe(false)
  t = 61_000 // window passed
  expect(await rl.allow("k", 3)).toBe(true)
})

test("in-memory: separate keys have separate windows", async () => {
  const rl = createInMemoryRateLimiter(() => 0)
  expect(await rl.allow("a", 1)).toBe(true)
  expect(await rl.allow("a", 1)).toBe(false)
  expect(await rl.allow("b", 1)).toBe(true)
})

test("redis: blocks when the counter exceeds the limit", async () => {
  let n = 0
  const client: RedisLike = { incrWithExpiry: async () => ++n }
  const rl = createRedisRateLimiter(client, createInMemoryRateLimiter(() => 0))
  expect(await rl.allow("k", 2)).toBe(true)  // 1
  expect(await rl.allow("k", 2)).toBe(true)  // 2
  expect(await rl.allow("k", 2)).toBe(false) // 3 > 2
})

test("redis: a client error falls back to the in-memory limiter (never blocks delivery on an outage)", async () => {
  const client: RedisLike = { incrWithExpiry: async () => { throw new Error("redis down") } }
  const rl = createRedisRateLimiter(client, createInMemoryRateLimiter(() => 0))
  expect(await rl.allow("k", 1)).toBe(true)   // fallback allows the first
  expect(await rl.allow("k", 1)).toBe(false)  // fallback then limits
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/relay/rate-limiter.test.ts`.

- [ ] **Step 3: Implement `src/relay/rate-limiter.ts`:**

```ts
export interface RateLimiter {
  /** Returns true if this event is allowed under `limitPerMin` for `key`. */
  allow(key: string, limitPerMin: number): Promise<boolean>
}

export function createInMemoryRateLimiter(now: () => number = () => Date.now()): RateLimiter {
  const hits = new Map<string, number[]>()
  return {
    async allow(key, limitPerMin) {
      const t = now()
      const win = (hits.get(key) ?? []).filter((x) => t - x < 60_000)
      if (win.length >= limitPerMin) { hits.set(key, win); return false }
      win.push(t)
      hits.set(key, win)
      return true
    },
  }
}

/** Minimal Redis surface: atomically increment `key` and (re)set a TTL, returning the new count. */
export interface RedisLike { incrWithExpiry(key: string, windowSeconds: number): Promise<number> }

export function createRedisRateLimiter(client: RedisLike, fallback: RateLimiter): RateLimiter {
  return {
    async allow(key, limitPerMin) {
      try {
        return (await client.incrWithExpiry(`rl:${key}`, 60)) <= limitPerMin
      } catch {
        return fallback.allow(key, limitPerMin)
      }
    },
  }
}
```

- [ ] **Step 4: Run it, verify PASS** — `bun test src/relay/rate-limiter.test.ts`.
- [ ] **Step 5: Commit** — `git add src/relay/rate-limiter.ts src/relay/rate-limiter.test.ts && git commit -m "feat(relay): pluggable RateLimiter (in-memory + optional Redis w/ fallback)"`.

---

### Task 3: Rewire core + main onto the codec/limiter; delete the SQLite store

**Files:**
- Modify: `src/relay/core.ts`
- Modify: `src/relay/core.test.ts`
- Modify: `src/relay/server.test.ts` (only its core-construction helper; assertions unchanged)
- Modify: `src/relay/main.ts`
- Delete: `src/relay/store.ts`, `src/relay/store.test.ts`

READ `src/relay/core.ts`, `core.test.ts`, `server.test.ts`, `main.ts` first.

- [ ] **Step 1: Rewrite `core.test.ts`** so `createRelayCore` takes `{ codec, apns, fcm, limiter, ttlSeconds, ratePerMin, globalRatePerMin }` (no `store`). Build it with a real `createTokenCodec` (a 1-key keyset) + a mock adapter + `createInMemoryRateLimiter`. Keep the same behavioral tests, adapted:

```ts
import { expect, test } from "bun:test"
import { randomBytes } from "node:crypto"
import { createTokenCodec } from "./token-codec"
import { createInMemoryRateLimiter } from "./rate-limiter"
import { createRelayCore } from "./core"

function mk(ratePerMin = 100) {
  const sent: any[] = []
  const adapter = { send: (token: string, p: any, opts?: any) => { sent.push({ token, p, opts }); return Promise.resolve({ ok: true as const }) } }
  const codec = createTokenCodec({ currentKeyId: "k1", keys: new Map([["k1", randomBytes(32)]]) })
  const core = createRelayCore({ codec, apns: adapter, fcm: adapter, limiter: createInMemoryRateLimiter(), ttlSeconds: 3600, ratePerMin, globalRatePerMin: 1000 })
  return { core, sent, codec }
}

test("register seals a token and silently bootstrap-pushes it to the device", async () => {
  const { core, sent, codec } = mk()
  const { routingToken } = await core.register("ios", "apns-tok")
  expect(sent).toHaveLength(1)
  expect(sent[0].token).toBe("apns-tok")
  expect(sent[0].opts).toEqual({ silent: true })
  expect(codec.open(routingToken)).toMatchObject({ ok: true, pushToken: "apns-tok" })
})

test("push opens the token and forwards the ciphertext", async () => {
  const { core, sent } = mk()
  const { routingToken } = await core.register("android", "fcm-tok")
  sent.length = 0
  expect(await core.push(routingToken, "CIPHER")).toEqual({ ok: true })
  expect(sent[0]).toMatchObject({ token: "fcm-tok", p: { ciphertext: "CIPHER" } })
})

test("push with an invalid/garbage token returns gone", async () => {
  const { core } = mk()
  expect(await core.push("not-a-real-token", "x")).toEqual({ ok: false, gone: true })
})

test("rate limit blocks the N+1th push for a token", async () => {
  const { core } = mk(3)
  const { routingToken } = await core.register("ios", "t")
  for (let i = 0; i < 3; i++) expect((await core.push(routingToken, "x")).ok).toBe(true)
  expect(await core.push(routingToken, "x")).toEqual({ ok: false, gone: false })
})
```

- [ ] **Step 2: Run it, verify it fails** — `bun test src/relay/core.test.ts` (signature mismatch / store import gone).

- [ ] **Step 3: Rewrite `src/relay/core.ts`:**

```ts
import type { TokenCodec } from "./token-codec"
import type { RateLimiter } from "./rate-limiter"
import type { PlatformPushAdapter } from "../core/push/native-sender"

export interface RelayCore {
  register(platform: "ios" | "android", pushToken: string): Promise<{ routingToken: string; status: "pending" }>
  push(routingToken: string, ciphertext: string): Promise<{ ok: true } | { ok: false; gone: boolean }>
  unregister(routingToken: string): void
}
export function createRelayCore(o: {
  codec: TokenCodec; apns: PlatformPushAdapter; fcm: PlatformPushAdapter; limiter: RateLimiter
  ttlSeconds: number; ratePerMin: number; globalRatePerMin: number
}): RelayCore {
  const adapterFor = (p: "ios" | "android") => (p === "ios" ? o.apns : o.fcm)
  return {
    async register(platform, pushToken) {
      const routingToken = o.codec.seal({ platform, pushToken, ttlSeconds: o.ttlSeconds })
      await adapterFor(platform).send(pushToken, { ciphertext: JSON.stringify({ kind: "bootstrap", routingToken }) } as any, { silent: true })
      return { routingToken, status: "pending" }
    },
    async push(routingToken, ciphertext) {
      const r = o.codec.open(routingToken)
      if (!r.ok) return { ok: false, gone: true }
      if (!(await o.limiter.allow(routingToken, o.ratePerMin))) return { ok: false, gone: false }
      if (!(await o.limiter.allow("__global__", o.globalRatePerMin))) return { ok: false, gone: false }
      return adapterFor(r.platform).send(r.pushToken, { ciphertext } as any)
    },
    unregister() { /* stateless: nothing to delete; tokens expire via TTL */ },
  }
}
```
(NOTE: confirm `PlatformPushAdapter.send` already accepts the optional `opts?: { silent?: boolean }` third arg — it does, from the silent-bootstrap change. If the mock in `core.test.ts` captures `opts`, keep it.)

- [ ] **Step 4: Update `server.test.ts`'s `handler()` helper** to build the core with a codec + limiter (assertions unchanged — `/register`→202, `/push` unknown token→gone, malformed→400):

```ts
import { randomBytes } from "node:crypto"
import { createTokenCodec } from "./token-codec"
import { createInMemoryRateLimiter } from "./rate-limiter"
// ...
function handler() {
  const a = { send: () => Promise.resolve({ ok: true as const }) }
  const codec = createTokenCodec({ currentKeyId: "k1", keys: new Map([["k1", randomBytes(32)]]) })
  const core = createRelayCore({ codec, apns: a, fcm: a, limiter: createInMemoryRateLimiter(), ttlSeconds: 3600, ratePerMin: 100, globalRatePerMin: 1000 })
  return makeRelayHandler(core)
}
```

- [ ] **Step 5: Rewrite `src/relay/main.ts`** — replace the SQLite store + `MUX_RELAY_DB` with the keyset + limiter selection. Keep the env-int helper + per-IP `/register` limiting (now via the limiter). Sketch (adapt to the existing structure you read):

```ts
import { createTokenCodec, type RelayKeyset } from "./token-codec"
import { createInMemoryRateLimiter, createRedisRateLimiter, type RedisLike } from "./rate-limiter"
// remove: import { Database } from "bun:sqlite"; import { RelayStore } from "./store"

function loadKeyset(env: NodeJS.ProcessEnv): RelayKeyset {
  const multi = env.MUX_RELAY_TOKEN_KEYS // "k1:base64,k2:base64" (first = current)
  const single = env.MUX_RELAY_TOKEN_KEY // single base64 → keyId "k1"
  const keys = new Map<string, Buffer>()
  let currentKeyId = ""
  if (multi) for (const [i, pair] of multi.split(",").entries()) {
    const [id, b64] = pair.split(":"); keys.set(id!, Buffer.from(b64!, "base64")); if (i === 0) currentKeyId = id!
  } else if (single) { currentKeyId = "k1"; keys.set("k1", Buffer.from(single, "base64")) }
  if (!currentKeyId || (keys.get(currentKeyId)?.length ?? 0) !== 32) throw new Error("set MUX_RELAY_TOKEN_KEY(S) to 32-byte base64 key(s)")
  return { currentKeyId, keys }
}

const codec = createTokenCodec(loadKeyset(env))
const inMem = createInMemoryRateLimiter()
const limiter = env.MUX_RELAY_REDIS_URL ? createRedisRateLimiter(makeRedisClient(env.MUX_RELAY_REDIS_URL), inMem) : inMem
const core = createRelayCore({ codec, apns, fcm, limiter, ttlSeconds: intEnv(env.MUX_RELAY_TOKEN_TTL_DAYS, 90) * 86400, ratePerMin: intEnv(env.MUX_RELAY_RATE_PER_MIN, 30), globalRatePerMin: intEnv(env.MUX_RELAY_GLOBAL_PER_MIN, 6000) })
// per-IP /register: await limiter.allow(`reg:${ip}`, intEnv(env.MUX_RELAY_REGISTER_PER_MIN, 10))
```
For `makeRedisClient(url)`: a tiny adapter implementing `RedisLike.incrWithExpiry(key, windowSeconds)` over `Bun.redis` (use it if available in this bun version) or `ioredis` (add the dep only if `Bun.redis` is absent) via a Lua `INCR`+`EXPIRE` or a pipeline. If wiring a real Redis client is non-trivial in this bun version, implement `makeRedisClient` to lazily connect and `incrWithExpiry` = `INCR` then `EXPIRE NX`; keep it ~15 lines. The unit tests cover `RedisRateLimiter` via the injected `RedisLike`, so `main.ts`'s client is integration-glue (not unit-tested) — keep it minimal and correct.

- [ ] **Step 6: Delete the store** — `git rm src/relay/store.ts src/relay/store.test.ts`. Grep for any remaining `RelayStore`/`bun:sqlite`/`MUX_RELAY_DB` references in `src/relay/` and remove them.

- [ ] **Step 7: Verify the drop-in** — run the whole relay suite: `bun test src/relay/` → all green (token-codec, rate-limiter, core, server, apns, fcm). Confirm `src/relay/server.test.ts` assertions are unchanged. Then `rm -rf /tmp/rb && bun build --target=bun src/relay/main.ts --outdir /tmp/rb >/dev/null && echo RELAY_OK`. Also `bun test src/core/push/native-sender.test.ts` (the relay still imports `PlatformPushAdapter` from there) → green.

- [ ] **Step 8: Commit** — `git add -A src/relay && git commit -m "feat(relay): stateless core/main on token codec + RateLimiter; drop SQLite store"`.

---

## Self-Review

**Spec coverage:** sealed token (Task 1) ✓ · keyId rotation + AAD (Task 1) ✓ · TTL/expiry (Task 1 + core ttlSeconds) ✓ · RateLimiter in-memory + Redis + fallback (Task 2) ✓ · core seal/open/no-op-unregister + silent bootstrap (Task 3) ✓ · main keyset + limiter selection, drop sqlite/`MUX_RELAY_DB` (Task 3) ✓ · drop-in: server.test assertions unchanged (Task 3 Step 4/7) ✓ · errors invalid/expired→gone, rate-limit→not-gone (Task 3 core) ✓. Migration = delete the DB (Task 3 Step 6); no data move. Redis client glue flagged as integration (Task 3 Step 5).

**Placeholder scan:** the only non-literal is `makeRedisClient` in `main.ts` (real Redis client glue) — bounded to ~15 lines with the exact `INCR`+`EXPIRE` semantics, injected-mock-tested at the `RedisLike` boundary, explicitly an integration seam (not unit-tested), per the spec's "Redis against a mock client." Everything else is complete code.

**Type consistency:** `TokenCodec.open → OpenResult` (`{ok:true,platform,pushToken}` | `{ok:false,reason}`) consumed by `core.push` (checks `r.ok`). `RateLimiter.allow(key, limitPerMin)` used by core (per-token + `__global__`) and main (per-IP `reg:<ip>`). `RelayKeyset {currentKeyId, keys:Map}` produced by `loadKeyset`, consumed by `createTokenCodec`. `PlatformPushAdapter.send(token, payload, opts?)` (silent arg already exists) used by core's bootstrap.
