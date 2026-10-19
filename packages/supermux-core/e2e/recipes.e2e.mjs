// The recipes of examples/recipes, run the way a reader runs them: with Node, in a git repository, against the stub
// agent CLIs of support/stub-agents.mjs. They import the built package: run `bun run build` first.
import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile, execFileSync, spawn } from "node:child_process"
import { once } from "node:events"
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { devNull, tmpdir } from "node:os"
import { dirname, join, sep } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { promisify } from "node:util"
import { fileURLToPath } from "node:url"
import { stubAgents } from "./support/stub-agents.mjs"

const run = promisify(execFile)
const recipe = name => fileURLToPath(new URL(`../examples/recipes/${name}`, import.meta.url))
// The stubs are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
// The test's own git commands leave the user's git config out (a signing or hook setting would change them).
const gitEnv = { ...process.env, GIT_CONFIG_GLOBAL: devNull, GIT_CONFIG_NOSYSTEM: "1" }
const git = (cwd, ...args) => execFileSync("git", args, { cwd, encoding: "utf8", env: gitEnv }).trim()

/**
 * Runs `body({ root, repo, tmp, stubs, env })` with a git repository of one commit, a TMPDIR of its own (what a recipe
 * makes in os.tmpdir() lands in `tmp`, to be found and removed), the stub agent CLIs, and `env` to run a recipe in. `root`
 * holds the other two, and is the place for a test's own files. `stubEnv`, or what it returns when it is a function of
 * `root`, is added to the stubs' environment. Whatever happens, all of it is removed afterwards.
 */
