import { test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { Database } from "bun:sqlite"
import { DevicePushTokenStore } from "../src/core/push/device-tokens"
import { WebChannel, __resetAuthFailures } from "../src/channels/web"
import { DeviceStore } from "../src/channels/web/device-store"

let tmpDir: string
let port: number
let channel: WebChannel
let token: string
let deviceStore: DevicePushTokenStore

beforeEach(async () => {
  __resetAuthFailures()
  tmpDir = mkdtempSync(join(tmpdir(), "cmux-push-dev-ep-"))

  const db = new Database(":memory:")
  db.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
  deviceStore = new DevicePushTokenStore(db)

  const devicesFile = join(tmpDir, "devices.json")
  const ds = new DeviceStore(devicesFile)
  token = ds.mint("iphone").token

  channel = new WebChannel({
    port: 0,
    devicesFile,
    publicUrl: "http://127.0.0.1",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    deviceTokenStore: deviceStore,
  } as any)
  await channel.start()
  port = channel.boundPort
})

afterEach(async () => {
  await channel.stop()
  rmSync(tmpDir, { recursive: true, force: true })
})

test("POST /push/device stores the native token for the bearer device", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({ platform: "ios", routingToken: "rt-1", pubkey: "PUB" }),
  })
  expect(res.status).toBe(200)
  const row = deviceStore.get("iphone")
  expect(row?.routing_token).toBe("rt-1")
  expect(row?.device_pubkey).toBe("PUB")
})

test("POST /push/device 401 without bearer", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ platform: "ios", routingToken: "rt-1", pubkey: "PUB" }),
  })
  expect(res.status).toBe(401)
})

test("POST /push/device 400 on missing routingToken", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "POST",
    headers: { Cookie: `cmux_token=${token}`, "content-type": "application/json" },
    body: JSON.stringify({ platform: "ios", pubkey: "PUB" }),
  })
  expect(res.status).toBe(400)
})

test("DELETE /push/device removes the native token", async () => {
  deviceStore.putNative("iphone", "ios", "rt-1", "PUB")
  const res = await fetch(`http://127.0.0.1:${port}/push/device`, {
    method: "DELETE",
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  expect(deviceStore.get("iphone")).toBeNull()
})

test("GET /me returns relayUrl when channel is configured with one", async () => {
  // Spin up a separate channel that has relayUrl set.
  const tmpDir2 = mkdtempSync(join(tmpdir(), "cmux-me-relay-"))
  try {
    const devicesFile2 = join(tmpDir2, "devices.json")
    const ds2 = new DeviceStore(devicesFile2)
    const token2 = ds2.mint("iphone2").token

    const ch2 = new WebChannel({
      port: 0,
      devicesFile: devicesFile2,
      publicUrl: "http://127.0.0.1",
      getSessionsSnapshot: () => [],
      getSessionLog: () => [],
      setMute: () => {},
      onSendFromWeb: () => {},
      deviceTokenStore: deviceStore,
      relayUrl: "https://relay.example",
    } as any)
    await ch2.start()
    const port2 = ch2.boundPort
    try {
      const res = await fetch(`http://127.0.0.1:${port2}/me`, {
        headers: { Cookie: `cmux_token=${token2}` },
      })
      expect(res.status).toBe(200)
      const body = await res.json() as any
      expect(body.paired).toBe(true)
      expect(body.relayUrl).toBe("https://relay.example")
    } finally {
      await ch2.stop()
    }
  } finally {
    rmSync(tmpDir2, { recursive: true, force: true })
  }
})

test("GET /me omits relayUrl when not configured", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/me`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  expect(res.status).toBe(200)
  const body = await res.json() as any
  expect(body.paired).toBe(true)
  // relayUrl should be absent (or undefined) when not configured
  expect(body.relayUrl).toBeUndefined()
})

// Card #86: with the hosted connectivity relay on, `relayUrl` names the tunnel
// (the desktop pairs phones with it), so the phones' push registration needs
// the push relay under a field of its own.
test("GET /me names the push relay as pushRelayUrl when the connectivity relay fills relayUrl", async () => {
  const tmpDir2 = mkdtempSync(join(tmpdir(), "cmux-me-push-relay-"))
  try {
    const devicesFile2 = join(tmpDir2, "devices.json")
    const token2 = new DeviceStore(devicesFile2).mint("fold").token
    const ch2 = new WebChannel({
      port: 0,
      devicesFile: devicesFile2,
      publicUrl: "http://127.0.0.1",
      getSessionsSnapshot: () => [],
      getSessionLog: () => [],
      setMute: () => {},
      onSendFromWeb: () => {},
      deviceTokenStore: deviceStore,
      relayUrl: "https://push.example",
      getRelayUrl: () => "https://h-abc.relay.example",
    } as any)
    await ch2.start()
    try {
      const res = await fetch(`http://127.0.0.1:${ch2.boundPort}/me`, {
        headers: { Cookie: `cmux_token=${token2}` },
      })
      expect(res.status).toBe(200)
      const body = await res.json() as any
      expect(body.relayUrl).toBe("https://h-abc.relay.example")
      expect(body.pushRelayUrl).toBe("https://push.example")
    } finally {
      await ch2.stop()
    }
  } finally {
    rmSync(tmpDir2, { recursive: true, force: true })
  }
})

