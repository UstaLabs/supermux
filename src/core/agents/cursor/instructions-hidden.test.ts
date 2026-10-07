// Cursor's session instructions travel as an embedded resource in front of the first prompt
// (core cursorContext). They must never surface as part of the user's message in the broker:
// not in the adapter's events (transcript rows, activity cards, the web timeline), not in the
// core events the broker consumes, live or in Cursor's session/load replay, which echoes the
// first message with the resource flattened into its text. This file sets HOME globally: run it
// on its own.
import { afterAll, expect, setDefaultTimeout, test } from "bun:test"
import { mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { fileURLToPath } from "node:url"

setDefaultTimeout(30_000)
const root = mkdtempSync(join(tmpdir(), "cursor-instr-"))
process.env.HOME = join(root, "home")
delete process.env.XDG_CONFIG_HOME
delete process.env.CURSOR_API_KEY
process.env.MUX_HOME = join(root, "mux")
process.env.MUX_STATE_DIR = join(root, "mux", "state")
mkdirSync(join(root, "home", ".cursor"), { recursive: true })
writeFileSync(join(root, "home", ".cursor", "cli-config.json"), "{}")
mkdirSync(join(root, "home", ".config", "cursor"), { recursive: true })
writeFileSync(join(root, "home", ".config", "cursor", "auth.json"), JSON.stringify({ accessToken: "x" }))
afterAll(() => rmSync(root, { recursive: true, force: true }))

const { createCursorCoreHost } = await import("./core-host")
const { cursor } = await import("../../../../packages/supermux-core/src/agents/index.js")
const { CoreAdapter, CORE_ADAPTER_PROFILES } = await import("../core-bridge/core-adapter")

const fixture = fileURLToPath(new URL("../../../../packages/supermux-core/tests/fixtures/cursor-agent.mjs", import.meta.url))
const KINDS = ["assistant-message", "tool-call", "turn-start", "turn-complete", "error", "activity", "request-open", "request-closed", "subagent", "subagent-activity", "task", "commands-update"]

test("cursor: the instructions block never reaches the broker as part of the user's message (live echo and session/load replay)", async () => {
  const work = join(root, "work"), state = join(root, "core"), trace = join(root, "params.jsonl")
  mkdirSync(join(work, ".git", "info"), { recursive: true })
  const env: Record<string, string> = { ECHO_USER: "1", PARAMS_TRACE: trace }
  const host = createCursorCoreHost({
    stateDirectory: state, smoke: async () => {}, sharedRuntime: null,
    driverFactory: (options) => cursor({ ...options, command: process.execPath, commandArgs: [fixture], env: { ...options.env, ...env }, setupTimeoutMs: 5000 }),
  })
  const id = "cursor-instr", name = "cursor-instr-probe"
  const extra = { sessionHome: join(root, "agents", name), sessionName: name, sessionId: id, workdir: work, cwd: work }
  const seen: unknown[] = []
  host.core.subscribe((e) => { seen.push(e) })
  const adapter = (initialSessionId?: string) => {
    const a = new CoreAdapter(CORE_ADAPTER_PROFILES.cursor, {
      handle: host.register({ id, env: {}, extra }),
      reregister: () => host.register({ id, env: {}, extra }),
      core: host.core, id, sessionName: name, workdir: work,
      persistSessionId: async () => {}, stallTimeoutMs: 60_000,
      ...(initialSessionId ? { initialSessionId } : {}),
    })
    for (const k of KINDS) a.on(k, (e: unknown) => seen.push(e))
    return a
  }
  const turn = async (a: InstanceType<typeof CoreAdapter>, text: string) => {
    const ended = new Promise<void>((resolve) => a.on("turn-complete", () => resolve()))
    await a.send(text)
    await ended
  }
  try {
    const first = adapter()
    await first.start()
    await turn(first, "hello")
    const prompts = () => readFileSync(trace, "utf8").trim().split("\n").map((l) => JSON.parse(l)).filter((l) => l.method === "session/prompt").map((l) => l.params.prompt)
    // The instructions did go to the agent: the broker's session header is in the resource.
    expect(prompts()[0][0].resource.uri).toBe("supermux://instructions")
    expect(prompts()[0][0].resource.text).toContain(`You are "${name}"`)
    const instructions: string = prompts()[0][0].resource.text
    await first.stop()

    // A relaunch: Cursor replays the first message with the resource flattened into its text.
    env.REPLAY_USER = "1"
    env.REPLAY_USER_TEXT = `hello\n\nAdditional ACP context:\n[ACP embedded_resource] supermux://instructions\n${instructions}`
    const record = await host.core.sessions.get(id)
    const second = adapter(record!.agentSessionId ?? "agent-1")
    await second.start()
    await turn(second, "again")
    expect(prompts().at(-1)).toEqual([{ type: "text", text: "again" }])
    await second.stop()

    // What reaches transcripts / the timeline: the adapter's events and the core's session.event /
    // session.update stream (the session record legitimately stores the instructions).
    const all = JSON.stringify(seen.filter((e) => {
      const type = (e as { type?: string }).type
      return type === undefined || type === "session.event" || type === "session.update"
    }))
    expect(all).not.toContain("supermux://instructions")
    expect(all).not.toContain("<session-instructions>")
    expect(all).not.toContain(`You are \\"${name}\\"`)
    // The user's own text still came through as the echoed user message.
    const userChunks = seen.flatMap((e) => {
      const ev = e as { type?: string; update?: { protocol?: string; value?: { sessionUpdate?: string; content?: { text?: string } } } }
      return ev.type === "session.update" && ev.update?.value?.sessionUpdate === "user_message_chunk" ? [ev.update.value.content?.text] : []
    })
    expect(userChunks).toContain("hello")
  } finally {
    await host.close({ agents: "shutdown" }).catch(() => {})
  }
})
