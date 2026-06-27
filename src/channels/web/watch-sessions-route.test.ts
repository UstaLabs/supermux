// Integration tests for the watch-facing GET /sessions enrichment + POST /sessions/:id/read,
// driven over real HTTP against a booted WebChannel (mirrors update-routes.test.ts). The
// per-row derivation is unit-tested in watch-session-row.test.ts; this proves the route wires
// the existing opts (getSessionAgentState / getSessionLog / getReads / markRead) together.
import { afterEach, expect, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts, type SessionSnapshot } from "./index"
import { DeviceStore } from "./device-store"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })
function base(): string { return `http://127.0.0.1:${channel!.boundPort}` }
function mintToken(devicesFile: string): string { return new DeviceStore(devicesFile).mint("test-device").token }

function makeChannel(opts: Partial<WebChannelOpts>): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-watch-sessions-"))
  const devicesFile = join(dir, "devices.json")
  const full: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    ...opts,
  }
  return { channel: new WebChannel(full), devicesFile }
}

test("GET /sessions enriches rows with phase, preview, lastFrom, unread", async () => {
  const snap: SessionSnapshot[] = [
    { id: "s1", name: "alpha", workdir: "/w", mute: false, connected: true, agent: "claude" },
    { id: "s2", name: "beta", workdir: "/w", mute: false, connected: true, agent: "claude" },
  ]
  const made = makeChannel({
    getSessionsSnapshot: () => snap,
    getSessionAgentState: (id) => (id === "s1" ? { phase: "running", tool: "Bash" } : { phase: "idle" }),
    getSessionLog: (id) =>
      id === "s1"
        ? [{ id: "m1", ts: "2026-06-27T05:00:00Z", direction: "outbound", text: "working on it" }]
        : [{ id: "m2", ts: "2026-06-27T04:00:00Z", direction: "outbound", text: "done" }],
    // s2 is read up to its last message; s1 has no read pointer.
    getReads: () => ({ s2: "2026-06-27T04:00:00Z" }),
  })
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const res = await fetch(`${base()}/sessions`, { headers: { authorization: `Bearer ${token}` } })
  expect(res.status).toBe(200)
  const rows = (await res.json()) as Array<Record<string, unknown>>
  const s1 = rows.find((r) => r.id === "s1")!
  const s2 = rows.find((r) => r.id === "s2")!

  expect(s1.phase).toBe("running")
  expect(s1.tool).toBe("Bash")
  expect(s1.lastText).toBe("working on it")
  expect(s1.lastFrom).toBe("out")
  expect(s1.unread).toBe(true) // no read pointer → unread
  // original snapshot fields survive
  expect(s1.name).toBe("alpha")
  expect(s1.connected).toBe(true)

  expect(s2.phase).toBe("idle")
  expect(s2.unread).toBe(false) // read pointer == last ts → read
})

test("POST /sessions/:id/read calls markRead with the id", async () => {
  let marked: string | undefined
  const made = makeChannel({ markRead: (id) => { marked = id } })
  channel = made.channel
  await channel.start()
  const token = mintToken(made.devicesFile)

  const res = await fetch(`${base()}/sessions/abc/read`, {
    method: "POST",
    headers: { authorization: `Bearer ${token}` },
  })
  expect(res.status).toBe(200)
  expect(marked).toBe("abc")
})
