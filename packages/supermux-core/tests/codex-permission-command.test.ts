import { afterEach, expect, setDefaultTimeout, test } from "bun:test"
import { existsSync } from "node:fs"
import { mkdtemp, readFile, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import { join } from "node:path"
import { codex } from "../src/codex/index.js"
import { createCodexNormalizer } from "../src/codex/normalize.js"
import type { NormalizedBody } from "../src/events/normalized.js"
import { createCore } from "../src/index.js"
import type { PendingRequest } from "../src/types.js"
import { TEST_LIMITS, nextId, WORKDIR } from "./helpers.js"
import { replayAgent } from "./subagent-fixtures.js"

setDefaultTimeout(20_000)
const dirs: string[] = []
const cores: ReturnType<typeof createCore>[] = []
afterEach(async () => {
  for (const core of cores.splice(0)) await core.close({ agents: "shutdown" }).catch(() => {})
  for (const dir of dirs.splice(0)) await rm(dir, { recursive: true, force: true }).catch(() => {})
})

type Permission = Extract<PendingRequest, { kind: "permission" }>

async function until(check: () => boolean | Promise<boolean>, ms = 8000) {
  const deadline = Date.now() + ms
  while (Date.now() < deadline) {
    if (await check()) return
    await new Promise(r => setTimeout(r, 20))
  }
  throw new Error("condition not reached")
}

const THREAD = "01a0e787-8290-7723-9808-eefa580bf3ef"
const TURN = "01a0e787-8c00-75d2-b619-e14c6d6bcca1"

// `rg -n "email|@" . --glob '!node_modules/**'` as codex-cli sends it: the argv it runs, joined into one shell line that
// quotes the script of `bash -lc` a second time.
const RG_LINE = String.raw`/bin/bash -lc "rg -n \"email|@\" . --glob '"'!node_modules/**'"'"`
const RG_SCRIPT = `rg -n "email|@" . --glob '!node_modules/**'`

/** The params of an `item/commandExecution/requestApproval` request, with the fields codex-cli 0.159 sends (CommandExecutionRequestApprovalParams). */
const approval = (itemId: string, command: string, more: Record<string, unknown> = {}) => ({
  kind: "command", threadId: THREAD, turnId: TURN, itemId, startedAtMs: 1790014547313, approvalId: null, environmentId: null,
  reason: null, networkApprovalContext: null, command, cwd: "/work", commandActions: [{ type: "unknown", command }],
  additionalPermissions: null, proposedExecpolicyAmendment: null, proposedNetworkPolicyAmendments: null,
  availableDecisions: ["accept", "acceptForSession", "decline", "cancel"],
  ...more,
})

/**
 * A session whose Codex (a replay of one turn that stops at these approval requests) has asked for all of them, and a
 * reader of every frame the driver wrote back to it.
 */
async function asked(requests: Record<string, unknown>[]) {
  const dir = await mkdtemp(join(tmpdir(), "codex-approval-"))
  dirs.push(dir)
  const file = join(dir, "approvals.ndjson")
  const trace = join(dir, "trace.ndjson")
  const turn = { id: TURN, items: [], status: "inProgress" }
  const frames = [
    { d: "c", m: { id: 1, method: "initialize", params: {} } },
    { d: "a", m: { id: 1, result: { userAgent: "fixture" } } },
    { d: "c", m: { id: 2, method: "thread/start", params: {} } },
    { d: "a", m: { id: 2, result: { thread: { id: THREAD } } } },
    { d: "c", m: { id: 3, method: "turn/start", params: {} } },
    { d: "a", m: { id: 3, result: { turn } } },
    { d: "a", m: { method: "turn/started", params: { threadId: THREAD, turn } } },
    ...requests.map((params, i) => ({ d: "a", m: { id: `approval-${i + 1}`, method: "item/commandExecution/requestApproval", params } })),
  ]
  await writeFile(file, frames.map(frame => JSON.stringify(frame)).join("\n") + "\n")
  const driver = codex({
    id: "codex", command: process.execPath, args: [replayAgent, "--replay", file], env: { REPLAY_TRACE: trace }, inheritEnv: true,
    sandbox: "workspace-write", approvalPolicy: "on-request",
    permissions: { kind: "codex", approvalPolicy: "on-request", sandbox: "workspace-write" },
    setupTimeoutMs: 5000, requestTimeoutMs: 5000, shutdownTimeoutMs: 200, maxFrameBytes: 16 * 1024 * 1024,
    keeper: { stateDirectory: join(dir, "keeper"), limits: { parkedDeadlineMs: 5000, journalMaxBytes: 5_000_000, connectTimeoutMs: 4000 } },
  })
  const core = createCore({ stateDirectory: join(dir, "core"), agents: [driver], limits: { ...TEST_LIMITS, interruptTimeoutMs: 2000 } })
  cores.push(core)
  const session = await core.sessions.create({ id: nextId("codex-approval-"), agent: "codex", cwd: WORKDIR })
  // The turn never completes (the capture ends at the requests); closing the core ends it.
  void session.send({ content: [{ type: "text", text: "search" }], whenBusy: "queue" })
  await until(() => session.requests.list().length === requests.length)
  const prompts = session.requests.list().filter((request): request is Permission => request.kind === "permission")
  const written = async (): Promise<{ id?: unknown; result?: unknown }[]> =>
    existsSync(trace) ? (await readFile(trace, "utf8")).split("\n").filter(Boolean).map(line => JSON.parse(line)) : []
  return { session, prompts, written }
}

test("the prompt for a command shows the script the agent wrote; the request is left as Codex sent it", async () => {
  const request = approval("call_rg", RG_LINE, {
    // Codex's own re-quoting of the same command: not what the agent wrote, and not used.
    commandActions: [{ type: "search", command: "rg -n 'email|@' . --glob '!node_modules/**'", query: "email|@", path: "." }],
  })
  const { session, prompts, written } = await asked([request])
  const [prompt] = prompts
  expect(prompt!.body).toMatchObject({
    kind: "permission-request",
    toolCall: { callId: "call_rg", tool: "execute", category: "execute", title: RG_SCRIPT },
    detail: { command: RG_SCRIPT, cwd: "/work" },
  })
  // Only what is shown changed: the input is the request as it arrived, quoting and all.
  expect(prompt!.body.toolCall.input).toEqual(request)
  expect((prompt!.body.toolCall.input as { command: string }).command).toBe(RG_LINE)

  await session.requests.respond(prompt!.requestId, { optionId: "allow_once" })
  await until(async () => (await written()).some(frame => frame.id === "approval-1"))
  expect((await written()).find(frame => frame.id === "approval-1")).toEqual({ id: "approval-1", result: { decision: "accept" } })
})

test("a prompt shows every command the way its tool call does", async () => {
  const commands: [line: string, shown: string][] = [
    [RG_LINE, RG_SCRIPT],
    [String.raw`/bin/bash -lc "echo \"it's\" "'$HOME'`, `echo "it's" $HOME`],
    [`/bin/bash -lc "python3 - <<'PY'\nprint('hi')\nPY"`, "python3 - <<'PY'\nprint('hi')\nPY"],
    ["/bin/zsh -lc ls", "ls"],
    // Not the `-c` script of a shell, or no valid shell: as it came.
    ["npm test", "npm test"],
    ["python3 -c 'print(1)'", "python3 -c 'print(1)'"],
    ["/bin/bash -lc 'echo hi' extra", "/bin/bash -lc 'echo hi' extra"],
    ["/bin/bash -lc 'unfinished", "/bin/bash -lc 'unfinished"],
  ]
  const { prompts } = await asked(commands.map(([line], i) => approval(`call_${i}`, line)))
  expect(prompts).toHaveLength(commands.length)
  for (const [i, [line, shown]] of commands.entries()) {
    const prompt = prompts.find(p => p.body.toolCall.callId === `call_${i}`)!
    const [tool] = createCodexNormalizer()({
      protocol: "native",
      value: { method: "item/started", params: { item: { type: "commandExecution", id: `call_${i}`, command: line, cwd: "/work", status: "inProgress" } } },
    }) as [Extract<NormalizedBody, { kind: "tool-call" }>]
    expect(tool.title).toBe(shown)
    expect(prompt.body.toolCall.title).toBe(shown)
    expect(prompt.body.detail?.command).toBe(shown)
    expect((prompt.body.toolCall.input as { command: string }).command).toBe(line)
  }
})
