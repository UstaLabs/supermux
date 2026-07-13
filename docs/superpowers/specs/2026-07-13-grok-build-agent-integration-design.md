# Grok Build (xAI) agent integration — design

**Date:** 2026-07-13
**Author:** supermux session `grok-cli-supermux-integration`
**Status:** Draft, awaiting review
**Scope:** Full parity — add Grok Build as a first-class agent kind alongside claude/codex/cursor/opencode.

## Summary

Add **Grok Build** (xAI's official terminal coding agent, `grok`, v0.2.99) as a new
supermux agent kind: **`grok`**. Grok Build exposes the **Agent Client Protocol
(ACP)** — JSON-RPC 2.0 over stdio via `grok agent stdio` — which carries structured
streaming, tool-call, model, permission, and usage events. This maps cleanly onto
supermux's existing agent-agnostic seam (`AgentAdapter` → `AgentEvent` →
`adapter-activity.ts` → `ActivityEvent`), so this is an *additive adapter*, not new
architecture.

All findings below were **live-verified** on the box (see "Recon evidence").

## Motivation

- Grok Build entered the terminal-coding-agent race (Claude Code / Codex CLI / Cursor)
  on 2026-05-25; supermux's positioning pillar is **agent-agnostic**. Adding grok
  keeps that promise credible.
- Free right now via xAI's launch promo (no API key); fallback `grok-build-0.1` API is
  cheap ($1/1M in, $2/1M out).
- The integration surface is unusually clean (a documented standard protocol), so the
  cost/benefit is strongly positive.

## Recon evidence (live-verified 2026-07-13)

- Install: `curl -fsSL https://x.ai/cli/install.sh | bash` → `~/.grok/bin/grok` (v0.2.99).
- Auth: device-code (`grok login --device-auth`), token cached at `~/.grok/auth.json`.
  Signed in via grok.com subscription; sole model **grok-4.5** (500k ctx, agentType
  `grok-build-plan`).
- **`grok agent stdio` = ACP (JSON-RPC 2.0).** Handshake:
  `initialize {protocolVersion:1, clientCapabilities:{fs:{...}}}` →
  `session/new {cwd, mcpServers}` → `session/prompt {sessionId, prompt:[{type:"text",text}]}`.
- **MCP auto-discovery works:** the ACP init stream already surfaced supermux's
  `mux-shim` + `mux-channel` MCP servers (`_x.ai/mcp/servers_updated`) with zero extra
  config. `mcpCapabilities: {http:true, sse:true}`.
- **`session/update` variants** observed:
  - `agent_thought_chunk` — reasoning token deltas → **thinking** state
  - `agent_message_chunk` — assistant reply token deltas → **reply text**
  - `tool_call` — `{toolCallId, title:"write", rawInput:{file_path,content,...}}`
  - `tool_call_update` — `{toolCallId, kind:"edit", title:"Write \`path\`", status:"completed"|"failed", content}`
  - `user_message_chunk`, `available_commands_update`
- **`session/request_permission`** — server→client request; client replies with a valid
  `optionId` (enumerated in the request). Wrong id → `tool_call_update status:"failed"`.
- **Hooks:** `_meta.x.ai/hooks` — blockingEvents `["pre_tool_use"]`, decisions `["deny"]`.
  `x.ai/fs_notify: true`.
- **Model/effort:** `initialize` result `modelState.availableModels[]` carries
  `reasoningEfforts` (high/medium/low, default high) + `supportsReasoningEffort`.
- **Usage:** ACP prompt-done result `_meta` carries
  `{inputTokens, outputTokens, cachedReadTokens, reasoningTokens, apiDurationMs, numTurns, modelUsage}`.
- **Note:** the simpler `grok -p --output-format streaming-json` headless mode emits only
  `text`/`thought` deltas + a final `end` — **no tool events**. Therefore the adapter
  MUST use `grok agent stdio` (ACP) to get activity.

## Architecture

### Where it slots in

