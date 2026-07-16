# Grok Build Agent (broker-side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Grok Build (xAI's `grok` CLI) as a first-class supermux agent kind on the broker, driven over its ACP (Agent Client Protocol) stdio interface, at feature parity with codex/cursor/opencode.

**Architecture:** A new `src/core/agents/grok/` unit mirrors the cursor adapter but holds a **persistent** `grok agent stdio` process (ACP JSON-RPC 2.0 over newline-delimited stdio) per session — because grok's simpler `-p` mode drops tool events. A thin `acp-client.ts` frames JSON-RPC; `GrokAdapter` translates ACP `session/update` notifications into supermux `AgentEvent`s; `adapter-activity.ts` gains a `grok` branch mapping `tool_call`/`tool_call_update` → `ActivityEvent`. grok is registered as a streamed agent, so `mux-shim` gives it the file-delivery reply tool + orchestration tools automatically, and `agent_message_chunk` text is stream-relayed.

**Tech Stack:** TypeScript on Bun; `bun test`; existing supermux agent-adapter seam (`AgentAdapter`, `AgentEvent`, `ActivityEvent`); JSON-RPC 2.0; ACP protocolVersion 1.

**Scope note:** This plan is the broker-side agent. Enumerating grok in the web/iOS/Android/desktop pickers (logo asset + agent-kind enum) is a **sibling plan** (`2026-07-13-grok-build-clients.md`) — the clients already render agent activity agnostically, so once the broker speaks grok, existing clients render it; the client plan only adds the picker entry + brand logo.

**Reference fixtures (captured live 2026-07-13):** raw ACP frames live in the recon scratchpad (`acp-full.log`, `grok-stream.jsonl`). Representative frames are inlined in the test tasks below so this plan is self-contained.

---

## File Structure

**Create:**
- `src/core/agents/grok/acp-client.ts` — JSON-RPC 2.0 framing over a stdio pipe: request/response correlation by `id`, notification dispatch, server→client request handling. Pipe-agnostic (takes read/write callbacks) so it unit-tests with no subprocess.
- `src/core/agents/grok/acp-client.test.ts`
- `src/core/agents/grok/stream-parser.ts` — pure function: one ACP `session/update` params object → zero or more supermux stream events (`init`/`assistant-message`/`thought`/`tool-call`/`result`). Mirrors `cursor/stream-parser.ts`.
- `src/core/agents/grok/stream-parser.test.ts`
- `src/core/agents/grok/runner.ts` — spawns `grok agent stdio`, wires the `AcpClient` to the child's stdin/stdout, exposes start/kill. Real vs. fake runner injection like cursor.
- `src/core/agents/grok/adapter.ts` — `GrokAdapter implements AgentAdapter`: persistent ACP session, prompt queue/drain, translate events, interrupt via `session/cancel`.
- `src/core/agents/grok/adapter.test.ts`
- `src/core/agents/grok/mcp-writer.ts` — writes grok's `mux-shim` MCP config (config file + `session/new` mcpServers). Mirrors `cursor/mcp-writer.ts`.
- `src/core/agents/grok/mcp-writer.test.ts`
- `src/core/agents/grok/preamble-writer.ts` — writes the supermux preamble as `AGENTS.md` in the workdir (git-excluded). Mirrors `cursor/preamble-writer.ts`.
- `src/core/agents/grok/auth.ts` — detect `~/.grok/auth.json`.

**Modify:**
- `src/shared/agents.ts` — add `Grok: "grok"` to `AgentKind` + `AGENT_KINDS`.
- `src/core/agents/detect.ts` — grok binary (`grok`) + cred path (`~/.grok/auth.json`).
- `src/core/agents/adapter-activity.ts` — add `if (agent === "grok")` branch to `summarizeDetail`.
- `src/core/agents/tool-normalize.ts` — no code change needed (grok tool stems `write`/`read`/`edit`/`shell` already in `STEM_MAP`); add a test asserting grok names normalize.
- Broker streamed-agent set (the code that rejects text-only `reply()` and relays assistant text for codex/cursor/opencode) — add `grok`.
- Adapter factory (where cursor/codex adapters are constructed from a session record) — construct `GrokAdapter` for `kind === "grok"`.

---

## Task 1: Register the `grok` AgentKind

**Files:**
- Modify: `src/shared/agents.ts`
- Test: `src/shared/agents.test.ts` (create if absent)

- [ ] **Step 1: Write the failing test**

```ts
import { test, expect } from "bun:test"
import { AgentKind, AGENT_KINDS, isAgentKind, parseAgentKind } from "./agents"

test("grok is a recognized agent kind", () => {
  expect(AgentKind.Grok).toBe("grok")
  expect(AGENT_KINDS).toContain("grok")
  expect(isAgentKind("grok")).toBe(true)
  expect(parseAgentKind("grok")).toBe("grok")
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/shared/agents.test.ts`
Expected: FAIL — `AgentKind.Grok` is undefined / `AGENT_KINDS` lacks "grok".

- [ ] **Step 3: Add grok to the enum and list**

In `src/shared/agents.ts`:

```ts
export const AgentKind = {
  Claude: "claude",
  Codex: "codex",
  Cursor: "cursor",
  OpenCode: "opencode",
  Grok: "grok",
} as const

// ...

export const AGENT_KINDS = [
  AgentKind.Claude,
  AgentKind.Codex,
  AgentKind.Cursor,
  AgentKind.OpenCode,
  AgentKind.Grok,
] as const
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/shared/agents.test.ts`
Expected: PASS.

- [ ] **Step 5: Typecheck the fallout**

