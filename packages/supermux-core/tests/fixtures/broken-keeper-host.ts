// A host binary whose keeper cannot load: its keeper subcommand fails the way a stale checkout's did (an import that does
// not resolve) and exits before any socket, as the live broker's keepers did after a fast-forward without `bun install`.
// Otherwise it opens a keeper and prints the error that start ended with.
import { CORE_SUBCOMMANDS, connectKeeper } from "../../src/index.js"

if (process.argv[2] === CORE_SUBCOMMANDS.keeper) {
  process.stderr.write("error: Cannot find package '@supermux/platform' from 'keeper.ts'\n")
  process.exit(1)
}
try {
  await connectKeeper({
    stateDirectory: process.env.STATE_DIR!,
    sessionId: "broken",
    spec: { command: process.execPath, args: [], cwd: process.env.STATE_DIR!, env: { PATH: process.env.PATH ?? "" }, frameShape: "jsonrpc", captureStderr: false },
    limits: { maxFrameBytes: 65536, shutdownTimeoutMs: 500, parkedDeadlineMs: 5000, journalMaxBytes: 1 << 20, connectTimeoutMs: 5000 },
    cursor: 0,
  })
  console.log("connected")
} catch (error) {
  console.log(error instanceof Error ? error.message : String(error))
}