grok is **tmux-free** (like cursor/codex — the broker owns the process pipe, no TUI
window) but **persistent-process** (like claude — one long-lived ACP process per session
holds session state, unlike cursor's one-shot-per-turn `-p`). This is a supported shape:
`AgentAdapter.start()` spins up and holds the ACP process; `send()` issues a
`session/prompt` on the live session; `interrupt()` sends ACP `session/cancel`;
`stop()` tears the process down.

### New unit: `src/core/agents/grok/`

Mirrors `src/core/agents/cursor/`:

| File | Purpose |
|---|---|
| `adapter.ts` | `GrokAdapter implements AgentAdapter`. Holds the ACP client, drives the handshake, translates `session/update` → `AgentEvent`, manages the prompt queue + abort. |
| `acp-client.ts` | Thin JSON-RPC 2.0 framing over the stdio pipe (newline-delimited JSON): request/response correlation by `id`, notification dispatch, server→client request handling (permissions, fs). Isolated + unit-testable with a fake pipe. |
| `runner.ts` | Spawns `grok agent stdio` with the right env/PATH/cwd; exposes stdin/stdout streams + a kill/abort. Real vs. fake (test) runner, same as cursor. |
| `mcp-writer.ts` | Writes grok's `mux-shim` MCP config (into `~/.grok` MCP config and/or the `session/new` `mcpServers` array) — same role as cursor's `mcp-writer.ts`. Gives grok the file-delivery reply tool + orchestration/side-effect tools. Do NOT rely on grok auto-discovering supermux's project MCP config; write it explicitly per session. |
| `preamble-writer.ts` | Writes the supermux system prompt via `AGENTS.md` (grok merges git-root-down), matching how codex/cursor ingest their preamble. |
| `auth.ts` | Detects `~/.grok/auth.json`; surfaces "signed in / free tier / needs login" for the session-detect + install UI. |
| `activity map` (in `adapter-activity.ts`) | New `if (agent === "grok")` branch in `summarizeDetail`. |

### Registration touch-points (existing files)

- `src/shared/agents.ts` — add `"grok"` to the `AgentKind` union + logo/label metadata.
- Broker outbound/routing — add grok to the **streamed-agent set** so text-only `reply()` is
  rejected and `agent_message_chunk` is relayed (grok is non-Claude, so `listTools()` already
  gives it the file-only reply description automatically).
- `src/core/agents/detect.ts` — add grok binary detection + `~/.grok/auth.json` cred path.
- `src/core/agents/install.ts` / `bin-dirs.ts` — grok install detection + PATH dir.
- `src/core/agents/tool-normalize.ts` — grok tool-name normalization (`write`→Write, etc.).
- Adapter factory (wherever cursor/codex adapters are constructed) — construct `GrokAdapter`.
- Clients (web/iOS/Android/desktop) — grok appears wherever the agent picker enumerates
  kinds; brand logo asset; model/effort pills read from the shared discovery endpoints.

### Data flow (one turn)

```
inbound msg ─► GrokAdapter.send() ─► queue ─► session/prompt (ACP)
                                                   │
  grok agent stdio ── session/update ──────────────┤
      agent_message_chunk  ─► buffer ─► assistant-message (flush at turn end)
      agent_thought_chunk  ─► (thinking state via hooks/state machine)
      tool_call            ─► tool-call {phase:"started"}  ─► ActivityEvent
      tool_call_update     ─► tool-call {phase:"completed"|"failed"} ─► ActivityEvent
  session/request_permission (server→client) ─► approval flow ─► reply optionId
  prompt result (_meta usage) ─► usage accounting
```

**Reply model — grok is a "streamed agent" (identical to codex/cursor/opencode), verified
against the code:** grok's normal words reach the user by the broker **relaying
`agent_message_chunk`** (the `assistant-message` event), NOT via the reply tool. `mux-shim`
IS still injected, and `listTools()` automatically hands any non-Claude kind the reply tool
**re-described as file-delivery-only** (`REPLY_FOR_STREAMED_AGENTS`, `src/shim/tools.ts`):

- **Text** → stream-relayed automatically. The broker **rejects text-only `reply()`** from
  streamed agents to prevent a double-send (a grok-visible rule stated in the preamble/agent
  header, mirroring codex/cursor).
- **Files/attachments** → the reply tool's `files[]` is the **only** outbound file channel;
  `transform-outbound.ts` reads each path and registers it into the file store as an
  attachment. Without the reply tool, grok could not send a file back — hence we keep it.
- grok also gets the orchestration/side-effect tools (spawn_session, react,
  download_attachment, expose_port, memory_search, …), same as the other streamed agents.

Making grok a streamed agent is almost free: it's the generic non-Claude path. grok just
needs (a) to be an `AgentKind` and (b) a `grok/mcp-writer.ts` that writes its mux-shim MCP
config (via `~/.grok` MCP config and/or the `session/new` `mcpServers` array), plus adding
grok to the broker's text-only-reply rejection set.

## Full-parity feature map

| supermux capability | Grok Build mechanism |
|---|---|
| Reply to user (text) | Broker relays `agent_message_chunk` (stream-relay); text-only `reply()` is rejected — same as codex/cursor/opencode |
| Send files/attachments out | `mux-shim` `reply` tool with `files[]` (file-delivery-only for streamed agents) → `transform-outbound.ts` |
| Orchestration / side-effects | `mux-shim` tools (spawn_session, react, download_attachment, expose_port, memory_search, …) |
| Activity stream (▸ verb arg … status) | `tool_call` / `tool_call_update` → `ActivityEvent` |
| Thinking / running / idle state | ACP turn lifecycle (`session/prompt` start → result) + `agent_thought_chunk`; feeds the broker's pure-reflector state machine |
| Dead/crash detection | ACP pipe close (process exit) → liveness `dead`, same as today |
| Interrupt | ACP `session/cancel` on the active `sessionId` |
| Model discovery | `initialize` → `modelState.availableModels[]` (refreshed by the periodic all-agents discovery) |
| Model / effort switching | grok's single model today; `reasoningEfforts` high/med/low via ACP session update / `--effort`; wired into the existing live-switch endpoints |
| Usage panel | prompt-result `_meta` token accounting (`inputTokens`/`outputTokens`/`cachedReadTokens`/`reasoningTokens`) |
| Permission approval UI | ACP `session/request_permission` → supermux approval prompt → reply `optionId`; default spawn uses `--permission-mode` matching supermux policy |
| System prompt | `AGENTS.md` (git-root-down merge) via `preamble-writer.ts` |
| Slash commands | `available_commands_update` (`compact`, `context`, `session-info`, `goal`, `always-approve`) surfaced to the launcher slash menu |
| `fs_changed` (editor refresh) | `x.ai/fs_notify` notifications |
| Attachments | fold resolved file path into the prompt text (cursor's approach) unless ACP prompt content-parts accept files (`promptCapabilities.image:false` today → path-fold) |
| Resume | ACP `session/load` with the persisted `sessionId` (agentCapabilities `loadSession:true`) |

## Error handling

- **ACP process dies mid-turn:** pipe-close → flush any buffered assistant text → emit
  `turn-complete` (clean end, not error) → liveness marks `dead` if it doesn't respawn.
  Never leave the queue's drain loop hung (mirror cursor's `exitDone` discipline).
- **Malformed / unknown `session/update`:** log + skip; never throw out of the parser
  (one bad frame must not kill the turn).
- **Permission request with unknown options:** reply with the deny/first-safe option and
  surface the tool as `failed`, rather than hanging the turn.
- **JSON-RPC id correlation:** requests without a matching pending id are logged and
  dropped; server→client requests (permission/fs) always get a response so grok never
  blocks waiting on us.
- **Auth expiry / free-window close:** `auth.ts` detects missing/expired token → session
  shows "needs login"; spawning fail-closes gracefully with a clear message (not a crash).

## Testing

Follow the repo's existing per-agent test pattern (fake runner + recorded frames):

- `acp-client.test.ts` — JSON-RPC framing: request/response correlation, notification
  dispatch, server→client request handling, partial-line buffering across chunks.
- `grok/adapter.test.ts` — feed **recorded real ACP frames** (captured during recon:
  `acp-full.log`) through a fake runner; assert the emitted `AgentEvent` sequence
  (turn-start, tool-call started/completed, assistant-message, turn-complete).
- `adapter-activity.test.ts` — add grok cases: `tool_call`/`tool_call_update` →
  correct `ActivityEvent` title/detail/phase (parity with the cursor/codex cases).
- `tool-normalize.test.ts` — grok tool-name mappings.
- `detect.test.ts` — grok binary + auth detection.
- **Live-verify gate** (supermux culture): spawn a real grok session against the live
  broker, confirm reply + native activity rendering + model pill + interrupt, before
  merge. Capture a fresh transcript while the free window is open.

## Rollout / phasing

Delivered as one full-parity effort, but built in reviewable milestones:

1. **M1 — ACP client + runner + handshake** (`acp-client.ts`, `runner.ts`, detect/install),
   unit-tested against recorded frames. No user-visible session yet.
2. **M2 — GrokAdapter: spawn, prompt, reply, activity, lifecycle, interrupt.** First
   end-to-end working grok session (reply + activity render). Live-verified.
3. **M3 — parity polish:** model/effort discovery + switching, usage panel, permission
   approval UI, `AGENTS.md` preamble, slash commands, `fs_changed`, attachments, resume.
4. **M4 — clients:** grok in every agent picker + brand logo + pills across
   web/iOS/Android/desktop.

## Open questions / risks

- **Beta churn:** Grok Build is early beta; ACP frame shapes may shift. Mitigation:
  isolate all parsing in `acp-client.ts` + `stream-parser`-equivalent, lock behavior with
  recorded-frame tests so a breaking change fails loudly at test time.
- **Permission `optionId` enumeration:** must read options from each
  `session/request_permission` rather than hardcode; confirm the auto-approve path
  (`always-approve` / `--permission-mode`) for unattended AFK sessions.
- **Single model today:** effort-switch UI should hide gracefully if only one model /
  effort is offered (reuse the reasoning-levels endpoint's "none → hidden" behavior).
- **Auth is device-code / subscription-bound:** multi-host / headless brokers may need the
  API-key path (`GROK_API_KEY` + `config.toml`) instead of `~/.grok/auth.json`; support
  both in `auth.ts`.

## Non-goals

- Running grok's fullscreen TUI in a tmux window (rejected: throws away the structured
  ACP stream for brittle screen-scraping).
- The community `superagent-ai/grok-cli` (separate tool; not this integration).
- Grok's `agent serve` (WebSocket) / `agent headless` (xAI relay) surfaces — stdio ACP is
  sufficient and matches the local-process model.
