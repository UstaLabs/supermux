# Stateless Push Relay — Design (2026-06-23)

## Goal

Re-architect the push **relay** (from the native-push project) to hold **zero state at rest** — no
database — so it can (1) **scale horizontally** behind a load balancer, (2) run with **zero DB ops**
(nothing to back up / migrate / babysit), and (3) hold **nothing** that could leak or be subpoenaed.
It is a **drop-in**: the external `/register` + `/push` API and the token's opacity stay identical, so
the broker and the iOS/Android clients are **untouched**.

## Context (current relay)

`src/relay/` currently maps `routingToken → {platform, pushToken}` in a **SQLite** table
(`relay_routes`, via `RelayStore`) plus in-memory rate-limit counters. `core.register` mints a random
token, stores the row, and bootstrap-pushes the token to the device; `core.push` looks the row up and
forwards. The SQLite file is the only persistent state.

## Decisions (and why)

- **Self-describing sealed token** (over a signed-but-readable token, and over keeping a DB). The
  routing token becomes an **AEAD-encrypted blob** carrying `{platform, pushToken, exp}`, sealed with a
  relay-held secret key. The relay recovers the push token by **decrypting the token** — no lookup.
  **Encrypted, not merely signed**, so the underlying push token stays confidential even from the
  broker that holds the token and from anyone who intercepts it.
- **Shared key = shared config, not shared state.** Every relay instance loads the same secret key
  (env/secret, alongside the APNs/FCM creds). Any instance opens any token → horizontal scaling with
  no shared store. Config distribution, not runtime state.
- **Pluggable `RateLimiter`** — `InMemoryRateLimiter` by default (per-instance; with N instances the
  global ceiling is ~N× looser, fine as a safety net), `RedisRateLimiter` only if `MUX_RELAY_REDIS_URL`
  is set (tight global limits at scale). Redis is the **only** optional state, and **only** for
  rate-limiting — routing stays 100% stateless. A Redis error falls back to in-memory (never block a
  real push on a limiter outage).
- **Revocation by TTL + key rotation** (no per-token revoke without a DB). Tokens carry an `exp`; the
  app re-registers on a self-tracked cadence; key rotation revokes en masse.
- **Drop-in** — same endpoints, same opaque-token contract → no broker or client changes.

## Token format

`r1.<keyId>.<payload>`
- **`r1`** — version tag (lets the format/crypto evolve).
- **`<keyId>`** — short id (4–8 base64url chars) of the key used to seal, so the relay knows which key
  to open with during rotation.
- **`<payload>`** — `base64url(iv ‖ AES-256-GCM(key, plaintext, aad = "r1.<keyId>"))`; 12-byte random
  iv, 16-byte tag appended. `plaintext` = compact JSON `{p:"ios"|"android", t:<pushToken>,
  e:<expEpochSeconds>}`. Binding `version‖keyId` as **AAD** prevents swapping them.
- Crypto via Node `crypto` (AES-256-GCM) — already in use, **no new dependency**. (Branca or PASETO
  v4.local are acceptable vetted off-the-shelf alternatives of the same shape, if preferred.)
- Size ~120–250 bytes base64url — fine for the bootstrap push payload and for the broker to hold.

## Components (all changes are relay-internal)

- **`src/relay/token-codec.ts` (new) — `TokenCodec`**: `seal({platform, pushToken, ttlSeconds}) →
  token` and `open(token) → { ok: true; platform; pushToken } | { ok: false; reason: "expired" |
  "invalid" }`. Holds a keyset `{ currentKeyId, keys: Map<keyId, key> }`. `seal` uses the current key;
  `open` parses the keyId, looks up the key, decrypts (AAD-checked), verifies `exp`. **Replaces
  `RelayStore`.**
- **`src/relay/rate-limiter.ts` (new) — `RateLimiter` interface** (`allow(key: string):
  Promise<boolean>`, sliding window per key) + **`InMemoryRateLimiter`** (the current `allowed()` logic
  extracted) + **`RedisRateLimiter`** (sliding-window via Redis, used iff configured; on a Redis error,
  delegate to an in-memory fallback).
- **`src/relay/core.ts` (modified)**: `register` → `codec.seal(...)` then the **silent** bootstrap
  push; `push` → `codec.open(token)` → on `{ok:false}` return `{ok:false, gone:true}`, else
  rate-limit-check (per-token + global) then forward; `unregister` → **no-op**.
