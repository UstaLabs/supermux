import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile, spawn } from "node:child_process"
import { once } from "node:events"
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { promisify } from "node:util"
import { fileURLToPath } from "node:url"
import { stubAgents } from "./support/stub-agents.mjs"

const run = promisify(execFile)
const example = fileURLToPath(new URL("../examples/real-agents.mjs", import.meta.url))
const cwd = fileURLToPath(new URL("..", import.meta.url))
// The stubs are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
// An agent's executable is its name, except Cursor's.
const executable = agent => (agent === "cursor" ? "cursor-agent" : agent)
const ended = completion => (completion.error ? `${completion.status}: ${completion.error}` : completion.status)

/** One run of the example on `agent` against the stubs. It counts only if that agent's stub is what ran. */
async function turn(agent, prompt) {
  const stubs = stubAgents()
  try {
    const args = [example, agent, ...(prompt === undefined ? [] : [prompt])]
    const { stdout, stderr } = await run(process.execPath, args, { cwd, env: stubs.env, timeout: 60_000 })
    const launched = stubs.launched()
    assert.ok(launched.includes(executable(agent)), `the ${executable(agent)} stub never ran (launched: ${launched.join(", ") || "nothing"}), so it did not give the answer`)
    const result = JSON.parse(stdout.trim().split("\n").at(-1))
    assert.equal(result.agent, agent)
    return { result, stderr }
  } finally {
    stubs.cleanup()
  }
}

async function until(check, what, ms = 15_000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (await check()) return
    await sleep(25)
  }
  assert.fail(`timed out waiting for ${what}`)
}

const alive = pid => {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return error.code === "EPERM"
  }
}

for (const agent of ["claude", "codex", "grok", "opencode", "cursor"]) {
  test(`examples/real-agents.mjs runs a turn on ${agent}`, { skip }, async () => {
    const { result, stderr } = await turn(agent)
    assert.equal(result.completion.status, "completed", `the turn ended ${ended(result.completion)}`)
    assert.ok(stderr === "" || stderr.endsWith("\n"), `the streamed text does not end in a newline: ${JSON.stringify(stderr)}`)
  })
}

test("examples/real-agents.mjs refuses a permission request, and the turn goes on", { skip }, async () => {
  const { result, stderr } = await turn("claude", "permission")
  assert.equal(result.completion.status, "completed", `the turn ended ${ended(result.completion)}`)
  assert.match(stderr, /^refused permission request: Bash "pwd"$/m)
})

test("examples/real-agents.mjs declines a question, and the turn goes on", { skip }, async () => {
  const { result, stderr } = await turn("claude", "ask-user-question")
  assert.equal(result.completion.status, "completed", `the turn ended ${ended(result.completion)}`)
  assert.match(stderr, /^declined question: "Favorite color\?"$/m)
})

test("examples/real-agents.mjs ends a turn that waits on a permission it cannot refuse", { skip }, async () => {
  // The ACP fixture's `permission` request offers an allow option and nothing to refuse with.
  const { result, stderr } = await turn("opencode", "permission")
  assert.equal(result.completion.status, "cancelled", `the turn ended ${ended(result.completion)}`)
  assert.match(stderr, /^cannot refuse permission request: Read; interrupting the turn$/m)
})

test("examples/real-agents.mjs closes the core on SIGTERM: no agent or state directory is left behind", { skip, timeout: 60_000 }, async () => {
  const work = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const trace = join(work, "agent.trace")
  const pidFile = join(work, "agent.pid")
  const stubs = stubAgents({ TRACE: trace, PID_FILE: pidFile })
  let child
  try {
    // The example makes its state directory under TMPDIR, so here it can be found afterwards.
    child = spawn(process.execPath, [example, "claude", "hang"], { cwd, env: { ...stubs.env, TMPDIR: work }, stdio: ["ignore", "ignore", "pipe"] })
    let stderr = ""
    child.stderr.on("data", chunk => { stderr += chunk })
    const exited = once(child, "exit")
    // The fixture never finishes a `hang` turn: once it has the message, the turn is in flight.
    await until(() => existsSync(trace) && readFileSync(trace, "utf8").includes('"hang"'), "the agent to receive the prompt")
    child.kill("SIGTERM")
    const [code, signal] = await exited
    assert.deepEqual({ code, signal }, { code: 143, signal: null }, `stderr: ${stderr}`)
    assert.deepEqual(readdirSync(work).filter(name => name.startsWith("supermux-real-")), [], "the state directory was left behind")
    const pid = Number(readFileSync(pidFile, "utf8"))
    await until(() => !alive(pid), `agent ${pid} to exit`, 5_000)
  } finally {
    child?.kill("SIGKILL")
    stubs.cleanup()
    rmSync(work, { recursive: true, force: true })
  }
})
