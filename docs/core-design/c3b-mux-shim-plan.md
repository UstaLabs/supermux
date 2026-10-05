# C3b plan: mux-shim becomes a host MCP server in the broker (2026-10-05)

Status: **built** (see "C3b as built" at the end, which supersedes the plan where they differ).
Plan text below as approved, except "Rollout and rollback", rewritten for the preview target.

Original status: plan, no code. C3a (`session-context.md` "C3a as built") made mux-shim an EXTERNAL
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

## Rollout and rollback (the core-exploration preview only)

This branch runs ONLY as the isolated core-exploration preview: transient systemd unit
`mux-core-preview-relay` (`bun src/main.ts` from this worktree), state `~/.mux/preview-core-state`,
port 9899, served through the supermux connect relay. Not `mux.service`, not the :9898
preview-broker swap. The supervisor restarts the preview and does the flip; nothing here starts or
stops a service. The preview shares the user's HOME (and so the real `~/.claude.json`) with the
LIVE broker (`mux.service`, `~/projects/supermux`).

1. **Mode switch.** `muxShim: "external" | "host"`: the settings-table key `muxShim` (JSON string),
   else env `MUX_SHIM`, else `"external"`. Read once at boot (`mux_shim_mode` log line); a flip is a
   restart. `"external"` launches byte-for-byte as C3a (`tests/c3b-launch-modes.test.ts`; Cursor
   apart from its 2026-10-05 instructions / plugins change, `CURSOR_C3`). Since 2026-10-05 Cursor
   follows the mode like every agent (it was external in both). Both host servers are registered with every agent host in both modes.
2. **Step 1: external.** Restart the preview on this commit with no `MUX_SHIM` (or
   `MUX_SHIM=external`). Expect `mux_shim_mode {"mode":"external"}`; sessions resume as with C3a.
   What changed in this mode: the shared handlers (same results, de-dup now in the handler), and the
   dead inbound channel path is gone (see "C3b as built").
