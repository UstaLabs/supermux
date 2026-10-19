import { mkdir, writeFile } from "node:fs/promises"
import { createServer, type Socket } from "node:net"
import { join } from "node:path"

/**
 * A live keeper as a client finds it on re-attach (status, token, socket), standing in for one
 * whose agent kept working while no host was attached. It answers `hello` with the welcome AND
 * the replayed frames in a single write, so the frames are already queued when the client's
 * connect resolves: what a driver does with them before applying the welcome is deterministic.
 * Close the driver with mode "detach" (a shutdown would wait for this process to exit).
 */
export async function fakeKeeper(stateDirectory: string, sessionId: string, meta: Record<string, unknown>, frames: unknown[]) {
  const dir = join(stateDirectory, "keepers", sessionId)
  await mkdir(dir, { recursive: true })
  await writeFile(join(dir, "token"), "fake-token\n")
  await writeFile(join(dir, "status.json"), JSON.stringify({
    keeperPid: process.pid, agentPid: process.pid, startedAt: Date.now(),
    lastSeq: frames.length, firstSeq: 1, ackedSeq: 0, updatedAt: Date.now(), meta,
  }) + "\n")
  const sockets = new Set<Socket>()
  const server = createServer(sock => {
    sockets.add(sock)
    sock.on("close", () => sockets.delete(sock))
    sock.on("end", () => sock.end())
    let buffer = ""
    sock.on("data", chunk => {
      buffer += chunk
      for (let newline = buffer.indexOf("\n"); newline >= 0; newline = buffer.indexOf("\n")) {
        const message = JSON.parse(buffer.slice(0, newline))
        buffer = buffer.slice(newline + 1)
        if (message.type !== "hello") continue
        const welcome = { type: "welcome", lastSeq: frames.length, firstSeq: 1, ackedSeq: 0, agentRunning: true, agentExited: null, meta, maxRequestId: 0 }
        const replay = frames.map((frame, index) => ({ type: "frame", seq: index + 1, line: JSON.stringify(frame) }))
        sock.write([welcome, ...replay].map(m => JSON.stringify(m) + "\n").join(""))
      }
    })
  })
  await new Promise<void>(resolve => server.listen(join(dir, "keeper.sock"), resolve))
  return {
    async close() {
      for (const sock of sockets) sock.destroy()
      await new Promise<void>(resolve => server.close(() => resolve()))
    },
  }
}
