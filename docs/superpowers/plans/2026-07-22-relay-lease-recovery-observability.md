# Relay Lease Recovery and Observability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make expired FRP credentials self-heal without disrupting possibly healthy tunnels during network outages, and expose structured authentication rejection reasons at the relay.

**Architecture:** The server track enriches HMAC lease verification with typed failure reasons and emits safe structured events from the auth-plugin decision point. The host track keeps FRP responsible for transport reconnects while independently tracking credential expiry with a proactive timer, a five-minute local audit, and bounded acquisition backoff; it acquires and writes fresh credentials before replacing the existing child.

**Tech Stack:** TypeScript, Bun runtime and `bun:test`, FRP 0.61 server-plugin JSON protocol, existing structured logger.

---

### Task 1: Typed lease verification results

**Files:**
- Modify: `src/core/relay/lease.ts`
- Test: `src/core/relay/lease.test.ts`

- [ ] **Step 1: Write failing tests for useful but safe verification reasons**

Extend `lease.test.ts` so it asserts the complete result for a valid lease and distinct results for missing, malformed, invalid-signature, invalid-expiry, and correctly signed expired leases. The key assertions are:

```ts
expect(verifyLease("", { secret: SECRET, now: 2_000 })).toEqual({ ok: false, reason: "missing" })
expect(verifyLease("not-a-lease", { secret: SECRET, now: 2_000 })).toEqual({ ok: false, reason: "malformed" })

const valid = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5_000, now: 1_000 })
expect(verifyLease(valid, { secret: SECRET, now: 2_000 })).toEqual({
  ok: true,
  hostId: "habc",
  expiresAt: 6_000,
})
expect(verifyLease(valid, { secret: SECRET, now: 7_000 })).toEqual({
  ok: false,
  reason: "expired",
  hostId: "habc",
  expiresAt: 6_000,
})

const forged = valid.replace("habc", "hxyz")
expect(verifyLease(forged, { secret: SECRET, now: 2_000 })).toEqual({ ok: false, reason: "invalid_signature" })
```

Also construct an invalid numeric-expiry shape and expect `invalid_expiry`; it must not expose host identity or expiry metadata.

- [ ] **Step 2: Run the lease tests and verify RED**

Run: `bun test src/core/relay/lease.test.ts`

Expected: FAIL because `verifyLease()` still returns only `{ok:false}` and a valid result lacks `expiresAt`.

- [ ] **Step 3: Implement typed verification without trusting unsigned metadata**

In `lease.ts`, replace the result type with:

```ts
export type VerifyFailureReason = "missing" | "malformed" | "invalid_expiry" | "invalid_signature" | "expired"
export type VerifyResult =
  | { ok: true; hostId: string; expiresAt: number }
  | { ok: false; reason: VerifyFailureReason; hostId?: string; expiresAt?: number }
```

Implement this order:

1. empty input → `missing`;
2. wrong component count or empty component → `malformed`;
3. expiry not a positive safe integer → `invalid_expiry`;
4. HMAC length/content mismatch → `invalid_signature`;
5. correctly signed but `now > expiresAt` → `expired` with verified `hostId` and `expiresAt`;
6. otherwise success with `hostId` and `expiresAt`.

Only steps 5 and 6 may return host/expiry metadata because only then has the signature been verified.

- [ ] **Step 4: Run the lease tests and verify GREEN**

Run: `bun test src/core/relay/lease.test.ts`

Expected: all lease tests pass.

- [ ] **Step 5: Commit**

```bash
git add src/core/relay/lease.ts src/core/relay/lease.test.ts
git commit -m "feat(relay): classify lease verification failures"
```

### Task 2: Structured auth-plugin rejection events

**Files:**
- Modify: `src/core/relay/auth-plugin.ts`
- Modify: `src/core/relay/auth-plugin.test.ts`
- Modify: `src/core/relay/control.ts`
- Modify: `src/core/relay/control.test.ts`
- Modify: `src/connectivity-relay/main.ts`

- [ ] **Step 1: Write failing auth-plugin event tests**

