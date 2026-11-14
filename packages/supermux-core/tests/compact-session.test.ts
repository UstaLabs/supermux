// #144 Compact through Core: a turn in the session's queue, run by runtime.compact, with the note where it's taken.
import { afterEach, expect, test } from "bun:test"
import { mkdtemp, rm } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { createCore } from "../src/index.js"
import type { AgentDriver, AgentRuntime } from "../src/types.js"
import { TEST_LIMITS, nextId, WORKDIR } from "./helpers.js"

const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  for (const core of cores.splice(0)) await core.close({ agents: "shutdown" }).catch(() => {})
  for (const dir of dirs.splice(0)) await rm(dir, { recursive: true, force: true }).catch(() => {})
})

function driver(calls: string[], compact: "note" | "no-note" | "none"): AgentDriver {
  return {
    id: "fake",
    async open() {
      const runtime: AgentRuntime = {
        agentSessionId: "native-1",
        capabilities: { resume: true, steer: false, fork: false, detach: false },
        async prompt(content) { calls.push(`prompt:${(content[0] as { text: string }).text}`); return { stopReason: "end_turn" } },
        async interrupt() {},
        async close() {},
        ...(compact === "none" ? {} : {
          compactTakesNote: compact === "note",
          async compact(note: string | undefined) { calls.push(`compact:${note ?? "-"}`); return { stopReason: "end_turn" } },
        }),
      }
      return runtime
    },
  }
}

async function session(compact: "note" | "no-note" | "none") {
  const dir = await mkdtemp(join(tmpdir(), "compact-"))
  dirs.push(dir)
  const calls: string[] = []
  const core = createCore({ stateDirectory: dir, agents: [driver(calls, compact)], limits: TEST_LIMITS })
  cores.push(core)
  return { calls, session: await core.sessions.create({ id: nextId("c-"), agent: "fake", cwd: WORKDIR }) }
}

test("compact runs as a turn through runtime.compact, with the note where the agent takes one", async () => {
  const { calls, session: s } = await session("note")
  expect(s.canCompact()).toEqual({ compact: true, note: true })
  expect(await (await s.compact({ note: "  keep the #144 decisions ", whenBusy: "queue" })).completed).toEqual({ status: "completed", stopReason: "end_turn" })
  expect(calls).toEqual(["compact:keep the #144 decisions"])
})

test("an agent that takes no note compacts without it", async () => {
  const { calls, session: s } = await session("no-note")
  expect(s.canCompact()).toEqual({ compact: true, note: false })
  await (await s.compact({ note: "ignored", whenBusy: "queue" })).completed
  expect(calls).toEqual(["compact:-"])
})

test("an agent that can't compact says so and refuses", async () => {
  const { session: s } = await session("none")
  expect(s.canCompact()).toEqual({ compact: false, note: false })
  await expect(s.compact({ whenBusy: "queue" })).rejects.toThrow()
})
