# Relay Lease Recovery and Observability Design

## Problem

The connectivity relay gives each host a 24-hour signed lease and starts `frpc`
with that lease in both the Login and NewProxy metadata. A live FRP control
connection continues after the lease expires because FRPS validates the lease
only when Login or NewProxy is processed.

The provider schedules one renewal five minutes before expiry. On the observed
Mac, that callback did not run. The existing FRP connection later disappeared
during a 1%-battery hibernate. FRP then retried Login with the expired lease;
FRPS rejected every attempt, but `frpc` remained alive. The broker equated a
live child process with a working relay and therefore never acquired a new
lease.

FRP 0.61.1 offers no structured client-side reconnect-authentication event.
`loginFailExit` applies only to the first Login, and the local `/api/status`
endpoint reports proxy state rather than the failed control Login or its
reason. Recovery must therefore be based on the lease state that supermux owns,
while rejection observability belongs at the server auth plugin that makes the
decision.

## Goals

- Recover automatically when the proactive renewal timer is missed and the
  current lease becomes due or stale.
- Preserve the existing FRP process while a fresh lease cannot be obtained, so
  temporary loss of Wi-Fi, DNS, or the control service does not create an
  additional outage.
- Let FRP continue to own ordinary transport reconnection.
- Record structured, low-volume lifecycle and rejection events without parsing
  FRP text logs and without logging credentials.
- Retry every failed lease acquisition, including HTTP non-2xx responses.

## Responsibility Split

FRP owns transport health: TCP loss, sleep/wake, address changes, heartbeats,
and reconnecting the control connection with its current configuration.

The host broker owns credential health: acquiring leases, tracking their expiry,
renewing them, replacing the FRP configuration after a successful acquisition,
and restarting `frpc` once so the fresh credential is loaded.

The connectivity-relay auth plugin owns the authoritative rejection decision.
It emits a structured rejection event at that decision point. The Mac does not
attempt to infer authentication rejection from public-route failures, an empty
FRP status response, or raw log text.

## Host Provider Design

### Independent timers

The provider keeps two independent timers:

1. The existing one-shot renewal timer fires five minutes before the current
   lease expires.
2. A local lease audit runs every five minutes. It compares `Date.now()` with
   the stored renewal deadline and requests renewal only when the deadline has
   passed. Healthy audits perform no network request and produce no log entry.

The audit is a safety net for a missed one-shot callback. It is not a 30-second
route-health loop. Both timers are cleared on `stop()`.

### Acquire before replace

Renewal first requests a nonce and fresh lease while the existing child and
configuration remain untouched. Only after a valid lease response is received
does the provider:

1. write the new FRP configuration;
2. stop the old `frpc` child;
3. spawn one replacement child; and
4. store the new expiry and reschedule both timers.

If acquisition fails, the provider preserves the old child. Before lease expiry,
that tunnel may still be healthy. After expiry, an already-established tunnel
may still be healthy. In either case, killing it because the network or control
service is temporarily unreachable would turn uncertainty into a definite
outage.

Initial startup has no child to preserve and reports `connecting` followed by
`error` if acquisition fails. A failed renewal with an existing child retains
the relay URL and online state while logging that credential refresh is pending.

### Pending-renewal retries

Every lease-acquisition failure schedules another attempt. This includes nonce
errors, exceptions, malformed success bodies, and HTTP non-2xx responses. Retry
delays are 5, 10, 20, 30, 60, 120, then 300 seconds, capped at five minutes.
Only one attempt and one retry timer may exist at a time. A successful renewal
resets the backoff.

The five-minute audit may notice the same overdue deadline while a retry is
pending; it must not create a duplicate acquisition. The provider's existing
connection guard remains the single-flight boundary.

### Child exit

An unexpected `frpc` exit remains a provider-owned failure because FRP can no
longer reconnect itself. The provider schedules credential acquisition using
the same retry path. Generation checks prevent an old child's exit promise from
affecting a replacement child or a stopped provider.

## Structured Host Logging

The provider receives the project logger and emits lifecycle events only at
meaningful transitions:

- `relay_lease_acquire_started` with trigger (`startup`, `renewal`, `audit`, or
  `child_exit`);
- `relay_lease_acquired` with host ID, expiry, and trigger;
- `relay_lease_acquire_failed` with trigger, safe error/status, whether an old
  child was preserved, and next retry delay;
- `relay_lease_audit_recovery` when the audit catches a missed renewal deadline;
- `relay_frpc_started`, `relay_frpc_exited`, and `relay_stopped`.

No event contains the lease, nonce, signature, public key, or full generated
configuration. Routine healthy audits are silent.

## Structured Server Rejection Logging

Lease verification returns an internal reason instead of only `{ok:false}`:
missing, malformed, invalid signature, invalid expiry, or expired. Valid and
expired signed leases may safely identify the bound host ID and expiry; data
from an unverifiable lease is not trusted as host identity.

When the auth plugin rejects Login or NewProxy, it calls an injected rejection
observer with a typed event. The connectivity-relay process records
`relay_auth_rejected` with:

- operation (`Login` or `NewProxy`);
- stable reason code;
- verified host ID when available;
- lease expiry and `expiredByMs` for an expired, correctly signed lease;
- FRP-provided client address when available; and
- requested proxy type or subdomain when relevant.

The response sent to FRPS remains deliberately generic for invalid credentials.
The event never includes the raw lease or any signing material. Rejections are
logged individually because they should be rare; aggregation and alerting can
be added outside this change if operational volume later requires it.

## Error and State Semantics

- Offline host or DNS/control outage: fresh acquisition fails, current child is
  preserved, retry backs off, and no public-route probe is used.
- Relay control returns 4xx/5xx: current child is preserved and acquisition is
  retried; the structured host event includes only HTTP status.
- Lease response lacks a usable lease or expiry: treat it as a failed acquisition
  and preserve the child.
- Renewal succeeds: the fresh configuration is written successfully before
  `frpc` is replaced once. A write failure preserves the existing child.
- Old child exits during acquisition: generation and child-identity checks avoid
  duplicate recovery; the pending acquisition supplies the replacement.
- Provider stops: timers and pending generations are invalidated and the child
  is stopped without scheduling recovery.

## Testing

Host-provider tests use injected time and timer functions to prove:

- proactive renewal remains scheduled five minutes before expiry;
- a five-minute audit catches an overdue renewal deadline;
- a healthy audit performs no network request;
- failed renewal preserves the existing child;
- HTTP non-2xx and thrown errors both schedule exponential retries;
- success resets backoff, writes fresh credentials, and replaces the child once;
- audit and retry callbacks cannot create concurrent acquisitions; and
- stop clears both timer classes and prevents recovery.

Lease/auth tests prove each internal verification reason, that only a correctly
signed expired lease contributes host/expiry metadata, and that rejection
events contain no credential. Control-handler tests prove that the injected
observer receives Login and NewProxy rejections. Existing valid Login,
NewProxy, lease issuance, host registry, and Caddy authorization tests remain
unchanged.

## Out of Scope

- Polling the public host URL as a repair trigger.
- A 30-second perpetual network or route-health loop.
- Parsing, retaining, or exposing raw `frpc`/FRPS logs.
- Changing FRP itself or depending on its optional local admin server.
- Removing registered hosts when a lease expires.
- Deploying or restarting the Mac broker or production relay as part of this
  code change.
