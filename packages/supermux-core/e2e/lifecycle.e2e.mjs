// examples/lifecycle.mjs on the generic ACP agent fixture: a turn, a resume, a request nobody can answer, a signal and a
// close that fails. It imports the built package: run `bun run build` first.
import { test } from "node:test"
import assert from "node:assert/strict"
import { spawn } from "node:child_process"
import { once } from "node:events"
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { fileURLToPath } from "node:url"
import { freeze, keepersIn, killAll } from "./support/keepers.mjs"

const example = fileURLToPath(new URL("../examples/lifecycle.mjs", import.meta.url))
const cwd = fileURLToPath(new URL("..", import.meta.url))
const fixture = fileURLToPath(new URL("../tests/fixtures/acp-agent.mjs", import.meta.url))

// The example sends "Hello" and nothing else. This script runs between the keeper and the fixture, and has the fixture be
// asked PROXY_PROMPT instead, so the turn can be one the fixture has ("permission", "hang", ...). It notes in PROMPTED that
// the agent has been asked.
const ASK = `
import { spawn } from "node:child_process"
import { appendFileSync } from "node:fs"
import { createInterface } from "node:readline"
const child = spawn(process.execPath, process.argv.slice(2), { stdio: ["pipe", "pipe", "inherit"] })
createInterface({ input: process.stdin }).on("line", line => {
  try {
    const message = JSON.parse(line)
    if (message.method === "session/prompt") {
      if (process.env.PROXY_PROMPT !== undefined) message.params.prompt[0].text = process.env.PROXY_PROMPT
      if (process.env.PROMPTED) appendFileSync(process.env.PROMPTED, "asked\\n")
      line = JSON.stringify(message)
    }
  } catch {}
  child.stdin.write(line + "\\n")
}).on("close", () => child.stdin.end())
createInterface({ input: child.stdout }).on("line", line => process.stdout.write(line + "\\n"))
for (const signal of ["SIGINT", "SIGTERM"]) process.on(signal, () => child.kill(signal))
child.on("close", (code, signal) => process.exit(code ?? (signal ? 1 : 0)))
`

