// HTTP tests for the legacy /workspaces/:id/fs* routes (Task 7 review findings):
// errors from WorkdirFs are mapped by `err.code`, not by sniffing the message text,
// and a workspace whose workdir has been deleted 404s instead of 500ing. Boots a
// real WebChannel on an ephemeral port with a real DeviceStore token, mirroring
// upload-routes.test.ts.
import { afterEach, describe, expect, test } from "bun:test"
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts } from "./index"
import { DeviceStore } from "./device-store"

function makeChannel(getWorkspaceWorkdir: (id: string) => string | undefined): { channel: WebChannel; devicesFile: string } {
  const dir = mkdtempSync(join(tmpdir(), "mux-fs-routes-"))
  const devicesFile = join(dir, "devices.json")
  const opts: WebChannelOpts = {
    port: 0,
    devicesFile,
    publicUrl: "http://localhost",
    getSessionsSnapshot: () => [],
    getSessionLog: () => [],
    setMute: () => {},
    onSendFromWeb: () => {},
    getWorkspaceWorkdir,
  }
  return { channel: new WebChannel(opts), devicesFile }
}

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

describe("legacy workspace fs routes — error mapping by code", () => {
  test("reading a file over 1 MiB → 413", async () => {
    const workdir = mkdtempSync(join(tmpdir(), "mux-fs-workdir-"))
    writeFileSync(join(workdir, "big.txt"), Buffer.alloc(1024 * 1024 + 1, "A"))
    const made = makeChannel((id) => (id === "w1" ? workdir : undefined))
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/workspaces/w1/fs/read?path=big.txt`, { headers: { authorization: `Bearer ${token}` } })
    expect(res.status).toBe(413)
  })

  test("reading a binary file → 415", async () => {
    const workdir = mkdtempSync(join(tmpdir(), "mux-fs-workdir-"))
    const bin = Buffer.alloc(100)
    bin[10] = 0x00
    writeFileSync(join(workdir, "bin.dat"), bin)
    const made = makeChannel((id) => (id === "w1" ? workdir : undefined))
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/workspaces/w1/fs/read?path=bin.dat`, { headers: { authorization: `Bearer ${token}` } })
    expect(res.status).toBe(415)
  })

  test("reading a missing file named binary.txt → 404, not 415", async () => {
    const workdir = mkdtempSync(join(tmpdir(), "mux-fs-workdir-"))
    const made = makeChannel((id) => (id === "w1" ? workdir : undefined))
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/workspaces/w1/fs/read?path=binary.txt`, { headers: { authorization: `Bearer ${token}` } })
    expect(res.status).toBe(404)
  })

  test("listing a missing folder → 404", async () => {
    const workdir = mkdtempSync(join(tmpdir(), "mux-fs-workdir-"))
    const made = makeChannel((id) => (id === "w1" ? workdir : undefined))
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/workspaces/w1/fs?path=nope`, { headers: { authorization: `Bearer ${token}` } })
    expect(res.status).toBe(404)
  })

  test("searching a workspace whose workdir was deleted → 404, not 500", async () => {
    const workdir = mkdtempSync(join(tmpdir(), "mux-fs-workdir-"))
    mkdirSync(join(workdir, "src"))
    writeFileSync(join(workdir, "src", "a.ts"), "x")
    rmSync(workdir, { recursive: true, force: true })
    const made = makeChannel((id) => (id === "w1" ? workdir : undefined))
    channel = made.channel
    await channel.start()
    const token = mintToken(made.devicesFile)

    const res = await fetch(`${base()}/workspaces/w1/fs/search?q=a`, { headers: { authorization: `Bearer ${token}` } })
    expect(res.status).toBe(404)
    const body = (await res.json()) as Record<string, unknown>
    expect(typeof body.error).toBe("string")
  })
})
