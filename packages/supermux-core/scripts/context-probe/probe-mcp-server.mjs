// Stdio MCP probe server for scripts/context-probe.ts, built on @modelcontextprotocol/server v2.
// It is copied next to a scratch node_modules that has the SDK (never the repo's package.json).
//
// env: PROBE_LOG (jsonl file), PROBE_SERVER (name), PROBE_WORD (returned by get_code_word),
//      PROBE_LATE_WORD (returned by get_late_code_word once PROBE_LOG + ".late" exists).
// Every inbound JSON-RPC message and every outbound response to `initialize` is logged, so
// the probe can read the client's protocolVersion/clientInfo and the negotiated version.
import { appendFileSync, existsSync } from "node:fs"
import { McpServer, SUPPORTED_PROTOCOL_VERSIONS } from "@modelcontextprotocol/server"
import { StdioServerTransport } from "@modelcontextprotocol/server/stdio"
import * as z from "zod"

const LOG = process.env.PROBE_LOG
const NAME = process.env.PROBE_SERVER ?? "probe"
const log = (entry) => { if (LOG) appendFileSync(LOG, JSON.stringify({ t: Date.now(), pid: process.pid, server: NAME, ...entry }) + "\n") }
log({ event: "start", sdkSupported: SUPPORTED_PROTOCOL_VERSIONS, ppid: process.ppid })

const server = new McpServer({ name: NAME, version: "0.0.1" }, { capabilities: { tools: { listChanged: true } } })
server.registerTool("get_code_word", {
  description: `Returns the probe code word of the ${NAME} server.`,
  inputSchema: z.object({}),
}, async () => {
  log({ event: "call", tool: "get_code_word" })
  return { content: [{ type: "text", text: `The code word is ${process.env.PROBE_WORD}` }] }
})

let late = false
const addLate = (connected) => {
  late = true
  server.registerTool("get_late_code_word", {
    description: `Returns the late probe code word of the ${NAME} server.`,
    inputSchema: z.object({}),
  }, async () => {
    log({ event: "call", tool: "get_late_code_word" })
    return { content: [{ type: "text", text: `The late code word is ${process.env.PROBE_LATE_WORD}` }] }
  })
  if (connected) server.sendToolListChanged()
  log({ event: "late-tool-registered", connected })
}
if (LOG && existsSync(LOG + ".late")) addLate(false)
const transport = new StdioServerTransport()
await server.connect(transport)
const inbound = transport.onmessage
transport.onmessage = (message, extra) => {
  log({ event: "in", method: message.method, id: message.id, params: message.method === "initialize" ? message.params : undefined })
  inbound?.(message, extra)
}
const send = transport.send.bind(transport)
transport.send = async (message, options) => {
  if (message.result && message.result.protocolVersion) log({ event: "initialize-result", result: message.result })
  if (message.error) log({ event: "out-error", error: message.error })
  if (message.method) log({ event: "out", method: message.method })
  return send(message, options)
}

const timer = setInterval(() => {
  if (late || !LOG || !existsSync(LOG + ".late")) return
  addLate(true)
}, 300)
timer.unref?.()
process.stdin.on("end", () => { log({ event: "stdin-end" }); process.exit(0) })
