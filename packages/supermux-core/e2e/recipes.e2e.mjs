// The recipes of examples/recipes, run the way a reader runs them: with Node, in a git repository, against the stub
// agent CLIs of support/stub-agents.mjs. They import the built package: run `bun run build` first.
import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile, execFileSync, spawn } from "node:child_process"
import { createHash } from "node:crypto"
import { once } from "node:events"
import { existsSync, mkdirSync, mkdtempSync, readdirSync, readFileSync, rmSync, statSync, symlinkSync, writeFileSync } from "node:fs"
import { devNull, homedir, tmpdir } from "node:os"
import { basename, dirname, join, sep } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { promisify } from "node:util"
import { fileURLToPath } from "node:url"
import { freeze, keepersIn, killAll } from "./support/keepers.mjs"
import { stubAgents } from "./support/stub-agents.mjs"

const run = promisify(execFile)
const recipe = name => fileURLToPath(new URL(`../examples/recipes/${name}`, import.meta.url))
const rewritePrompt = fileURLToPath(new URL("./support/rewrite-prompt.mjs", import.meta.url))
// The stubs are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
// The test's own git commands leave the user's git config out (a signing or hook setting would change them).
const gitEnv = { ...process.env, GIT_CONFIG_GLOBAL: devNull, GIT_CONFIG_NOSYSTEM: "1" }
const git = (cwd, ...args) => execFileSync("git", args, { cwd, encoding: "utf8", env: gitEnv }).trim()
/** `value` as one sh word. */
const quote = value => `'${String(value).replaceAll("'", `'\\''`)}'`
/** What a stub wrote to its TRACE file: one JSON value per line. */
const traced = path => readFileSync(path, "utf8").split("\n").filter(Boolean).map(line => JSON.parse(line))

/**
 * Runs `body({ root, repo, tmp, stubs, env })` with a git repository of one commit, a TMPDIR of its own (a recipe must
 * make nothing there), the stub agent CLIs, and `env` to run a recipe in. The recipes keep what they make under the home:
 * theirs is the stubs' empty one, `stubs.home`, so a test never writes to the real one. A keeper's or a host tool's socket
 * too long for its state directory goes to the temporary directory (XDG_RUNTIME_DIR, here a directory of the test's own,
 * `root/run`, only after it and /tmp). `root` holds `tmp` and `run`, and is the place for a test's own files. The repository is under the real home, not in the temporary directories: a
 * recipe refuses one the agents can write to. `stubEnv`, or what it returns when it is a function of `root`, is added to
 * the stubs' environment. Whatever happens, all of it is removed afterwards.
 */
async function inRepo(stubEnv, body) {
  const root = mkdtempSync(join(tmpdir(), "recipes-e2e-"))
  const repo = mkdtempSync(join(homedir(), ".recipes-e2e-"))
  let stubs
  try {
    const tmp = join(root, "tmp")
    const runtime = join(root, "run")
    mkdirSync(tmp)
    mkdirSync(runtime, { mode: 0o700 })
    git(repo, "init", "-q", "-b", "main")
    writeFileSync(join(repo, "a.txt"), "a\n")
    git(repo, "add", ".")
    git(repo, "-c", "user.email=e2e@example.com", "-c", "user.name=e2e", "commit", "-qm", "init")
    stubs = stubAgents(typeof stubEnv === "function" ? stubEnv(root) : stubEnv)
    return await body({ root, repo, tmp, stubs, env: { ...stubs.env, TMPDIR: tmp, XDG_RUNTIME_DIR: runtime } })
  } finally {
    stubs?.cleanup()
    rmSync(root, { recursive: true, force: true })
    rmSync(repo, { recursive: true, force: true })
  }
}

/**
 * What is in the temporary directory `tmp`, but for the directories of sockets: the core puts a keeper's or a host
 * tool's socket there when its path would be too long for the state directory, and that is the core's doing, not the
 * recipe's.
 */
const inTemp = tmp => readdirSync(tmp).filter(name => !/^supermux-(keepers|mcp)-/.test(name))
/** Where the recipes keep what they make: a folder of the home. */
const recipesDir = home => join(home, ".supermux-recipes")
/** The folder of a recipe for the directory `dir` it works in. */
const folderOf = (name, dir) => `${name}-${basename(dir)}-${createHash("sha1").update(dir).digest("hex").slice(0, 8)}`

/** Runs a recipe: how it ended (`code`) and what it printed, whether it succeeded or not. One that never ends is an error. */
async function ran(args, options) {
  try {
    const { stdout, stderr } = await run(process.execPath, args, { timeout: 60_000, ...options })
    return { code: 0, stdout, stderr }
  } catch (error) {
    if (typeof error.code !== "number") throw error
    return { code: error.code, stdout: error.stdout, stderr: error.stderr }
  }
}

/** Rewrites what the stub `name` runs: `change` takes its sh script and returns the new one. */
function patchStub(stubs, name, change) {
  const path = join(stubs.bin, name)
  writeFileSync(path, change(readFileSync(path, "utf8")))
}

/** The stub `name` runs `commands` (sh) in its working directory, an agent's worktree, before it starts: what an agent did to its files. */
const agentDoes = (stubs, name, commands) => patchStub(stubs, name, script => script.replace("\nexec env", () => `\n${commands}\nexec env`))

/** The Codex stub is asked PROXY_PROMPT (a stubAgents() env value) whatever a recipe asks it. */
const askedInstead = stubs => patchStub(stubs, "codex", script => script.replace(/'[^']*codex-agent\.mjs'/, fixture => `${quote(rewritePrompt)} ${fixture}`))

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

// ---- same-task-three-agents

// Codex gets approvalPolicy "never" with the workspace-write sandbox, at launch and in each turn: its stub must expect that.
const RACE = { EXPECT_POLICY: "never", EXPECT_SANDBOX: "workspace-write", EXPECT_TURN_POLICY: "never" }
const FALLBACK = "supermux recipes <recipes@supermux.invalid>"

test("same-task-three-agents: each agent has a branch and a worktree of its own, with its changes committed on the branch", { skip }, async () => {
  // The stubs write what they get to TRACE: Claude's command line is among it.
  await inRepo(root => ({ ...RACE, TRACE: join(root, "agents.trace") }), async ({ root, repo, tmp, stubs, env }) => {
    // What the agents do to their worktrees: Claude moves HEAD to a branch of its own, edits a file and makes one, Codex
    // makes one in a new directory.
    agentDoes(stubs, "claude", "git checkout -q -b elsewhere; echo more >> a.txt; echo made > claude.txt")
    agentDoes(stubs, "codex", "mkdir sub; echo made > sub/codex.txt")
    const args = [recipe("same-task-three-agents.ts"), "hello", "--agents", "claude,codex", "--repo", repo]
    // From a directory that is not the repository: --repo names it.
    const { stdout, stderr } = await ran(args, { cwd: tmp, env })
    assert.equal(stdout.trim().split("\n").length, 2, stdout)
    const base = git(repo, "rev-parse", "HEAD")
    const folder = join(recipesDir(stubs.home), folderOf("same-task-three-agents", git(repo, "rev-parse", "--show-toplevel")))
    const made = {}
    for (const [agent, stat] of [["claude", "2 files changed, 2 insertions\\(\\+\\)"], ["codex", "1 file changed, 1 insertion\\(\\+\\)"]]) {
      const [, branch, worktree] = new RegExp(`^${agent}: ${stat} \\(branch (race/${agent}-\\d+), worktree (.+)\\)$`, "m").exec(stdout) ?? []
      assert.ok(branch, `no line for ${agent} in:\n${stdout}`)
      // The worktree is in the recipe's folder of the home, under a root of its own for this run.
      assert.equal(dirname(dirname(worktree)), folder)
      assert.match(basename(dirname(worktree)), /^\d+$/)
      assert.equal(basename(worktree), agent)
      // The branch is checked out in the worktree, holds one commit on the repository's own, and nothing is left uncommitted.
      assert.equal(git(worktree, "rev-parse", "--abbrev-ref", "HEAD"), branch)
      assert.equal(git(repo, "rev-parse", `${branch}^`), base)
      assert.equal(git(repo, "log", "-1", "--format=%s", branch), `${agent}: hello`)
      assert.equal(git(worktree, "status", "--porcelain"), "")
      // With no identity of its own, git gets the recipe's.
      assert.equal(git(repo, "log", "-1", "--format=%an <%ae> / %cn <%ce>", branch), `${FALLBACK} / ${FALLBACK}`)
      made[agent] = { branch, worktree }
    }
    // The commit is on the race branch, not where Claude moved HEAD.
    assert.equal(git(repo, "rev-parse", "elsewhere"), base)
    assert.equal(git(repo, "show", `${made.claude.branch}:claude.txt`), "made")
    assert.equal(git(repo, "show", `${made.claude.branch}:a.txt`), "a\nmore")
    assert.equal(git(repo, "show", `${made.codex.branch}:sub/codex.txt`), "made")
    assert.deepEqual([...new Set(stubs.launched())].sort(), ["claude", "codex"])
    // Claude was told to accept edits, without a prompt for each.
    const { argv } = traced(join(root, "agents.trace")).find(entry => entry.argv?.includes("--print"))
    assert.deepEqual(argv.slice(argv.indexOf("--permission-mode"), argv.indexOf("--permission-mode") + 2), ["--permission-mode", "acceptEdits"])
    assert.doesNotMatch(stderr, /auto-approve/)
    // The worktrees stay, for the reader to look at; the sessions' state does not. Nothing is made in the temporary
    // directory, and what is made in the home is the user's alone.
    const rootDir = dirname(made.claude.worktree)
    assert.deepEqual(readdirSync(rootDir).sort(), ["claude", "codex"])
    assert.deepEqual(inTemp(tmp), [])
    for (const dir of [rootDir, folder, recipesDir(stubs.home)]) assert.equal(statSync(dir).mode & 0o777, 0o700, dir)

    // The commands it prints work. The branches hold the work when their worktrees are gone, and a merge takes it.
    const printed = pattern => stderr.split("\n").map(line => line.trim()).filter(line => line.startsWith("git ") && pattern.test(line))
    const sh = command => execFileSync("sh", ["-c", command], { env: gitEnv })
    assert.equal(printed(/ worktree remove /).length, 2, stderr)
    for (const command of printed(/ worktree remove /)) sh(command)
    assert.deepEqual([made.claude.worktree, made.codex.worktree].filter(existsSync), [])
    assert.equal(git(repo, "show", `${made.claude.branch}:claude.txt`), "made")
    const [merge] = printed(new RegExp(` merge ${made.claude.branch}$`))
    assert.ok(merge, `no command to merge ${made.claude.branch} in:\n${stderr}`)
    sh(merge)
    assert.equal(readFileSync(join(repo, "claude.txt"), "utf8"), "made\n")
    for (const command of printed(/ branch -D /)) sh(command)
    assert.equal(git(repo, "branch", "--list", "race/*"), "")
    // The last command takes the run's folder, empty by now.
    const rmdir = stderr.split("\n").map(line => line.trim()).find(line => line.startsWith("rmdir "))
    assert.ok(rmdir, `no command to remove the folder in:\n${stderr}`)
    sh(rmdir)
    assert.equal(existsSync(rootDir), false)
  })
})

test("same-task-three-agents: Claude and Codex by default, in the repository of the current directory; no change, no commit", { skip }, async () => {
  await inRepo(RACE, async ({ repo, stubs, env }) => {
    const { stdout, stderr } = await ran([recipe("same-task-three-agents.ts"), "hello"], { cwd: repo, env })
    for (const agent of ["claude", "codex"]) {
      const [, branch] = new RegExp(`^${agent}: no changes \\(branch (race/${agent}-\\d+), worktree .+\\)$`, "m").exec(stdout) ?? []
      assert.ok(branch, `no line for ${agent} in:\n${stdout}`)
      assert.equal(git(repo, "rev-parse", branch), git(repo, "rev-parse", "HEAD"))
    }
    assert.deepEqual([...new Set(stubs.launched())].sort(), ["claude", "codex"])
    assert.doesNotMatch(stderr, /auto-approve/)
  })
})

test("same-task-three-agents: the commit is by the identity git has; the recipe's own is for when there is none", { skip }, async () => {
  await inRepo(RACE, async ({ repo, env, stubs }) => {
    git(repo, "config", "user.name", "Ada Lovelace")
    git(repo, "config", "user.email", "ada@example.com")
    agentDoes(stubs, "claude", "echo made > claude.txt")
    const { stdout } = await ran([recipe("same-task-three-agents.ts"), "hello", "--agents", "claude"], { cwd: repo, env })
    const [, branch] = /^claude: .* \(branch (race\/claude-\d+), worktree /m.exec(stdout) ?? []
    assert.ok(branch, stdout)
    assert.equal(git(repo, "log", "-1", "--format=%an <%ae> / %cn <%ce>", branch), "Ada Lovelace <ada@example.com> / Ada Lovelace <ada@example.com>")
  })
})

test("same-task-three-agents: a .git an agent plants in its worktree runs nothing", { skip }, async () => {
  await inRepo(RACE, async ({ root, repo, env, stubs }) => {
    const marker = join(root, "ran")
    const bomb = join(root, "bomb.sh")
    writeFileSync(bomb, `#!/bin/sh\ntouch ${quote(marker)}\n`, { mode: 0o755 })
    // What a hostile agent leaves in its worktree: a .git of its own, which git then takes for the repository. Its config
    // has git run the bomb as the fsmonitor hook and as a clean filter, its hooks are the bomb, and .gitattributes sends
    // every file through the filter. And a file of work.
    agentDoes(stubs, "claude", [
      "rm -f .git && mkdir -p .git/objects .git/refs .git/hooks",
      "echo 'ref: refs/heads/main' > .git/HEAD",
      `printf '[core]\\n\\tfsmonitor = %s\\n[filter "x"]\\n\\tclean = %s\\n' ${quote(bomb)} ${quote(bomb)} > .git/config`,
      `cp ${quote(bomb)} .git/hooks/pre-commit && cp ${quote(bomb)} .git/hooks/post-commit`,
      "echo '* filter=x' > .gitattributes",
      "echo work > planted.txt",
    ].join("\n"))
    const { stdout } = await ran([recipe("same-task-three-agents.ts"), "hello", "--agents", "claude"], { cwd: repo, env })
    assert.equal(existsSync(marker), false, "a command of the planted .git ran")
    // The recipe went on as it should: the work is on the branch, and the planted .git is not.
    const [, branch] = /^claude: 2 files changed, 2 insertions\(\+\) \(branch (race\/claude-\d+), worktree /m.exec(stdout) ?? []
    assert.ok(branch, stdout)
    assert.deepEqual(git(repo, "ls-tree", "-r", "--name-only", branch).split("\n"), [".gitattributes", "a.txt", "planted.txt"])
  })
})

