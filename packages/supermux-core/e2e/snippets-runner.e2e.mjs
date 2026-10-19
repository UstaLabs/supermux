// The runner behind snippets.e2e.mjs, tested on Markdown of its own: it must run what a block says, report a failure
// by file, block and line with what the block printed, and leave nothing behind.
import { test } from "node:test"
import assert from "node:assert/strict"
import { existsSync, mkdtempSync, readdirSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { SNIPPETS, namedAgents, runBlock, runnableBlocks } from "./support/snippets.mjs"

// The blocks run against the stub agent CLIs, which are sh scripts.
const skip = process.platform === "win32" && "the stub agent CLIs are sh scripts"
const FENCE = "```"
/** Markdown from lines. */
const md = (...lines) => lines.join("\n")
/** One ```ts run block. */
const block = (...lines) => [`${FENCE}ts run`, ...lines, FENCE]

/** Runs `body` with TMPDIR set to a new empty directory, where the runner and stubAgents() make theirs. */
async function withOwnTmpdir(body) {
  const work = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const saved = process.env.TMPDIR
  process.env.TMPDIR = work
  try {
    return await body(work)
  } finally {
    if (saved === undefined) delete process.env.TMPDIR
    else process.env.TMPDIR = saved
    rmSync(work, { recursive: true, force: true })
  }
}

const alive = pid => {
  try {
    process.kill(pid, 0)
    return true
  } catch (error) {
    return error.code === "EPERM"
  }
}

test("runnableBlocks takes exactly the ```ts run blocks, numbered from 1, with the line of their fence", () => {
  const markdown = md(
    "# Title",
    `${FENCE}ts`, "notRun()", FENCE,
    `${FENCE}ts run`, "first()", FENCE,
    `${FENCE}ts running`, "notRun()", FENCE,
    `${FENCE}tsx run`, "notRun()", FENCE,
    "~~~ts run", "second()", "~~~",
    // A longer fence holds a shorter one.
    `${FENCE}\`ts run`, "third()", FENCE, "stillThird()", `${FENCE}\``,
    "1. In a list:", "   ```ts run", "   fourth()", "   ```",
  )
  assert.deepEqual(runnableBlocks(markdown).map(({ number, line, code }) => ({ number, line, code })), [
    { number: 1, line: 5, code: "first()\n" },
    { number: 2, line: 14, code: "second()\n" },
    { number: 3, line: 17, code: `third()\n${FENCE}\nstillThird()\n` },
    { number: 4, line: 23, code: "fourth()\n" },
  ])
})

test("the // expect-stdout: lines a block ends with are its expected output, ^ and $ at line ends", () => {
  const [plain, expecting] = runnableBlocks(md(
    ...block(`console.log("a")`),
    ...block(`console.log("a")`, "// expect-stdout: ^a$", "// expect-stdout: b|c", ""),
  ))
  assert.deepEqual(plain.expectStdout, [])
  assert.deepEqual(expecting.expectStdout.map(String), ["/^a$/m", "/b|c/m"])
})

test("namedAgents reads the agents a block names in agents: { … }", () => {
  const code = `supermux({ stateDirectory: "s", agents: { claude: {}, codex: { sandbox: "read-only", keeper: { connectTimeoutMs: 1 } } } })`
  assert.deepEqual(namedAgents(code), ["claude", "codex"])
  assert.deepEqual(namedAgents(`supermux({ stateDirectory, agents: { cursor: { model: "auto" }, grok } })`), ["cursor", "grok"])
  // Nothing it can tell: a computed key, a driver array, no agents at all.
  assert.deepEqual(namedAgents("supermux({ stateDirectory, agents: { [agent]: {} } })"), [])
  assert.deepEqual(namedAgents("createCore({ stateDirectory, agents: [claude(options)], limits })"), [])
  assert.deepEqual(namedAgents(`console.log("claude")`), [])
})

test("of a passing and a failing block, the failure names the file, its block number and line, and what it printed", { skip }, async () => {
  const markdown = md(
    "Prints:", "",
    ...block(`console.log("fine")`, "// expect-stdout: ^fine$"),
    "",
    ...block(`console.log("so far so good")`, `console.error("boom")`, "process.exit(3)"),
  )
  const [passing, failing] = runnableBlocks(markdown)
  await withOwnTmpdir(async work => {
    assert.match((await runBlock("selftest.md", passing)).stdout, /^fine$/m)
    await assert.rejects(runBlock("selftest.md", failing), error => {
      assert.match(error.message, /^selftest\.md block 2 \(line 8\): exited with code 3\n/)
      const [printed, stderr] = error.message.split("\nstderr:\n")
      assert.match(printed, /\nstdout:\nso far so good\n/)
      // Node may warn first that type stripping is experimental.
      assert.match(stderr, /^boom$/m)
      return true
    })
    // The run directories and the stubs are gone, and so are the blocks.
    assert.deepEqual(readdirSync(work), [])
  })
  assert.equal(existsSync(join(SNIPPETS, "selftest-1.ts")), false)
  assert.equal(existsSync(join(SNIPPETS, "selftest-2.ts")), false)
})

test("a block runs as TypeScript, from an empty directory of its own, against the stub agents", { skip }, async () => {
  const [typed] = runnableBlocks(md(...block(
    `import { readdirSync } from "node:fs"`,
    "const entries: string[] = readdirSync(process.cwd())",
    "console.log(`cwd has ${entries.length} entries; HOME is ${process.env.HOME}`)",
  )))
  const { stdout } = await runBlock("selftest.md", typed)
  assert.match(stdout, /^cwd has 0 entries; HOME is \/.*supermux-stub-agents-.*\/home$/m)
})

test("a block whose stdout does not match its // expect-stdout: fails", { skip }, async () => {
  const [wrong] = runnableBlocks(md(...block(`console.log("this")`, "// expect-stdout: ^that$")))
  await assert.rejects(runBlock("selftest.md", wrong), /^Error: selftest\.md block 1 \(line 1\): its stdout does not match \/\^that\$\/m\n/)
})

test("every agent a block names in agents: { … } must have run: its stub answered, not a real CLI", { skip, timeout: 120_000 }, async () => {
  const launching = (agents, agent) => runnableBlocks(md(...block(
    `import { supermux } from "@supermux/core"`,
    `const core = supermux({ stateDirectory: ".supermux", agents: { ${agents} } })`,
    `await core.sessions.create({ id: "one", agent: "${agent}", cwd: process.cwd() })`,
    `await core.close({ agents: "shutdown" })`,
  )))[0]
  assert.deepEqual((await runBlock("selftest.md", launching("claude: {}", "claude"))).launched, ["claude"])
  await assert.rejects(runBlock("selftest.md", launching("claude: {}, cursor: {}", "claude")),
    /^Error: selftest\.md block 1 \(line 1\): it names cursor in agents: \{ … \}, but the cursor-agent stub never ran\n/)
})

test("a block that dies with its agents still running has them killed", { skip, timeout: 120_000 }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  const pidFile = join(dir, "agent.pid")
  try {
    // The session's agent runs behind its keeper, which outlives the block: nothing closes the core.
    const [dying] = runnableBlocks(md(...block(
      `import { supermux } from "@supermux/core"`,
      `const core = supermux({ stateDirectory: ".supermux", agents: { claude: { env: { PID_FILE: ${JSON.stringify(pidFile)} } } } })`,
      `await core.sessions.create({ id: "left", agent: "claude", cwd: process.cwd() })`,
      "process.exit(1)",
    )))
    await assert.rejects(runBlock("selftest.md", dying), /^Error: selftest\.md block 1 \(line 1\): exited with code 1\n/)
    const pid = Number(readFileSync(pidFile, "utf8"))
    const deadline = Date.now() + 5_000
    while (alive(pid) && Date.now() < deadline) await sleep(25)
    assert.equal(alive(pid), false, `agent ${pid} is still running`)
  } finally {
    // Should the runner have failed to, this test leaves nothing running either: its keeper ends with its agent.
    if (existsSync(pidFile)) try { process.kill(Number(readFileSync(pidFile, "utf8")), "SIGKILL") } catch { /* gone */ }
    rmSync(dir, { recursive: true, force: true })
  }
})

test("a block that never ends is stopped at the timeout", { skip }, async () => {
  const [hanging] = runnableBlocks(md(...block("setInterval(() => {}, 1000)")))
  await assert.rejects(runBlock("selftest.md", hanging, { timeoutMs: 1_000 }), /^Error: selftest\.md block 1 \(line 1\): did not finish in 1 s\n/)
})
