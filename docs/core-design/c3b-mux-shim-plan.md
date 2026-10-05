# C3b plan: mux-shim becomes a host MCP server in the broker (plan only, 2026-10-05)

Status: **plan, no code.** C3a (`session-context.md` "C3a as built") made mux-shim an EXTERNAL
context server with exactly its old command and env. C3b replaces that process and its socket
protocol with a `supermux-core/mcp` host server inside the broker, so a tool call runs the
broker's handler directly. Line numbers are from this worktree at C3a.

## Today (after C3a)

- Each agent starts `bun run src/shim/index.ts` (compiled: `<binary> shim`) as a stdio MCP server
  (`src/core/session-manager/shim-spawn.ts`). Env: `MUX_SESSION_ID`, `MUX_DISPLAY_NAME`,
  `MUX_AGENT_KIND`, `MUX_SOCKETS_DIR` (`src/core/agents/mux-shim-server.ts`).
- The shim connects to the broker's per-session socket `<SOCKETS_DIR>/<id>.sock`
  (`socket-server.ts:162-174`; bound by each agent's `session.ts` spawn, resume in
  `manager.ts:1237-1301`, and the supervisor at boot `supervisor.ts:323-326`). It sends a
  `register` frame, then one `outbound` / `orchestration` frame per tool call, with a 10 s timeout
  (`socket-client.ts:153-201`). Identity is the socket's bound session id
  (`socket-server.ts:165, 211, 244, 250`), nothing the shim says.
- The broker answers in `sessionManager.handleOutbound` (`manager.ts:408-521`) and
  `handleOrchestration` (`manager.ts:523+`), wired at `main.ts:2724-2728`. Orchestration calls are
  de-duplicated for 10 s per session + op + args (`socket-server.ts:239-248`).
- Claude system-account sessions do not get mux-shim from the context: they read `mux-shim`
  (tools) and `mux-channel` (`MUX_CHANNEL_ONLY=1`, zero tools) from the user's `~/.claude.json`,
  written by `preAcceptTrust` (`trust.ts:54-146`, called only from `spawnPA`,
  `spawn-helper.ts:159`; the entries are global, so they serve every Claude session). Subscription
  accounts get mux-shim from `<home>/mcp-account.json` (`account-env.ts:61-71`). rpc workers get
  `mux-rpc` (`MUX_RPC_ONLY=1`) + `mux-channel` from `writeRpcWorkerMcpConfig` (`trust.ts:21-30`,
  `main.ts:3424`), passed as context servers under `--strict-mcp-config`.

## Target

```ts
// src/core/mux-tools/server.ts (new): ONE host server object, built once, registered with every agent's host.
export const muxShim = mcpServer({
  name: "mux-shim",               // same name: tool ids stay mcp__mux-shim__reply etc.
  instructions: undefined,
  create: (ctx) => buildMuxShimServer(ctx),   // per (session, connection): the tool set depends on the session
})
// createHost({ …, mcpServers? }) passes it to createCore({ mcpServers }) (a small createHost addition),
// and each core-host's prepare returns context.mcpServers: [{ kind: "host", name: "mux-shim" }]
// (rpc workers: { kind: "host", name: "mux-rpc" }, a second server object).
```

`buildMuxShimServer(ctx)` looks up the broker session by `ctx.sessionId` (the core session id IS
the broker session id: every host registers with it, e.g. `claude/session.ts:103-107`), builds an
SDK `McpServer` with the tools that session gets (below), and each tool's handler calls the broker
handler in-process:

```ts
server.registerTool(name, { description, inputSchema: fromJsonSchema(jsonSchema) }, async (args) =>
  toCallToolResult(await sessionManager.handleToolCall(ctx.sessionId, kind, { name, args })))
```

`handleOutbound` / `handleOrchestration` take a socket frame today; C3b splits each into a pure
`(sessionId, op) → { ok, value?, error? }` function used by both the socket path (kept while
external shims exist, see rollout) and the host server. The result mapping is exactly
`callTool`'s (`src/shim/tools.ts:179-208`): `ok:false` → `isError` with the error text, `reply`
→ the `message_id` text, the rest → `JSON.stringify(value ?? "ok")`.

## Tool list and handlers