Add tests which pass `onReject` in the auth context and verify:

```ts
const events: AuthRejectionEvent[] = []
const ctx = { secret: SECRET, now: () => 7_000, onReject: (event: AuthRejectionEvent) => events.push(event) }
const lease = mintLease({ hostId: "habc", secret: SECRET, ttlMs: 5_000, now: 1_000 })
const response = handleAuthOp({ op: "Login", content: { metas: { lease }, client_address: "203.0.113.9:1234" } }, ctx)

expect(response).toEqual({ reject: true, reject_reason: "invalid or missing lease" })
expect(events).toEqual([{
  operation: "Login",
  reason: "expired_lease",
  hostId: "habc",
  leaseExpiresAt: 6_000,
  expiredByMs: 1_000,
  clientAddress: "203.0.113.9:1234",
}])
expect(JSON.stringify(events)).not.toContain(lease)
```

Add separate assertions for `missing_lease`, `invalid_lease_signature`, `unsupported_proxy_type`, and `subdomain_mismatch`. Confirm valid operations emit no event and external rejection strings remain generic.

- [ ] **Step 2: Run auth tests and verify RED**

Run: `bun test src/core/relay/auth-plugin.test.ts`

Expected: FAIL because the rejection event type and callback do not exist.

- [ ] **Step 3: Implement typed rejection events at the decision point**

Export these public shapes from `auth-plugin.ts`:

```ts
export type AuthRejectionReason =
  | "missing_lease"
  | "malformed_lease"
  | "invalid_lease_expiry"
  | "invalid_lease_signature"
  | "expired_lease"
  | "unsupported_proxy_type"
  | "subdomain_mismatch"

export interface AuthRejectionEvent {
  operation: "Login" | "NewProxy"
  reason: AuthRejectionReason
  hostId?: string
  leaseExpiresAt?: number
  expiredByMs?: number
  clientAddress?: string
  proxyType?: string
  subdomain?: string
}

export interface AuthOpCtx {
  secret: string
  now?: () => number
  onReject?: (event: AuthRejectionEvent) => void
}
```

Map lease failure reasons to stable auth reason codes. Route every deny path through one helper which calls `onReject` and returns the existing FRP `AuthResponse`. Never attach the lease to the event. Add optional `client_address` to the Login content shape and proxy type/subdomain only for relevant NewProxy denials.

- [ ] **Step 4: Run auth tests and verify GREEN**

Run: `bun test src/core/relay/auth-plugin.test.ts`

Expected: all auth-plugin tests pass.

- [ ] **Step 5: Write a failing control-handler observer test**

In `control.test.ts`, create the control with `onAuthRejected: event => events.push(event)`, send an expired signed Login lease to `/handler`, and assert the captured event is `expired_lease` with verified host/expiry metadata. Send a NewProxy subdomain mismatch and assert the second event contains `operation: "NewProxy"` and `reason: "subdomain_mismatch"`.

- [ ] **Step 6: Run the control test and verify RED**

Run: `bun test src/core/relay/control.test.ts`

Expected: FAIL because `RelayControlOpts` does not accept or forward `onAuthRejected`.

- [ ] **Step 7: Wire the observer and production structured logger**

Add this option to `RelayControlOpts` and pass it into `handleAuthOp`:

```ts
onAuthRejected?: (event: AuthRejectionEvent) => void
```

In `src/connectivity-relay/main.ts`, create `const log = makeLogger("connectivity-relay")` before `createRelayControl()` and configure:

```ts
onAuthRejected: (event) => log.warn("relay_auth_rejected", event),
```

Keep the existing ready log and do not log request bodies or credentials.

- [ ] **Step 8: Run the server-track tests and typecheck the touched boundary**

Run: `bun test src/core/relay/lease.test.ts src/core/relay/auth-plugin.test.ts src/core/relay/control.test.ts`

Expected: all tests pass.

Run: `bunx tsc --noEmit --pretty false 2>&1 | tee /tmp/relay-server-typecheck.log`; compare any failures against the documented pre-existing project typecheck failures and ensure none reference the five server-track files.

