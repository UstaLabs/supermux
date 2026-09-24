// Regression for the "Empty reply from server" bug: GET /worktrees over a large
// ~/.mux/worktrees root (tens of thousands of folders) takes >10s, and Bun.serve's default
// idleTimeout (10s) killed the connection mid-flight even though the broker stayed healthy
// and finished the work. Client-side timeouts (2min list, 10min delete) are meaningless while
// the SERVER drops the connection first — this pins that Bun.serve actually gets a much
// larger idleTimeout, not just that some constant looks big enough somewhere unused.
import { afterEach, expect, spyOn, test } from "bun:test"
import { mkdtempSync } from "fs"
import { join } from "path"
import { tmpdir } from "os"
import { WebChannel, type WebChannelOpts, SERVE_IDLE_TIMEOUT_SECONDS } from "./index"

let channel: WebChannel | undefined
afterEach(async () => { if (channel) { await channel.stop(); channel = undefined } })

function makeChannel(): WebChannel {
  const dir = mkdtempSync(join(tmpdir(), "mux-idle-timeout-"))
  const devicesFile = join(dir, "devices.json")
  const full: WebChannelOpts = {
    port: 0, devicesFile, publicUrl: "http://localhost",
    getSessionsSnapshot: () => [], getSessionLog: () => [], setMute: () => {}, onSendFromWeb: () => {},
  }
  return new WebChannel(full)
}

test("SERVE_IDLE_TIMEOUT_SECONDS is well above Bun's 10s default (>= 200s)", () => {
  // 255 is Bun's own hard maximum for this option; the floor here just guards against a
  // future edit accidentally shrinking it back toward the default.
  expect(SERVE_IDLE_TIMEOUT_SECONDS).toBeGreaterThanOrEqual(200)
  expect(SERVE_IDLE_TIMEOUT_SECONDS).toBeLessThanOrEqual(255)
})

test("Bun.serve is actually called with idleTimeout: SERVE_IDLE_TIMEOUT_SECONDS", async () => {
  const serveSpy = spyOn(Bun, "serve")
  channel = makeChannel()
  try {
    await channel.start()
    expect(serveSpy).toHaveBeenCalledTimes(1)
    const opts = serveSpy.mock.calls[0]?.[0] as { idleTimeout?: number } | undefined
    expect(opts?.idleTimeout).toBe(SERVE_IDLE_TIMEOUT_SECONDS)
  } finally {
    serveSpy.mockRestore()
  }
})
