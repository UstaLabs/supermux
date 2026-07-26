import { test, expect } from "bun:test"
import { AcpClient } from "./acp-client"

function makeClient() {
  const written: string[] = []
  const client = new AcpClient((line) => written.push(line))
  return { client, written }
}

test("request() sends JSON-RPC with incrementing id and resolves on matching response", async () => {
  const { client, written } = makeClient()
  const p = client.request("initialize", { protocolVersion: 1 })
  expect(JSON.parse(written[0]!)).toEqual({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: 1 } })
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } }) + "\n")
  expect(await p).toEqual({ protocolVersion: 1 })
})

test("notifications are dispatched to onNotification", () => {
  const { client } = makeClient()
  const seen: any[] = []
  client.onNotification = (method, params) => seen.push({ method, params })
  client.feed(JSON.stringify({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk" } } }) + "\n")
  expect(seen).toEqual([{ method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk" } } }])
})

test("partial lines buffer across feeds", async () => {
  const { client } = makeClient()
  const p = client.request("x", {})
  const full = JSON.stringify({ jsonrpc: "2.0", id: 1, result: "ok" }) + "\n"
  client.feed(full.slice(0, 5))
  client.feed(full.slice(5))
  expect(await p).toBe("ok")
})

test("server->client request is answered by onServerRequest handler", async () => {
  const { client, written } = makeClient()
  client.onServerRequest = async (method, params) => {
    expect(method).toBe("session/request_permission")
    return { outcome: { outcome: "selected", optionId: "allow" } }
  }
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 7, method: "session/request_permission", params: { toolCall: {} } }) + "\n")
  await new Promise((r) => setTimeout(r, 0))
  expect(JSON.parse(written[0]!)).toEqual({ jsonrpc: "2.0", id: 7, result: { outcome: { outcome: "selected", optionId: "allow" } } })
})

test("a rejected response rejects the request promise", async () => {
  const { client } = makeClient()
  const p = client.request("bad", {})
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 1, error: { code: -32602, message: "Invalid params" } }) + "\n")
  await expect(p).rejects.toThrow("Invalid params")
})

test("formatJsonRpcError folds data into the message", async () => {
  const { formatJsonRpcError } = await import("./acp-client")
  expect(formatJsonRpcError({ message: "rate limited", data: "retry in 30s" })).toBe("rate limited: retry in 30s")
  expect(formatJsonRpcError({ message: "boom", data: { code: "AUTH" } })).toBe('boom: {"code":"AUTH"}')
  expect(formatJsonRpcError({ message: "same", data: "same" })).toBe("same")
  expect(formatJsonRpcError({})).toBe("jsonrpc error")
})

test("a rejected response with data rejects with the full diagnostic", async () => {
  const { client } = makeClient()
  const p = client.request("bad", {})
  client.feed(JSON.stringify({
    jsonrpc: "2.0", id: 1,
    error: { code: -32000, message: "Unauthorized", data: "token expired" },
  }) + "\n")
  await expect(p).rejects.toThrow("Unauthorized: token expired")
})
