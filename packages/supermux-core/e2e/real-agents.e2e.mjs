import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile, spawn } from "node:child_process"
import { once } from "node:events"
import { existsSync, mkdtempSync, readdirSync, readFileSync, realpathSync, rmSync, statSync } from "node:fs"
import { homedir, tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { promisify } from "node:util"
import { fileURLToPath } from "node:url"
import { freeze, keepersIn, killAll } from "./support/keepers.mjs"
import { stubAgents } from "./support/stub-agents.mjs"

const run = promisify(execFile)
const example = fileURLToPath(new URL("../examples/real-agents.mjs", import.meta.url))
const cwd = fileURLToPath(new URL("..", import.meta.url))
// The stubs are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
// An agent's executable is its name, except Cursor's.
const executable = agent => (agent === "cursor" ? "cursor-agent" : agent)
const ended = completion => (completion.error ? `${completion.status}: ${completion.error}` : completion.status)
/** Where the example keeps what it makes: a folder of the home, with a directory of its own for each run. */
const examplesDir = home => join(home, ".supermux-examples")
/** The runs of the example that left their directory in `home`: none, when each removed it. */
const leftIn = home => (existsSync(examplesDir(home)) ? readdirSync(examplesDir(home)) : [])

/**
 * One run of the example on `agent` against the stubs, in `workdir`. It counts only if that agent's stub is what ran, and
 * the state directory of the run is gone afterwards. `home` is the stubs' empty one unless a test says another.
 */
async function turn(agent, prompt, { workdir = cwd, home } = {}) {
  const stubs = stubAgents()
  try {
    const args = [example, agent, ...(prompt === undefined ? [] : [prompt])]
    const env = home ? { ...stubs.env, HOME: home } : stubs.env
    const { stdout, stderr } = await run(process.execPath, args, { cwd: workdir, env, timeout: 60_000 })
    assert.deepEqual(leftIn(home ?? stubs.home), [], "the state directory was left behind")
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

test("examples/real-agents.mjs runs in the temporary directory: its state is not in it", { skip }, async () => {
  // HOME is outside the temporary directory, as a user's is. The state used to be made in the temporary directory, which a
  // working directory there contains: the core refused it (invalid_workdir).
  const home = mkdtempSync(join(homedir(), ".real-agents-e2e-"))
  try {
    const { result } = await turn("claude", undefined, { workdir: realpathSync(tmpdir()), home })
    assert.equal(result.completion.status, "completed", `the turn ended ${ended(result.completion)}`)
  } finally {
    rmSync(home, { recursive: true, force: true })
  }
})

test("examples/real-agents.mjs keeps its state under the home, for you alone, and closes the core on SIGTERM: no agent or state directory is left behind", { skip, timeout: 60_000 }, async () => {
  const work = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const trace = join(work, "agent.trace")
  const pidFile = join(work, "agent.pid")
  const stubs = stubAgents({ TRACE: trace, PID_FILE: pidFile })
  let child
  try {
    // TMPDIR is a directory of the test's own, which the example must make nothing in: its state is in the home (the stubs' empty one).
    child = spawn(process.execPath, [example, "claude", "hang"], { cwd, env: { ...stubs.env, TMPDIR: work }, stdio: ["ignore", "ignore", "pipe"] })
    let stderr = ""
    child.stderr.on("data", chunk => { stderr += chunk })
    const exited = once(child, "exit")
    // The fixture never finishes a `hang` turn: once it has the message, the turn is in flight.
    await until(() => existsSync(trace) && readFileSync(trace, "utf8").includes('"hang"'), "the agent to receive the prompt")
    const [made, ...more] = leftIn(stubs.home)
    assert.match(made, /^real-agents-\d+$/)
    assert.deepEqual(more, [])
    for (const dir of [examplesDir(stubs.home), join(examplesDir(stubs.home), made)]) assert.equal(statSync(dir).mode & 0o777, 0o700, dir)
    assert.deepEqual(readdirSync(work).filter(name => name.startsWith("supermux-real-")), [])
    child.kill("SIGTERM")
    const [code, signal] = await exited
    assert.deepEqual({ code, signal }, { code: 143, signal: null }, `stderr: ${stderr}`)
    assert.deepEqual(leftIn(stubs.home), [], "the state directory was left behind")
    const pid = Number(readFileSync(pidFile, "utf8"))
    await until(() => !alive(pid), `agent ${pid} to exit`, 5_000)
  } finally {
    child?.kill("SIGKILL")
    // A run that failed the test may have left its keeper and agent: they are this test's own, started moments ago.
    killAll(keepersIn(stubs.home))
    stubs.cleanup()
    rmSync(work, { recursive: true, force: true })
  }
})

test("examples/real-agents.mjs keeps the state of a keeper it could not close, and says where it is and how to stop it", { skip, timeout: 90_000 }, async () => {
  const work = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const trace = join(work, "agent.trace")
  const stubs = stubAgents({ TRACE: trace, PID_FILE: join(work, "agent.pid") })
  let child
  let keepers = []
  try {
    child = spawn(process.execPath, [example, "claude", "hang"], { cwd, env: stubs.env, stdio: ["ignore", "ignore", "pipe"] })
    let stderr = ""
    child.stderr.on("data", chunk => { stderr += chunk })
    const exited = once(child, "exit")
    await until(() => existsSync(trace) && readFileSync(trace, "utf8").includes('"hang"'), "the agent to receive the prompt")
    await until(() => (keepers = keepersIn(examplesDir(stubs.home))).length > 0, "the keeper's status")
    // A keeper that does not answer: the core cannot confirm it gone, so its close fails, with the agent still running.
    freeze(keepers)
    child.kill("SIGTERM")
    const [code] = await exited
    assert.equal(code, 143, `stderr: ${stderr}`)
    // What a keeper that may still run reads and writes is not removed from under it.
    const [left, ...more] = leftIn(stubs.home)
    assert.match(left ?? "", /^real-agents-\d+$/, `the state directory of a keeper that may still run was removed; stderr: ${stderr}`)
    assert.deepEqual(more, [])
    const dir = join(examplesDir(stubs.home), left)
    assert.deepEqual(keepersIn(dir).map(keeper => keeper.keeperPid), keepers.map(keeper => keeper.keeperPid))
    // Where it is, which processes to stop and where their pids are, and how to remove it.
    assert.ok(stderr.includes(`left in ${dir}`), `stderr does not say where the state is (${dir}): ${stderr}`)
    assert.match(stderr, /kill the processes named by keeperPid and agentPid in keepers\/\*\/status\.json/)
    assert.ok(stderr.includes(`rm -r ${JSON.stringify(dir)}`), `stderr does not say how to remove it: ${stderr}`)
  } finally {
    killAll(keepers)
    child?.kill("SIGKILL")
    stubs.cleanup()
    rmSync(work, { recursive: true, force: true })
  }
})