- **`src/relay/main.ts` (modified)**: load the keyset from env (`MUX_RELAY_TOKEN_KEYS` =
  `keyId:base64key[,keyId:base64key…]`, first entry = current; or a single `MUX_RELAY_TOKEN_KEY`); pick
  `Redis` vs `InMemory` limiter from `MUX_RELAY_REDIS_URL`; the per-IP `/register` limiter routes
  through the same `RateLimiter`.
- **Removed**: `src/relay/store.ts` (`RelayStore`) + `store.test.ts` + the `bun:sqlite` use + the DB
  file (`MUX_RELAY_DB` env retired).

## Data flow (unchanged externally)

1. App → `POST {relay}/register {platform, pushToken}` → `codec.seal` → token → **silent bootstrap
   push** delivers it to the device (proof-of-possession unchanged) → `202`.
2. App → `POST {broker}/push/device {platform, routingToken, pubkey}` (unchanged).
3. Broker → `POST {relay}/push {routingToken, ciphertext}` → `codec.open` → rate-limit → forward to
   APNs/FCM. No DB.

The token is opaque to the app + broker throughout — now **cryptographically**, not just by DB
indirection.

## Revocation, TTL, rotation

- **TTL** — `exp` in the token (default **90 days**). On `open`, expired → `{ok:false,
  reason:"expired"}` → `{gone:true}` → broker prunes → device re-registers next launch.
- **Re-register cadence** — the app tracks its own "last registered" timestamp and re-registers when
  older than **30 days** (it can't read the encrypted `exp`), keeping tokens well within TTL.
- **Key rotation** — add a new key as `currentKeyId`; keep prior keys in the keyset as **decrypt-only**
  for a window ≥ TTL (or until the fleet re-registers), then drop them. Dropping **all** prior keys =
  revoke everything (forces a re-register wave). Losing the keyset entirely → same re-register wave;
  nothing to restore (no backup needed).

## Error handling

- `open` invalid / tampered / wrong-key / expired → `{ok:false, gone:true}` (broker prunes; device
  re-registers).
- APNs/FCM `gone` → `{ok:false, gone:true}` (broker prunes; the relay can't pre-forget — a few wasted
  calls until the broker drops the row, acceptable).
- Rate-limit exceeded → `429` / `{ok:false, gone:false}`.
- Redis configured but erroring → fall back to the in-memory limiter for that call (log once); never
  block delivery on a limiter outage.

## Migration from the DB relay

- The token format changes (sealed vs random+row), so existing routing tokens are invalid under the
  new relay. On cutover, devices re-register on next launch (the apps re-register on a cadence anyway).
  **No data migration** — the SQLite file is simply abandoned/deleted.
- Strictly **relay-internal**: swap `RelayStore`→`TokenCodec`, extract `RateLimiter`, drop `bun:sqlite`,
  add the keyset config. Broker + apps untouched. The external API + its tests are unchanged.

## Testing

- **`token-codec.test.ts`**: seal→open round-trip; expired token → `expired`; tampered ciphertext/iv →
  `invalid`; wrong/unknown keyId → `invalid`; rotation (a token sealed with the old key still opens
  while that key is in the keyset, fails once dropped); AAD binding (editing the `keyId` in the string
  → `invalid`). Pure unit, no DB/network.
- **`rate-limiter.test.ts`**: `InMemory` sliding window (N allowed then blocked); `Redis` against a
  fake/mock client including the error→in-memory-fallback path.
- **`core.test.ts` (updated)**: `register` seals + silent-bootstrap-pushes; `push` opens + forwards;
  invalid/expired token → `{gone:true}`; rate-limit blocks.
- **`server.test.ts` (unchanged)**: the external `/register` (202) · `/push` · `/unregister` behavior
  stays identical — these tests pass **without modification**, proving the drop-in.

## Non-goals

- Changing the broker or the iOS/Android clients (the entire point is that they don't change).
- Per-token revocation lists (incompatible with statelessness; TTL + rotation cover it).
- A shared store for **routing** (defeats the purpose; only **rate-limiting** may optionally use Redis).
- Re-keying live tokens in place (devices re-register instead).

## Open questions (resolve in the plan)

- Token inner encoding JSON vs MessagePack — lean **JSON** (tokens are tiny either way).
- Confirm default TTL (90d) and re-register cadence (30d).
- Redis client for bun (`Bun.redis` if present, else `ioredis`) + the sliding-window algorithm
  (fixed-window vs sliding-log vs token-bucket) — lean a simple **sliding-window counter**.
- Fully replace the SQLite relay, or keep it as an option? **Recommend full replace** — the stateless
  relay strictly dominates for the stated goals; the DB version offered only per-token revoke +
  proactive pruning, both adequately covered by TTL/rotation + broker-side pruning.
