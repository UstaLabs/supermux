import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }

function makeChannel(extra: Partial<WebChannelOpts> = {}): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-auth-rl-"))
  const devicesFile = join(dir, "devices.json")
  const full: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
    getHostInfo: () => ({ hostId: "h1", name: "box", platform: "linux", version: "0.11.0", protocolVersion: 1 }),
    ...extra,
  }
  return { channel: new WebChannel(full), devicesFile }
}

function bearer(token: string): RequestInit {
  return { headers: { authorization: `Bearer ${token}` } }
}

// 17 > RATE_LIMIT_MAX (16), i.e. enough to trip the window.
async function burstBadAuth(count = 17): Promise<void> {
  for (let i = 0; i < count; i++) {
    await fetch(`${base()}/me`, bearer(`bogus-token-${i}`))
  }
}

// The onboarding bug: the Mac app's state was reset, so it retried config() 20x with a
// STALE token (SupermuxApp.checkMacOnboarding loops 20x/500ms). That burst of 401s tripped
// the shared limiter bucket, and every subsequent request from the SAME host — including
// ones carrying the freshly minted, perfectly valid token — was rejected as 401. The whole
// wizard then reported "Couldn't reach the host" / "Couldn't load connections" / etc.
test("a burst of bad-token requests must not lock out a VALID token", async () => {
  const made = makeChannel()
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("mac").token

  expect((await fetch(`${base()}/me`, bearer(token))).status).toBe(200)

  await burstBadAuth()

  const res = await fetch(`${base()}/me`, bearer(token))
  expect(res.status).toBe(200)
  expect(await res.json()).toMatchObject({ paired: true, device: "mac" })
})

// A valid token is already immune to throttling (we verify before we throttle), so a
// success must NOT clear the failure budget: that would buy legitimate clients nothing and
// hand anyone sharing their bucket a fresh 16 guesses every time. The Mac app polls ~1/s,
// which would have reset a co-bucketed brute-forcer's counter indefinitely.
test("a successful auth does not hand a co-bucketed brute-forcer a fresh budget", async () => {
  const made = makeChannel()
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("mac").token

  await burstBadAuth()
  // The legitimate client keeps working throughout...
  expect((await fetch(`${base()}/me`, bearer(token))).status).toBe(200)
  // ...without un-throttling the attacker sharing its bucket.
  expect((await fetch(`${base()}/me`, bearer("guess-again"))).status).toBe(429)
})

// Brute-force protection must still bite: repeated GUESSES get throttled.
test("repeated bad tokens are throttled with 429, not a misleading 401", async () => {
  const made = makeChannel()
  channel = made.channel; await channel.start()

  await burstBadAuth()

  const res = await fetch(`${base()}/me`, bearer("still-guessing"))
  expect(res.status).toBe(429)
})

// A 401 means "your credential is wrong". Reporting throttling as 401 is what sent the
// previous fix attempt hunting for a client-side cancellation bug: /me even replied
// {"paired":false} to a correctly paired device.
test("throttling never reports a paired device as unpaired", async () => {
  const made = makeChannel()
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("mac").token

  await burstBadAuth()

  const res = await fetch(`${base()}/me`, bearer(token))
  expect(await res.json()).not.toMatchObject({ paired: false })
})

// The burst that actually broke onboarding. /host is public ("identity-only without auth"),
// but it asked requireAuth() whether to add the authed-only fields — and that counted every
// anonymous caller as an auth failure. MacBrokerSidecar.pollForHost probes GET /host with no
// credential up to 60x at 500ms on every app start, so the sidecar's own health check burned
// through the budget of 16 and locked the wizard out of the broker it had just started.
// Presenting no token is not a guess, so it must never consume the brute-force budget.
test("unauthenticated probes of public /host do not consume the brute-force budget", async () => {
  const made = makeChannel()
  channel = made.channel; await channel.start()

  for (let i = 0; i < 60; i++) {
    expect((await fetch(`${base()}/host`)).status).toBe(200)
  }

  // Budget untouched, so a first wrong guess is still answered honestly as 401. Before the
  // fix these 60 anonymous probes had already spent 60 of the 16-failure budget, so a client
  // whose token had simply rotated was told "rate limited" instead of "bad credential".
  expect((await fetch(`${base()}/me`, bearer("wrong-token"))).status).toBe(401)
})

// Security: clientIp() trusted X-Forwarded-For from ANY caller, so a brute-forcer defeated
// the limiter entirely by varying one header — each guess minted a fresh bucket. Only a peer
// we actually sit behind may name the client. `trustedProxyPeers: []` models a caller that
// is not our proxy (the real deployment trusts loopback, where frpc/nginx forward from).
test("X-Forwarded-For from an untrusted peer cannot bypass the limiter", async () => {
  const made = makeChannel({ trustedProxyPeers: [] })
  channel = made.channel; await channel.start()

  for (let i = 0; i < 17; i++) {
    await fetch(`${base()}/me`, {
      headers: { authorization: `Bearer guess-${i}`, "x-forwarded-for": `10.0.0.${i}` },
    })
  }

  const res = await fetch(`${base()}/me`, {
    headers: { authorization: "Bearer guess-final", "x-forwarded-for": "10.0.0.99" },
  })
  expect(res.status).toBe(429)
})

// The flip side: behind a real proxy, each forwarded client gets its own budget, so one
// remote brute-forcer must not throttle everyone else arriving via the same relay.
test("behind a trusted proxy, one client's failures don't throttle another", async () => {
  const made = makeChannel({ trustedProxyPeers: ["127.0.0.1", "::1", "::ffff:127.0.0.1"] })
  channel = made.channel; await channel.start()
  const token = new DeviceStore(made.devicesFile).mint("phone").token

  for (let i = 0; i < 17; i++) {
    await fetch(`${base()}/me`, {
      headers: { authorization: `Bearer guess-${i}`, "x-forwarded-for": "203.0.113.7" },
    })
  }

  const attacker = await fetch(`${base()}/me`, {
    headers: { authorization: "Bearer guess-final", "x-forwarded-for": "203.0.113.7" },
  })
  expect(attacker.status).toBe(429)

  const victim = await fetch(`${base()}/me`, {
    headers: { authorization: `Bearer ${token}`, "x-forwarded-for": "203.0.113.8" },
  })
  expect(victim.status).toBe(200)
})
