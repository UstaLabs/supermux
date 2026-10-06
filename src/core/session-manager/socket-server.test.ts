import { describe, test, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { connect } from "net"
import { ShimLiveness, startSocketServer } from "./socket-server"
import { encodeFrame } from "../../shared/frame-codec"

function wait(ms: number) { return new Promise((r) => setTimeout(r, ms)) }

test("a non-pong frame marks the session connected (liveness on any frame)", async () => {
  const dir = mkdtempSync(join(tmpdir(), "sock-"))
  const events: Array<[string, boolean]> = []
  const server = await startSocketServer({
    socketsDir: dir,
    onStatusChange: (sid, connected) => events.push([sid, connected]),
    handler: {
      onRegister: async (m) => ({ name: "n", session_id: m.session_id }),
      onOutbound: async () => ({ ok: true }),
      onOrchestration: async () => ({ ok: true }),
    },
  })
  await server.bind("s1")
  const c = connect(join(dir, "s1.sock"))
  await new Promise((r) => c.once("connect", r))
  c.write(encodeFrame({ kind: "register", workdir: "/tmp", pid: 1 }))
  await wait(150)
  expect(events.some(([sid, up]) => sid === "s1" && up)).toBe(true)
  c.destroy(); await server.close()
})

// ── sleep is not silence (spec "Wake reconnect, sleep ≠ idle") ───────────────────────────────

describe("ShimLiveness", () => {
  test("a shim silent past 45 s on regular ticks is stale", () => {
    const l = new ShimLiveness()
    let t = 0
    l.tick(t, ["s"])
    expect(l.markAlive("s", t)).toBe(true) // first frame: the alive edge
    for (let i = 0; i < 3; i++) expect(l.tick((t += 15_000), ["s"])).toEqual([])
    expect(l.tick((t += 15_000), ["s"])).toEqual([{ session_id: "s", lastPong: 0 }])
    expect(l.markAlive("s", t)).toBe(true) // back from stale
    expect(l.markAlive("s", t + 1_000)).toBe(false)
  })

  test("a tick after an hour-long sleep shifts the baselines instead of marking every session dead", () => {
    const l = new ShimLiveness()
    let t = 0
    l.tick(t, ["a", "b"])
    l.markAlive("a", t)
    l.markAlive("b", t + 10_000)
    l.tick((t += 15_000), ["a", "b"])
    expect(l.tick((t += 3_600_000), ["a", "b"])).toEqual([])
    expect(l.get("a")).toBe(3_600_000 - 15_000)
    // Shims that stay silent after the wake still go stale on the normal schedule.
    expect(l.tick((t += 15_000), ["a", "b"])).toEqual([])
    expect(l.tick((t += 15_000), ["a", "b"]).map((x) => x.session_id)).toEqual(["a", "b"])
  })
})