- [ ] **Step 9: Commit**

```bash
git add src/core/relay/auth-plugin.ts src/core/relay/auth-plugin.test.ts src/core/relay/control.ts src/core/relay/control.test.ts src/connectivity-relay/main.ts
git commit -m "feat(relay): log structured auth rejections"
```

### Task 3: Host lease audit and acquire-before-replace recovery

**Files:**
- Modify: `src/core/relay/frp-provider.ts`
- Modify: `src/core/relay/frp-provider.test.ts`
- Modify: `src/main.ts`

- [ ] **Step 1: Introduce deterministic timer and logger test helpers**

Refactor the existing provider tests to use a fake timer registry that records `{fn, delay, cleared}` and can select active callbacks by delay. Add an injected logger recorder matching `Pick<Logger, "info" | "warn">`. This is test scaffolding only; existing lease acquisition, hostname, wrapper, failure, and stop assertions must remain covered.

- [ ] **Step 2: Write failing tests for independent proactive and audit timers**

Add tests proving that after successful startup:

```ts
expect(activeDelays()).toContain(3_300_000) // expiry - now - five minutes
expect(activeDelays()).toContain(300_000)   // recurring local audit
```

Invoke an audit while `now < renewalDueAt` and assert no additional nonce/lease request. Advance `now` beyond `renewalDueAt`, invoke the next audit, and assert acquisition starts and `relay_lease_audit_recovery` is logged exactly once.

- [ ] **Step 3: Run the focused provider test and verify RED**

Run: `bun test src/core/relay/frp-provider.test.ts`

Expected: FAIL because the provider has only one timer and no audit/logger support.

- [ ] **Step 4: Add independent timers and tracked lease deadlines**

In `frp-provider.ts`:

- import `Logger` and add `log?: Pick<Logger, "info" | "warn">` to options;
- replace the single timer with `renewTimer`, `auditTimer`, and `retryTimer`;
- store `leaseExpiry` and `renewalDueAt`;
- schedule the audit every `300_000` ms using the injected timer functions and `unref()` for real timers;
- keep proactive renewal at expiry minus `300_000` ms, clamped to the existing minimum 30 seconds and maximum timer delay;
- keep healthy audits silent and network-free;
- skip an overdue audit acquisition when an acquisition is active or a retry timer is already pending;
- clear all three timers and lease deadlines on stop.

Use trigger type:

```ts
type AcquireTrigger = "startup" | "renewal" | "audit" | "child_exit" | "retry"
```

- [ ] **Step 5: Run provider tests and verify the timer cases GREEN**

Run: `bun test src/core/relay/frp-provider.test.ts`

Expected: the timer/audit cases pass; remaining new recovery cases may not exist yet.

- [ ] **Step 6: Write failing tests for acquire-before-replace and retry backoff**

Create a successful initial lease/child, then make the next lease request return HTTP 503. Fire renewal and assert:

```ts
expect(firstChildKilled).toBe(false)
expect(provider.status().state).toBe("online")
expect(activeDelays()).toContain(5_000)
expect(lastWarning()).toMatchObject({
  event: "relay_lease_acquire_failed",
  data: { trigger: "renewal", preservedChild: true, nextRetryMs: 5_000 },
})
```

Fire successive failed retry callbacks and assert delays `10_000`, `20_000`, `30_000`, `60_000`, `120_000`, then `300_000`, with the cap retained. Cover a thrown nonce/network error as well as non-2xx. Then return a fresh lease and assert the old child is killed only after the new config is written, one replacement is spawned, backoff resets, and fresh proactive/audit timers exist.

Add a malformed 200 response case (missing/invalid lease expiry) and assert it follows the same preservation/retry path.

- [ ] **Step 7: Run provider tests and verify RED**

Run: `bun test src/core/relay/frp-provider.test.ts`

Expected: FAIL because current `connect()` kills the child before acquisition and does not retry non-2xx responses.

- [ ] **Step 8: Implement single-flight acquisition and bounded retry**

Refactor `connect()` into an acquisition method accepting `AcquireTrigger`:

