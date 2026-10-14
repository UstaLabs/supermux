// scripts/smoke-core-entries.ts — proves a compiled supermux binary can start supermux-core's helper
// processes. usage: bun scripts/smoke-core-entries.ts <binary>
//
// v0.12.0 shipped a binary in which the core resolved its keeper as a sibling `keeper.js` file that
// a compiled binary does not have: every session start/resume failed with "keeper entry not found"
// while the boot/PWA smoke checks all passed. The core now launches these helpers as
// `<binary> __core-keeper` / `<binary> __core-bridge`; this probe runs both against the binary:
//   1. `__core-keeper` starts a keeper (agent = the echo fixture), which accepts a hello with its
//      token, answers with a welcome, and relays one frame to the agent and its answer back.
//   2. `__core-bridge` is routed (it reaches the bridge's own usage error, not "unknown subcommand").
import { spawn, spawnSync } from "node:child_process"
import { existsSync, mkdtempSync, rmSync } from "node:fs"
import { createConnection } from "node:net"
import { tmpdir } from "node:os"
import { join, resolve } from "node:path"
import { fileURLToPath } from "node:url"
import { createInterface } from "node:readline"
import { CORE_SUBCOMMANDS } from "../packages/supermux-core/src/self-entry"
import { KEEPER_ENV } from "../packages/supermux-core/src/keeper/protocol"

const bin = resolve(process.argv[2] ?? "")
if (!process.argv[2] || !existsSync(bin)) { console.error("usage: bun scripts/smoke-core-entries.ts <binary>"); process.exit(2) }
const echoAgent = fileURLToPath(new URL("../packages/supermux-core/tests/fixtures/echo-agent.mjs", import.meta.url))

function fail(message: string): never { console.error(`SMOKE FAIL: ${message}`); process.exit(1) }

async function waitFor(path: string, ms: number) {
  const start = Date.now()
  while (!existsSync(path)) {
    if (Date.now() - start > ms) return false
    await new Promise(r => setTimeout(r, 25))
  }
  return true
}

async function keeper() {
  const dir = mkdtempSync(join(tmpdir(), "supermux-smoke-keeper-"))
  const token = "smoke-token"
  const child = spawn(bin, [CORE_SUBCOMMANDS.keeper], {
    stdio: ["ignore", "ignore", "pipe"],
    env: {
      ...process.env,
      [KEEPER_ENV.sessionDir]: dir,
      [KEEPER_ENV.command]: process.execPath,
      [KEEPER_ENV.args]: JSON.stringify([echoAgent]),
      [KEEPER_ENV.cwd]: dir,
      [KEEPER_ENV.agentEnv]: JSON.stringify({ PATH: process.env.PATH ?? "" }),
      [KEEPER_ENV.token]: token,
      [KEEPER_ENV.limits]: JSON.stringify({ maxFrameBytes: 65536, shutdownTimeoutMs: 500, parkedDeadlineMs: 5000, journalMaxBytes: 1 << 20, connectTimeoutMs: 5000 }),
      [KEEPER_ENV.frameShape]: "jsonrpc",
      [KEEPER_ENV.captureStderr]: "false",
    },
  })
  let stderr = ""
  child.stderr!.on("data", chunk => { stderr += chunk })
  try {
    if (!await waitFor(join(dir, "keeper.sock"), 10_000)) fail(`\`${CORE_SUBCOMMANDS.keeper}\` never opened its socket. stderr: ${stderr.trim()}`)
    const sock = createConnection(join(dir, "keeper.sock"))
    const lines = createInterface({ input: sock })[Symbol.asyncIterator]()
    const next = async (what: string) => {
      const step = await Promise.race([lines.next(), new Promise<never>((_, reject) => setTimeout(() => reject(new Error(`timed out waiting for ${what}`)), 5000))])
      if (step.done) fail(`keeper closed the socket before ${what}`)
      return JSON.parse(step.value)
    }
    sock.write(JSON.stringify({ type: "hello", token, cursor: 0 }) + "\n")
    const welcome = await next("the welcome")
    if (welcome.type !== "welcome" || welcome.agentRunning !== true) fail(`expected a welcome with a running agent, got ${JSON.stringify(welcome)}`)
    sock.write(JSON.stringify({ type: "frame", line: JSON.stringify({ cmd: "echo", n: 7 }) }) + "\n")
    let frame
    do frame = await next("the agent's answer"); while (frame.type !== "frame")
    if (JSON.parse(frame.line).echoed !== 7) fail(`expected the agent's echo, got ${JSON.stringify(frame)}`)
    sock.write(JSON.stringify({ type: "shutdown" }) + "\n")
    sock.end()
    console.log("PASS: keeper starts from the binary and relays frames")
  } finally {
    child.kill("SIGKILL")
    rmSync(dir, { recursive: true, force: true })
  }
}

function bridge() {
  const run = spawnSync(bin, [CORE_SUBCOMMANDS.bridge], { encoding: "utf8", input: "", timeout: 10_000, env: { PATH: process.env.PATH ?? "" } })
  const out = `${run.stdout}${run.stderr}`
  if (/unknown subcommand/.test(out) || !/supermux-mcp-bridge/.test(out)) fail(`\`${CORE_SUBCOMMANDS.bridge}\` is not routed to the bridge. output: ${out.trim()}`)
  console.log("PASS: bridge is routed by the binary")
}

await keeper()
bridge()
process.exit(0)
