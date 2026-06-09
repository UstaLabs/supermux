import { test, expect } from "bun:test"
import { mkdtempSync } from "fs"
import { tmpdir } from "os"
import { join } from "path"
import { resumeOpenCodeSession } from "./spawn-helper"
import type { OpenCodeClientLike } from "../agents/opencode/adapter"

// A fake `opencode serve` handle: records prompt/create/abort calls so the test
// can assert resume() (no create) vs start() (create) without a real server.
function fakeServer() {
  const createCalls: string[] = []
  const promptIds: string[] = []
  const client: OpenCodeClientLike = {
    session: {
      async create() { createCalls.push("create"); return { data: { id: "ses_new" } } },
      async update() { return { data: {} } },
      async prompt(o) { promptIds.push(o.path.id); return { data: { parts: [{ type: "text", text: "ok" }] } } },
      async abort() { return true },
    },
    listCommands: async () => [],
    event: { async subscribe() { return { stream: (async function* () {})() } } },
  }
  const spawnServer = async () => ({ pid: 4242, baseUrl: "http://127.0.0.1:0", client, child: {} as any, kill: () => {}, onExit: () => {} })
  return { createCalls, promptIds, spawnServer: spawnServer as any }
}

function session(over: Partial<{ agent_session_id: string }> = {}) {
  const home = mkdtempSync(join(tmpdir(), "oc-resume-"))
  return { id: "uuid-1", name: "demo", workdir: "/tmp", agent_home: home, ...over }
}

test("resume without a persisted id starts a FRESH opencode session (calls create)", async () => {
  const fk = fakeServer()
  let persisted: { name: string; sid: string } | undefined
  const { adapter, handle } = await resumeOpenCodeSession(
    { onOpenCodeSessionId: (name, sid) => { persisted = { name, sid } } },
    session(),
    { spawnServer: fk.spawnServer },
  )
  expect(handle.pid).toBe(4242)
  expect(fk.createCalls).toEqual(["create"]) // start() path
  expect(persisted).toEqual({ name: "demo", sid: "ses_new" }) // id now persisted for next restart
  // the rebuilt adapter is usable: a turn prompts the freshly-created session
  await adapter.send("hi")
  expect(fk.promptIds).toEqual(["ses_new"])
})

test("resume WITH a persisted id reuses it WITHOUT creating a new session", async () => {
  const fk = fakeServer()
  const { adapter } = await resumeOpenCodeSession(
    {},
    session({ agent_session_id: "ses_prior" }),
    { spawnServer: fk.spawnServer },
  )
  expect(fk.createCalls).toEqual([]) // resume() path — no create
  await adapter.send("hi")
  expect(fk.promptIds).toEqual(["ses_prior"]) // prompts the resumed session id
})
