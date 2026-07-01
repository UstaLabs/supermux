import { test, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { connect } from "net"
import { startSocketServer } from "./socket-server"
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
