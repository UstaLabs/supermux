// Route tests for /api/update/status + /api/update/check + /api/update/run.
//
// These boot a real WebChannel on an ephemeral port (port:0 → boundPort) and
// drive it over HTTP — the same way the broker serves it — because routeRequest
// is private and the auth/CSRF/static-exclusion behavior all lives in the live
// fetch path. Auth is presented as `Authorization: Bearer <token>` (a native
// client): the device token is minted via a sibling DeviceStore on the same
// devices file, and bearer auth also bypasses the same-origin CSRF guard the way
// native clients do, so POSTs don't need an Origin header.
//
// We never exercise a REAL apply over HTTP: on this (source-mode) host POST
// /api/update/run returns 400 before any apply. To prove the 409 busy-guard we
// inject a checker and drive checker.setState("downloading") directly. The apply
// engine itself is covered exhaustively in apply.test.ts.
import { afterEach, beforeEach, describe, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"
import { UpdateChecker } from "../../core/update/checker"
import { BUILD_VERSION } from "../../shared/build-info"

// A fetchImpl that must never be called: the checker is never .start()ed in these
// tests, and status/run-in-source-mode never trigger a network call.
const neverFetch = async (): Promise<Response> => {
  throw new Error("fetch must not be called in route tests")
}

function makeChannel(updateChecker: UpdateChecker | null): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-update-routes-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0, // ephemeral; real port via channel.boundPort after start()
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    updateChecker,
  }
  return { channel: new WebChannel(opts), devicesFile }
}

/** Mint a device token via a sibling store on the same file the channel reads. */
function mintToken(devicesFile: string): string {
  return new DeviceStore(devicesFile).mint("test-device").token
}

let channel: WebChannel | undefined

afterEach(async () => {
  if (channel) {
    await channel.stop()
    channel = undefined
  }
})

function base(): string {
  return `http://127.0.0.1:${channel!.boundPort}`
}

describe("GET /api/update/status", () => {
  test("unauthed → 401", async () => {
    const made = makeChannel(null)
    channel = made.channel
    await channel.start()
    const res = await fetch(`${base()}/api/update/status`)
    expect(res.status).toBe(401)
  })

  test("authed with a checker → 200 + pinned status shape", async () => {
    const checker = new UpdateChecker({
      url: "http://127.0.0.1:1/versions.json",
      currentVersion: "1.2.3",
      commit: "abc1234",
      mode: "binary",
      fetchImpl: neverFetch,
    })
    const made = makeChannel(checker)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/api/update/status`, {
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    // Pinned shape from checker.status()
    expect(body.current).toBe("1.2.3")
    expect(body.commit).toBe("abc1234")
    expect(body.mode).toBe("binary")
    expect(body.state).toBe("idle")
    expect(body.updateAvailable).toBe(false)
    expect(body.latest).toBeNull()
    expect(body.lastChecked).toBeNull()
    expect(body.lastError).toBeNull()
    // No disabled flag when a checker is present.
    expect(body.disabled).toBeUndefined()
  })

  test("authed with checker=null → 200 + disabled:true fallback shape", async () => {
    const made = makeChannel(null)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/api/update/status`, {
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.disabled).toBe(true)
    expect(body.current).toBe(BUILD_VERSION)
    expect(body.updateAvailable).toBe(false)
    expect(body.latest).toBeNull()
    expect(body.state).toBe("idle")
    // mode is whatever the host detects (source under bun test).
    expect(typeof body.mode).toBe("string")
  })
})