All in `src/core/session-manager/manager.ts`. "Gate": the `can_orchestrate` check
(`manager.ts:528-531`; true only for `personal_assistant`, `policy.ts:11-12`), skipped for the
names in `NO_ORCHESTRATE_REQUIRED`.

| Tool | Frame today | Handler | Per kind / role |
|---|---|---|---|
| `reply` | outbound | `handleOutbound` 420-453 → `onAssistantMessage` | Claude: full text reply. Others: description `REPLY_FOR_STREAMED_AGENTS` (`tools.ts:155-160`), text-only refused (427-433). Destination = reply target, never `chat_id` |
| `react` | outbound | 454-481 | refused when the channel cannot react |
| `edit_message` | outbound | 482-499 | refused when the channel cannot edit |
| `download_attachment` | outbound | 500-515 → `resolveDownloadAttachment` | |
| `spawn_session` | orchestration | 534 | gate (PA) |
| `kill_session` | orchestration | 574 | gate |
| `rename_session` | orchestration | 585 (renames the caller) | no gate |
| `mute_session` | orchestration | 610 | gate |
| `list_sessions` | orchestration | 620 | gate |
| `set_active` | orchestration | 621 | gate |
| `get_active` | orchestration | 622 | gate |
| `memory_search` | orchestration | 623-628 | no gate; PA also searches `personal/` |
| `find_sessions` | orchestration | 629 | no gate |
| `read_session` | orchestration | 639 | no gate |
| `expose_port` | orchestration | 645 | no gate |
| `unexpose_port` | orchestration | 667 (own proxies only) | no gate |
| `set_proxy_public` | orchestration | 678 | no gate |
| `list_devices` | orchestration | 695 | no gate |
| `start_display` | orchestration | 698 | no gate |
| `stop_display` | orchestration | 716 | no gate |
| `walkthrough` | orchestration | 728 | no gate |
| `reply_comment` | orchestration | 763 | no gate |
| `resolve` (rpc) | orchestration `rpc_resolve` | 726 → `agentRpc.settle` | rpc workers only |
| `reject` (rpc) | orchestration `rpc_reject` | 727 → `agentRpc.fail` | rpc workers only |

Keep the gate in the handlers (not in the listing): today every non-rpc session LISTS the
orchestration tools and the broker refuses the gated ones, and the agent-facing errors stay the
same. Optional later: list only what the session may call (a host server can, since `create` is
per session).

## PA / worker / RPC_ONLY / CHANNEL_ONLY

- **PA vs worker:** same listing (`listTools`, `tools.ts:171-177`); the difference is the gate and
  `memory_search`'s scope, both in the handlers. No change.
- **Per kind:** `create(ctx)` uses `ctx.agent` for the `reply` description (Claude vs streamed
  agents), exactly `listTools(kind)`.