1. return when stopped or already acquiring;
2. retain the current child and online state while nonce/lease/config writing runs;
3. validate that `lease` is non-empty and `expiresAt` (response field or signed-token component) is a finite future timestamp;
4. after a successful config write and generation check, detach/kill the old child, spawn the replacement, set online state, reset backoff, and schedule renewal/audit;
5. on any failure, retain the child when present, otherwise set error state, log a safe failure, and schedule the next delay from `[5, 10, 20, 30, 60, 120, 300]` seconds;
6. retry HTTP non-2xx exactly like thrown errors;
7. ensure timer callbacks clear their own timer slot before acquiring;
8. use generation and child-identity checks so replaced/stopped children cannot schedule recovery.

Do not add a public-route request or parse child output.

- [ ] **Step 9: Write and pass child-exit/stop race tests**

Add tests proving an unexpected current-child exit schedules a one-second `child_exit` acquisition, an old replaced child's exit does nothing, `stop()` clears renewal/audit/retry timers, and callbacks invoked after stop do not fetch or spawn.

Run: `bun test src/core/relay/frp-provider.test.ts`

Expected: all provider tests pass.

- [ ] **Step 10: Wire the provider logger**

In `src/main.ts`, create a focused logger alongside the existing main logger:

```ts
const relayLog = makeLogger("core/relay/frp-provider")
```

Pass `log: relayLog` into `new FrpRelayProvider(...)`. Keep FRP stdout/stderr ignored because structured provider/auth events replace reliance on raw log parsing.

- [ ] **Step 11: Run host-track tests and typecheck the touched boundary**

Run: `bun test src/core/relay/frp-provider.test.ts`

Expected: all tests pass.

Run: `bunx tsc --noEmit --pretty false 2>&1 | tee /tmp/relay-host-typecheck.log`; compare any failures against the documented baseline and ensure none reference `frp-provider.ts`, its tests, or `main.ts` changes.

- [ ] **Step 12: Commit**

```bash
git add src/core/relay/frp-provider.ts src/core/relay/frp-provider.test.ts src/main.ts
git commit -m "fix(relay): recover stale FRP leases safely"
```

### Task 4: Integrated review and verification

**Files:**
- Review: `src/core/relay/lease.ts`
- Review: `src/core/relay/auth-plugin.ts`
- Review: `src/core/relay/control.ts`
- Review: `src/core/relay/frp-provider.ts`
- Review: `src/connectivity-relay/main.ts`
- Review: `src/main.ts`

- [ ] **Step 1: Review the combined diff against the design**

Run: `git diff 717bf93...HEAD -- src/core/relay src/connectivity-relay/main.ts src/main.ts`

Check explicitly: no credential logging; no public route probe; no healthy-audit network request; acquisition precedes child kill; every acquisition error schedules retry; old-child and stop races are generation-safe.

- [ ] **Step 2: Run all focused relay tests**

Run:

```bash
bun test \
  src/core/relay/lease.test.ts \
  src/core/relay/auth-plugin.test.ts \
  src/core/relay/control.test.ts \
  src/core/relay/frp-provider.test.ts
```

Expected: all focused tests pass with zero failures.

- [ ] **Step 3: Run the broader core relay test directory**

Run: `bun test src/core/relay`

Expected: all relay-directory tests pass with zero failures.

- [ ] **Step 4: Run formatting/diff and type diagnostics**

Run: `git diff --check 717bf93...HEAD`

Expected: no whitespace errors.

Run: `bun run typecheck`

Expected: either zero errors, or only the documented pre-existing TypeScript 5.9 errors. Any new error in a touched file must be fixed.

- [ ] **Step 5: Run the complete Bun test suite with an extended timeout**

Run: `bun test`

Expected: no new failures. Compare any failures with the documented clean-tree baseline; rerun suspected environmental/flaky tests in isolation.

- [ ] **Step 6: Record durable operational findings**

Append a dated note to `/home/ahmet/.mux/domains/claudemux.md` describing the final recovery behavior, structured event names, exact focused verification, and any deployment caveat. Do not edit the digest file.