describe("POST /api/update/check", () => {
  test("unauthed → 401", async () => {
    const made = makeChannel(null)
    channel = made.channel
    await channel.start()
    const res = await fetch(`${base()}/api/update/check`, { method: "POST" })
    expect(res.status).toBe(401)
  })

  test("authed with checker → forces checkNow and returns fresh status", async () => {
    let fetchCalls = 0
    const fetchImpl = async (): Promise<Response> => {
      fetchCalls++
      return new Response(
        JSON.stringify({
          schemaVersion: 1,
          channels: {
            stable: {
              version: "9.9.9",
              publishedAt: "2026-01-01T00:00:00Z",
              notesUrl: "https://example.com/notes",
              assets: {
                "linux-x64": {
                  url: "https://example.com/bin",
                  sha256: "a".repeat(64),
                },
              },
            },
          },
        }),
        { status: 200, headers: { "content-type": "application/json" } },
      )
    }
    const checker = new UpdateChecker({
      url: "http://127.0.0.1:1/versions.json",
      currentVersion: "1.0.0",
      commit: "abc1234",
      mode: "binary",
      fetchImpl,
    })
    const made = makeChannel(checker)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    // Cached GET has never checked → latest still null.
    const before = await fetch(`${base()}/api/update/status`, {
      headers: { authorization: `Bearer ${token}` },
    })
    expect((await before.json() as { latest: string | null }).latest).toBeNull()
    expect(fetchCalls).toBe(0)

    const res = await fetch(`${base()}/api/update/check`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(fetchCalls).toBe(1)
    expect(body.latest).toBe("9.9.9")
    expect(body.updateAvailable).toBe(true)
    expect(body.state).toBe("idle")
    expect(typeof body.lastChecked).toBe("number")
  })

  test("no checker → 200 + disabled:true (no network)", async () => {
    const made = makeChannel(null)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/api/update/check`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.disabled).toBe(true)
    expect(body.updateAvailable).toBe(false)
  })

  test("apply mid-flight (downloading) → returns current status without re-fetch", async () => {
    let fetchCalls = 0
    const fetchImpl = async (): Promise<Response> => {
      fetchCalls++
      throw new Error("should not fetch during apply")
    }
    const checker = new UpdateChecker({
      url: "http://127.0.0.1:1/versions.json",
      currentVersion: "1.0.0",
      commit: "abc1234",
      mode: "binary",
      fetchImpl,
    })
    checker.setState("downloading")
    const made = makeChannel(checker)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/api/update/check`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(200)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.state).toBe("downloading")
    expect(fetchCalls).toBe(0)
  })
})

describe("POST /api/update/run", () => {
  test("unauthed → 401", async () => {
    const made = makeChannel(null)
    channel = made.channel
    await channel.start()
    const res = await fetch(`${base()}/api/update/run`, { method: "POST" })
    expect(res.status).toBe(401)
  })

  test("source mode (mode≠binary) → 400 + instruction", async () => {
    // A source-mode checker → run is notify-only; returns the instruction text.
    const checker = new UpdateChecker({
      url: "http://127.0.0.1:1/versions.json",
      currentVersion: BUILD_VERSION,
      commit: "abc1234",
      mode: "source",
      fetchImpl: neverFetch,
    })
    const made = makeChannel(checker)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/api/update/run`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(400)
    const body = (await res.json()) as Record<string, unknown>
    expect(typeof body.error).toBe("string")
    expect(typeof body.instruction).toBe("string")
    expect((body.instruction as string).length).toBeGreaterThan(0)
  })

  test("no checker → 400 + instruction (cannot self-update)", async () => {
    const made = makeChannel(null)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/api/update/run`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(400)
    const body = (await res.json()) as Record<string, unknown>
    expect(typeof body.error).toBe("string")
    expect(typeof body.instruction).toBe("string")
  })

  test("busy: state already downloading → 409", async () => {
    // binary mode so we'd normally proceed; force state into the busy set first.
    const checker = new UpdateChecker({
      url: "http://127.0.0.1:1/versions.json",
      currentVersion: BUILD_VERSION,
      commit: "abc1234",
      mode: "binary",
      fetchImpl: neverFetch,
    })
    checker.setState("downloading")
    const made = makeChannel(checker)
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/api/update/run`, {
      method: "POST",
      headers: { authorization: `Bearer ${token}` },
    })
    expect(res.status).toBe(409)
    const body = (await res.json()) as Record<string, unknown>
    expect(body.error).toBe("busy")
  })
})