test("start() drops push rows of devices that are no longer paired", async () => {
  const tmpDir2 = mkdtempSync(join(tmpdir(), "cmux-push-orphans-"))
  try {
    const devicesFile2 = join(tmpDir2, "devices.json")
    new DeviceStore(devicesFile2).mint("fold")
    const db2 = new Database(":memory:")
    db2.run(`CREATE TABLE device_push_tokens (device TEXT PRIMARY KEY, platform TEXT NOT NULL, token TEXT NOT NULL, routing_token TEXT, device_pubkey TEXT, created_at TEXT NOT NULL, last_used_at TEXT)`)
    const tokens2 = new DevicePushTokenStore(db2)
    tokens2.putNative("fold", "android", "rt-fold", "PK1")
    tokens2.putNative("dev iphone", "ios", "rt-old", "PK2")
    const ch2 = new WebChannel({
      port: 0,
      devicesFile: devicesFile2,
      publicUrl: "http://127.0.0.1",
      getSessionsSnapshot: () => [],
      getSessionLog: () => [],
      setMute: () => {},
      onSendFromWeb: () => {},
      deviceTokenStore: tokens2,
    } as any)
    await ch2.start()
    try {
      expect(tokens2.all().map((r) => r.device)).toEqual(["fold"])
    } finally {
      await ch2.stop()
    }
  } finally {
    rmSync(tmpDir2, { recursive: true, force: true })
  }
})

test("GET /me omits pushRelayUrl when no push relay is configured", async () => {
  const res = await fetch(`http://127.0.0.1:${port}/me`, {
    headers: { Cookie: `cmux_token=${token}` },
  })
  const body = await res.json() as any
  expect(body.pushRelayUrl).toBeUndefined()
})

// Card #86: the broker's log is where a silent push failure has to show up.
async function capturingStderr<T>(fn: () => Promise<T>): Promise<string> {
  const lines: string[] = []
  const write = process.stderr.write.bind(process.stderr)
  process.stderr.write = ((chunk: any) => { lines.push(String(chunk)); return true }) as any
  try { await fn() } finally { process.stderr.write = write }
  return lines.join("")
}

test("a phone app connecting with no push registration is logged as push_device_unregistered", async () => {
  const out = await capturingStderr(async () => {
    const ws = new WebSocket(`ws://127.0.0.1:${port}/ws?clientSurface=android`, { headers: { Authorization: `Bearer ${token}` } } as never)
    await new Promise<void>((resolve, reject) => { ws.onopen = () => resolve(); ws.onerror = () => reject(new Error("ws failed")) })
    await new Promise((r) => setTimeout(r, 50))
    ws.close()
  })
  expect(out).toContain(`push_device_unregistered {"device":"iphone","surface":"android"}`)
})

test("POST /push/device logs the registration", async () => {
  const out = await capturingStderr(async () => {
    await fetch(`http://127.0.0.1:${port}/push/device`, {
      method: "POST",
      headers: { Authorization: `Bearer ${token}`, "content-type": "application/json" },
      body: JSON.stringify({ platform: "android", routingToken: "rt-1", pubkey: "PUB" }),
    })
  })
  expect(out).toContain(`push_device_registered {"device":"iphone","platform":"android"}`)
})