3. **Step 2: host, when sessions are idle.** The flip changes every session's context (Cursor too since 2026-10-05)
   (mux-shim external spec → host server), hence its keeper fingerprint: the first resume of a
   detached session relaunches its agent instead of re-attaching, which cuts a running detached
   turn. Recreate the unit with the same env plus `MUX_SHIM=host` (systemd-run gotcha: a stopped
   `KillMode=process` unit whose keepers are still in its cgroup stays loaded, so a new
   `systemd-run --unit=<same name>` fails "already loaded"; use a new unit name). Expect:
   - `mux_shim_mode {"mode":"host"}`;
   - `~/.claude.json`: `removeBrokerShimEntries` removes `mux-shim` / `mux-channel` only when they
     are THIS worktree's spawn spec. Today they hold the LIVE broker's
     (`bun run /home/ahmet/projects/supermux/src/shim/index.ts`), so the preview logs
     `claude_json_foreign_shim_entry_kept` twice and leaves them (correct: the live broker needs
     them). Read-only pre-check: `jq -c '.mcpServers | with_entries(select(.key|test("mux")))' ~/.claude.json`.
     If they point at this worktree (an external-mode preview PA spawn rewrote them), the flip
     removes them and the live broker's Claude sessions miss mux-shim until its next spawn writes
     them back: flip only when they are the live broker's;
   - Claude keeps the context's `mux-shim` over the global one of the same name (one `mux-shim` in
     the init frame, `source: "dynamic"`; proven live) but still starts the global `mux-channel`
     (the live broker's code, zero tools): log `claude_stray_mux_channel`, harmless;
     `claude_duplicate_mux_shim` must never appear;
   - each agent, new session: `reply` with a file, `rename_session`, `list_sessions` (PA ok,
     worker `permission denied (can_orchestrate=false)`); no `src/shim/index.ts` process for a
     session (apart from that stray mux-channel); the UI connected dot follows the
     adapter (sweep every 2 s).
4. **Rollback.** Recreate the unit without `MUX_SHIM`. Records naming `{ kind: "host", name:
   "mux-shim" }` get the external spec back on their next resume (the context is replaced on every
   resume; both host servers stay registered, so nothing fails with `missing_mcp_servers`); again a
   fingerprint change → relaunch of detached sessions. External mode writes the ~/.claude.json
   entries again only through `preAcceptTrust` (PA spawns), as in C3a: if host mode had removed
   this broker's own entries, Claude system-account sessions resumed before the next PA spawn have
   no mux-shim (not the case on the preview today, whose entries are the live broker's).
5. **Later** (after a quiet period in host mode; Cursor's host server is verified, see below, apart from the C3b live run): delete `src/shim`,
   the socket tool frames, the external spec, the setting, the zero-tools `mux-channel`, and the
   `@modelcontextprotocol/sdk` v1 dependency.

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
- **Cursor (2026-10-05):** host servers work on Cursor (C2 live with model Auto: two host + one
  external server, a tool added mid-session through a reload, a throwing tool, a subagent calling a
  host tool, a detached re-attach on the same pid with the bridges kept; the call through the
  reconnected bridge and `scripts/c3b-live.ts cursor` were not run: the free plan's Auto quota ran
  out). Cursor now follows `muxShim` like every agent.

## C3b as built (2026-10-05)

**Core** (`db9aa982`): `createHost({ mcpServers })` → `createCore({ mcpServers })`; `supermux-core/mcp`
re-exports the SDK's `McpServer` and `fromJsonSchema` (probed on 2.3.0: the JSON Schema is listed
verbatim, arguments are validated by the SDK's ajv validator, extra properties pass through).

**Broker** (`287f10dd`):
- `src/core/mux-tools/server.ts`: `muxShimHostServer` / `muxRpcHostServer` =
  `mcpServer({ name, create })`; `create(ctx)` registers `listTools(ctx.agent, rpcOnly)` with
  `fromJsonSchema(inputSchema)` (no zod in the broker, root stays zod 3) and calls the bound
  handler. `MUX_HOST_SERVERS` goes to all five `createHost` calls in both modes.
- `SessionManager.outbound(sessionId, op)` / `orchestration(sessionId, op)`: the shared handlers.
  `handleOutbound` / `handleOrchestration` (socket frames) delegate to them. The 10 s single-flight
  moved from `socket-server.ts` into `orchestration()` (key session + op + args, kept 10 s after it
  settles), so the socket path and the host server share one de-dup. Gate, per-agent reply rule,
  error texts unchanged; a thrown handler → `handler threw: …` on both paths.
- `src/shim/tools.ts`: `toolRoute` + `toolResult` (the one mapping) used by `callTool` and the host
  server; a unit test compares both for every result shape.
- Cancellation: the host tool handler never passes `ctx.signal` on. An agent cancel or session
  interrupt gets the core's "Cancelled" answer while the mutation finishes; the retry gets the
  de-duplicated result (`server.test.ts`: spawn_session and kill_session cancelled mid-flight).
- `src/core/mux-tools/mode.ts`: `muxShim` setting → `MUX_SHIM` → "external"; `muxShimModeFor`
  (the same for every agent; until 2026-10-05 Cursor was always external). Core-hosts: `muxShimContextServer` (Codex / Grok / OpenCode / Cursor);
  Claude: host → `[muxShimHostServer]`, rpc worker → `[muxRpcHostServer]` (still
  `--strict-mcp-config`; the rpc json file is still written and only read in external mode).
  `claudeAccountArgs`: no `mcp-account.json` in host mode.
- `trust.ts`: `preAcceptTrust` writes the two entries only in external mode;
  `removeBrokerShimEntries()` (main.ts, host mode, at boot before any launch) deletes them only when
  command, args and the MUX_CHANNEL_ONLY flag equal this broker's spawn spec; a foreign entry is
  kept and logged.
- `src/core/mux-tools/duplicates.ts`: the Claude core-host watches native `system/init` frames;
  two `mux-shim` → `claude_duplicate_mux_shim` (warn); a `mux-channel` in host mode →
  `claude_stray_mux_channel` (info).
- Connected dot: in host mode no shim connects for a core session, so `main.ts` sweeps
  `CoreAdapter.isAlive()` every 2 s (`adapter-liveness.ts`) and feeds the edges into
  `applyConnectionStatus` (the socket's old callback body: `session_state` broadcast, "connected"
  revives a dead session, a non-suspended session going down → "dead", background tasks cleared,
  subagents abandoned). Socket status is ignored for core sessions in host mode (a leftover shim
  may still connect). External mode: unchanged (the socket drives it).

**Equivalence** (`tests/c3b-launch-modes.test.ts`, baseline `tests/c3-launch/c3a.json` recorded on
`0f83c1f3` before any C3b change, deterministic across two captures): external mode, all five
agents × worker / PA, `toEqual` the C3a launch (args, env, instructions, plugins, MCP servers,
generated files). Host mode: the only diff key is `mcpServers`; the `mux-shim` entry becomes
`process.execPath <core>/src/mcp/bridge.ts --server mux-shim` with env
`SUPERMUX_MCP_SESSION/SOCKET/TOKEN` and nothing else changes (Cursor: also `CURSOR_C3`; until 2026-10-05 Cursor was identical to C3a). The pre-C3
equivalence test still passes.

**Live** (`bun scripts/c3b-live.ts`, broker core-hosts in host mode with the REAL shared handlers
over a scratch registry, `~/.cache/context-c3b/`, haiku / gpt-5.6-luna low / grok low /
opencode-go qwen3.7-plus): 47/47, runs `live-2026-10-05T14-56-12-086Z` (codex, grok, opencode
31/31) and `live-2026-10-05T14-59-04-997Z` (claude 16/16).

| | Claude | Codex | Grok | OpenCode |
|---|---|---|---|---|
| PA: rename / list_sessions / reply + file | ok / ok / ok | ok / ok / ok | ok / ok / ok | ok / ok / ok |
| worker: list_sessions | gate error | gate error | gate error ("Failed to call list_sessions: permission denied …") | gate error |
| external shim or mux-channel process | none (bridge only) | none | none | none |
| detached restart (new core, same registry) | same pid, same bridge, reply ok | same | same | same |

Claude also: the scratch `~/.claude.json` with this broker's two entries → removed at boot, none
re-added, init frame one `mux-shim`, no `mux-channel`. With FOREIGN entries (another shim path, the
preview's real case): kept; the init frame lists `mux-shim` (source `dynamic`, the host one, which
answered the call) and `mux-channel` (source `user`, started, zero tools).

**Deleted** (`5ce46e04`; dead in both modes: every session's input goes through its adapter):
- `socket-server.ts`: inbound queue (cap 20, 5 min expiry), `sendInbound`, channel-connection
  tracking, the undeliverable grace timer, `onUndeliverable` / `deliveryGraceMs`.
- `main.ts` `onUndeliverable`; `SessionManagerPorts.socket`; `deliverInbound`'s Claude socket fallback
  (`isClaude` / `sendInboundSocket`): no adapter → `adapter_not_ready` for every agent and for an
  unknown id.
- `src/shim/inbound-gate.ts` + `inbound-gate.test.ts` + `channel-gate-e2e.test.ts`; the shim's
  `experimental: { "claude/channel" }` capability and channel notifications; the socket client's
  `onInbound` / `channelOnly` / `channel_only` on register.
- Tests of that path (socket-transport inbound / queue / undeliverable, the socket-path deliver
  tests, the e2e inbound leg).

Kept, and why: `MUX_CHANNEL_ONLY` as a ZERO-TOOLS `mux-channel` (external mode still writes the
`mux-channel` entry, and a Claude with any such entry starts it: with tools it would double every
call); the `mux-channel` entries in `preAcceptTrust` (external) and the rpc json; the frame parser's
`inbound` / `channel_only` (wire compatibility with older shims); the shim tool path (
external mode, rollback); the tmux helpers (`retireTmuxWindow` and the non-core kill branch
retire a tmux-era Claude window on its first Core resume: the migration the plan asks for).

**core=0 rows.** Read-only query on a copy of `~/.mux/preview-core-state/db.sqlite3`: 20 rows with
`core=0`, all active, none of them Claude (codex 8, cursor 5, grok 3, opencode 4); 9 Claude rows,
all `core=1`. `core` is a Claude-only flag (the other kinds were always adapter-driven), so the
socket inbound fallback had no user on the preview: removed, no fallback kept.

**Differences from the plan / behaviour changes in host mode.**
- Arguments are validated by the SDK before the handler (the external shim validated nothing):
  e.g. `reply` without `text`, a non-object rpc `data`, an enum mismatch → `isError`
  "Input validation error: …". An unknown tool name is a JSON-RPC error ("Tool x not found")
  instead of the shim's `isError` text.
- No 10 s ceiling on a host call (the shim's socket timeout); a long `spawn_session` simply takes
  longer. A call while the broker is down gets the bridge's "host unavailable" at once.
- The de-dup now also covers a socket call and a host call of the same op (a stray external shim
  next to the host server).

**Open risks.**
- Shared HOME with the live broker: see rollout step 3 (`~/.claude.json` entries) and the stray
  zero-tools `mux-channel` every host-mode Claude system-account session starts from them.
- Keeper fingerprint relaunch on every flip (both directions).
- ~~Cursor stays external~~: since 2026-10-05 Cursor follows the mode (host servers proven on
  Cursor in C2; `scripts/c3b-live.ts cursor` supports it but has not run: quota).
- rpc workers in host mode were unit-tested (tool list, resolve → settle) but not run live.
- Token in the Codex app-server argv (C2 item), unchanged.
- A live rollout to `mux.service` state would meet tmux-era Claude rows (`core=0`) with live panes,
  which no longer get input until they are resumed through Core (the socket fallback is gone).
