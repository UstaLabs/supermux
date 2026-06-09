// Regression: previously a multi-minute hang could occur in mux-shim's
// callOutbound after a broker disconnect/reconnect. This test fires 5
// consecutive replies through a live shim-broker pair, asserts each
// completes in well under 2 seconds, and repeats the whole battery 3
// times in a single run. If anything in the call path silently wedges
// for more than a few seconds, this turns into a loud test failure.

import { describe, test, expect, beforeEach, afterEach } from "bun:test"
import { mkdtempSync, rmSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { startSocketServer, SocketServer } from "../src/core/session-manager/socket-server"
import { connectShim } from "../src/shim/socket-client"

describe("shim 5-consecutive-replies", () => {
  let dir: string
  let server: SocketServer

  beforeEach(() => {
    dir = mkdtempSync(join(tmpdir(), "agentmux-5cr-"))
  })
  afterEach(async () => {
    await server?.close()
    rmSync(dir, { recursive: true, force: true })
  })

  async function runBattery(): Promise<number[]> {
    const handler = {
      onRegister: async () => ({ name: "shim5", session_id: "shim5" }),
      onOutbound: async () => ({ ok: true, value: { message_id: "ok" } }),
      onOrchestration: async () => ({ ok: false, error: "denied" }),
    }
    server = await startSocketServer({ socketsDir: dir, handler })
    await server.bind("shim5")

    const client = await connectShim({
      socketsDir: dir, sessionId: "shim5", workdir: "/tmp", pid: 1,
    })

    const elapsed: number[] = []
    for (let i = 0; i < 5; i++) {
      const start = Date.now()
      const r = await client.callOutbound({ name: "reply", args: { chat_id: "c1", text: `msg ${i}` } })
      const ms = Date.now() - start
      elapsed.push(ms)
      expect(r.ok).toBe(true)
      expect(ms).toBeLessThan(2000)
    }
    await client.close()
    await server.close()
    return elapsed
  }

  test("battery 1: 5 sequential replies all finish under 2s", async () => {
    const elapsed = await runBattery()
    expect(elapsed.length).toBe(5)
    expect(Math.max(...elapsed)).toBeLessThan(2000)
  }, 15_000)

  test("battery 2: 5 sequential replies all finish under 2s", async () => {
    const elapsed = await runBattery()
    expect(elapsed.length).toBe(5)
    expect(Math.max(...elapsed)).toBeLessThan(2000)
  }, 15_000)

  test("battery 3: 5 sequential replies all finish under 2s", async () => {
    const elapsed = await runBattery()
    expect(elapsed.length).toBe(5)
    expect(Math.max(...elapsed)).toBeLessThan(2000)
  }, 15_000)

  test("timeout fires when broker never responds", async () => {
    // Override the call timeout via env so the test is fast.
    const prev = process.env.MUX_SHIM_CALL_TIMEOUT_MS
    process.env.MUX_SHIM_CALL_TIMEOUT_MS = "300"
    try {
      const handler = {
        onRegister: async () => ({ name: "shim-to", session_id: "shim-to" }),
        // Never resolves — simulates a broker stuck in onOutbound.
        onOutbound: () => new Promise<{ ok: boolean }>(() => {}),
        onOrchestration: async () => ({ ok: false, error: "denied" }),
      }
      server = await startSocketServer({ socketsDir: dir, handler })
      await server.bind("shim-to")
      const client = await connectShim({
        socketsDir: dir, sessionId: "shim-to", workdir: "/tmp", pid: 1,
      })
      const start = Date.now()
      const r = await client.callOutbound({ name: "reply", args: { chat_id: "c1", text: "stuck" } })
      const ms = Date.now() - start
      expect(r.ok).toBe(false)
      expect(r.error).toMatch(/timeout/)
      // Should have rejected within ~500ms (timeout 300ms + a little slack)
      expect(ms).toBeLessThan(1000)
      await client.close()
    } finally {
      if (prev !== undefined) process.env.MUX_SHIM_CALL_TIMEOUT_MS = prev
      else delete process.env.MUX_SHIM_CALL_TIMEOUT_MS
    }
  }, 5_000)
})
