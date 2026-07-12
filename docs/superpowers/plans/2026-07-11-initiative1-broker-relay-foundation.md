# Initiative 1 · Plan 1 — Broker & Relay Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the broker a stable public identity, a real one-time pairing claim, and reachability from anywhere through an frp-based relay — dogfooded with the live server as host #1 — without changing how the broker serves requests.

**Architecture:** Three layers, all server-side. (1) A **host identity** module: an Ed25519 keypair generated once, a derived `hostId`, and a `GET /host` endpoint. (2) A **pairing claim** upgrade: replace today's trust-on-first-connect `/pair/claim` with a hashed, single-use, expiring `claimSecret` that works even after devices exist. (3) A **relay data plane** using self-operated `frps` + a bundled `frpc` sidecar proxying to `localhost:9898`, with a supermux identity sidecar (an frps auth plugin + a host-key lease endpoint) that binds each host to exactly its own `h-<hostId>` subdomain. A 1-day spike (Task 9) gates adoption; if it fails, the rev-3 custom protocol (git `8e63513`) is the fallback and Tasks 10–13 are replaced.

**Tech Stack:** TypeScript on Bun; `bun:test`; Node `crypto` (Ed25519 via `generateKeyPairSync`/`sign`/`verify`); the existing `atomic-json` + `DeviceStore` patterns; frp (`frps`/`frpc`, pinned); Caddy for wildcard TLS. This plan is broker + relay only — clients are Plan 2, desktop hosting is Plan 3.

**Spec:** `docs/superpowers/specs/2026-07-11-desktop-host-multihost-design.md` (§3 host model, §3.4 pairing, §4 frp relay, §8 phase-0 seam). This plan covers rollout steps 1–3.

**Repo note:** Fresh worktrees have empty `node_modules` → run `bun install` first (see `~/.mux` conventions). Run all tests with `TMPDIR=/home/ahmet/.cache/x` to avoid the tmpfs-quota "exit 1, no output" trap.

---

## File Structure

**Create:**
- `src/core/host-identity/keypair.ts` — generate/load the Ed25519 host keypair; derive `hostId`.
- `src/core/host-identity/keypair.test.ts`
- `src/core/host-identity/index.ts` — re-exports.
- `src/channels/web/host-route.ts` — pure builder for the `GET /host` response body.
- `src/channels/web/host-route.test.ts`
- `src/channels/web/pair-claim.ts` — the claim-secret store (mint/verify/consume) + response builder.
- `src/channels/web/pair-claim.test.ts`
- `src/core/relay/provider.ts` — the `RelayProvider` interface + `NullRelayProvider`.
- `src/core/relay/frp-provider.ts` — frpc sidecar supervisor + lease acquisition.
- `src/core/relay/frp-provider.test.ts`
- `src/core/relay/lease.ts` — host-key lease mint/verify (shared by the endpoint and the frps plugin).
- `src/core/relay/lease.test.ts`
- `src/core/relay/auth-plugin.ts` — the frps auth-plugin HTTP handler (Login/NewProxy validation).
- `src/core/relay/auth-plugin.test.ts`
- `docs/relay/SPIKE.md` — the Task 9 spike protocol + gate results.
- `docs/relay/frps.ini.example`, `docs/relay/Caddyfile.example` — relay-box config templates.

**Modify:**
- `src/shared/paths.ts` — add `HOST_KEY_FILE`.
- `src/shared/preflight.ts` — tmux no longer fatal (phase-0 seam).
- `src/shared/preflight.test.ts` — update expectations.
- `src/channels/web/index.ts` — register `GET /host`; swap the claim route; add `/pair/claim` and `/host` to `API_PREFIXES`; thread new opts.
- `src/channels/web/index.test.ts` (or a new focused test file) — route wiring.
- `src/main.ts` — construct host identity at boot; start the `RelayProvider`; pass `hostId`/relay opts into the web channel.

---

## Task 1: `HOST_KEY_FILE` path

**Files:**
- Modify: `src/shared/paths.ts:14`
- Test: `src/shared/paths.test.ts` (create if absent)

- [ ] **Step 1: Write the failing test**

Create/append `src/shared/paths.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { HOST_KEY_FILE, STATE_DIR } from "./paths"

test("HOST_KEY_FILE lives under STATE_DIR", () => {
  expect(HOST_KEY_FILE).toBe(`${STATE_DIR}/host-key`)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/shared/paths.test.ts`
Expected: FAIL — `HOST_KEY_FILE` is not exported.

- [ ] **Step 3: Add the export**

In `src/shared/paths.ts`, after the `DEVICES_FILE` line (14):

