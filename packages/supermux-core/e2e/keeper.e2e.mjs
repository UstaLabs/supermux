// The keeper under Node, against the build: what Bun forgives (a socket path longer than sun_path) fails here.
import { test } from "node:test"
import assert from "node:assert/strict"
import { mkdtempSync, readFileSync, rmSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { setTimeout as sleep } from "node:timers/promises"
import { fileURLToPath } from "node:url"
import { supermux } from "@supermux/core"

// Unix sockets and signals.
const skip = process.platform === "win32" && "the keeper listens on a unix socket"
const fixture = fileURLToPath(new URL("../tests/fixtures/claude-agent.mjs", import.meta.url))
const text = value => [{ type: "text", text: value }]
const alive = pid => {
  try {
    process.kill(pid, 0)
    return true
  } catch {
    return false
  }
}

async function dead(pid) {
  const deadline = Date.now() + 5_000
  while (alive(pid) && Date.now() < deadline) await sleep(20)
  return !alive(pid)
}

test("a state directory too deep for a unix socket path: a session opens, detaches and re-attaches", { skip, timeout: 60_000 }, async () => {
  const dir = mkdtempSync(join(tmpdir(), "supermux-e2e-"))
  // Like the kanban example's state inside a repository nested deep in a home directory.
  const state = join(dir, "a-repository-nested-deep-in-a-home-directory".repeat(2), ".supermux-kanban")
  const open = () => supermux({ stateDirectory: state, agents: { claude: { command: process.execPath, args: [fixture], env: { EXPECT_DEFAULT_DENY: "0" } } } })
  const status = () => JSON.parse(readFileSync(join(state, "keepers", "deep", "status.json"), "utf8"))
  let core = open()
  let started
  try {
    const session = await core.sessions.create({ id: "deep", agent: "claude", cwd: dir })
    assert.equal((await (await session.send({ content: text("hello"), whenBusy: "queue" })).completed).status, "completed")
    started = status()
    await core.close({ agents: "detach" })
    assert.ok(alive(started.keeperPid), "the keeper ended with the core it was detached from")

    core = open()
    const resumed = await core.sessions.resume("deep")
    assert.equal(status().keeperPid, started.keeperPid, "the session got a new keeper instead of re-attaching")
    assert.equal((await (await resumed.send({ content: text("again"), whenBusy: "queue" })).completed).status, "completed")
    await core.close({ agents: "shutdown" })
    assert.ok(await dead(started.keeperPid), "the keeper outlived the shutdown")
    assert.ok(await dead(started.agentPid), "the agent outlived the shutdown")
  } finally {
    await core.close({ agents: "shutdown" }).catch(() => {})
    for (const pid of [started?.keeperPid, started?.agentPid]) if (pid && alive(pid)) process.kill(pid, "SIGKILL")
    rmSync(dir, { recursive: true, force: true })
  }
})