test("same-task-three-agents: a repository an agent puts where the base has a submodule runs nothing", { skip }, async () => {
  await inRepo(RACE, async ({ root, repo, env, stubs }) => {
    // The base records a submodule at sub (a gitlink: the repository holds no clone of it).
    git(repo, "update-index", "--add", "--cacheinfo", `160000,${git(repo, "rev-parse", "HEAD")},sub`)
    git(repo, "-c", "user.email=e2e@example.com", "-c", "user.name=e2e", "commit", "-qm", "a submodule")
    const marker = join(root, "ran")
    const bomb = join(root, "bomb.sh")
    writeFileSync(bomb, `#!/bin/sh\ntouch ${quote(marker)}\n`, { mode: 0o755 })
    // The agent fills sub with a repository of its own: a tracked file that changed (it has the same size and an older time,
    // so git must hash it to know), and a clean filter that runs the bomb, set after its `git add`. Git asks such a
    // repository for its status, filters and all, when a command in the one above looks at the submodule. And a bit of work.
    agentDoes(stubs, "claude", [
      "mkdir -p sub && cd sub && git init -q -b main .",
      "echo x > f.txt && git add f.txt",
      `git config filter.x.clean ${quote(bomb)} && echo '* filter=x' > .gitattributes`,
      "echo y > f.txt && touch -t 200001010000 f.txt",
      "cd .. && echo work > work.txt",
    ].join("\n"))
    const { stdout } = await ran([recipe("same-task-three-agents.ts"), "hello", "--agents", "claude"], { cwd: repo, env })
    assert.equal(existsSync(marker), false, "a command of the repository in the submodule ran")
    // The submodule is as the base has it, and the work is on the branch.
    const [, branch] = /^claude: 1 file changed, 1 insertion\(\+\) \(branch (race\/claude-\d+), worktree /m.exec(stdout) ?? []
    assert.ok(branch, stdout)
    assert.equal(git(repo, "ls-tree", branch, "sub"), git(repo, "ls-tree", "HEAD", "sub"))
    assert.equal(git(repo, "show", `${branch}:work.txt`), "work")
  })
})

test("same-task-three-agents: a worktree an agent swaps for a link is left alone", { skip }, async () => {
  await inRepo(RACE, async ({ root, repo, env, stubs }) => {
    // A private directory, with a file that is nobody's work.
    const target = join(root, "private")
    mkdirSync(target)
    writeFileSync(join(target, "secret.txt"), "secret\n")
    // The agent moves its worktree away and leaves a link to the private directory in its place.
    agentDoes(stubs, "claude", `here=$(pwd) && cd .. && mv "$here" "$here.moved" && ln -s ${quote(target)} "$here" && cd "$here.moved"`)
    const { code, stdout } = await ran([recipe("same-task-three-agents.ts"), "hello", "--agents", "claude"], { cwd: repo, env })
    assert.equal(code, 1)
    const line = /^claude: not kept: .* is not the directory made for claude: left alone \(branch (race\/claude-\d+), worktree /m.exec(stdout)
    assert.ok(line, stdout)
    // Git never read the link's target: nothing was committed.
    assert.equal(git(repo, "rev-parse", line[1]), git(repo, "rev-parse", "HEAD"))
    assert.equal(git(repo, "log", "--all", "--oneline", "--", "secret.txt"), "")
  })
})

test("same-task-three-agents: a home that is itself behind a link is no reason to refuse a worktree", { skip }, async () => {
  await inRepo(RACE, async ({ root, repo, env, stubs }) => {
    // HOME is a link to the stubs' home, as on a machine where /home is one: the worktree is made, and found again, by
    // paths that go through the link.
    const home = join(root, "home-link")
    symlinkSync(stubs.home, home)
    agentDoes(stubs, "claude", "echo made > claude.txt")
    const { code, stdout, stderr } = await ran([recipe("same-task-three-agents.ts"), "hello", "--agents", "claude"], { cwd: repo, env: { ...env, HOME: home } })
    assert.equal(code, 0, stderr)
    assert.match(stdout, /^claude: 1 file changed, 1 insertion\(\+\) /m)
    assert.ok(stdout.includes(join(home, ".supermux-recipes")), stdout)
  })
})

// A request the agent makes is refused (a permission) or declined (a question), and logged: it never waits for ever.
for (const [prompt, logged] of [["permission", /^refused: Bash \(claude\)$/m], ["ask-user-question", /^declined: Favorite color\? \(claude\)$/m]]) {
  test(`same-task-three-agents: a "${prompt}" turn of the Claude stub ends, with the request logged`, { skip }, async () => {
    await inRepo({}, async ({ repo, env }) => {
      const { code, stdout, stderr } = await ran([recipe("same-task-three-agents.ts"), prompt, "--agents", "claude"], { cwd: repo, env })
      assert.equal(code, 0, stderr)
      assert.match(stdout, /^claude: no changes /m)
      assert.match(stderr, logged)
    })
  })
}

test("same-task-three-agents: Grok runs on auto-approve only when asked for, and the recipe says so", { skip }, async () => {
  // Here the Grok stub is the generic ACP fixture, whose "permission-execute" turn asks to run something (with an allow
  // and a reject option) and writes the answer it gets to TRACE.
  await inRepo(root => ({ TRACE: join(root, "acp.trace") }), async ({ root, repo, stubs, env }) => {
    patchStub(stubs, "grok", script => script.replace("grok-agent.mjs", "acp-agent.mjs"))
    const { code, stdout, stderr } = await ran([recipe("same-task-three-agents.ts"), "permission-execute", "--agents", "grok"], { cwd: repo, env })
    assert.equal(code, 0, stderr)
    assert.match(stderr, /^grok runs on auto-approve: /m)
    assert.match(stdout, /^grok: no changes /m)
    // Nobody was asked, and the answer the agent got is the first option that allows.
    assert.doesNotMatch(stderr, /^refused: /m)
    const answers = traced(join(root, "acp.trace")).filter(entry => entry?.outcome)
    assert.deepEqual(answers, [{ outcome: { outcome: "selected", optionId: "allow" } }])
    assert.deepEqual(stubs.launched(), ["grok"])
  })
})

test("same-task-three-agents: no prompt, an agent it does not run, a directory that is no git work tree and a repository in the temporary directories are refused before anything starts", { skip }, async () => {
  await inRepo({}, async ({ repo, tmp, stubs, env }) => {
    // A repository the agents can write to: they could change its .git, and git would run what they wrote.
    const inside = join(tmp, "inside")
    mkdirSync(inside)
    git(inside, "init", "-q", "-b", "main")
    for (const [args, said] of [
      [[], /^Usage: /m],
      [["hello", "--agents", "claude,cursor"], /^Usage: /m],
      [["hello", "--repo", tmp], /is not a git work tree/],
      [["hello", "--repo", inside], /inside a temporary directory/],
    ]) {
      const { code, stdout, stderr } = await ran([recipe("same-task-three-agents.ts"), ...args], { cwd: repo, env })
      assert.deepEqual({ code, stdout }, { code: 1, stdout: "" })
      assert.match(stderr, said)
    }
    assert.deepEqual(stubs.launched(), [])
    assert.deepEqual(readdirSync(tmp), ["inside"])
    assert.equal(git(repo, "branch", "--list", "race/*"), "")
  })
})

// ---- ci-review

test("ci-review: Codex reviews read-only, and the review is printed", { skip }, async () => {
  // Every message the Codex stub gets is written to TRACE, among them the review request.
  const policies = { EXPECT_POLICY: "never", EXPECT_SANDBOX: "read-only", EXPECT_TURN_POLICY: "never" }
  await inRepo(root => ({ ...policies, TRACE: join(root, "codex.trace") }), async ({ root, repo, tmp, stubs, env }) => {
    const { stdout } = await ran([recipe("ci-review.ts"), "--base", "HEAD"], { cwd: repo, env })
    // What the Codex stub answers, as it said it.
    assert.equal(stdout.trim(), "héllo")
    assert.deepEqual(stubs.launched(), ["codex"])
    const request = traced(join(root, "codex.trace")).find(message => message.method === "turn/start")
    assert.match(request.params.input[0].text, /git diff HEAD\.\.\.HEAD/)
    // Its state is in the home, never in the temporary directory, and is gone: only the (empty) folder of the recipe is left.
    assert.deepEqual(inTemp(tmp), [])
    const kept = readdirSync(recipesDir(stubs.home), { recursive: true })
    assert.equal(kept.length, 1, `left behind in the home: ${kept}`)
    assert.match(kept[0], /^ci-review-.+-[0-9a-f]{8}$/)
  })
})

test("ci-review: exits 1 when the base is no commit, and when Codex cannot start", { skip }, async () => {
  // The stub's own policies (on-request, workspace-write) are not what the review runs with: it refuses to start.
  await inRepo({}, async ({ repo, stubs, env }) => {
    const bad = await ran([recipe("ci-review.ts"), "--base", "no-such-branch"], { cwd: repo, env })
    assert.deepEqual({ code: bad.code, stdout: bad.stdout }, { code: 1, stdout: "" })
    assert.match(bad.stderr, /no-such-branch/)
    assert.deepEqual(stubs.launched(), [], "Codex started for a base that is no commit")
    const refused = await ran([recipe("ci-review.ts"), "--base", "HEAD"], { cwd: repo, env })
    assert.deepEqual({ code: refused.code, stdout: refused.stdout }, { code: 1, stdout: "" })
    assert.match(refused.stderr, /^No review: /m)
    assert.deepEqual(stubs.launched(), ["codex"])
  })
})

// The review is a turn of the Codex stub, and the recipe's prompt is fixed: the stub is asked another one (see
// support/rewrite-prompt.mjs), one of those it has a turn for.
for (const { asked, title, code, logged } of [
  { asked: "fail", title: "a turn that fails is no review, whatever the agent said", code: 1, logged: /^No review: /m },
  { asked: "permission", title: "a permission request is refused, and a turn that ends without text is no review", code: 1, logged: /^refused: ls$/m },
  { asked: "ask-inline", title: "a question is declined, and the turn ends", logged: /^declined: Which color\?$/m },
]) {
  test(`ci-review: ${title}`, { skip }, async () => {
    const policies = { EXPECT_POLICY: "never", EXPECT_SANDBOX: "read-only", EXPECT_TURN_POLICY: "never" }
    await inRepo({ ...policies, PROXY_PROMPT: asked }, async ({ repo, stubs, env }) => {
      askedInstead(stubs)
      const result = await ran([recipe("ci-review.ts"), "--base", "HEAD"], { cwd: repo, env })
      if (code !== undefined) assert.equal(result.code, code, result.stderr)
      assert.match(result.stderr, logged)
      if (asked === "permission") assert.match(result.stderr, /^No review: the agent sent no review$/m)
      assert.deepEqual(stubs.launched(), ["codex"])
    })
  })
}

// ---- deploy-tool

test("deploy-tool: the agent calls a function of the recipe's own process", { skip }, async () => {
  // The Claude stub starts the MCP servers it is given, as Claude does, and calls the tool a prompt names. It writes its
  // command line to TRACE.
  await inRepo(root => ({ FIXTURE_MCP: "1", TRACE: join(root, "claude.trace") }), async ({ root, repo, tmp, stubs, env }) => {
    const prompt = 'Call mcp__ops__deploy with {"environment":"staging"}'
    const { stdout } = await ran([recipe("deploy-tool.ts"), prompt], { cwd: repo, env })
    assert.match(stdout, /^deploy called by claude session \S+: staging$/m)
    // The tool's result went back to the agent, which says it.
    assert.match(stdout, /deployed to staging/)
    assert.deepEqual(stubs.launched(), ["claude"])
    // Claude may call that tool without asking, and has the recipe's server.
    const { argv } = traced(join(root, "claude.trace")).find(entry => entry.argv?.includes("--print"))
    assert.deepEqual(argv.slice(argv.indexOf("--allowedTools"), argv.indexOf("--allowedTools") + 2), ["--allowedTools", "mcp__ops__deploy"])
    assert.ok(argv.includes("--mcp-config"), "Claude was given no MCP server")
    // Its state is in the home, never in the temporary directory, and is gone.
    assert.deepEqual(inTemp(tmp), [])
    const kept = readdirSync(recipesDir(stubs.home), { recursive: true })
    assert.equal(kept.length, 1, `left behind in the home: ${kept}`)
    assert.match(kept[0], /^deploy-tool-.+-[0-9a-f]{8}$/)
  })
})

test("deploy-tool: the tool takes staging only", { skip }, async () => {
  await inRepo({ FIXTURE_MCP: "1" }, async ({ repo, env }) => {
    const prompt = 'Call mcp__ops__deploy with {"environment":"production"}'
    const { stdout } = await ran([recipe("deploy-tool.ts"), prompt], { cwd: repo, env })
    assert.doesNotMatch(stdout, /deploy called by/)
    assert.match(stdout, /validation/i)
  })
})

test("deploy-tool: --agent codex runs the recipe on Codex, read-only and never asked", { skip }, async () => {
  // The Codex stub must expect those policies, at launch and in each turn.
  const policies = { EXPECT_POLICY: "never", EXPECT_SANDBOX: "read-only", EXPECT_TURN_POLICY: "never" }
  await inRepo(policies, async ({ repo, stubs, env }) => {
    const { stdout } = await ran([recipe("deploy-tool.ts"), "hello", "--agent", "codex"], { cwd: repo, env })
    assert.equal(stdout.trim(), "héllo")
    assert.deepEqual(stubs.launched(), ["codex"])
  })
})

for (const [prompt, logged] of [["permission", /^refused: Bash$/m], ["ask-user-question", /^declined: Favorite color\?$/m]]) {
  test(`deploy-tool: a "${prompt}" turn of the Claude stub ends, with the request logged`, { skip }, async () => {
    await inRepo({}, async ({ repo, env }) => {
      const { code, stderr } = await ran([recipe("deploy-tool.ts"), prompt], { cwd: repo, env })
      assert.equal(code, 0, stderr)
      assert.match(stderr, logged)
    })
  })
}

// ---- every recipe

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
      await inRepo(files, async ({ root, repo, stubs, env }) => {
        const trace = join(root, "agent.trace")
        const child = spawn(process.execPath, [recipe(name), ...args(repo)], { cwd: repo, env, stdio: ["ignore", "ignore", "pipe"] })
        try {
          let stderr = ""
          child.stderr.on("data", chunk => { stderr += chunk })
          const exited = once(child, "exit")
          // A keeper's directory is in the recipe's state, in the home (only its socket may be elsewhere, when its path is too long).
          const keepers = () => {
            const dir = recipesDir(stubs.home)
            return existsSync(dir) ? readdirSync(dir, { recursive: true }).filter(path => path.split(sep).includes("keepers")) : []
          }
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

// ---- every recipe, when its core cannot close

// A keeper that does not answer (here one held still with SIGSTOP) fails the close of the core, with its agent still running.
// What such a keeper reads and writes is not removed from under it: the recipe leaves its state where it is, and says where,
// and how to stop the keeper (`removable`: the folder holds nothing but that state, so it also says how to remove it).
const READ_ONLY = { EXPECT_POLICY: "never", EXPECT_SANDBOX: "read-only", EXPECT_TURN_POLICY: "never" }
for (const { name, args, stubEnv, working, setup, removable } of [
  { name: "same-task-three-agents.ts", args: repo => ["hang", "--agents", "claude", "--repo", repo], stubEnv: {}, working: '"hang"', removable: false },
  { name: "deploy-tool.ts", args: () => ["hang"], stubEnv: {}, working: '"hang"', removable: true },
  // The review is asked to hang: the Codex stub is given that turn instead of the recipe's prompt.
  { name: "ci-review.ts", args: () => ["--base", "HEAD"], stubEnv: { ...READ_ONLY, PROXY_PROMPT: "hang" }, working: '"turn/start"', setup: askedInstead, removable: true },
]) {
  test(`${name} keeps the state of a keeper it could not close, and says where it is and how to stop it`, { skip, timeout: 90_000 }, async () => {
    const files = root => ({ ...stubEnv, TRACE: join(root, "agent.trace"), PID_FILE: join(root, "agent.pid") })
    await inRepo(files, async ({ root, repo, stubs, env }) => {
      setup?.(stubs)
      const child = spawn(process.execPath, [recipe(name), ...args(repo)], { cwd: repo, env, stdio: ["ignore", "ignore", "pipe"] })
      let keepers = []
      try {
        let stderr = ""
        child.stderr.on("data", chunk => { stderr += chunk })
        const exited = once(child, "exit")
        const trace = join(root, "agent.trace")
        await until(() => existsSync(trace) && readFileSync(trace, "utf8").includes(working), "the agent to be at work")
        await until(() => (keepers = keepersIn(recipesDir(stubs.home))).length > 0, "the keeper's status")
        freeze(keepers)
        child.kill("SIGTERM")
        const [code] = await exited
        assert.equal(code, 143, `stderr: ${stderr}`)
        // It says where the run's folder is, and the keeper's status is still in it.
        const [, where] = / left in (.+)$/m.exec(stderr) ?? []
        assert.ok(where, `stderr does not say where the state is: ${stderr}`)
        assert.equal(dirname(dirname(where)), recipesDir(stubs.home))
        assert.deepEqual(keepersIn(where).map(keeper => keeper.keeperPid), keepers.map(keeper => keeper.keeperPid), `the state of a keeper that may still run was removed; stderr: ${stderr}`)
        // Which processes to stop and where their pids are; and for a folder that holds nothing else, how to remove it.
        assert.ok(stderr.includes(`kill the processes named by keeperPid and agentPid in ${join(where, "state")}/keepers/*/status.json`), stderr)
        assert.equal(stderr.includes(`rm -r ${JSON.stringify(where)}`), removable, stderr)
        if (!removable) {
          // The worktrees are as they were: nothing was committed, and nothing was removed.
          const [branch, ...more] = git(repo, "for-each-ref", "--format=%(refname:short)", "refs/heads/race/").split("\n")
          assert.deepEqual(more, [])
          assert.equal(git(repo, "rev-parse", branch), git(repo, "rev-parse", "HEAD"))
          assert.ok(existsSync(join(where, "claude")), "the worktree was removed")
        }
      } finally {
        killAll(keepers)
        child.kill("SIGKILL")
      }
    })
  })
}