- **RPC_ONLY** becomes a second host server `mux-rpc` (tools `resolve`, `reject` only), given to rpc
  workers instead of `mux-shim`. Its tool ids stay `mcp__mux-rpc__resolve` / `__reject`, which
  `buildRpcPrompt` relies on. `writeRpcWorkerMcpConfig` and the rpc json files go away; the rpc
  worker keeps `--strict-mcp-config` (it must not see the user's `~/.claude.json` servers).
- **CHANNEL_ONLY: not carried over** (decision 2026-10-05, see "Channel side: removal").

## Channel side: removal (cleanup in C3b, nothing new built on it)

The inbound "channel" (Claude `notifications/claude/channel`) was a workaround for tmux-era
Claude, which had no input pipe. Core sessions get input through their driver (stream-json,
app-server, ACP). No core-host passes `--dangerously-load-development-channels` (only comments
mention it: `trust.ts:10,43`, `socket-server.ts:46`, `shim/index.ts:31`). So a host server never
needs to send channel notifications. (It could: a `create` server is a full SDK server and the
bridge forwards every server→agent message, `host.ts:299-301`, `bridge.ts:72-81`; but Claude would
also need the dev-channels flag, and a disconnected bridge drops notifications, `bridge.ts:58-63`.)

What still references it, all to delete in C3b:
- `src/shim/index.ts:35-111`: `MUX_CHANNEL_ONLY`, the `experimental: { "claude/channel" }`
  capability, the `mux-channel` server name, the channel `notify`; `src/shim/inbound-gate.ts`
  (+ `inbound-gate.test.ts`, `channel-gate-e2e.test.ts`).
- `src/shim/socket-client.ts:119,140` (`channel_only` on register), `onInbound`.
- `trust.ts`: `CLAUDE_CHANNEL_SERVER`, the `mux-channel` entry `preAcceptTrust` writes into
  `~/.claude.json` (86-104), the `mux-channel` entry in `writeRpcWorkerMcpConfig`.
- `socket-server.ts`: inbound queue / send / channel tracking (51-54, 86-160, 222-225, 312-329).
- `main.ts:2715-2723` `onUndeliverable`; the socket branch of `deliverInbound`
  (`manager.ts:216-220`, used only when there is no adapter and the row is a persistent, i.e.
  non-core, Claude session).
- Today every system-account Core Claude session STILL starts a `mux-channel` process (from
  `~/.claude.json`) that registers `channel_only` and never receives anything: wasted process,
  and the 2026-09-19 "two shims raced on attach → mux-shim CONNECTION_CLOSED" bug lives here.

**Non-core sessions.** No code path creates a non-core agent session any more (`SessionBackend.create`
is only used for terminals, `sessiond-term.ts:312,463`); new spawns are `core=1`, and a successful
resume flips a row to `core=1` (`manager.ts:1240,1290,1350`; `supervisor.ts:127,149`). Left over:
`core=0` rows (live state from the tmux-era broker, archived rows, a row whose Core resume failed,
`manager.ts:1354-1356`) and their helpers (`retireTmuxWindow` 1206-1221, the non-core kill branch
321-324, `reconcileOnStartup` window/pid adoption `supervisor.ts:300-321`, `getSessionTmuxTarget`
`main.ts:2348-2356`, `tmux_window_id` healing `main.ts:663-668`). ⚠️ The LIVE broker
(`~/projects/supermux`, branch `dev`) predates supermux-core entirely, so on the first rollout of
this branch every live session is such a row until its first Core resume. Removal order: ship the
core branch → every live row resumed once through Core (core=1) → then delete the channel path
and the tmux helpers; a `core=0` row left after that gets a one-time "resume through Core or
archive" migration instead of the socket fallback.

## Claude's global `~/.claude.json` entry

With mux-shim a host server, Claude gets it from the context (`--mcp-config <ctx>/mcp.json`, the
bridge). The global `mux-shim` entry would then start a SECOND server with the same name
(external, socket) → duplicate tools / double calls (the reason CHANNEL_ONLY existed). So C3b must:
1. stop writing `mux-shim` and `mux-channel` in `preAcceptTrust` (keep the workdir-trust part);
2. remove those two entries from the user's `~/.claude.json` once, only when they are the
   broker's (command = the shim spawn spec), leaving the user's other servers alone;
3. drop the subscription-account `mcp-account.json` path (`claudeAccountArgs` mux-shim part): the
   context covers every account;
4. ordering: (2) must run before the first Claude launch with the host server, and not while an
   old external-shim Claude process might be relaunched by a rollback (see rollback).

Codex, Grok, OpenCode, Cursor: their context entry changes from the external spec to
`{ kind: "host", name: "mux-shim" }`; nothing global to clean.

## zod 4 vs the broker's zod 3

The broker root pins `zod ^3.23` (3.25.76 installed; used only by `src/shared/protocol.ts:1` and
`src/core/update/versions.ts:7`); the core and `@modelcontextprotocol/server` 2.3.0 use zod 4.6.5.
Plan: **no zod in the broker's tool definitions.** The tools already have JSON Schemas
(`src/shim/tools.ts`); the SDK v2 exports `fromJsonSchema(jsonSchema)` → a Standard Schema it
validates with, so a `create` server registers them as they are. That keeps the broker's zod 3
untouched and avoids two zod copies meeting in one schema. (Alternatives: `zod/v4` subpath of the
installed 3.25 — a different instance from the SDK's zod 4; or bumping the root to zod 4 and
porting the two files.) The v1 `@modelcontextprotocol/sdk` dependency goes with `src/shim`.

## Behaviour to keep

- **Identity:** `ctx.sessionId` (from the bridge token, HMAC over session + server) replaces the
  socket binding; refuse a call for an unknown / archived session with the same errors.
- **De-dup:** the 10 s orchestration de-dup (`socket-server.ts:239-248`) protects against an agent
  calling a tool twice (it was introduced for Claude's double shim). Keep it in the shared handler.
- **Timeouts:** the shim's 10 s ceiling becomes the host's own; long handlers (spawn_session) must
  still answer in time or return a "started" result.
- **Cancellation:** a session interrupt now cancels in-flight tool calls (`tool.finished ok:false`,
  C2). Handlers that mutate (spawn, kill, expose) must tolerate an aborted `ctx.signal`: finish or
  roll back, never half-apply.
- **Connected / deliverable state:** today the socket's connect/close drives
  `setConnectionStatus` (`main.ts:2689-2711`) and `isDeliverable` for non-core rows. For core
  sessions liveness is the adapter (`isAlive`), so nothing is lost; the UI "connected" dot needs a
  new source (the adapter / `tool.called` events) or goes.
- **Host restart:** detached agents keep their bridges, which reconnect to the new broker's core
  socket; calls during the restart get "host unavailable" (C2), not a 10 s socket timeout.
- **Prompts** that name `mcp__mux-shim__reply` (`prompts/reply-fallback.md:3`,
  `mux-core-assets/skills/reply-conventions/SKILL.md:16,29`): unchanged, the server keeps its name.

## Rollout and rollback with the preview broker

Prerequisite: C3a (and the core cutover it sits on) is what the preview runs; C3b is a separate,
later preview.

1. Land C3b behind a broker setting `muxShim: "external" | "host"` (default `external`), so one
   build can run both. `host` switches each core-host's context entry and stops the
   `~/.claude.json` writes.
2. Preview (`mux:preview-broker`, scheduled by the supervisor / user; it swaps :9898 and
   auto-reverts on a timer): start with `external` (= C3a behaviour), check health; then flip to
   `host` for NEW sessions only (an existing session's own context still names the external server
   until its next resume replaces it, which every resume does since the host passes the context).
3. Checks: a new Claude, Codex, Grok, OpenCode session each calls `reply` with a file,
   `rename_session`, `list_sessions` (PA) and gets the gate error (worker); an rpc worker resolves;
   a broker restart with a detached session: the bridge reconnects and the next call works; no
   `mux-channel` process; `~/.claude.json` has no broker entries.
4. Keeper fingerprints: switching mux-shim from external to host CHANGES a session's context
   fingerprint, so the first resume of a detached (keeper) session relaunches its agent instead of
   re-attaching. Do the flip when sessions are idle; a running detached turn would be cut.
5. Rollback: flip back to `external` (or the preview auto-reverts). Records now name
   `{ kind: "host", name: "mux-shim" }` in their own context; the external-mode host passes the
   external spec on every resume, which replaces it, so no `missing_mcp_servers`. A rollback to a
   broker WITHOUT host-server registration would fail those resumes with `missing_mcp_servers`:
   keep the server registered in both modes. Re-adding the `~/.claude.json` entries is part of
   `external` mode's `preAcceptTrust` (it writes them when missing).
6. After a quiet period: delete `src/shim`, the socket protocol's tool frames, the external spec,
   the setting, and the channel path (section above).

## Risks

- **Double registration** if the `~/.claude.json` cleanup misses a case (a user-added entry with
  our name, a second HOME, the Mac app's own config dir): every tool call runs twice. Mitigation:
  keep the 10 s de-dup; detect two `mux-shim` servers in Claude's init frame (`mcp_servers`) and log.
- **Fingerprint relaunch** of detached sessions on the flip (above).
- **Token in argv on Codex** (C2 open item): the bridge's per-session token is in the app-server's
  `-c mcp_servers.mux-shim.env={…}`; same-user processes can read it (as they can the secret).
- **Bridge buffering:** a call made while the broker restarts fails ("host unavailable") instead of
  waiting; agents may retry or give up. Today's shim waits up to its reconnect backoff for the
  socket. Acceptable but different.
- **Interrupt now cancels tool calls** (C2 semantics) — new for mutating orchestration tools.
- **Non-core rows** on the live state (the live broker predates the core): the channel removal must
  wait until they are migrated, or those sessions lose input.
- **Cursor:** host servers on Cursor are unverified (no quota in C0/C2); keep `external` for Cursor
  until a live check passes.
