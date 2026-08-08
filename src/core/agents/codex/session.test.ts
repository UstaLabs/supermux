import { afterAll, beforeEach, describe, expect, mock, test } from "bun:test"
import type { ApplyConfigCtx } from "../session-types"
import type { CodexSpawnHandle } from "./spawn"

// Contract of the codex applyConfig DIALECT: codex has no live setter, so a
// model/effort change is a FULL respawn of the app-server with the new flags
// (same flag set as resume, minus the preamble rewrite). The dialect returns
// the fresh {adapter, handle}; the SessionManager swaps + rewires them.
//
// The spawn/auth/plugin collaborators are swapped via bun module mocks (the
// dialect has no injection seams). mock.module is process-global: capture the
// real export VALUES first and restore them in afterAll.

const realAuth = { ...(await import("./auth")) }
const realSpawn = { ...(await import("./spawn")) }
const realPlugins = { ...(await import("../../plugins")) }

let spawnCalls: Array<Record<string, unknown>> = []
let preparedHomes: string[] = []
let clientRequests: string[] = []

function fakeHandle(): CodexSpawnHandle {
  return {
    pid: 7,
    client: {
      onNotification: () => {},
      request: async (method: string) => {
        clientRequests.push(method)
        if (method === "thread/start") return { thread: { id: "t-new", sessionId: "cs-new" } }
        return {}
      },
    },
    child: {},
    kill: () => {},
    onExit: () => {},
  } as unknown as CodexSpawnHandle
}

mock.module("./auth", () => ({
  ...realAuth,
  resolveCodexAuth: async () => ({ mode: "api_key", env: { OPENAI_API_KEY: "test-key" } }),
}))
mock.module("./spawn", () => ({
  ...realSpawn,
  spawnCodexAppServer: (opts: Record<string, unknown>) => {
    spawnCalls.push(opts)
    return fakeHandle()
  },
}))
mock.module("../../plugins", () => ({
  ...realPlugins,
  codexPrepareSessionHome: async (home: string) => { preparedHomes.push(home) },
  codexSpawnArgs: () => ({ args: ["-c", 'plugins."test@mux".enabled=true'] }),
}))
afterAll(() => {
  mock.module("./auth", () => realAuth)
  mock.module("./spawn", () => realSpawn)
  mock.module("../../plugins", () => realPlugins)
})

const { applyConfig } = await import("./session")

const ctx: ApplyConfigCtx = {
  sessionEffort: () => "high",
  resolveAttachment: async () => { throw new Error("unused in this test") },
  persistAgentSessionId: () => {},
}

beforeEach(() => {
  spawnCalls = []
  preparedHomes = []
  clientRequests = []
})

describe("codex applyConfig dialect", () => {
  test("respawns the app-server with the row's flags and resumes the thread", async () => {
    const r = await applyConfig(
      ctx,
      { id: "s1", workdir: "/w", agent_home: "/state/codex/cx", model: "gpt-5.2-codex", agent_session_id: "thread-1" },
      "cx",
      { model: "gpt-5.2-codex", effort: "high" },
    )
    expect(preparedHomes).toEqual(["/state/codex/cx"])
    expect(spawnCalls).toHaveLength(1)
    expect(spawnCalls[0]).toEqual({
      codexHome: "/state/codex/cx",
      workdir: "/w",
      authEnv: { OPENAI_API_KEY: "test-key" },
      model: "gpt-5.2-codex",
      reasoningLevel: "high",
      pluginConfigArgs: ["-c", 'plugins."test@mux".enabled=true'],
    })
    // An existing thread id → resume (history preserved), never a fresh start.
    expect(clientRequests).toContain("thread/resume")
    expect(clientRequests).not.toContain("thread/start")
    expect(r.ok).toBe(true)
    expect(r.runtime.handle.pid).toBe(7)
    expect(r.runtime.adapter.sessionName).toBe("cx")
  })

  test("without a thread id, the fresh adapter starts a new thread", async () => {
    const r = await applyConfig(
      ctx,
      { id: "s2", workdir: "/w", agent_home: "/state/codex/cy", model: "gpt-5.2-codex" },
      "cy",
      { model: "gpt-5.2-codex" },
    )
    expect(clientRequests).toContain("thread/start")
    expect(clientRequests).not.toContain("thread/resume")
    expect(r.ok).toBe(true)
  })
})
