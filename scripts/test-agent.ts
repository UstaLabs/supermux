import { connectShim, type ShimClient } from "../src/shim/socket-client"

const socketsDir = process.env.MUX_SOCKETS_DIR
const sessionId = process.env.MUX_TEST_SESSION_ID
const sessionName = process.env.MUX_TEST_SESSION_NAME
const workdir = process.env.MUX_TEST_WORKDIR
if (!socketsDir || !sessionId || !sessionName || !workdir) {
  throw new Error("test agent requires sockets, session id/name, and workdir")
}

let client: ShimClient | undefined
client = await connectShim({
  socketsDir,
  sessionId,
  requestedName: sessionName,
  workdir,
  pid: process.pid,
  channelOnly: true,
  onInbound: ({ content, meta }) => {
    const chatId = meta.chat_id
    if (!client || !chatId) return
    void client.callOutbound({
      name: "reply",
      args: {
        chat_id: chatId,
        text: `Fixture reply: ${content}`,
      },
    }).then((result) => {
      if (!result.ok) {
        console.error(`test agent reply failed: ${result.error ?? "unknown error"}`)
      }
    })
  },
})

console.log(JSON.stringify({ ready: true, sessionId, sessionName }))

let closing = false
async function close(): Promise<void> {
  if (closing) return
  closing = true
  await client?.close()
  process.exit(0)
}
process.on("SIGTERM", () => { void close() })
process.on("SIGINT", () => { void close() })
await new Promise<void>(() => {})
