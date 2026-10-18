import { test } from "node:test"
import assert from "node:assert/strict"
import { execFile } from "node:child_process"
import { promisify } from "node:util"
import { fileURLToPath } from "node:url"
import { stubAgents } from "./support/stub-agents.mjs"

const run = promisify(execFile)
const example = fileURLToPath(new URL("../examples/real-agents.mjs", import.meta.url))
const cwd = fileURLToPath(new URL("..", import.meta.url))

for (const agent of ["claude", "codex", "grok", "opencode", "cursor"]) {
  test(`examples/real-agents.mjs runs a turn on ${agent}`, async () => {
    const stubs = stubAgents()
    try {
      const { stdout } = await run(process.execPath, [example, agent], { cwd, env: stubs.env, timeout: 60_000 })
      const last = JSON.parse(stdout.trim().split("\n").at(-1))
      assert.equal(last.agent, agent)
      assert.equal(last.completion.status, "completed")
    } finally {
      stubs.cleanup()
    }
  })
}