async function inRepo(stubEnv, body) {
  const root = mkdtempSync(join(tmpdir(), "recipes-e2e-"))
  let stubs
  try {
    const repo = join(root, "repo")
    const tmp = join(root, "tmp")
    mkdirSync(repo)
    mkdirSync(tmp)
    git(repo, "init", "-q", "-b", "main")
    writeFileSync(join(repo, "a.txt"), "a\n")
    git(repo, "add", ".")
    git(repo, "-c", "user.email=e2e@example.com", "-c", "user.name=e2e", "commit", "-qm", "init")
    stubs = stubAgents(typeof stubEnv === "function" ? stubEnv(root) : stubEnv)
    return await body({ root, repo, tmp, stubs, env: { ...stubs.env, TMPDIR: tmp } })
  } finally {
    stubs?.cleanup()
    rmSync(root, { recursive: true, force: true })
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

// Codex gets approvalPolicy "never" with the workspace-write sandbox, at launch and in each turn: its stub must expect that.
const RACE = { EXPECT_POLICY: "never", EXPECT_SANDBOX: "workspace-write", EXPECT_TURN_POLICY: "never" }

test("same-task-three-agents: each agent has a branch and a worktree of its own, and a line", { skip }, async () => {
  // The stubs write what they get to TRACE: Claude's command line is among it.
  await inRepo(root => ({ ...RACE, TRACE: join(root, "agents.trace") }), async ({ root, repo, tmp, stubs, env }) => {
    const args = [recipe("same-task-three-agents.ts"), "hello", "--agents", "claude,codex", "--repo", repo]
    // From a directory that is not the repository: --repo names it.
    const { stdout } = await run(process.execPath, args, { cwd: tmp, env, timeout: 60_000 })
    const lines = stdout.trim().split("\n")
    assert.equal(lines.length, 2, stdout)
    const worktrees = []
    for (const agent of ["claude", "codex"]) {
      const [, branch, worktree] = new RegExp(`^${agent}: no changes \\(branch (race/${agent}-\\d+), worktree (.+)\\)$`, "m").exec(stdout) ?? []
      assert.ok(branch, `no line for ${agent} in:\n${stdout}`)
      assert.ok(worktree.startsWith(tmp), `${worktree} is not under the temporary directory ${tmp}`)
      // Its branch is checked out in the worktree, made from the commit the repository is on.
      assert.equal(git(worktree, "rev-parse", "--abbrev-ref", "HEAD"), branch)
      assert.equal(git(worktree, "rev-parse", "HEAD"), git(repo, "rev-parse", "HEAD"))
      worktrees.push(worktree)
    }
    assert.deepEqual([...new Set(stubs.launched())].sort(), ["claude", "codex"])
    // Claude was told to accept edits, without a prompt for each.
    const launches = readFileSync(join(root, "agents.trace"), "utf8").split("\n").filter(Boolean).map(line => JSON.parse(line))
    const { argv } = launches.find(entry => entry.argv?.includes("--print"))
    assert.deepEqual(argv.slice(argv.indexOf("--permission-mode"), argv.indexOf("--permission-mode") + 2), ["--permission-mode", "acceptEdits"])
    // The worktrees stay, for the reader to look at; the sessions' state does not.
    assert.deepEqual(readdirSync(dirname(worktrees[0])).sort(), ["claude", "codex"])
  })
})

test("same-task-three-agents: a line says what the agent changed, a new file too (all three agents, in the current directory's repository)", { skip }, async () => {
  await inRepo(RACE, async ({ repo, stubs, env }) => {
    // Git runs this when it checks out a worktree: it stands in for the agent's edits, a modified file and a new one.
    writeFileSync(join(repo, ".git", "hooks", "post-checkout"), "#!/bin/sh\necho more >> a.txt\necho new > b.txt\n", { mode: 0o755 })
    const { stdout } = await run(process.execPath, [recipe("same-task-three-agents.ts"), "hello"], { cwd: repo, env, timeout: 60_000 })
    for (const agent of ["claude", "codex", "grok"]) {
      assert.match(stdout, new RegExp(`^${agent}: 2 files changed, 2 insertions\\(\\+\\) \\(branch race/${agent}-\\d+, worktree .+\\)$`, "m"))
    }
    assert.deepEqual([...new Set(stubs.launched())].sort(), ["claude", "codex", "grok"])
  })
})

test("same-task-three-agents: a directory that is not a git work tree is refused, before anything starts", { skip }, async () => {
  await inRepo({}, async ({ tmp, stubs, env }) => {
    await assert.rejects(
      run(process.execPath, [recipe("same-task-three-agents.ts"), "hello", "--repo", tmp], { cwd: tmp, env, timeout: 60_000 }),
      error => error.code === 1 && /is not a git work tree/.test(error.stderr),
    )
    assert.deepEqual(stubs.launched(), [])
    assert.deepEqual(readdirSync(tmp), [])
  })
})

test("ci-review: Codex reviews read-only, and the review is printed", { skip }, async () => {
  // Every message the Codex stub gets is written to TRACE, among them the review request.
  const policies = { EXPECT_POLICY: "never", EXPECT_SANDBOX: "read-only", EXPECT_TURN_POLICY: "never" }
  await inRepo(root => ({ ...policies, TRACE: join(root, "codex.trace") }), async ({ root, repo, tmp, stubs, env }) => {
    const { stdout } = await run(process.execPath, [recipe("ci-review.ts"), "--base", "HEAD"], { cwd: repo, env, timeout: 60_000 })
    // What the Codex stub answers, as it said it.
    assert.equal(stdout.trim(), "héllo")
    assert.deepEqual(stubs.launched(), ["codex"])
    const request = readFileSync(join(root, "codex.trace"), "utf8").split("\n").filter(Boolean).map(line => JSON.parse(line)).find(message => message.method === "turn/start")
    assert.match(request.params.input[0].text, /git diff HEAD\.\.\.HEAD/)
    assert.deepEqual(readdirSync(tmp), [], "the state directory was left behind")
  })
})

test("ci-review: exits 1 when the base is no commit, and when Codex cannot start", { skip }, async () => {
  // The stub's own policies (on-request, workspace-write) are not what the review runs with: it refuses to start.
  await inRepo({}, async ({ repo, stubs, env }) => {
    await assert.rejects(
      run(process.execPath, [recipe("ci-review.ts"), "--base", "no-such-branch"], { cwd: repo, env, timeout: 60_000 }),
      error => error.code === 1 && error.stdout === "" && /no-such-branch/.test(error.stderr),
    )
    assert.deepEqual(stubs.launched(), [], "Codex started for a base that is no commit")
    await assert.rejects(
      run(process.execPath, [recipe("ci-review.ts"), "--base", "HEAD"], { cwd: repo, env, timeout: 60_000 }),
      error => error.code === 1 && error.stdout === "" && /^No review: /m.test(error.stderr),
    )
    assert.deepEqual(stubs.launched(), ["codex"])
  })
})

test("deploy-tool: the agent calls a function of the recipe's own process", { skip }, async () => {
  // The Claude stub starts the MCP servers it is given, as Claude does, and calls the tool a prompt names.
  await inRepo({ FIXTURE_MCP: "1" }, async ({ repo, stubs, env }) => {
    const prompt = 'Call mcp__ops__deploy with {"environment":"staging"}'
    const { stdout } = await run(process.execPath, [recipe("deploy-tool.ts"), prompt], { cwd: repo, env, timeout: 60_000 })
    assert.match(stdout, /^deploy called by claude session \S+: staging$/m)
    // The tool's result went back to the agent, which says it.
    assert.match(stdout, /deployed to staging/)
    assert.deepEqual(stubs.launched(), ["claude"])
  })
})

test("deploy-tool: the tool takes staging only", { skip }, async () => {
  await inRepo({ FIXTURE_MCP: "1" }, async ({ repo, env }) => {
    const prompt = 'Call mcp__ops__deploy with {"environment":"production"}'
    const { stdout } = await run(process.execPath, [recipe("deploy-tool.ts"), prompt], { cwd: repo, env, timeout: 60_000 })
    assert.doesNotMatch(stdout, /deploy called by/)
    assert.match(stdout, /validation/i)
  })
})

test("deploy-tool: --agent codex runs the recipe on Codex", { skip }, async () => {
  await inRepo({}, async ({ repo, stubs, env }) => {
    const { stdout } = await run(process.execPath, [recipe("deploy-tool.ts"), "hello", "--agent", "codex"], { cwd: repo, env, timeout: 60_000 })
    assert.equal(stdout.trim(), "héllo")
    assert.deepEqual(stubs.launched(), ["codex"])
  })
})

// A recipe that is stopped ends its agents and removes its state directory before it exits (130 and 143 are what a shell
// reports for SIGINT and SIGTERM). The Claude stub keeps a "hang" turn running until it is interrupted; the Codex stub
// never answers its first message in MODE=setup-hang, which holds the recipe in its start.
for (const { name, args, stubEnv, working } of [
  { name: "same-task-three-agents.ts", args: repo => ["hang", "--agents", "claude", "--repo", repo], stubEnv: {}, working: '"hang"' },
  { name: "deploy-tool.ts", args: () => ["hang"], stubEnv: {}, working: '"hang"' },
  { name: "ci-review.ts", args: () => ["--base", "HEAD"], stubEnv: { MODE: "setup-hang" }, working: '"initialize"' },
]) {
  for (const [signal, status] of [["SIGTERM", 143], ["SIGINT", 130]]) {
    test(`${name} closes the core on ${signal}: no agent is left running`, { skip, timeout: 60_000 }, async () => {
      // The Claude and Codex stubs write what they get to TRACE, and their process id to PID_FILE.
      const files = root => ({ ...stubEnv, TRACE: join(root, "agent.trace"), PID_FILE: join(root, "agent.pid") })
      await inRepo(files, async ({ root, repo, tmp, env }) => {
        const trace = join(root, "agent.trace")
        const child = spawn(process.execPath, [recipe(name), ...args(repo)], { cwd: repo, env, stdio: ["ignore", "ignore", "pipe"] })
        try {
          let stderr = ""
          child.stderr.on("data", chunk => { stderr += chunk })
          const exited = once(child, "exit")
          const keepers = () => readdirSync(tmp, { recursive: true }).filter(path => path.split(sep).includes("keepers"))
          await until(() => existsSync(trace) && readFileSync(trace, "utf8").includes(working), "the agent to be at work")
          assert.notDeepEqual(keepers(), [], "the recipe has no keeper yet: the test would prove nothing")
          child.kill(signal)
          const [code, by] = await exited
          assert.deepEqual({ code, by }, { code: status, by: null }, `stderr: ${stderr}`)
          const pid = Number(readFileSync(join(root, "agent.pid"), "utf8"))
          await until(() => !alive(pid), `agent ${pid} to exit`, 5_000)
          assert.deepEqual(keepers(), [], "the state directory was left behind")
        } finally {
          child.kill("SIGKILL")
        }
      })
    })
  }
}
