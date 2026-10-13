// A minimal host binary that embeds supermux-core the way supermux's src/cli.ts does: compiled with
// `bun build --compile` by compiled-entry.test.ts. Routes the core's subcommands, otherwise opens a
// keeper for the echo agent (AGENT_RUNTIME + ECHO_AGENT) and prints what the agent echoed.
import { CORE_SUBCOMMANDS, connectKeeper } from "../../src/index.js"

const sub = process.argv[2]
if (sub === CORE_SUBCOMMANDS.keeper) {
  await import("../../src/keeper/keeper.js")
} else if (sub === CORE_SUBCOMMANDS.bridge) {
  await import("../../src/mcp/bridge.js")
} else {
  const conn = await connectKeeper({
    stateDirectory: process.env.STATE_DIR!,
    sessionId: "compiled",
    spec: { command: process.env.AGENT_RUNTIME!, args: [process.env.ECHO_AGENT!], cwd: process.env.STATE_DIR!, env: { PATH: process.env.PATH ?? "" }, frameShape: "jsonrpc", captureStderr: false },
    limits: { maxFrameBytes: 65536, shutdownTimeoutMs: 500, parkedDeadlineMs: 5000, journalMaxBytes: 1 << 20, connectTimeoutMs: 5000 },
    cursor: 0,
  })
  conn.write(JSON.stringify({ cmd: "echo", n: 42 }))
  for await (const frame of conn.frames) {
    if (frame.type !== "frame") continue
    console.log(JSON.stringify({ welcome: conn.welcome.agentRunning, echoed: JSON.parse(frame.line).echoed }))
    break
  }
  await conn.shutdown()
  process.exit(0)
}