/** The state of a run: a folder of the home, with a directory of its own for each run. */
const examplesDir = home => join(home, ".supermux-examples")
const leftIn = home => (existsSync(examplesDir(home)) ? readdirSync(examplesDir(home)) : [])
const alive = pid => {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return error.code === "EPERM"
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

/**
 * Runs `body({ home, work, start })` with a HOME and a TMPDIR of the test's own, both removed afterwards. `start(env)` runs
 * the example on the fixture, behind the script that asks it `env.PROXY_PROMPT` (when given): the child process, and what it
 * has written to stderr and stdout so far. The fixture writes its pid to `work/agent.pid`.
 */
async function inHome(body) {
  const work = mkdtempSync(join(tmpdir(), "lifecycle-e2e-"))
  const home = join(work, "home")
  const tmp = join(work, "tmp")
  mkdirSync(home)
  mkdirSync(tmp)
  writeFileSync(join(work, "ask.mjs"), ASK)
  const children = []
  try {
    const start = (env = {}) => {
      const child = spawn(process.execPath, [example, process.execPath, join(work, "ask.mjs"), fixture], {
        cwd,
        env: { ...process.env, HOME: home, TMPDIR: tmp, PID_FILE: join(work, "agent.pid"), PROMPTED: join(work, "prompted"), ...env },
        stdio: ["ignore", "pipe", "pipe"],
      })
      children.push(child)
      const run = { stdout: "", stderr: "", exited: once(child, "exit"), child }
      child.stdout.on("data", chunk => { run.stdout += chunk })
      child.stderr.on("data", chunk => { run.stderr += chunk })
      return run
    }
    return await body({ home, tmp, work, start })
  } finally {
    for (const child of children) child.kill("SIGKILL")
    // A run that did not end well may have left its keeper and agent: they are this test's own, started moments ago.
    killAll(keepersIn(home))
    rmSync(work, { recursive: true, force: true })
  }
}

/** Runs the example to its end (a run that is not over in `ms` is killed, and its code is null). */
async function finished(start, env, ms = 30_000) {
  const run = start(env)
  const timer = setTimeout(() => run.child.kill("SIGKILL"), ms)
  const [code, signal] = await run.exited
  clearTimeout(timer)
  return { code, signal, stdout: run.stdout, stderr: run.stderr }
}

test("examples/lifecycle.mjs runs a turn and resumes the session; its state is under the home and gone afterwards", async () => {
  await inHome(async ({ home, tmp, start }) => {
    const { code, stdout, stderr } = await finished(start)
    assert.equal(code, 0, stderr)
    assert.match(stdout, /Completion: \{ status: 'completed'/)
    assert.match(stdout, /Restored:/)
    assert.deepEqual(leftIn(home), [], "the state directory was left behind")
    // The core puts a socket whose path is too long for the state directory in the temporary directory: its doing, not the example's.
    assert.deepEqual(readdirSync(tmp).filter(name => !/^supermux-(keepers|mcp)-/.test(name)), [], "something was made in the temporary directory")
  })
})

test("examples/lifecycle.mjs refuses a permission request, and the turn goes on", async () => {
  // The fixture's permission-execute request offers an allow and a reject option, and writes the answer it gets to TRACE.
  await inHome(async ({ work, start }) => {
    const trace = join(work, "answers.trace")
    const { code, stdout, stderr } = await finished(start, { PROXY_PROMPT: "permission-execute", TRACE: trace })
    assert.equal(code, 0, stderr)
    assert.match(stderr, /^refused: Run$/m)
    assert.match(stdout, /Completion: \{ status: 'completed'/)
    const answers = readFileSync(trace, "utf8").split("\n").filter(Boolean).map(line => JSON.parse(line)).filter(entry => entry?.outcome)
    assert.deepEqual(answers, [{ outcome: { outcome: "selected", optionId: "reject" } }])
  })
})

test("examples/lifecycle.mjs ends a turn that waits on a permission it cannot refuse", async () => {
  // The fixture's permission request offers an allow option and nothing to refuse with.
  await inHome(async ({ start }) => {
    const { code, stdout, stderr } = await finished(start, { PROXY_PROMPT: "permission" })
    assert.equal(code, 0, stderr)
    assert.match(stderr, /^refused: Read$/m)
    assert.match(stdout, /Completion: \{ status: 'cancelled'/)
  })
})

for (const [signal, status] of [["SIGTERM", 143], ["SIGINT", 130]]) {
  test(`examples/lifecycle.mjs closes the core on ${signal}: no agent or state directory is left behind`, { timeout: 60_000 }, async () => {
    await inHome(async ({ home, work, start }) => {
      const run = start({ PROXY_PROMPT: "hang" })
      // The fixture never finishes a `hang` turn: once it has been asked, the turn is in flight.
      await until(() => existsSync(join(work, "prompted")), "the agent to be asked")
      // The state is a folder of this run's own, under the home, for the user alone.
      const [made, ...more] = leftIn(home)
      assert.match(made, /^lifecycle-\d+$/)
      assert.deepEqual(more, [])
      for (const dir of [examplesDir(home), join(examplesDir(home), made)]) assert.equal(statSync(dir).mode & 0o777, 0o700, dir)
      run.child.kill(signal)
      const [code, by] = await run.exited
      assert.deepEqual({ code, by }, { code: status, by: null }, `stderr: ${run.stderr}`)
      await until(() => !alive(Number(readFileSync(join(work, "agent.pid"), "utf8"))), "the agent to exit", 5_000)
      assert.deepEqual(leftIn(home), [], "the state directory was left behind")
    })
  })
}

test("examples/lifecycle.mjs keeps the state of a keeper it could not close, and says where it is and how to stop it", { timeout: 90_000 }, async () => {
  await inHome(async ({ home, work, start }) => {
    let keepers = []
    try {
      const run = start({ PROXY_PROMPT: "hang" })
      await until(() => existsSync(join(work, "prompted")), "the agent to be asked")
      await until(() => (keepers = keepersIn(examplesDir(home))).length > 0, "the keeper's status")
      // A keeper that does not answer: the core cannot confirm it gone, so its close fails, with the agent still running.
      freeze(keepers)
      run.child.kill("SIGTERM")
      const [code] = await run.exited
      assert.equal(code, 143, `stderr: ${run.stderr}`)
      const [left, ...more] = leftIn(home)
      assert.match(left ?? "", /^lifecycle-\d+$/, `the state directory of a keeper that may still run was removed; stderr: ${run.stderr}`)
      assert.deepEqual(more, [])
      const dir = join(examplesDir(home), left)
      assert.deepEqual(keepersIn(dir).map(keeper => keeper.keeperPid), keepers.map(keeper => keeper.keeperPid))
      assert.ok(run.stderr.includes(`left in ${dir}`), `stderr does not say where the state is (${dir}): ${run.stderr}`)
      assert.match(run.stderr, /kill the processes named by keeperPid and agentPid in keepers\/\*\/status\.json/)
      assert.ok(run.stderr.includes(`rm -r ${JSON.stringify(dir)}`), `stderr does not say how to remove it: ${run.stderr}`)
    } finally {
      killAll(keepers)
    }
  })
})
