import { describe, test, expect } from "bun:test"
import { Readable, Writable } from "stream"
import { JsonRpcClient } from "../../src/core/agents/codex/jsonrpc"

function pair(): { client: JsonRpcClient; serverInbox: any[]; emitToClient: (msg: any) => void } {
  const serverInbox: any[] = []

  const stdin = new Writable({
    write(chunk: Buffer, _enc, cb) {
      for (const line of chunk.toString("utf8").split("\n")) {
        if (line.trim()) serverInbox.push(JSON.parse(line))
      }
      cb()
    },
  })
  const stdout = new Readable({ read() {} })
  const client = new JsonRpcClient({ stdin: stdin as any, stdout: stdout as any })

  const emitToClient = (msg: any) => {
    stdout.push(Buffer.from(JSON.stringify(msg) + "\n", "utf8"))
  }
  return { client, serverInbox, emitToClient }
}

describe("JsonRpcClient", () => {
  test("request → matches response by id", async () => {
    const { client, serverInbox, emitToClient } = pair()
    const p = client.request("ping", { x: 1 })
    await new Promise(r => setImmediate(r))
    expect(serverInbox).toHaveLength(1)
    expect(serverInbox[0].method).toBe("ping")
    expect(serverInbox[0].params).toEqual({ x: 1 })
    const id = serverInbox[0].id
    emitToClient({ jsonrpc: "2.0", id, result: { pong: true } })
    const r = await p
    expect(r).toEqual({ pong: true })
  })

  test("notifications fire onNotification callback", async () => {
    const { client, emitToClient } = pair()
    const got: any[] = []
    client.onNotification((m) => got.push(m))
    emitToClient({ jsonrpc: "2.0", method: "turn/started", params: { x: 1 } })
    await new Promise(r => setImmediate(r))
    expect(got).toEqual([{ method: "turn/started", params: { x: 1 } }])
  })

  test("error response rejects the request", async () => {
    const { client, serverInbox, emitToClient } = pair()
    const p = client.request("boom", {})
    await new Promise(r => setImmediate(r))
    const id = serverInbox[0].id
    emitToClient({ jsonrpc: "2.0", id, error: { code: -1, message: "no" } })
    await expect(p).rejects.toThrow(/no/)
  })
})