```typescript
export const HOST_KEY_FILE = join(STATE_DIR, "host-key")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/shared/paths.test.ts`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add src/shared/paths.ts src/shared/paths.test.ts
git commit -m "feat(paths): add HOST_KEY_FILE for the host identity keypair"
```

---

## Task 2: Host keypair — generate, persist, derive hostId

**Files:**
- Create: `src/core/host-identity/keypair.ts`, `src/core/host-identity/keypair.test.ts`

`hostId` = lowercase base32 (RFC 4648, no padding) of the first 16 bytes (128 bits) of SHA-256(raw 32-byte Ed25519 public key). Node has no built-in base32, so include a tiny encoder in this file.

- [ ] **Step 1: Write the failing test**

Create `src/core/host-identity/keypair.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { mkdtempSync, existsSync, statSync, readFileSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { loadOrCreateHostKey, hostIdFromPublicKey } from "./keypair"

function freshKeyPath(): string {
  return join(mkdtempSync(join(tmpdir(), "mux-hostkey-")), "host-key")
}

test("hostId is 26 lowercase base32 chars, deterministic from the public key", () => {
  const p = freshKeyPath()
  const a = loadOrCreateHostKey(p)
  expect(a.hostId).toMatch(/^[a-z2-7]{26}$/)
  expect(hostIdFromPublicKey(a.publicKeyRaw)).toBe(a.hostId)
})

test("second load returns the SAME identity (persisted, not regenerated)", () => {
  const p = freshKeyPath()
  const a = loadOrCreateHostKey(p)
  const b = loadOrCreateHostKey(p)
  expect(b.hostId).toBe(a.hostId)
  expect(b.publicKeyRaw.equals(a.publicKeyRaw)).toBe(true)
})

test("key file is created 0600", () => {
  const p = freshKeyPath()
  loadOrCreateHostKey(p)
  expect(existsSync(p)).toBe(true)
  expect(statSync(p).mode & 0o777).toBe(0o600)
})

test("sign/verify round-trips; a tampered message fails", () => {
  const p = freshKeyPath()
  const id = loadOrCreateHostKey(p)
  const msg = Buffer.from("challenge-nonce-123")
  const sig = id.sign(msg)
  expect(id.verify(msg, sig)).toBe(true)
  expect(id.verify(Buffer.from("challenge-nonce-124"), sig)).toBe(false)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/host-identity/keypair.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `src/core/host-identity/keypair.ts`:

```typescript
import {
  generateKeyPairSync, createHash, sign as edSign, verify as edVerify,
  createPublicKey, createPrivateKey, type KeyObject,
} from "crypto"
import { readFileSync, writeFileSync, existsSync, mkdirSync, chmodSync } from "fs"
import { dirname } from "path"

const B32 = "abcdefghijklmnopqrstuvwxyz234567" // RFC 4648 lower, no padding

function base32(buf: Buffer): string {
  let bits = 0, value = 0, out = ""
  for (const byte of buf) {
    value = (value << 8) | byte; bits += 8
    while (bits >= 5) { out += B32[(value >>> (bits - 5)) & 31]; bits -= 5 }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31]
  return out
}

/** Raw 32-byte Ed25519 public key → 26-char base32 hostId (128-bit hash prefix). */
export function hostIdFromPublicKey(publicKeyRaw: Buffer): string {
  const digest = createHash("sha256").update(publicKeyRaw).digest()
  return base32(digest.subarray(0, 16))
}

export interface HostIdentity {
  hostId: string
  publicKeyRaw: Buffer
  sign(message: Buffer): Buffer
  verify(message: Buffer, signature: Buffer): boolean
}

function rawPublicKey(pub: KeyObject): Buffer {
  // DER SPKI for Ed25519 is a fixed 44-byte prefix + 32-byte key.
  const der = pub.export({ type: "spki", format: "der" })
  return Buffer.from(der.subarray(der.length - 32))
}

function toIdentity(priv: KeyObject, pub: KeyObject): HostIdentity {
  const publicKeyRaw = rawPublicKey(pub)
  return {
    hostId: hostIdFromPublicKey(publicKeyRaw),
    publicKeyRaw,
    sign: (message) => edSign(null, message, priv),
    verify: (message, signature) => edVerify(null, message, pub, signature),
  }
}

export function loadOrCreateHostKey(path: string): HostIdentity {
  if (existsSync(path)) {
    const pem = readFileSync(path, "utf8")
    const priv = createPrivateKey(pem)
    return toIdentity(priv, createPublicKey(priv))
  }
  const { privateKey, publicKey } = generateKeyPairSync("ed25519")
  mkdirSync(dirname(path), { recursive: true, mode: 0o700 })
  writeFileSync(path, privateKey.export({ type: "pkcs8", format: "pem" }), { mode: 0o600 })
  chmodSync(path, 0o600) // umask-proof
  return toIdentity(privateKey, publicKey)
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/host-identity/keypair.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Create the barrel + commit**

Create `src/core/host-identity/index.ts`:

```typescript
export { loadOrCreateHostKey, hostIdFromPublicKey, type HostIdentity } from "./keypair"
```

```bash
git add src/core/host-identity/
git commit -m "feat(host-identity): Ed25519 host keypair + derived hostId"
```

---

## Task 3: `GET /host` response builder

**Files:**
- Create: `src/channels/web/host-route.ts`, `src/channels/web/host-route.test.ts`

The endpoint returns identity fields publicly and adds `platform`/`version` only when authenticated (spec §3.3). This task builds the pure body function; Task 6 wires it into the router.

- [ ] **Step 1: Write the failing test**

Create `src/channels/web/host-route.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { buildHostBody } from "./host-route"

const info = { hostId: "abc23def", name: "Ahmet-MBP", platform: "macos", version: "0.11.0", protocolVersion: 1 }

test("unauthenticated body is identity-only", () => {
  expect(buildHostBody(info, false)).toEqual({ hostId: "abc23def", name: "Ahmet-MBP", protocolVersion: 1 })
})

test("authenticated body adds platform + version", () => {
  expect(buildHostBody(info, true)).toEqual({
    hostId: "abc23def", name: "Ahmet-MBP", protocolVersion: 1, platform: "macos", version: "0.11.0",
  })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/host-route.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `src/channels/web/host-route.ts`:

```typescript
export interface HostInfo {
  hostId: string
  name: string
  platform: string
  version: string
  protocolVersion: number
}

export interface HostBody {
  hostId: string
  name: string
  protocolVersion: number
  platform?: string
  version?: string
}

/** Public callers get identity only; authed callers also get platform + version. */
export function buildHostBody(info: HostInfo, authed: boolean): HostBody {
  const base: HostBody = { hostId: info.hostId, name: info.name, protocolVersion: info.protocolVersion }
  if (authed) { base.platform = info.platform; base.version = info.version }
  return base
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/host-route.test.ts`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/host-route.ts src/channels/web/host-route.test.ts
git commit -m "feat(web): GET /host response builder (public identity / authed detail)"
```

---

## Task 4: Pairing claim store (hashed, single-use, expiring)

**Files:**
- Create: `src/channels/web/pair-claim.ts`, `src/channels/web/pair-claim.test.ts`

Mirrors `DeviceStore`'s hashing discipline but for short-lived one-time secrets held in memory (claims don't need to survive a restart — the wizard/app just mints a new one). `consume` verifies + deletes atomically and rejects expired secrets.

- [ ] **Step 1: Write the failing test**

Create `src/channels/web/pair-claim.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { ClaimStore } from "./pair-claim"

test("mint returns a 128-bit-plus base64url secret; consume works exactly once", () => {
  let now = 1000
  const store = new ClaimStore({ ttlMs: 5000, clock: () => now })
  const secret = store.mint()
  expect(secret.length).toBeGreaterThanOrEqual(22) // 16 bytes base64url
  expect(store.consume(secret)).toBe(true)
  expect(store.consume(secret)).toBe(false) // already consumed
})

test("expired secret is rejected and swept", () => {
  let now = 1000
  const store = new ClaimStore({ ttlMs: 5000, clock: () => now })
  const secret = store.mint()
  now = 6001
  expect(store.consume(secret)).toBe(false)
})

test("unknown secret is rejected", () => {
  const store = new ClaimStore({ ttlMs: 5000, clock: () => 0 })
  expect(store.consume("nope")).toBe(false)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/pair-claim.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `src/channels/web/pair-claim.ts`:

```typescript
import { createHash, randomBytes } from "crypto"

function sha256(s: string): string { return createHash("sha256").update(s).digest("hex") }

export interface ClaimStoreOpts {
  ttlMs?: number
  clock?: () => number
}

/** In-memory one-time pairing secrets: hashed at rest, single-use, expiring. */
export class ClaimStore {
  private readonly ttlMs: number
  private readonly clock: () => number
  private readonly entries = new Map<string, number>() // hash → expiresAt

  constructor(opts: ClaimStoreOpts = {}) {
    this.ttlMs = opts.ttlMs ?? 10 * 60 * 1000
    this.clock = opts.clock ?? (() => Date.now())
  }

  mint(): string {
    const secret = randomBytes(16).toString("base64url")
    this.entries.set(sha256(secret), this.clock() + this.ttlMs)
    return secret
  }

  /** Verify + delete atomically. Returns true only for a live, unused secret. */
  consume(secret: string): boolean {
    const key = sha256(secret)
    const expiresAt = this.entries.get(key)
    if (expiresAt === undefined) return false
    this.entries.delete(key)
    return this.clock() <= expiresAt
  }

  sweep(): void {
    const now = this.clock()
    for (const [k, exp] of this.entries) if (now > exp) this.entries.delete(k)
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/pair-claim.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/channels/web/pair-claim.ts src/channels/web/pair-claim.test.ts
git commit -m "feat(web): one-time pairing claim store (hashed, single-use, expiring)"
```

---

## Task 5: Phase-0 seam — tmux no longer broker-fatal

**Files:**
- Modify: `src/shared/preflight.ts:8-28`
- Modify: `src/shared/preflight.test.ts` (create if absent)

tmux absence must disable tmux-backed Claude + persistent terminals with a warning, not kill the broker — codex/cursor/opencode hosts run without it (spec §8).

- [ ] **Step 1: Write the failing test**

Create/replace `src/shared/preflight.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { checkPreflight } from "./preflight"

const has = (present: string[]) => (bin: string) => present.includes(bin)

test("missing tmux is a WARNING, not fatal, when an agent CLI exists", () => {
  const r = checkPreflight(has(["codex"]))
  expect(r.fatal).toEqual([])
  expect(r.warnings.some((w) => w.toLowerCase().includes("tmux"))).toBe(true)
})

test("no agent CLI at all is still fatal", () => {
  const r = checkPreflight(has([]))
  expect(r.fatal.length).toBeGreaterThan(0)
})

test("tmux present produces no tmux warning", () => {
  const r = checkPreflight(has(["tmux", "claude"]))
  expect(r.warnings.some((w) => w.toLowerCase().includes("tmux"))).toBe(false)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/shared/preflight.test.ts`
Expected: FAIL — first test sees tmux in `fatal`.

- [ ] **Step 3: Edit the implementation**

In `src/shared/preflight.ts`, replace the tmux block (lines 15-17) with:

```typescript
  if (!has("tmux")) {
    warnings.push("tmux not found on PATH — Claude sessions and persistent terminals are disabled on this host; codex/cursor/opencode still work. Install tmux to enable them.")
  }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/shared/preflight.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/shared/preflight.ts src/shared/preflight.test.ts
git commit -m "feat(preflight): tmux is non-fatal — disables Claude+terminals, not the broker"
```

---

## Task 6: Wire `GET /host` + new claim route into the web channel

**Files:**
- Modify: `src/channels/web/index.ts` (opts interface ~122; `API_PREFIXES` line 66; claim route ~1417; add `/host` route near `/me` ~1409)
- Test: `src/channels/web/host-route-wiring.test.ts` (new)

Add three opts to `WebChannelOpts`: `getHostInfo?: () => HostInfo`, `claimStore?: ClaimStore`, `mintDeviceToken?: (name: string) => { token: string; name: string }` (defaults to `this.store.mint`). Keep `hostId` out of `relayUrl` (that opt already exists for a different purpose — line 241).

- [ ] **Step 1: Write the failing wiring test**

Create `src/channels/web/host-route-wiring.test.ts`:

```typescript
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { ClaimStore } from "./pair-claim"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }

function makeChannel(opts: Partial<WebChannelOpts>): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-host-wiring-"))
  const devicesFile = join(dir, "devices.json")
  const full: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    ...opts,
  }
  return { channel: new WebChannel(full), devicesFile }
}

test("GET /host is public and identity-only without auth", async () => {
  const made = makeChannel({
    getHostInfo: () => ({ hostId: "h123", name: "box", platform: "linux", version: "0.11.0", protocolVersion: 1 }),
  })
  channel = made.channel; await channel.start()
  const res = await fetch(`${base()}/host`)
  expect(res.status).toBe(200)
  expect(await res.json()).toEqual({ hostId: "h123", name: "box", protocolVersion: 1 })
})

test("POST /pair/claim mints a device token for a valid one-time secret, even with devices present", async () => {
  const claimStore = new ClaimStore({ clock: () => 0 })
  const secret = claimStore.mint()
  const made = makeChannel({
    claimStore,
    getAppConfig: () => ({ onboarded: true } as any), // configured host — old flow would 403
    getHostInfo: () => ({ hostId: "h123", name: "box", platform: "linux", version: "0.11.0", protocolVersion: 1 }),
  })
  channel = made.channel; await channel.start()
  new DeviceStore(made.devicesFile).mint("existing-device") // devices already exist

  const res = await fetch(`${base()}/pair/claim`, {
    method: "POST", headers: { "content-type": "application/json" },
    body: JSON.stringify({ claimSecret: secret, deviceName: "phone" }),
  })
  expect(res.status).toBe(200)
  const body = await res.json()
  expect(body.host).toEqual({ hostId: "h123", name: "box", platform: "linux", version: "0.11.0" })
  expect(typeof body.deviceToken).toBe("string")
})

test("POST /pair/claim rejects a reused secret", async () => {
  const claimStore = new ClaimStore({ clock: () => 0 })
  const secret = claimStore.mint()
  const made = makeChannel({ claimStore, getHostInfo: () => ({ hostId: "h", name: "b", platform: "linux", version: "0", protocolVersion: 1 }) })
  channel = made.channel; await channel.start()
  const once = { method: "POST", headers: { "content-type": "application/json" }, body: JSON.stringify({ claimSecret: secret, deviceName: "p" }) }
  expect((await fetch(`${base()}/pair/claim`, once)).status).toBe(200)
  expect((await fetch(`${base()}/pair/claim`, once)).status).toBe(401)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/host-route-wiring.test.ts`
Expected: FAIL — `/host` 404s (falls to static gate) and `/pair/claim` uses old semantics.

- [ ] **Step 3: Edit `WebChannelOpts` + constructor**

In `src/channels/web/index.ts`, add to the `WebChannelOpts` interface (near line 241, beside `relayUrl?`):

```typescript
  getHostInfo?: () => import("./host-route").HostInfo
  claimStore?: import("./pair-claim").ClaimStore
  mintDeviceToken?: (name: string) => { token: string; name: string }
```

Add matching private fields + assignments in the constructor (mirror how `relayUrl` is stored at line 260/278):

```typescript
  private readonly getHostInfo?: () => import("./host-route").HostInfo
  private readonly claimStore?: import("./pair-claim").ClaimStore
```
```typescript
    this.getHostInfo = opts.getHostInfo
    this.claimStore = opts.claimStore
```

Add the imports at the top:

```typescript
import { buildHostBody } from "./host-route"
```

- [ ] **Step 4: Add `/host` + `/pair/claim` to `API_PREFIXES`**

Edit line 66 — add `"/host"` (`/pair/claim` is already covered by the `"/pair"` prefix, but add `"/pair/claim"` explicitly for clarity is NOT needed; `/pair` matches `/pair/claim` via the `startsWith(p + "/")` rule). Add only `"/host"`:

```typescript
const API_PREFIXES = ["/api", "/sessions", ... , "/forge", "/host"]
```

> **Gotcha (spec §ref, live gotchas):** a GET route not in `API_PREFIXES` is shadowed by the SPA `index.html`. This test boots WITH no static dir so it wouldn't catch a missing prefix — but prod would silently break. `/host` MUST be in the list.

- [ ] **Step 5: Add the `/host` route**

In the request handler, right before the `GET /me` block (~line 1409):

```typescript
    if (method === "GET" && path === "/host") {
      const info = this.getHostInfo?.()
      if (!info) return this.json({ error: "host identity unavailable" }, 503)
      const authed = this.requireAuth(req).ok
      return this.json(buildHostBody(info, authed))
    }
```

- [ ] **Step 6: Replace the `/pair/claim` route**

Replace the existing `POST /pair/claim` block (~1417-1434) with the claim-secret flow:

```typescript
    if (method === "POST" && path === "/pair/claim") {
      const info = this.getHostInfo?.()
      const body = (await req.json().catch(() => ({}))) as Record<string, unknown>
      const secret = typeof body.claimSecret === "string" ? body.claimSecret : ""
      // Fallback: brand-new broker with no claimStore + no devices keeps the
      // legacy trust-on-first-connect path so today's onboarding still works.
      if (!this.claimStore) {
        const onboarded = this.opts.getAppConfig?.()?.onboarded ?? false
        if (this.store.list().length > 0 || onboarded) return this.json({ error: "already set up" }, 403)
        const requested = ((body.deviceName as string | undefined)?.trim()) || "setup"
        const minted = (this.mintDeviceToken ?? ((n) => this.store.mint(n)))(requested)
        this.store.touch(minted.token)
        return new Response(JSON.stringify({ paired: true, name: minted.name }), {
          status: 200,
          headers: { "content-type": "application/json", "set-cookie": buildAuthCookie(minted.token, { publicUrl: this.opts.publicUrl, proxyBaseDomain: this.opts.proxyBaseDomain }) },
        })
      }
      if (!secret || !this.claimStore.consume(secret)) return new Response("unauthorized", { status: 401 })
      const name = ((body.deviceName as string | undefined)?.trim()) || "device"
      const minted = (this.mintDeviceToken ?? ((n) => this.store.mint(n)))(name)
      this.store.touch(minted.token)
      const hostForBody = info ? { hostId: info.hostId, name: info.name, platform: info.platform, version: info.version } : undefined
      return this.json({ host: hostForBody, deviceToken: minted.token, name: minted.name })
    }
```

Add `mintDeviceToken` field + assignment alongside the others (constructor).

- [ ] **Step 7: Run the wiring test**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/host-route-wiring.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 8: Guard against regression — full web suite**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/`
Expected: PASS (existing pairing/`/me` tests still green; the legacy-fallback branch preserves old behavior).

- [ ] **Step 9: Commit**

```bash
git add src/channels/web/index.ts src/channels/web/host-route-wiring.test.ts
git commit -m "feat(web): wire GET /host + claim-secret pairing; keep legacy TOFU fallback"
```

---

## Task 7: Construct host identity + host info at boot

**Files:**
- Modify: `src/main.ts` (web-channel construction site; find with grep in Step 1)

- [ ] **Step 1: Locate the web-channel construction**

Run: `grep -n "new WebChannel\|getAppConfig:\|relayUrl:" src/main.ts`
Expected: the `new WebChannel({ ... })` opts object.

- [ ] **Step 2: Add identity construction above it**

Near the other boot setup, add:

```typescript
import { loadOrCreateHostKey } from "./core/host-identity"
import { HOST_KEY_FILE } from "./shared/paths"
import { ClaimStore } from "./channels/web/pair-claim"
import { hostname } from "os"
import { version as brokerVersion } from "./shared/version" // if absent, use the existing version source
```
```typescript
const hostIdentity = loadOrCreateHostKey(HOST_KEY_FILE)
const claimStore = new ClaimStore()
setInterval(() => claimStore.sweep(), 60_000).unref()
```

> If `src/shared/version` doesn't exist, grep for how the broker already reports its version (e.g. `package.json` import or a constant) and reuse that — do NOT invent a new source.

- [ ] **Step 3: Pass the new opts to WebChannel**

Inside the `new WebChannel({ ... })` opts, add:

```typescript
      getHostInfo: () => ({
        hostId: hostIdentity.hostId,
        name: hostname(),
        platform: process.platform === "darwin" ? "macos" : process.platform === "win32" ? "windows" : "linux",
        version: brokerVersion,
        protocolVersion: 1,
      }),
      claimStore,
```

- [ ] **Step 4: Typecheck**

Run: `TMPDIR=/home/ahmet/.cache/x bunx tsc --noEmit`
Expected: no NEW errors (repo has ~3 known pre-existing; compare against a clean `git stash` baseline if unsure).

- [ ] **Step 5: Boot smoke test**

Run: `MUX_STATE_DIR=/home/ahmet/.cache/x/mux-boot TMPDIR=/home/ahmet/.cache/x bun src/main.ts &` then after 3s: `curl -s localhost:9898/host` (adjust port if occupied); expect `{"hostId":"...","name":"...","protocolVersion":1}`. Kill the boot process afterward. Do NOT touch the live broker.

- [ ] **Step 6: Commit**

```bash
git add src/main.ts
git commit -m "feat(broker): construct host identity + claim store at boot; serve GET /host"
```

---

## Task 8: `RelayProvider` interface + null provider

**Files:**
- Create: `src/core/relay/provider.ts`, `src/core/relay/provider.test.ts`

The boundary that keeps frp swappable (spec §4). Clients never see frp; the broker only sees `start/stop/status`.

- [ ] **Step 1: Write the failing test**

Create `src/core/relay/provider.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { NullRelayProvider } from "./provider"

test("null provider reports disabled and never throws", async () => {
  const p = new NullRelayProvider()
  expect(p.status().state).toBe("disabled")
  await p.start()
  expect(p.status().state).toBe("disabled")
  await p.stop()
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/provider.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `src/core/relay/provider.ts`:

```typescript
export type RelayState = "disabled" | "connecting" | "online" | "error"

export interface RelayStatus {
  state: RelayState
  relayUrl?: string  // https://h-<hostId>.relay.supermux.dev when online
  detail?: string
}

/** The swappable relay data plane boundary. frp is one implementation. */
export interface RelayProvider {
  start(): Promise<void>
  stop(): Promise<void>
  status(): RelayStatus
}

/** Relay off (LAN/direct only). Default until frp is configured + spike-passed. */
export class NullRelayProvider implements RelayProvider {
  async start(): Promise<void> {}
  async stop(): Promise<void> {}
  status(): RelayStatus { return { state: "disabled" } }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/provider.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/relay/provider.ts src/core/relay/provider.test.ts
git commit -m "feat(relay): RelayProvider boundary + NullRelayProvider default"
```

---

## Task 9: The frp spike (GATE — 1 day, manual, blocks Tasks 10–13)

**Files:**
- Create: `docs/relay/SPIKE.md`, `docs/relay/frps.ini.example`, `docs/relay/Caddyfile.example`

This is a spike, not TDD — it answers "does frp meet the four gates?" empirically before we build the provider around it. Do it on a scratch VM (or this box with throwaway ports), NEVER against the live relay/broker.

- [ ] **Step 1: Stand up the relay side**

Create `docs/relay/frps.ini.example`:

```ini
[common]
bind_port = 7000
vhost_http_port = 8080
subdomain_host = relay.supermux.dev
# Auth plugin (Task 11) validates every Login/NewProxy:
[plugin.supermux-auth]
addr = 127.0.0.1:7200
path = /handler
ops = Login,NewProxy,Ping
```

Create `docs/relay/Caddyfile.example`:

```
*.relay.supermux.dev {
  tls { on_demand }
  reverse_proxy 127.0.0.1:8080
}
```

Run `frps -c frps.ini` (pin a version, e.g. v0.61.x — record the exact version in SPIKE.md).

- [ ] **Step 2: Stand up a host side**

Run a throwaway broker (or a plain `python3 -m http.server` + a WS echo server) on `localhost:9898`, and `frpc` with an HTTP proxy claiming `subdomain = h-testhost`. Confirm `curl -H "Host: h-testhost.relay.supermux.dev" http://127.0.0.1:8080/` reaches it.

- [ ] **Step 3: GATE 1 — identity binding**

With the Task 11 auth plugin running (build it first if doing the spike properly, or stub it to always-allow for a pure-transport check, then re-run with real validation): prove a host leased for `h-A` is REJECTED when its frpc config requests `subdomain = h-B`, a raw TCP proxy, or a second proxy. Record pass/fail.

- [ ] **Step 4: GATE 2 — deterministic replacement**

Connect two frpc instances claiming the same `h-testhost`. Observe: does the newest win? Is the old one cleanly displaced, or do they fight/flap? Record the exact behavior and whether it's deterministic. **If replacement can't be made deterministic without forking frp → this gate FAILS.**

- [ ] **Step 5: GATE 3 — WebSocket + streaming fidelity**

Through the full chain (Caddy→frps→frpc→broker), exercise: a control WS (`/ws`), a terminal WS, a large file upload (≥40 MB), and a proxied dev-server WS. All must work and stream (no buffering-till-complete). Record.

- [ ] **Step 6: GATE 4 — capacity**

Spawn 500–1000 idle frpc clients against one frps (script it). Record RAM/FD/CPU per idle client; extrapolate to 5,000. Then induce a reconnect storm + concurrent WS/upload on a few. Confirm control traffic stays responsive. Record the per-idle-host cost and the projected single-VM ceiling.

- [ ] **Step 7: Write the verdict**

Create `docs/relay/SPIKE.md` documenting frp version, each gate's result (PASS/FAIL + evidence), and the decision. **If all four PASS → proceed to Task 10. If any FAILS → STOP: revert §4 of the spec to the rev-3 custom protocol (`git show 8e63513:docs/...`) and write Plan 1b for the custom relay instead. Do not proceed to Task 10.**

- [ ] **Step 8: Commit**

```bash
git add docs/relay/
git commit -m "docs(relay): frp spike protocol, config templates, and gate results"
```

---

## Task 10: Host-key lease — mint & verify

**Files:**
- Create: `src/core/relay/lease.ts`, `src/core/relay/lease.test.ts`

**Precondition:** Task 9 passed. A lease is a short-lived signed token binding a `hostId` to a permitted subdomain; the lease endpoint mints it after verifying the host's signature over a nonce, and the frps auth plugin (Task 11) verifies it. HMAC with a relay-server secret keeps verification cheap and local to the relay box.

- [ ] **Step 1: Write the failing test**

Create `src/core/relay/lease.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { mintLease, verifyLease } from "./lease"

const SECRET = "relay-hmac-secret"

test("a freshly minted lease verifies for its hostId", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = verifyLease(lease, { secret: SECRET, now: 2000 })
  expect(r.ok).toBe(true)
  if (r.ok) expect(r.hostId).toBe("habc")
})

test("an expired lease fails", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  expect(verifyLease(lease, { secret: SECRET, now: 7000 }).ok).toBe(false)
})

test("a tampered hostId fails the signature", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const forged = lease.replace("habc", "hxyz")
  expect(verifyLease(forged, { secret: SECRET, now: 2000 }).ok).toBe(false)
})

test("wrong secret fails", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  expect(verifyLease(lease, { secret: "other", now: 2000 }).ok).toBe(false)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/lease.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `src/core/relay/lease.ts`:

```typescript
import { createHmac, timingSafeEqual } from "crypto"

export interface MintLeaseArgs { hostId: string; secret: string; ttlMs: number; now?: number }
export type VerifyResult = { ok: true; hostId: string } | { ok: false }

function mac(payload: string, secret: string): string {
  return createHmac("sha256", secret).update(payload).digest("base64url")
}

/** Lease = "<hostId>.<expiresAt>.<hmac>". Opaque to frpc; verified by the plugin. */
export function mintLease({ hostId, secret, ttlMs, now = Date.now() }: MintLeaseArgs): string {
  const payload = `${hostId}.${now + ttlMs}`
  return `${payload}.${mac(payload, secret)}`
}

export function verifyLease(lease: string, { secret, now = Date.now() }: { secret: string; now?: number }): VerifyResult {
  const parts = lease.split(".")
  if (parts.length !== 3) return { ok: false }
  const [hostId, expStr, sig] = parts
  const expected = mac(`${hostId}.${expStr}`, secret)
  const a = Buffer.from(sig), b = Buffer.from(expected)
  if (a.length !== b.length || !timingSafeEqual(a, b)) return { ok: false }
  if (now > Number(expStr)) return { ok: false }
  return { ok: true, hostId }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/lease.test.ts`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/relay/lease.ts src/core/relay/lease.test.ts
git commit -m "feat(relay): short-lived HMAC host lease (mint + verify)"
```

---

## Task 11: frps auth plugin — Login/NewProxy validation

**Files:**
- Create: `src/core/relay/auth-plugin.ts`, `src/core/relay/auth-plugin.test.ts`

frps calls this HTTP handler on each operation (spec §4, verified in the spike). It runs on the relay box. `Login` carries the lease in metadata; `NewProxy` carries the requested `subdomain` — we enforce it equals `h-<leased hostId>`.

- [ ] **Step 1: Write the failing test**

Create `src/core/relay/auth-plugin.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { handleAuthOp } from "./auth-plugin"
import { mintLease } from "./lease"

const SECRET = "s"
const ctx = { secret: SECRET, now: () => 1000 }

test("Login with a valid lease is accepted", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "Login", content: { metas: { lease } } }, ctx)
  expect(r).toEqual({ reject: false, unchange: true })
})

test("Login with no lease is rejected", () => {
  const r = handleAuthOp({ op: "Login", content: { metas: {} } }, ctx)
  expect(r.reject).toBe(true)
})

test("NewProxy claiming the leased subdomain is accepted", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "NewProxy", content: { user: { metas: { lease } }, proxy_config: { subdomain: "h-habc", proxy_type: "http" } } }, ctx)
  expect(r.reject).toBe(false)
})

test("NewProxy claiming a DIFFERENT host's subdomain is rejected (GATE 1)", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "NewProxy", content: { user: { metas: { lease } }, proxy_config: { subdomain: "h-hbbb", proxy_type: "http" } } }, ctx)
  expect(r.reject).toBe(true)
})

test("NewProxy for a non-http proxy type is rejected", () => {
  const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5000, now: 1000 })
  const r = handleAuthOp({ op: "NewProxy", content: { user: { metas: { lease } }, proxy_config: { subdomain: "h-habc", proxy_type: "tcp" } } }, ctx)
  expect(r.reject).toBe(true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/auth-plugin.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `src/core/relay/auth-plugin.ts`:

```typescript
import { verifyLease } from "./lease"

export interface AuthOpCtx { secret: string; now?: () => number }
export interface AuthResponse { reject: boolean; reject_reason?: string; unchange?: boolean }

// Shapes are frp's server-plugin protocol (subset we use). See docs/relay/SPIKE.md.
type Op =
  | { op: "Login"; content: { metas?: Record<string, string> } }
  | { op: "NewProxy"; content: { user?: { metas?: Record<string, string> }; proxy_config?: { subdomain?: string; proxy_type?: string } } }
  | { op: "Ping"; content: unknown }

const ok: AuthResponse = { reject: false, unchange: true }
const deny = (reason: string): AuthResponse => ({ reject: true, reject_reason: reason })

export function handleAuthOp(op: Op, ctx: AuthOpCtx): AuthResponse {
  const now = ctx.now?.() ?? Date.now()
  if (op.op === "Login") {
    const lease = op.content.metas?.lease
    if (!lease || !verifyLease(lease, { secret: ctx.secret, now }).ok) return deny("invalid or missing lease")
    return ok
  }
  if (op.op === "NewProxy") {
    const lease = op.content.user?.metas?.lease ?? ""
    const v = verifyLease(lease, { secret: ctx.secret, now })
    if (!v.ok) return deny("invalid lease")
    const cfg = op.content.proxy_config ?? {}
    if (cfg.proxy_type !== "http") return deny("only http proxies permitted")
    if (cfg.subdomain !== `h-${v.hostId}`) return deny("subdomain does not match leased hostId")
    return ok
  }
  return ok // Ping and others pass through
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/auth-plugin.test.ts`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/relay/auth-plugin.ts src/core/relay/auth-plugin.test.ts
git commit -m "feat(relay): frps auth plugin — lease + subdomain-binding validation"
```

---

## Task 12: frp provider — frpc sidecar supervisor + lease acquisition

**Files:**
- Create: `src/core/relay/frp-provider.ts`, `src/core/relay/frp-provider.test.ts`

Implements `RelayProvider`. On `start()`: sign a nonce with the host key, POST to the relay's lease endpoint, write an `frpc` config with the lease + `subdomain = h-<hostId>`, spawn `frpc` (using the injected spawner), and report status. The spawner + fetch are injected so the test needs no real frpc/network. Mirror the tunnel supervisors' restart pattern (`src/core/tunnels/run.ts`).

- [ ] **Step 1: Write the failing test**

Create `src/core/relay/frp-provider.test.ts`:

```typescript
import { expect, test } from "bun:test"
import { FrpRelayProvider } from "./frp-provider"

function fakeIdentity(hostId = "habc") {
  return { hostId, publicKeyRaw: Buffer.alloc(32), sign: (m: Buffer) => Buffer.concat([Buffer.from("sig:"), m]), verify: () => true }
}

test("start acquires a lease, spawns frpc for the right subdomain, reports online", async () => {
  const spawned: string[][] = []
  const provider = new FrpRelayProvider({
    identity: fakeIdentity("habc"),
    relayBase: "https://relay.supermux.dev",
    relayDomain: "relay.supermux.dev",
    localPort: 9898,
    fetchImpl: async () => new Response(JSON.stringify({ lease: "habc.9999.sig", nonce: "n1" }), { status: 200 }),
    getNonce: async () => "n1",
    spawn: (argv) => { spawned.push(argv); return { kill: () => {}, exited: new Promise(() => {}) } },
    writeConfig: () => "/tmp/frpc.ini",
  })
  await provider.start()
  const st = provider.status()
  expect(st.state).toBe("online")
  expect(st.relayUrl).toBe("https://h-habc.relay.supermux.dev")
  expect(spawned.length).toBe(1)
  expect(spawned[0][0]).toContain("frpc")
})

test("a failed lease request reports error, does not spawn", async () => {
  const spawned: string[][] = []
  const provider = new FrpRelayProvider({
    identity: fakeIdentity(),
    relayBase: "https://relay.supermux.dev", relayDomain: "relay.supermux.dev", localPort: 9898,
    fetchImpl: async () => new Response("no", { status: 500 }),
    getNonce: async () => "n1",
    spawn: (argv) => { spawned.push(argv); return { kill: () => {}, exited: new Promise(() => {}) } },
    writeConfig: () => "/tmp/frpc.ini",
  })
  await provider.start()
  expect(provider.status().state).toBe("error")
  expect(spawned.length).toBe(0)
})

test("stop kills the sidecar and reports disabled", async () => {
  let killed = false
  const provider = new FrpRelayProvider({
    identity: fakeIdentity(),
    relayBase: "https://relay.supermux.dev", relayDomain: "relay.supermux.dev", localPort: 9898,
    fetchImpl: async () => new Response(JSON.stringify({ lease: "habc.9999.sig" }), { status: 200 }),
    getNonce: async () => "n1",
    spawn: () => ({ kill: () => { killed = true }, exited: new Promise(() => {}) }),
    writeConfig: () => "/tmp/frpc.ini",
  })
  await provider.start()
  await provider.stop()
  expect(killed).toBe(true)
  expect(provider.status().state).toBe("disabled")
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/frp-provider.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Write the implementation**

Create `src/core/relay/frp-provider.ts`:

```typescript
import type { RelayProvider, RelayStatus } from "./provider"

export interface FrpChild { kill(): void; exited: Promise<unknown> }
export interface FrpProviderOpts {
  identity: { hostId: string; sign(m: Buffer): Buffer }
  relayBase: string       // https://relay.supermux.dev (lease endpoint host)
  relayDomain: string     // relay.supermux.dev (subdomain suffix)
  localPort: number       // 9898
  fetchImpl?: typeof fetch
  getNonce: () => Promise<string>
  spawn: (argv: string[]) => FrpChild
  writeConfig: (ini: string) => string  // writes frpc.ini, returns path
}

export class FrpRelayProvider implements RelayProvider {
  private child: FrpChild | undefined
  private state: RelayStatus = { state: "disabled" }
  constructor(private readonly o: FrpProviderOpts) {}

  status(): RelayStatus { return this.state }

  async start(): Promise<void> {
    this.state = { state: "connecting" }
    const f = this.o.fetchImpl ?? fetch
    try {
      const nonce = await this.o.getNonce()
      const signature = this.o.identity.sign(Buffer.from(nonce)).toString("base64url")
      const res = await f(`${this.o.relayBase}/relay/lease`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ hostId: this.o.identity.hostId, nonce, signature }),
      })
      if (!res.ok) { this.state = { state: "error", detail: `lease ${res.status}` }; return }
      const { lease } = (await res.json()) as { lease: string }
      const subdomain = `h-${this.o.identity.hostId}`
      const ini = [
        "[common]",
        `server_addr = ${this.o.relayDomain}`,
        "server_port = 7000",
        `metadatas.lease = ${lease}`,
        "[web]",
        "type = http",
        "local_ip = 127.0.0.1",
        `local_port = ${this.o.localPort}`,
        `subdomain = ${subdomain}`,
        `metadatas.lease = ${lease}`,
      ].join("\n")
      const cfgPath = this.o.writeConfig(ini)
      this.child = this.o.spawn(["frpc", "-c", cfgPath])
      this.state = { state: "online", relayUrl: `https://${subdomain}.${this.o.relayDomain}` }
    } catch (e) {
      this.state = { state: "error", detail: String(e) }
    }
  }

  async stop(): Promise<void> {
    try { this.child?.kill() } catch { /* already gone */ }
    this.child = undefined
    this.state = { state: "disabled" }
  }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `TMPDIR=/home/ahmet/.cache/x bun test src/core/relay/frp-provider.test.ts`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/relay/frp-provider.ts src/core/relay/frp-provider.test.ts
git commit -m "feat(relay): frp provider — lease acquisition + frpc sidecar supervision"
```

---

## Task 13: Wire the relay provider into boot + expose status in `/host` and `/me`

**Files:**
- Modify: `src/main.ts` (boot), `src/channels/web/index.ts` (`/me` already returns `relayUrl` at line 1411 — feed it from the provider)

- [ ] **Step 1: Select the provider at boot**

In `src/main.ts`, after the host identity block (Task 7):

```typescript
import { NullRelayProvider } from "./core/relay/provider"
import { FrpRelayProvider } from "./core/relay/frp-provider"
```
```typescript
// Relay is opt-in via env until the spike passes + a relay box exists.
const relayProvider = process.env.MUX_RELAY_DOMAIN
  ? new FrpRelayProvider({
      identity: hostIdentity,
      relayBase: process.env.MUX_RELAY_BASE ?? `https://${process.env.MUX_RELAY_DOMAIN}`,
      relayDomain: process.env.MUX_RELAY_DOMAIN,
      localPort: Number(process.env.MUX_WEB_PORT ?? 9898),
      getNonce: async () => {
        const r = await fetch(`${process.env.MUX_RELAY_BASE ?? `https://${process.env.MUX_RELAY_DOMAIN}`}/relay/nonce`)
        return ((await r.json()) as { nonce: string }).nonce
      },
      spawn: (argv) => Bun.spawn(argv, { stdout: "ignore", stderr: "ignore" }),
      writeConfig: (ini) => { const p = `${STATE_DIR}/frpc.ini`; require("fs").writeFileSync(p, ini, { mode: 0o600 }); return p },
    })
  : new NullRelayProvider()
void relayProvider.start()
```

- [ ] **Step 2: Feed relay status into the web channel**

Change the existing `relayUrl` opt wiring so `/me` reports the LIVE relay URL. Add an opt `getRelayUrl?: () => string | undefined` and use it in the `/me` handler instead of the static `this.relayUrl`. In `src/main.ts` WebChannel opts:

```typescript
      getRelayUrl: () => relayProvider.status().relayUrl,
```

In `src/channels/web/index.ts`, line 1411, replace `relayUrl: this.relayUrl` with:

```typescript
        relayUrl: this.getRelayUrl?.() ?? this.relayUrl,
```

Add the `getRelayUrl` opt + field (mirror `relayUrl`).

- [ ] **Step 3: Typecheck + web suite**

Run: `TMPDIR=/home/ahmet/.cache/x bunx tsc --noEmit && TMPDIR=/home/ahmet/.cache/x bun test src/channels/web/ src/core/relay/`
Expected: no new tsc errors; all relay + web tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/main.ts src/channels/web/index.ts
git commit -m "feat(broker): start relay provider at boot; report live relay URL in /me"
```

---

## Task 14: Full-suite regression + finish

- [ ] **Step 1: Install deps (fresh worktree)**

Run: `bun install`

- [ ] **Step 2: Run the broker test suite**

Run: `TMPDIR=/home/ahmet/.cache/x bun test 2>&1 | tail -30`
Expected: green except the ~2 known pre-existing failures (`no-legacy-names` on the `agentmux-shim` literal; a `spawn-command` reply-fallback test) noted in `~/.mux` conventions. If a NEW test fails, fix it before proceeding.

- [ ] **Step 3: Typecheck**

Run: `TMPDIR=/home/ahmet/.cache/x bunx tsc --noEmit`
Expected: only the ~3 known pre-existing errors.

- [ ] **Step 4: Final commit if anything was fixed**

```bash
git add -A && git commit -m "test: initiative-1 plan-1 regression pass"
```

---

## Self-review notes (coverage map)

- Spec §3 host identity → Tasks 1, 2, 7. §3.3 `GET /host` public/authed split → Tasks 3, 6. §3.4 pairing claim → Tasks 4, 6. §4 frp relay (stack, identity, boundary, gates, ops) → Tasks 8–13; the gates are Task 9's steps 3–6. §8 phase-0 seam (tmux non-fatal, `GET /host`) → Tasks 5, 3. `RelayProvider` swap boundary → Task 8.
- Deferred to later plans (correctly out of scope here): the `handleHttp`/`acceptDuplex` seams are DELETED under D11, not built. Client `PairedHost`/fleet/push → Plan 2. Desktop sidecar embedding + wizard + bundled tmux/frpc packaging → Plan 3. Multi-region, E2E → phase 2.
- Fallback path: Task 9 failing flips §4 back to the rev-3 custom protocol (git `8e63513`) and replaces Tasks 10–13 with a custom-relay Plan 1b.