Run: `bunx tsc --noEmit` (or the repo's typecheck script).
Expected: Any `Record<AgentKind, …>` maps missing a `grok` key now error — note them; they are handled in later tasks (detect.ts Task 2). If a map is unrelated to this plan, add the `grok` entry with the same value shape as `opencode`.

- [ ] **Step 6: Commit**

```bash
git add src/shared/agents.ts src/shared/agents.test.ts
git commit -m "feat(agents): register grok AgentKind"
```

---

## Task 2: Detect the grok binary + auth

**Files:**
- Modify: `src/core/agents/detect.ts`
- Test: `src/core/agents/detect.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
import { test, expect } from "bun:test"
import { authCredPath, detectAgent } from "./detect"

const paths = { home: "/home/u" }

test("grok cred path is ~/.grok/auth.json", () => {
  expect(authCredPath("grok", paths)).toBe("/home/u/.grok/auth.json")
})

test("grok detects installed+authed from binary and auth file", () => {
  const probes = {
    hasBinary: (b: string) => b === "grok",
    fileExists: (p: string) => p === "/home/u/.grok/auth.json",
  }
  expect(detectAgent("grok", probes, paths)).toEqual({ kind: "grok", installed: true, authed: true })
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/agents/detect.test.ts`
Expected: FAIL — `authCredPath` switch has no `grok` case (TS non-exhaustive) and returns undefined.

- [ ] **Step 3: Add grok to BINARY and authCredPath**

In `src/core/agents/detect.ts`:

```ts
const BINARY: Record<AgentKind, string> = {
  claude: "claude",
  codex: "codex",
  cursor: "cursor-agent",
  opencode: "opencode",
  grok: "grok",
}
```

Add a case to `authCredPath`'s switch:

```ts
    case "grok":
      // Grok Build caches its device-code / subscription token here after
      // `grok login`. API-key users set GROK_API_KEY instead — spawning does
      // not fail-close on a missing file (like opencode's free tier), so this
      // only drives the status badge.
      return join(paths.home, ".grok", "auth.json")
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/agents/detect.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/detect.ts src/core/agents/detect.test.ts
git commit -m "feat(agents): detect grok binary and auth"
```

---

## Task 3: ACP JSON-RPC client framing

The ACP wire format is newline-delimited JSON-RPC 2.0. The client must: (a) buffer partial lines across chunk boundaries, (b) correlate responses to requests by `id`, (c) dispatch notifications (no `id`) to a handler, (d) answer server→client requests (which HAVE an `id` and a `method`).

**Files:**
- Create: `src/core/agents/grok/acp-client.ts`
- Test: `src/core/agents/grok/acp-client.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
import { test, expect } from "bun:test"
import { AcpClient } from "./acp-client"

function makeClient() {
  const written: string[] = []
  const client = new AcpClient((line) => written.push(line))
  return { client, written }
}

test("request() sends JSON-RPC with incrementing id and resolves on matching response", async () => {
  const { client, written } = makeClient()
  const p = client.request("initialize", { protocolVersion: 1 })
  expect(JSON.parse(written[0]!)).toEqual({ jsonrpc: "2.0", id: 1, method: "initialize", params: { protocolVersion: 1 } })
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } }) + "\n")
  expect(await p).toEqual({ protocolVersion: 1 })
})

test("notifications are dispatched to onNotification", () => {
  const { client } = makeClient()
  const seen: any[] = []
  client.onNotification = (method, params) => seen.push({ method, params })
  client.feed(JSON.stringify({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk" } } }) + "\n")
  expect(seen).toEqual([{ method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk" } } }])
})

test("partial lines buffer across feeds", async () => {
  const { client } = makeClient()
  const p = client.request("x", {})
  const full = JSON.stringify({ jsonrpc: "2.0", id: 1, result: "ok" }) + "\n"
  client.feed(full.slice(0, 5))
  client.feed(full.slice(5))
  expect(await p).toBe("ok")
})

test("server->client request is answered by onServerRequest handler", async () => {
  const { client, written } = makeClient()
  client.onServerRequest = async (method, params) => {
    expect(method).toBe("session/request_permission")
    return { outcome: { outcome: "selected", optionId: "allow" } }
  }
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 7, method: "session/request_permission", params: { toolCall: {} } }) + "\n")
  await new Promise((r) => setTimeout(r, 0))
  expect(JSON.parse(written[0]!)).toEqual({ jsonrpc: "2.0", id: 7, result: { outcome: { outcome: "selected", optionId: "allow" } } })
})

test("a rejected response rejects the request promise", async () => {
  const { client } = makeClient()
  const p = client.request("bad", {})
  client.feed(JSON.stringify({ jsonrpc: "2.0", id: 1, error: { code: -32602, message: "Invalid params" } }) + "\n")
  await expect(p).rejects.toThrow("Invalid params")
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/agents/grok/acp-client.test.ts`
Expected: FAIL — module `./acp-client` not found.

- [ ] **Step 3: Implement AcpClient**

```ts
// src/core/agents/grok/acp-client.ts
export type JsonRpcId = number
type Pending = { resolve: (v: unknown) => void; reject: (e: Error) => void }

/** Minimal JSON-RPC 2.0 client over a newline-delimited byte pipe. Transport-agnostic:
 * construct with a `write` callback (one framed line, newline already appended by us NOT
 * required — we append it) and push inbound bytes via feed(). */
export class AcpClient {
  private nextId = 1
  private pending = new Map<JsonRpcId, Pending>()
  private buf = ""

  /** Notification (no id) handler: (method, params) => void. */
  onNotification: (method: string, params: unknown) => void = () => {}
  /** Server->client request handler: must resolve to the `result` payload. */
  onServerRequest: (method: string, params: unknown) => Promise<unknown> = async () => ({})

  constructor(private write: (line: string) => void) {}

  request<T = unknown>(method: string, params: unknown): Promise<T> {
    const id = this.nextId++
    const line = JSON.stringify({ jsonrpc: "2.0", id, method, params })
    return new Promise<T>((resolve, reject) => {
      this.pending.set(id, { resolve: resolve as (v: unknown) => void, reject })
      this.write(line)
    })
  }

  notify(method: string, params: unknown): void {
    this.write(JSON.stringify({ jsonrpc: "2.0", method, params }))
  }

  feed(chunk: string): void {
    this.buf += chunk
    let i: number
    while ((i = this.buf.indexOf("\n")) >= 0) {
      const line = this.buf.slice(0, i)
      this.buf = this.buf.slice(i + 1)
      if (line.trim()) this.dispatch(line)
    }
  }

  private dispatch(line: string): void {
    let m: any
    try { m = JSON.parse(line) } catch { return }
    // Response to one of our requests.
    if (m.id != null && (m.result !== undefined || m.error !== undefined)) {
      const p = this.pending.get(m.id)
      if (!p) return
      this.pending.delete(m.id)
      if (m.error) p.reject(new Error(m.error.message ?? "jsonrpc error"))
      else p.resolve(m.result)
      return
    }
    // Server->client request (has id AND method).
    if (m.id != null && typeof m.method === "string") {
      void this.onServerRequest(m.method, m.params)
        .then((result) => this.write(JSON.stringify({ jsonrpc: "2.0", id: m.id, result })))
        .catch((e) => this.write(JSON.stringify({ jsonrpc: "2.0", id: m.id, error: { code: -32000, message: String(e?.message ?? e) } })))
      return
    }
    // Notification.
    if (typeof m.method === "string") this.onNotification(m.method, m.params)
  }

  /** Reject all in-flight requests (called on pipe close). */
  fail(err: Error): void {
    for (const p of this.pending.values()) p.reject(err)
    this.pending.clear()
  }
}
```

Note: the test calls `client.request("x", {})` expecting `written[0]` to be the raw JSON (no trailing newline in the stored string). Our `write` receives the line without `\n`; the runner (Task 6) appends `\n` when writing to the child. Keep framing (the `\n`) in the runner, not here — the test asserts the bare JSON.

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/agents/grok/acp-client.test.ts`
Expected: PASS (all 5 tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/grok/acp-client.ts src/core/agents/grok/acp-client.test.ts
git commit -m "feat(grok): ACP JSON-RPC client framing"
```

---

## Task 4: ACP session/update → stream events parser

Translates one ACP `session/update` `params` object into supermux stream events. Uses real captured frame shapes.

**Files:**
- Create: `src/core/agents/grok/stream-parser.ts`
- Test: `src/core/agents/grok/stream-parser.test.ts`

- [ ] **Step 1: Write the failing test (real captured frames)**

```ts
import { test, expect } from "bun:test"
import { parseGrokUpdate } from "./stream-parser"

test("agent_message_chunk -> assistant-message delta", () => {
  const p = { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Creating" } } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "assistant-message", text: "Creating" }])
})

test("agent_thought_chunk -> thought delta", () => {
  const p = { update: { sessionUpdate: "agent_thought_chunk", content: { type: "text", text: "The" } } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "thought", text: "The" }])
})

test("tool_call -> tool-call started with title+rawInput detail", () => {
  const p = { update: { sessionUpdate: "tool_call", toolCallId: "call-abc-0", title: "write",
    rawInput: { file_path: "/w/poem.txt", content: "roses" } } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "tool-call", phase: "started", call_id: "call-abc-0",
    tool: "write", detail: { title: "write", rawInput: { file_path: "/w/poem.txt", content: "roses" } } }])
})

test("tool_call_update completed -> tool-call completed", () => {
  const p = { update: { sessionUpdate: "tool_call_update", toolCallId: "call-abc-0", kind: "edit",
    title: "Write `/w/poem.txt`", status: "completed", content: [{ type: "content", content: { type: "text", text: "ok" } }] } }
  expect(parseGrokUpdate(p)).toEqual([{ kind: "tool-call", phase: "completed", call_id: "call-abc-0",
    tool: "edit", detail: { kind: "edit", title: "Write `/w/poem.txt`", status: "completed",
      content: [{ type: "content", content: { type: "text", text: "ok" } }] } }])
})

test("tool_call_update failed -> tool-call failed", () => {
  const p = { update: { sessionUpdate: "tool_call_update", toolCallId: "c1", status: "failed",
    content: [{ type: "content", content: { type: "text", text: "denied" } }] } }
  const [ev] = parseGrokUpdate(p)
  expect(ev).toMatchObject({ kind: "tool-call", phase: "failed", call_id: "c1" })
})

test("unknown sessionUpdate -> []", () => {
  expect(parseGrokUpdate({ update: { sessionUpdate: "available_commands_update" } })).toEqual([])
  expect(parseGrokUpdate({ update: { sessionUpdate: "user_message_chunk", content: { type: "text", text: "hi" } } })).toEqual([])
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/agents/grok/stream-parser.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement parseGrokUpdate**

```ts
// src/core/agents/grok/stream-parser.ts
export type GrokStreamEvent =
  | { kind: "assistant-message"; text: string }
  | { kind: "thought"; text: string }
  | { kind: "tool-call"; phase: "started" | "completed" | "failed"; call_id: string; tool: string; detail?: unknown }

/** One ACP `session/update` params object -> zero or more supermux stream events.
 * Frame shapes captured live from `grok agent stdio` v0.2.99 (2026-07-13). */
export function parseGrokUpdate(params: unknown): GrokStreamEvent[] {
  const p = params as { update?: Record<string, unknown> } | undefined
  const u = p?.update
  if (!u || typeof u !== "object") return []
  const kind = u.sessionUpdate as string | undefined

  if (kind === "agent_message_chunk") {
    const text = textOf(u.content)
    return text ? [{ kind: "assistant-message", text }] : []
  }
  if (kind === "agent_thought_chunk") {
    const text = textOf(u.content)
    return text ? [{ kind: "thought", text }] : []
  }
  if (kind === "tool_call") {
    const call_id = String(u.toolCallId ?? "")
    const tool = typeof u.title === "string" ? u.title : "tool"
    const { sessionUpdate: _s, toolCallId: _t, ...detail } = u
    return [{ kind: "tool-call", phase: "started", call_id, tool, detail }]
  }
  if (kind === "tool_call_update") {
    const call_id = String(u.toolCallId ?? "")
    const status = u.status as string | undefined
    // `kind` (edit/read/execute/...) is grok's own tool category; fall back to "tool".
    const tool = typeof u.kind === "string" ? u.kind : "tool"
    const phase = status === "failed" ? "failed" : status === "completed" ? "completed" : "started"
    const { sessionUpdate: _s, toolCallId: _t, ...detail } = u
    if (phase === "started") return [] // in-progress updates without terminal status are noise
    return [{ kind: "tool-call", phase, call_id, tool, detail }]
  }
  return []
}

function textOf(content: unknown): string {
  const c = content as { type?: string; text?: string } | undefined
  return c && c.type === "text" && typeof c.text === "string" ? c.text : ""
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/agents/grok/stream-parser.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/grok/stream-parser.ts src/core/agents/grok/stream-parser.test.ts
git commit -m "feat(grok): ACP session/update stream parser"
```

---

## Task 5: Activity mapping (grok branch in adapter-activity)

Map grok's tool-call detail into `ActivityEvent` summary/result, matching the codex/cursor branches.

**Files:**
- Modify: `src/core/agents/adapter-activity.ts`
- Test: `src/core/agents/adapter-activity.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
import { test, expect } from "bun:test"
import { toActivityEvents } from "./adapter-activity"

test("grok tool_call started -> title with file_path summary", () => {
  const ev = { tool: "write", phase: "started" as const, call_id: "c0",
    detail: { title: "write", rawInput: { file_path: "/w/poem.txt", content: "x" } } }
  const [a] = toActivityEvents("grok", ev, Date.parse("2026-07-13T00:00:00Z"))
  expect(a.kind).toBe("tool")
  expect(a.tool).toBe("Write")            // normalizeToolName("grok","write")
  expect(a.title).toContain("/w/poem.txt")
})

test("grok tool_call_update completed -> tool_result done", () => {
  const ev = { tool: "edit", phase: "completed" as const, call_id: "c0",
    detail: { title: "Write `/w/poem.txt`", status: "completed",
      content: [{ type: "content", content: { type: "text", text: "wrote 2 lines" } }] } }
  const [a] = toActivityEvents("grok", ev, Date.parse("2026-07-13T00:00:00Z"))
  expect(a.kind).toBe("tool_result")
  expect(a.title).toBe("done")
  expect(a.detail).toContain("wrote 2 lines")
})

test("grok tool_call_update failed -> tool_result error", () => {
  const ev = { tool: "write", phase: "failed" as const, call_id: "c0",
    detail: { status: "failed", content: [{ type: "content", content: { type: "text", text: "permission denied" } }] } }
  const [a] = toActivityEvents("grok", ev, Date.parse("2026-07-13T00:00:00Z"))
  expect(a.title).toBe("error")
  expect(a.detail).toContain("permission denied")
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/agents/adapter-activity.test.ts`
Expected: FAIL — grok falls into the `claude` default branch, so `file_path` summary and `content[]` result are empty.

- [ ] **Step 3: Add the grok branch to summarizeDetail**

In `src/core/agents/adapter-activity.ts`, add before the `// claude:` fallback (after the `cursor` branch):

```ts
  if (agent === "grok") {
    // grok ACP: `tool_call` carries `rawInput` (args) + `title`; `tool_call_update`
    // carries `status` + `content: [{ type:"content", content:{ type:"text", text }}]`.
    const rawInput = obj.rawInput as Record<string, unknown> | undefined
    const summary = rawInput
      ? pickString(rawInput, ["command", "file_path", "path", "file", "pattern", "query", "url", "content", "text", "name"])
      : (typeof obj.title === "string" ? obj.title : "")
    let result = ""
    if (ev.phase === "completed" || ev.phase === "failed") {
      result = extractGrokContent(obj.content)
    }
    return { summary, resultDetail: result }
  }
```

Add a helper near `extractCursorResult`:

```ts
/** grok tool_call_update `content` is an array of `{ type:"content", content:{ type:"text", text }}`
 * (also plain `{ type:"text", text }`). Join all text parts. */
function extractGrokContent(content: unknown): string {
  if (!Array.isArray(content)) return ""
  const out: string[] = []
  for (const item of content) {
    if (!item || typeof item !== "object") continue
    const row = item as { type?: string; text?: string; content?: { type?: string; text?: string } }
    if (row.type === "text" && typeof row.text === "string") out.push(row.text)
    else if (row.content?.type === "text" && typeof row.content.text === "string") out.push(row.content.text)
  }
  return out.join("\n")
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/agents/adapter-activity.test.ts`
Expected: PASS.

- [ ] **Step 5: Add a tool-normalize test (no impl change expected)**

Append to `src/core/agents/tool-normalize.test.ts`:

```ts
test("grok tool stems normalize to canonical names", () => {
  expect(normalizeToolName("grok", "write")).toBe("Write")
  expect(normalizeToolName("grok", "read")).toBe("Read")
  expect(normalizeToolName("grok", "edit")).toBe("Edit")
  expect(normalizeToolName("grok", "shell")).toBe("Bash")
})
```

Run: `bun test src/core/agents/tool-normalize.test.ts` → Expected: PASS (stems already in `STEM_MAP`). If `shell` maps differently in this grok build, adjust `STEM_MAP` and re-run.

- [ ] **Step 6: Commit**

```bash
git add src/core/agents/adapter-activity.ts src/core/agents/adapter-activity.test.ts src/core/agents/tool-normalize.test.ts
git commit -m "feat(grok): map ACP tool events to ActivityEvent"
```

---

## Task 6: The grok runner (spawn `grok agent stdio`)

Spawns the child, wires its stdout → `AcpClient.feed` and `AcpClient.write` → child stdin (appending `\n`), and reports exit. Injectable for tests.

**Files:**
- Create: `src/core/agents/grok/runner.ts`

- [ ] **Step 1: Implement the runner (thin process glue — tested via the adapter's fake runner in Task 7)**

```ts
// src/core/agents/grok/runner.ts
import { spawn } from "child_process"
import type { AcpClient } from "./acp-client"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/grok/runner")

export type GrokRunner = (opts: {
  workdir: string
  env: Record<string, string>
  client: AcpClient        // its write() is already set to push to this child's stdin
  onExit: (code: number | null) => void
}) => { kill: () => void }

/** Real runner: spawns `grok agent stdio`. The AcpClient must be constructed by the
 * caller with a write fn that this runner overrides to target the child's stdin. */
export const realGrokRunner: GrokRunner = ({ workdir, env, client, onExit }) => {
  const child = spawn("grok", ["agent", "stdio"], {
    cwd: workdir,
    env: { ...process.env, ...env },
    stdio: ["pipe", "pipe", "pipe"],
  })
  child.stdout.setEncoding("utf8")
  child.stdout.on("data", (chunk: string) => client.feed(chunk))
  child.stderr.setEncoding("utf8")
  child.stderr.on("data", (d: string) => log.debug("grok_stderr", { d: d.slice(0, 500) }))
  child.on("exit", (code) => { log.info("grok_exit", { code }); onExit(code) })
  child.on("error", (e) => { log.warn("grok_spawn_error", { err: String(e) }); onExit(null) })
  // Point the client's writes at this child.
  ;(client as unknown as { write: (l: string) => void }).write = (line: string) => {
    if (child.stdin.writable) child.stdin.write(line + "\n")
  }
  return { kill: () => { try { child.kill("SIGTERM") } catch {} } }
}
```

Note: `AcpClient.write` is private; expose a public setter instead of the cast. Add to `AcpClient`:

```ts
  setWrite(fn: (line: string) => void): void { this.write = fn }
```

and change the runner to `client.setWrite(...)`. Update Task 3's class to keep `write` mutable (it already is a constructor param field — change `private write` to `private write: (l: string) => void` assigned in ctor, add `setWrite`). Re-run Task 3 tests to confirm still green.

- [ ] **Step 2: Commit**

```bash
git add src/core/agents/grok/runner.ts src/core/agents/grok/acp-client.ts
git commit -m "feat(grok): grok agent stdio runner + AcpClient.setWrite"
```

---

## Task 7: GrokAdapter — persistent ACP session, prompt, events, interrupt

The adapter holds one ACP session. `start()` spawns the child, does `initialize` → `session/new`, stores the sessionId + discovered models. `send()` enqueues; the drain loop issues `session/prompt` and awaits the prompt response (turn end). `session/update` notifications become `AgentEvent`s. `interrupt()` sends `session/cancel`. `stop()` kills the child.

**Files:**
- Create: `src/core/agents/grok/adapter.ts`
- Test: `src/core/agents/grok/adapter.test.ts`

- [ ] **Step 1: Write the failing test (fake runner replays captured frames)**

```ts
import { test, expect } from "bun:test"
import { GrokAdapter } from "./adapter"
import { AcpClient } from "./acp-client"

// Fake runner: captures the client, lets the test drive frames + resolve requests.
function fakeRunner() {
  let client!: AcpClient
  let exit!: (c: number | null) => void
  const runner = (opts: any) => { client = opts.client; exit = opts.onExit; return { kill: () => exit(0) } }
  return { runner, feed: (o: any) => client.feed(JSON.stringify(o) + "\n"), get client() { return client }, exit: () => exit(0) }
}

test("start() handshakes and send() streams reply + tool events then completes", async () => {
  const fr = fakeRunner()
  const events: any[] = []
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  for (const k of ["assistant-message", "tool-call", "turn-start", "turn-complete"]) adapter.on(k, (e) => events.push(e))

  const started = adapter.start()
  // Respond to initialize (id 1) then session/new (id 2).
  fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1, _meta: { modelState: { availableModels: [{ modelId: "grok-4.5" }] } } } })
  fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started

  const sent = adapter.send("write a poem")
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "tool_call", toolCallId: "c0", title: "write", rawInput: { file_path: "/w/p.txt" } } } })
  fr.feed({ jsonrpc: "2.0", method: "session/update", params: { update: { sessionUpdate: "agent_message_chunk", content: { type: "text", text: "Done." } } } })
  fr.feed({ jsonrpc: "2.0", id: 3, result: { stopReason: "EndTurn", _meta: { inputTokens: 10, outputTokens: 2 } } })
  await sent

  expect(events.find((e) => e.kind === "turn-start")).toBeTruthy()
  expect(events.find((e) => e.kind === "tool-call" && e.phase === "started")).toBeTruthy()
  expect(events.find((e) => e.kind === "assistant-message")?.text).toBe("Done.")
  expect(events.find((e) => e.kind === "turn-complete")).toBeTruthy()
})

test("interrupt() sends session/cancel notification", async () => {
  const fr = fakeRunner()
  const adapter = new GrokAdapter({ sessionName: "s1", workdir: "/w", runner: fr.runner, persistSessionId: async () => {} })
  const started = adapter.start()
  fr.feed({ jsonrpc: "2.0", id: 1, result: { protocolVersion: 1 } })
  fr.feed({ jsonrpc: "2.0", id: 2, result: { sessionId: "sess-1" } })
  await started
  const writes: string[] = []
  fr.client.setWrite((l) => writes.push(l))
  await adapter.interrupt()
  expect(writes.some((w) => JSON.parse(w).method === "session/cancel")).toBe(true)
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/agents/grok/adapter.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement GrokAdapter**

```ts
// src/core/agents/grok/adapter.ts
import { EventEmitter } from "events"
import type { AgentAdapter, AgentKind, InboundMeta } from "../types"
import { AcpClient } from "./acp-client"
import { parseGrokUpdate } from "./stream-parser"
import type { GrokRunner } from "./runner"
import { makeLogger } from "../../../shared/log"

const log = makeLogger("agents/grok/adapter")

export type GrokAdapterOpts = {
  sessionName: string
  workdir: string
  runner: GrokRunner
  persistSessionId: (id: string) => Promise<void>
  initialSessionId?: string
  model?: string
  /** Resolve inbound attachment file_id -> local path (path-folded into the prompt). */
  resolveAttachment?: (file_id: string) => Promise<string>
  /** MCP servers to pass to session/new (mux-shim); written by mcp-writer. */
  mcpServers?: unknown[]
  env?: Record<string, string>
}

export class GrokAdapter extends EventEmitter implements AgentAdapter {
  readonly kind: AgentKind = "grok"
  readonly sessionName: string
  readonly workdir: string

  private client: AcpClient
  private child?: { kill: () => void }
  private sessionId?: string
  private persistSessionId: (id: string) => Promise<void>
  private runner: GrokRunner
  private _model?: string
  private mcpServers: unknown[]
  private env: Record<string, string>
  private resolveAttachment?: (file_id: string) => Promise<string>
  /** Discovered from initialize; consumed by model discovery. */
  availableModels: { modelId: string }[] = []

  private queue: { text: string; chat_id?: string; attachmentFileId?: string; resolve: () => void; reject: (e: Error) => void }[] = []
  private draining = false
  private activeChatId?: string
  private turnActive = false

  constructor(opts: GrokAdapterOpts) {
    super()
    this.sessionName = opts.sessionName
    this.workdir = opts.workdir
    this.runner = opts.runner
    this.persistSessionId = opts.persistSessionId
    this.sessionId = opts.initialSessionId
    this._model = opts.model
    this.mcpServers = opts.mcpServers ?? []
    this.env = opts.env ?? {}
    this.resolveAttachment = opts.resolveAttachment
    this.client = new AcpClient(() => {})
    this.client.onNotification = (method, params) => this.onNotification(method, params)
    this.client.onServerRequest = (method, params) => this.onServerRequest(method, params)
  }

  set model(m: string | undefined) { this._model = m }
  get model(): string | undefined { return this._model }

  async start(): Promise<void> {
    this.child = this.runner({
      workdir: this.workdir, env: this.env, client: this.client,
      onExit: (code) => this.onExit(code),
    })
    const init: any = await this.client.request("initialize", {
      protocolVersion: 1,
      clientCapabilities: { fs: { readTextFile: true, writeTextFile: true } },
    })
    this.availableModels = init?._meta?.modelState?.availableModels ?? init?.modelState?.availableModels ?? []
    const res: any = await this.client.request("session/new", {
      cwd: this.workdir,
      mcpServers: this.mcpServers,
      ...(this.sessionId ? { loadSessionId: this.sessionId } : {}),
    })
    if (res?.sessionId) {
      this.sessionId = res.sessionId
      this.persistSessionId(res.sessionId).catch(() => {})
    }
  }

  async resume(): Promise<void> { if (!this.child) await this.start() }

  async stop(): Promise<void> { this.child?.kill(); this.child = undefined }

  async send(text: string, meta?: InboundMeta): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      this.queue.push({ text, chat_id: meta?.chat_id, attachmentFileId: meta?.attachment_file_id, resolve, reject })
      if (!this.draining) void this.drain()
    })
  }

  private async drain(): Promise<void> {
    if (this.draining) return
    this.draining = true
    try {
      while (this.queue.length) {
        const next = this.queue.shift()!
        try {
          this.activeChatId = next.chat_id
          const text = await this.withAttachment(next.text, next.attachmentFileId)
          await this.runOne(text)
          next.resolve()
        } catch (err: any) {
          next.reject(err instanceof Error ? err : new Error(String(err)))
        } finally { this.activeChatId = undefined }
      }
    } finally { this.draining = false }
  }

  private async withAttachment(text: string, fileId?: string): Promise<string> {
    if (!fileId || !this.resolveAttachment) return text
    try {
      const path = await this.resolveAttachment(fileId)
      return text ? `${text}\n\n[Attached file: ${path}]` : `[Attached file: ${path}]`
    } catch { return text }
  }

  private async runOne(text: string): Promise<void> {
    if (!this.sessionId) throw new Error("grok session not initialized")
    this.turnActive = true
    this.emit("turn-start", { kind: "turn-start" })
    try {
      await this.client.request("session/prompt", {
        sessionId: this.sessionId,
        prompt: [{ type: "text", text }],
        ...(this._model ? { model: this._model } : {}),
      })
    } finally {
      this.turnActive = false
      this.emit("turn-complete", { kind: "turn-complete" })
    }
  }

  private onNotification(method: string, params: unknown): void {
    if (method !== "session/update") return
    for (const ev of parseGrokUpdate(params)) {
      if (ev.kind === "assistant-message") {
        this.emit("assistant-message", { kind: "assistant-message", text: ev.text, chat_id: this.activeChatId })
      } else if (ev.kind === "tool-call") {
        this.emit("tool-call", { kind: "tool-call", tool: ev.tool, phase: ev.phase, call_id: ev.call_id, detail: ev.detail })
      }
      // `thought` deltas are not forwarded as AgentEvents (state machine derives
      // thinking from turn lifecycle); kept in the parser for future use.
    }
  }

  private async onServerRequest(method: string, params: unknown): Promise<unknown> {
    // Permission requests: auto-approve for AFK sessions. Pick the first "allow"-ish
    // option the request enumerates; never hardcode an optionId (grok rejects unknowns).
    if (method === "session/request_permission") {
      const opts = (params as any)?.options as { optionId: string; kind?: string }[] | undefined
      const allow = opts?.find((o) => /allow|approve|accept|yes/i.test(o.optionId) || o.kind === "allow_once" || o.kind === "allow_always")
      const optionId = allow?.optionId ?? opts?.[0]?.optionId
      return optionId ? { outcome: { outcome: "selected", optionId } } : { outcome: { outcome: "cancelled" } }
    }
    return {}
  }

  private onExit(_code: number | null): void {
    this.client.fail(new Error("grok agent exited"))
    if (this.turnActive) { this.turnActive = false; this.emit("turn-complete", { kind: "turn-complete" }) }
    this.child = undefined
  }

  async interrupt(): Promise<void> {
    if (this.sessionId) this.client.notify("session/cancel", { sessionId: this.sessionId })
  }
}
```

Note on `AcpClient`: the fake runner in the test sets `opts.client` = the adapter's own `this.client`, so ensure the adapter constructs `this.client` and passes it into the runner (it does, in `start()`). The real runner calls `client.setWrite(...)`; the fake runner's `feed` drives `client.feed(...)`. The adapter's constructor `new AcpClient(() => {})` write is replaced by the runner via `setWrite`.

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/agents/grok/adapter.test.ts`
Expected: PASS (both tests).

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/grok/adapter.ts src/core/agents/grok/adapter.test.ts
git commit -m "feat(grok): GrokAdapter over persistent ACP session"
```

---

## Task 8: mux-shim MCP config writer for grok

Writes grok's MCP config so it gets `mux-shim` (file-delivery reply tool + orchestration tools). grok reads MCP config from `~/.grok`/project config AND accepts `mcpServers` in `session/new`; we do both.

**Files:**
- Create: `src/core/agents/grok/mcp-writer.ts`
- Test: `src/core/agents/grok/mcp-writer.test.ts`

- [ ] **Step 1: Write the failing test**

```ts
import { test, expect } from "bun:test"
import { buildGrokMcpServers } from "./mcp-writer"

test("buildGrokMcpServers returns an ACP mcpServers entry for mux-shim", () => {
  const servers = buildGrokMcpServers({
    shimCommand: "bun", shimArgs: ["run", "/app/src/shim/index.ts"],
    sessionId: "s1", sessionName: "My Session", socketsDir: "/run/mux",
  })
  expect(servers).toEqual([{
    name: "mux-shim", type: "stdio", command: "bun", args: ["run", "/app/src/shim/index.ts"],
    env: [
      { name: "MUX_SESSION_ID", value: "s1" },
      { name: "MUX_DISPLAY_NAME", value: "My Session" },
      { name: "MUX_AGENT_KIND", value: "grok" },
      { name: "MUX_SOCKETS_DIR", value: "/run/mux" },
    ],
  }])
})
```

- [ ] **Step 2: Run test to verify it fails**

Run: `bun test src/core/agents/grok/mcp-writer.test.ts`
Expected: FAIL — module not found.

- [ ] **Step 3: Implement buildGrokMcpServers**

```ts
// src/core/agents/grok/mcp-writer.ts
export function buildGrokMcpServers(opts: {
  shimCommand: string
  shimArgs: string[]
  sessionId: string
  sessionName: string
  socketsDir: string
}): unknown[] {
  // ACP `session/new` mcpServers entry shape (env is an array of {name,value},
  // matching the `_x.ai/mcp/servers_updated` frames observed live).
  return [{
    name: "mux-shim",
    type: "stdio",
    command: opts.shimCommand,
    args: opts.shimArgs,
    env: [
      { name: "MUX_SESSION_ID", value: opts.sessionId },
      { name: "MUX_DISPLAY_NAME", value: opts.sessionName },
      { name: "MUX_AGENT_KIND", value: "grok" },
      { name: "MUX_SOCKETS_DIR", value: opts.socketsDir },
    ],
  }]
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `bun test src/core/agents/grok/mcp-writer.test.ts`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/core/agents/grok/mcp-writer.ts src/core/agents/grok/mcp-writer.test.ts
git commit -m "feat(grok): mux-shim MCP servers for session/new"
```

---

## Task 9: preamble writer (AGENTS.md) + streamed-agent registration

grok merges `AGENTS.md` from git-root down. Write the supermux preamble there, git-excluded, and register grok in the broker's streamed-agent set so text-only `reply()` is rejected and assistant text is relayed.

**Files:**
- Create: `src/core/agents/grok/preamble-writer.ts`
- Modify: the broker streamed-agent set (locate via `grep -rn "REPLY_FOR_STREAMED\|text-only" src/`), and the agent header note

- [ ] **Step 1: Implement the preamble writer (mirror cursor's, target AGENTS.md)**

```ts
// src/core/agents/grok/preamble-writer.ts
import { writeFileSync, existsSync, appendFileSync, readFileSync } from "fs"
import { join } from "path"
import { buildMemoryPreamble } from "../../memory/preamble"
import { readEnvironmentMd } from "../environment"
import { buildAgentHeader } from "../agent-header"

const RULE_REL = "AGENTS.md"

export function writeGrokPreamble(opts: { workdir: string; sessionName: string }): void {
  const header = buildAgentHeader({ name: opts.sessionName, role: "worker", workdir: opts.workdir })
  const env = readEnvironmentMd()
  const memory = buildMemoryPreamble("worker")
  const body = [header, env, memory].filter((s) => s && s.trim()).join("\n\n")
  writeFileSync(join(opts.workdir, RULE_REL), body, { encoding: "utf8", mode: 0o644 })
  excludeFromGit(opts.workdir)
}

function excludeFromGit(workdir: string): void {
  const infoDir = join(workdir, ".git", "info")
  if (!existsSync(infoDir)) return
  const excludePath = join(infoDir, "exclude")
  const current = existsSync(excludePath) ? readFileSync(excludePath, "utf8") : ""
  if (current.split("\n").includes(RULE_REL)) return
  appendFileSync(excludePath, (current.endsWith("\n") || current === "" ? "" : "\n") + RULE_REL + "\n", "utf8")
}
```

Caveat to verify at integration: if the workdir already has a user `AGENTS.md`, do NOT overwrite it — instead append the supermux block under a marker or write `AGENTS.override.md` (grok honors it with precedence per its docs). Add that guard when wiring Task 10 and cover it with a test then.

- [ ] **Step 2: Register grok as a streamed agent**

Run `grep -rn "REPLY_FOR_STREAMED_AGENTS\|codex.*cursor\|isStreamed\|text-only" src/core src/shim` to find the set/predicate that currently lists codex/cursor/opencode for (a) the reply-tool re-description (already generic: any non-Claude — verify) and (b) the broker's text-only-reply rejection. Add `grok` wherever codex/cursor/opencode are enumerated as a literal set. Where it's `kind !== "claude"`, no change needed.

Write a test asserting a text-only reply from a grok session is rejected (mirror the existing codex/cursor rejection test — locate via `grep -rln "text-only\|rejects text" src/**/*.test.ts`), then make it pass.

- [ ] **Step 3: Run the shim + routing tests**

Run: `bun test src/shim/ src/core/routing/`
Expected: PASS, including the new grok rejection test.

- [ ] **Step 4: Commit**

```bash
git add src/core/agents/grok/preamble-writer.ts <the streamed-set file> <the test file>
git commit -m "feat(grok): AGENTS.md preamble + streamed-agent registration"
```

---

## Task 10: Wire GrokAdapter into the adapter factory + spawn

Construct `GrokAdapter` where cursor/codex adapters are built from a session record, wiring the real runner, mcp servers, preamble, and attachment resolver.

**Files:**
- Modify: the adapter factory (locate via `grep -rn "new CursorAdapter\|new CodexAdapter" src/`), and the spawn path that writes cursor's mcp/preamble (locate via `grep -rn "writeCursorMcpConfig\|writeCursorPreamble" src/`)

- [ ] **Step 1: Find the construction sites**

Run: `grep -rn "new CursorAdapter\|writeCursorMcpConfig\|writeCursorPreamble" src/ | grep -v test`
Read each site; grok mirrors cursor exactly except it uses `buildGrokMcpServers` (passed to the adapter as `mcpServers`, not written to a `.cursor/mcp.json`) and `writeGrokPreamble`.

- [ ] **Step 2: Add the grok branch to the factory**

At the factory switch on `kind`:

```ts
    case "grok": {
      writeGrokPreamble({ workdir, sessionName })
      const mcpServers = buildGrokMcpServers({
        shimCommand, shimArgs, sessionId, sessionName, socketsDir,
      })
      return new GrokAdapter({
        sessionName, workdir, runner: realGrokRunner,
        persistSessionId, initialSessionId, model, mcpServers,
        resolveAttachment,
        env: { /* same shim/session env the other agents pass */ },
      })
    }
```

Match the exact variable names/args the cursor case uses in that file (shimCommand/shimArgs/socketsDir/persistSessionId/resolveAttachment come from the same scope).

- [ ] **Step 3: Run the full broker test suite**

Run: `bun test`
Expected: PASS. Fix any `Record<AgentKind, …>` exhaustiveness errors surfaced by Task 1 by adding grok entries mirroring opencode.

- [ ] **Step 4: Commit**

```bash
git add <factory file> <spawn file>
git commit -m "feat(grok): construct GrokAdapter in the adapter factory"
```

---

## Task 11: Model discovery + reasoning effort

Surface grok's `availableModels` (from `initialize`) and `reasoningEfforts` to the existing model/effort discovery endpoints so the client pills work.

**Files:**
- Modify: the model-discovery module (locate via `grep -rn "discoverModels\|reasoning-levels\|availableModels" src/core | grep -v test`)

- [ ] **Step 1: Find how cursor/codex expose models**

Run: `grep -rn "reasoning-levels\|discoverModels\|modelState\|availableModels" src/core | grep -v test`
Read the discovery seam. For claude it's static; for codex/cursor it shells the CLI. For grok, the models come from the live ACP `initialize` result (`GrokAdapter.availableModels`) — expose them from the running session, OR shell `grok models` for the session-less discovery endpoint.

- [ ] **Step 2: Write a test for grok reasoning levels**

Add a test asserting `GET /reasoning-levels?agent=grok` returns `high|medium|low` (from the captured `reasoningEfforts`) — mirror the codex reasoning-levels test (locate via `grep -rln "reasoning-levels" src/**/*.test.ts`).

- [ ] **Step 3: Implement**

Add a grok arm to the reasoning-levels + model-discovery handlers returning the three efforts and the `grok-4.5` model. Keep it graceful: if only one model is offered, the client already hides the model pill (existing behavior).

- [ ] **Step 4: Run test + commit**

```bash
bun test <discovery test file>
git add <discovery files>
git commit -m "feat(grok): model + reasoning-effort discovery"
```

---

## Task 12: Live-verification against the real broker (the supermux gate)

No merge without a real grok session working end-to-end (supermux culture). The free Grok Build window may close — do this promptly.

- [ ] **Step 1: Build + full test suite**

Run: `bun test` and the repo typecheck.
Expected: all green.

- [ ] **Step 2: Preview the broker from this worktree**

Use the `mux:preview-broker` skill to run the broker from this worktree against the live PWA (auto-reverts on a timer). Confirm `~/.grok/auth.json` exists (from the recon login) so grok is authed.

- [ ] **Step 3: Spawn a real grok session and verify**

From the web PWA, start a new session with agent = **grok** in a scratch git repo. Send: "Create hello.txt with a haiku, then read it back and tell me the line count."
Verify, capturing evidence (screenshot/log):
- The reply text renders (from `agent_message_chunk` stream-relay).
- Activity rows render natively (▸ Write … done / ▸ Read … done) — proves the `adapter-activity` grok branch.
- The state pill shows thinking→running→idle and returns to idle at turn end.
- Interrupt (ESC/stop) ends the turn cleanly.
- The model pill shows grok-4.5; effort pill shows high/medium/low.

- [ ] **Step 4: Verify outbound file delivery**

Send: "Generate a small PNG (e.g. via ImageMagick or a here-doc) and send it to me." Confirm grok calls the file-delivery `reply(files=[…])` and the image arrives in chat (proves the streamed-agent reply-tool path + `transform-outbound`).

- [ ] **Step 5: Record findings to memory**

Append results (what worked, any frame-shape surprises vs. the recon) under a dated heading in `~/.mux/domains/claudemux.md`.

- [ ] **Step 6: Finish the branch**

Use `superpowers:finishing-a-development-branch` to choose merge/PR. Do NOT push without Ahmet's go (supermux release rule).

---

## Self-Review (completed against the spec)

- **Spec coverage:** Reply/text (Task 9 streamed reg), files (Task 8 mcp + Task 12 verify), activity (Task 5), thinking/running/dead (Task 7 turn lifecycle + onExit), interrupt (Task 7), model/effort (Task 11), usage (captured in `session/prompt` result `_meta` — surfaced via the same discovery/usage seam; Task 11 covers the discovery half, the token accounting is available in `runOne`'s result for a follow-up usage-panel wire), permissions (Task 7 onServerRequest), AGENTS.md preamble (Task 9), MCP (Task 8), detect/install (Task 2). **Gap flagged:** the usage-panel *display* wiring (feeding `session/prompt` result `_meta` tokens into the usage store) is noted but not a standalone task — add it during Task 11 if the usage seam is adjacent, else a fast follow-up. Slash commands (`available_commands_update`) are surfaced by the generic launcher path once grok is a kind; no broker task needed.
- **Placeholder scan:** all code steps contain real code; the two "locate via grep" steps (Tasks 9/10/11) are integration wiring into files whose exact paths are environment-specific — each gives the exact grep to run and the exact shape to add.
- **Type consistency:** `AcpClient` (setWrite/feed/request/notify/fail/onNotification/onServerRequest), `parseGrokUpdate`, `GrokAdapter` opts, `buildGrokMcpServers`, `writeGrokPreamble` names are consistent across tasks. `GrokRunner` signature in Task 6 matches its use in Task 7's fake runner and Task 10's `realGrokRunner`.

---

## Follow-up (sibling plan, not here)

`2026-07-13-grok-build-clients.md` — add grok to the web/iOS/Android/desktop agent pickers + brand logo asset. The clients already render agent activity/state agnostically, so this is additive enum + asset work per platform.
