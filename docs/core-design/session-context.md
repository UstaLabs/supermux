# supermux-core: session context: instructions, skills, plugins, MCP, tools (proposal, 2026-10-04)

Goal: one agent-neutral way to give a session extra **instructions** (system prompt), **skills**,
**plugins** and any number of **MCP servers**, external or built by the host from TypeScript functions. The core turns
these into whatever each of the five agents needs and says honestly what it could not apply.
Today this is split across `supermux-core/environment` (one helper per agent; the host passes
the result to the driver by hand) and the broker's `src/core/plugins/adapters/*`.

CLI versions on this host: claude 2.1.289, codex 0.159.2, cursor-agent 2026.09.18,
grok 1.0.46, opencode 1.16.2.

## What exists today (from code)

| | Instructions | Skills (`skillsPaths`) | Plugins | MCP servers |
|---|---|---|---|---|
| Claude | `--append-system-prompt-file` (append) | dropped | `pluginDirs` → `--plugin-dir` | `--mcp-config` (+ `--strict-mcp-config`) |
| Codex | `AGENTS.md` in session `CODEX_HOME` | dropped | broker only: `codex plugin add` into a marketplace + `-c plugins."x@mux".enabled=true` (global) | `config.toml` `[mcp_servers.*]` |
| Cursor | `.cursor/rules/mux.mdc` **written into the repo** | dropped | broker only: `--plugin-dir` | not written |
| Grok | `AGENTS.md`/`AGENTS.override.md` **written into the repo** | `[skills] paths` | broker only: skills from plugin trees | `config.toml` |
| OpenCode | `instructions` in `opencode.json` | `skills.paths` | `plugin: [...]` (+ skills.paths) | `mcp` (local) |

Problems: no single entry point; skills silently dropped for 3 agents; Cursor and Grok sessions
in the same repo overwrite each other's instructions; Codex plugins can't differ per session.

## Per-session channels, verified live (C0, 2026-10-04)

Proven against the real CLIs with a distinct code word per mechanism (`scripts/context-probe.ts`,
report: `context-c0-report.md`, commit `43bd1ccc`). Cursor was proven on 2026-10-05 with model Auto
(the only model a free plan accepts; see "Cursor cells as built").

| Agent | Instructions | Skills | Plugins | MCP | Subagents get the MCP servers |
|---|---|---|---|---|---|
| Claude | `--append-system-prompt-file` (at creation only: `--resume` keeps the stored prompt, C1b) | generated plugin dir → `--plugin-dir` | `--plugin-dir <plugin>`, or `--plugin-dir <folder of plugins>` (each child loads) | `--mcp-config` + `--strict-mcp-config` | yes |
| Codex | `thread/start {developerInstructions}` | `skills/extraRoots/set`, or `<CODEX_HOME>/skills` | local marketplace + `codex plugin add` into the session `CODEX_HOME` | session `config.toml`; needs approval policy `on-request` + auto-accept `mcpServer/elicitation/request` (with `never`, MCP tools never run) | yes |
| Cursor | **no system channel** (plugin `rules/`, `$HOME/.cursor/rules`, `$HOME/AGENTS.md`, `--add-dir`, ACP `_meta.rules`, `$HOME/.cursor/hooks.json` all ignored); the first prompt's leading block works and `session/load` keeps it | only `$HOME/.cursor/skills` / `$HOME/.agents/skills` (host-owned HOME); `--plugin-dir` ignored | **none**: `cursor-agent acp` ignores `--plugin-dir` | ACP `mcpServers` | yes (inferred) |
| Grok | ACP `session/new` `_meta.rules` (**`--rules` does nothing under `agent stdio`**) | `[skills] paths` | `grok agent --plugin-dir <p> stdio` (per process); `grok plugin install` not needed | ACP `mcpServers` | yes |
| OpenCode | config `instructions` | `skills.paths` | plugin's `skills/` → `skills.paths` (+ `plugin` for JS plugins) | ACP `mcpServers` | inferred, not observed |

MCP clients: Codex asks for protocol 2025-06-18; Claude, Cursor, Grok and OpenCode for
2025-11-25. Claude first sends `server/discover` (2026-07-28 style); the v2 SDK answers "method not
found" and Claude falls back to `initialize`. All five negotiated with `@modelcontextprotocol/server` 2.3.0.

## C1 as built (2026-10-04)

C1 is implemented in `packages/supermux-core/src/context/` (reference: `API.md` "Session context").
Live check through `core.sessions.create`: `bun scripts/context-live.ts` — 40/40 checks passed for
claude, codex, grok and opencode (runs `run-2026-10-04T20-01-58-374Z` and `…T20-02-06-649Z` under
`~/.cache/context-c1/`): instructions, skill, plugin skill and MCP tool each returned their own probe
token; after a core restart the resumed session repeated the instruction token and the earlier MCP
word; a Codex resume with changed instructions was `context_unsupported`; policy "warn" emitted
`context.degraded` for a plugin's hooks on Codex. Where reality differs from the plan below:

- **Codex MCP is per process, not `config.toml`.** `codex app-server -c mcp_servers.<n>.command=…
  -c …args=[…] -c …env={…}` works, and nothing is written to `CODEX_HOME`. MCP approval needs no
  policy change: `-c mcp_servers.<n>.default_tools_approval_mode="approve"` lets that server's tools
  run under approval policy `never` and skips the elicitation under `on-request` (verified on 0.159.2:
  never+approve → runs; never+none → "requires approval, but approval policy is never";
  on-request+none → one `mcpServer/elicitation/request`). Only context servers get it; the session's
  policy is untouched for everything else.
- **Grok skills ride the plugin channel** (a generated plugin per skills folder, `--plugin-dir`),
  so no grok config is written. Proven live.
- **OpenCode uses `OPENCODE_CONFIG`** (a session file), merged over the user's config:
  `instructions` and `plugin` concatenate, but `skills.paths` **replaces** the global list, so the
  core repeats the global paths in the session file. Any `plugin` entry makes OpenCode install
  `@opencode-ai/plugin` into the global config dir (`<XDG_CONFIG_HOME>/opencode/node_modules`):
  OpenCode's own write, which a host avoids with a session-private `XDG_CONFIG_HOME`.
- **Claude:** a repeated `--append-system-prompt-file` keeps only the **last** one, so the core
  copies a host's own appended prompt into its file first. A repeated `--mcp-config` accumulates.
- **Fork** stays on `Session.fork({ id, at?, context? })`; there is no `core.sessions.fork`.
- **Instructions fixed at creation** (Codex, Grok) were compared with the record's
  `createdInstructions` in C1; since C1b every agent's later launches use that snapshot directly
  (see "Instructions are fixed at creation").
- ~~`createHost` drivers declare no context support yet~~: C3 (`HostOptions.context`). Cursor:
  see "Cursor cells as built".
- ~~**`CODEX_HOME` sharing**~~ (fixed in C3: the policy is per process, see "C3a as built"): under a Codex subscription account (A1)
  `CODEX_HOME` is the account home, shared by every session of that account. Two Codex driver writes
  assume it is session-private: `persistPolicyToConfig()` (`config/batchWrite` of `sandbox_mode` /
  `approval_policy`, so sessions of one account overwrite each other's child-thread policy) and the
  multi-agent v1 catalog (`<CODEX_HOME>/supermux-model-catalog.json`, same content for all, so
  harmless). The context code writes nothing there. The broker refuses Codex subscription accounts
  today (A3a), which hides the conflict.
- ~~A detached (keeper) session that is re-attached keeps the args it was launched with~~: fixed in
  C1b (keeper fingerprint, see "Detached (keeper) sessions").

## API

```ts
type SessionContext = {
  /** Appended to the agent's own system prompt; never replaces it. Several entries are joined in order. */
  instructions?: string | string[]
  /** Folders of `<name>/SKILL.md` trees. */
  skills?: string[]
  /** Plugin folders (see "Plugin folder"). */
  plugins?: string[]
  /** MCP servers: external ones (a command to run) and host ones built with `mcpServer()`,
   *  whose tools are TS functions in this process (see "Host MCP servers"). Any number of each;
   *  the core adds none by default. */
  mcpServers?: (ExternalMcpServer | HostMcpServer)[]
}

createCore({ …, context?: SessionContext })               // defaults for every session
core.sessions.create({ agent, cwd, context?: SessionContext })
core.sessions.resume(id, { context?: Omit<SessionContext, "instructions"> })  // replace for this and later launches
session.fork({ id, context?: Omit<SessionContext, "instructions"> })        // inherits parent's (instructions always), overridable
```

- **Merge:** session context is added on top of the core default (instructions and lists
  concatenated, MCP server names must be unique or `invalid_context`).
- **Persistence:** the record stores the serialisable part (instructions, paths, MCP specs, tool
  *server names* for host servers), so resume after a host restart applies the same context.
  Host servers are code: on resume the host must pass servers covering the stored names,
  otherwise `missing_mcp_servers` (no silent drop).
- **Honesty:** before launch the core resolves the context against the agent's capabilities.
  Default `onUnsupported: "error"` → `context_unsupported` listing what can't apply;
  `"warn"` → launch anyway and emit `{ type: "context.degraded", sessionId, dropped: [...] }`.
  `core.capabilities(agent).context` exposes the table; the startup detection already in core
  (`ea1f5504`) fills the version-dependent cells.

## Changing context in flight (C1b, as built 2026-10-05)

Implemented in `packages/supermux-core/src/context/update.ts` + `core.ts` (`updateContext`); reference:
`API.md` "Changing context in flight". Live check: `bun scripts/context-live-update.ts`.

```ts
const r = await session.updateContext({
  skills: { add: ["/p/new-skills"], remove: ["/p/old"] },
  plugins: { add: ["/p/my-plugin"] },
  mcpServers: { add: [githubMcp], remove: ["old"] },  // external servers (host servers: C2)
}, { reload: "allow", holdOnCacheImpact: false })      // no instructions: fixed at creation
// r.applied: [{ kind, op, item, how: "live" | "reload" | "unsupported", reason? }]
// r.effective: "now" | "next_turn"; event { type: "context.updated", sessionId, applied }
core.sessions.updateContext(id, patch, options)   // a session that is not open: record only
```

- **live:** the running agent takes the change: same process, same conversation.
- **reload:** the core relaunches the agent on the same conversation between turns (the A1
  account-switch path: shutdown + resume with the record's context). It waits for the turn to
  end and never cancels it; the queue is held meanwhile and queued input moves to the relaunched
  `Session` and runs after the reload.
- **unsupported:** reported, never faked, and not stored in the record. Under policy "error"
  anything unsupported refuses the whole update (nothing applied, nothing stored).

The record is persisted before anything reaches the agent. Every live mechanism below was proven
with its own probe token through the core (`context-live-update.ts`, runs under
`~/.cache/context-c1b/`), or by a raw probe where noted.

| Agent | Add / remove skill | Add / remove plugin | Add / remove MCP server |
|---|---|---|---|
| Claude | **live**: a `supermux-skills-N` wrapper in `<ctx>/plugins` + `reload_plugins` (`reload_skills` does NOT load a new wrapper plugin: probe answered NONE) | **live**: symlink in `<ctx>/plugins` + `reload_plugins` (`hold_on_cache_impact` passed through) | **live**: `mcp_set_servers` with the full *dynamic* set; a launch (`--mcp-config`) server can only go by **reload** |
| Codex | **live**: `skills/extraRoots/set` with the full list | **live** for a skills-only plugin; **reload** when it has `.mcp.json` servers | **reload**: new app-server (`-c` args) + `thread/resume` |
| Cursor | unsupported | unsupported | **reload**: new process + `session/load` (proven 2026-10-05) |
| Grok | reload | reload | reload |
| OpenCode | reload | reload | reload |

### Instructions are fixed at creation (decision 2026-10-05)

Instructions never change after a session is created, for every agent (OpenCode included). At
create the merged instructions (core default + session) are snapshotted into
`record.createdInstructions`; resume, reload, keeper relaunch, OpenCode config regeneration and
forks all use the snapshot and never re-merge. A changed core default therefore reaches only new
sessions and never makes an existing session's resume fail. `updateContext`, `resume(id, { context })`
and `fork({ context })` take no `instructions` (`invalid_context` "instructions are fixed when the
session is created"); their context type is `Omit<SessionContext, "instructions">`. A fork inherits
the parent's snapshot (a fork continues the parent's conversation, which already carries those
instructions for Claude and Codex; OpenCode gets the same text in its config). The opt-in Codex
`additionalContext` append path built first in C1b was removed with this decision. Live
(`context-live-update.ts --quick`, run `2026-10-05T11-30-48-776Z`): OpenCode after a reload (add a
skill) and Claude after a live add both still answered the ORIGINAL instruction token, and again
after a core restart + resume (that second answer could also come from the history).

Where reality differed from the C0-based plan:

- **Claude instructions are not reloadable.** Proven with raw `claude -p` stream-json runs
  (2.1.289): a session created without an appended prompt and resumed with
  `--append-system-prompt-file` (or `--append-system-prompt`) answers "NONE"; a session created
  with token A and resumed with a file holding token B (or with no flag at all) still answers A.
  The prompt is stored with the session (one of the reasons instructions are now fixed at
  creation for every agent, see above). C1's "instructions reapplied after a restart" evidence came
  from the stored prompt (and the history), not from the flag.
- **Claude skills use `reload_plugins`.** A new skills folder is a new wrapper plugin, which
  `reload_skills` does not load (C0's live skill was a new skill *inside* an already loaded
  plugin). The Claude launch now always passes `--plugin-dir <ctx>/plugins` (empty is fine) when
  the session has any context, so later adds can be live.
- **Codex MCP stays per process, so it is reload.** Probed on 0.159.2 (`codex-mcp-probe.ts`):
  `thread/resume {threadId (loaded), config: {mcp_servers: {…}}}` (nested and dotted keys) is
  accepted but starts nothing (the model answered NONE, the server never ran);
  `config/value/write {filePath: <session file>}` is refused with `configLayerReadonly` "Only writes
  to the user config are allowed". So a live add would have to write the shared `CODEX_HOME`
  `config.toml`; the core relaunches the app-server instead (`thread/resume` in the new process
  keeps the conversation; the new server is started with the thread, no extra ready-wait needed:
  the next turn called it).
- **Codex plugins are live only without MCP servers** (their skills are extraRoots; their servers
  are process args).
- **Removal is real:** Claude `reload_plugins` drops an unlinked plugin from its plugin list and
  the skill is gone; `mcp_set_servers` without a server answers `removed: [name]` and its process
  exits. Codex `skills/extraRoots/set` without a root drops its skills from `skills/list`. For the
  reload agents the relaunched process simply never gets the item. In the live check the removed
  MCP server got no further call and its process was gone for all four agents.

### Detached (keeper) sessions

A reload must start a NEW agent process. C1 left a gap: a resume of a detached session re-attached
to the still-running keeper process, which kept its old launch args. Fixed in the keeper client:
every launch passes `KeeperSpec.fingerprint` (a digest of what the launch applies, `"none"` without
a context; `LaunchContext.fingerprint`). The client writes it next to the keeper
(`keepers/<id>/fingerprint`). When it finds a running keeper with a *different* recorded
fingerprint it SIGTERMs that keeper (which shuts its agent down), waits for both pids to be gone,
and spawns a new one. After a live change the runtime records the new fingerprint
(`RuntimeContextControl.recordFingerprint`), so a later re-attach with the same context is still
a re-attach (and does not end a running detached turn). A keeper without a recorded fingerprint
(started before C1b) is re-attached as before. `resume(id, { context })` uses the same path, so it
is fixed too. Tested with the Claude fixture through two core restarts (unchanged context → same
pid; changed → new pid, old one dead; the test fails without the fix).

## Plugin folder

A plugin is a folder. The core reads it and maps each part per agent:

```
my-plugin/
  .claude-plugin/plugin.json   .codex-plugin/plugin.json   .cursor-plugin/plugin.json   (any subset)
  skills/<name>/SKILL.md
  commands/*.md
  agents/*.md
  hooks/…
  .mcp.json                     (optional MCP servers)
  .opencode/plugins/*.js        (optional OpenCode JS plugin)
```

- An agent with native plugin support (Claude, Cursor, Codex, Grok) gets the folder as a plugin,
  if it has that agent's manifest. Without the manifest, the core generates one in the
  session folder (needs the Codex "marketplace name == manifest name" rule).
- An agent without it (OpenCode) gets the parts it understands: `skills/` → skill paths,
  `.mcp.json` → MCP servers, `.opencode/plugins` → `plugin`. Hooks and commands that can't map
  are listed in `context.degraded`.
- This moves the broker's `src/core/plugins/adapters/*` into the core; the broker keeps only
  its plugin *registry* (which plugins are installed and enabled).

## Host MCP servers: tools written as TypeScript functions

The host builds as many MCP servers as it wants, each with its own name and tools. The core ships
**no default server**. The broker's own tools (reply, spawn_session, …) become just one more host
server that the broker chooses to attach.

```ts
import { mcpServer, tool } from "supermux-core/mcp"
import { z } from "zod"   // zod 4

const orders = mcpServer({
  name: "orders",
  instructions: "Order lookup for the shop",        // optional MCP server instructions
  tools: {
    lookup: tool({
      description: "Look up an order by id",
      input: z.object({ id: z.string() }),
      async run({ id }, ctx) {          // ctx: { sessionId, agent, account, server, signal }
        return await db.orders.get(id)  // string | JSON | { content: [...] } | throws → isError
      },
    }),
  },
})
const calendar = mcpServer({ name: "calendar", tools: { … } })

createCore({ …, context: { mcpServers: [orders] } })                  // every session
core.sessions.create({ …, context: { mcpServers: [calendar, {         // plus, per session,
  name: "github", command: "github-mcp", args: [], env: {} }] } })    // an external one too

orders.add("cancel", tool({ … }))   // live in every session that has `orders`
orders.remove("lookup")
```

### Built on the official MCP SDK

Use **`@modelcontextprotocol/server` v2** (stable line, 2.3.0 on 2026-10-02; the README claims the
2026-07-28 spec, but in practice it negotiates 2025-11-25, see below). Unlike the v1 `@modelcontextprotocol/sdk` (express, hono, ajv, cors, jose, …)
it depends only on `zod ^4.2` and `@modelcontextprotocol/core`. The core imports no zod in `src/` today, so moving its
`zod ^3.25` dependency to 4 costs nothing; the ACP SDK accepts both.

- **All MCP logic runs in the host on the SDK:** one SDK `McpServer` per (session, server)
  connection, connected to a small custom `Transport` over the socket stream. The SDK provides
  protocol-version negotiation, `tools/list` / `tools/call`, input validation, structured
  output, `notifications/cancelled` → `extra.signal`, and `list_changed` when a tool is
  registered or removed on a live server. Resources and prompts come with it too.
- **The bridge has no MCP code:** it only pipes newline-delimited JSON-RPC between stdio and the socket
  (plus a one-line hello with the token). It stays tiny, never needs the SDK, and the
  agent-side process never has to match the SDK version.
- **Two ways to define a server:**
  - `mcpServer({ name, tools })` with `tool()`: our thin sugar, which registers each tool on the
    SDK server. It is enough for most hosts.
  - `mcpServer({ name, create: (ctx) => McpServer })`: hand over any SDK server you already
    have; it's called once per session connection, because an SDK server serves one
    connection at a time.
- **Verified in C0:** 2.3.0 negotiates with all five CLIs (2025-06-18 and 2025-11-25). It
  doesn't serve 2026-07-28 discovery: Claude's `server/discover` gets "method not found" and
  Claude falls back. That is fine; the core can answer `server/discover` itself later.
- The broker's `mux-shim` is on v1 `@modelcontextprotocol/sdk ^1.0.0` today; C3 moves it.

How it reaches the agent: every agent speaks **stdio MCP**, so each attached host server
becomes one ordinary stdio MCP entry, under the host's name. Its command is a small bridge
program shipped with the core (`supermux-core/mcp-bridge --server orders`). The bridge
connects to a Unix socket owned by the core (`<state>/mcp/mcp.sock`, 0600; a per-session,
per-server token passed by env) and relays `initialize`, `tools/list` and `tools/call`.
The function runs **in the host process**, with the session's context.

- **One process per attached server, not one shared multiplexer:** the agent sees them as
  separate servers (separate names, permissions and tool prefixes, e.g. `mcp__orders__lookup`),
  and one server failing doesn't take the others down.
- **Why a bridge and not HTTP MCP:** stdio works for all five agents; HTTP MCP support differs
  per agent and needs a port. One bridge also matches how detached (keeper) sessions survive a
  host restart: the agent keeps running and the bridge reconnects when the core is back.
  Calls made while the host is down return an MCP error ("host unavailable"); they are not
  queued.
- **Per-session behaviour:** the same server object can serve many sessions; `ctx.sessionId`
  says which. A server can also be built per session (a closure over that session's data).
- **Cancellation:** an MCP `notifications/cancelled` or a session interrupt aborts `ctx.signal`.
- **Schemas:** zod 4 (via the SDK) → JSON Schema for `tools/list`; input is validated before
  `run`. Resources and prompts work through `create`; the `tools` sugar covers tools only.
- **Events:** `tool.called` / `tool.finished` (with `server`) on the session stream; the agent's
  own MCP tool events show the same calls.

## C2 as built (2026-10-05)

Implemented in `packages/supermux-core/src/mcp/` (`server.ts`: `mcpServer` / `tool`; `host.ts`: socket,
token, SDK transport; `bridge.ts`: the agent-side script) plus the registry in `core.ts`. Reference:
`API.md` "Host MCP servers". Export paths `supermux-core/mcp` and `supermux-core/mcp-bridge`.
Dependencies: `@modelcontextprotocol/server` 2.3.0, core zod `^3.25` → `^4.2` (4.6.5 installed).

**API as built.**
`mcpServer({ name, instructions?, version?, toolChanges?: "reload" | "live-only", tools: { x: tool({ description, input?, output?, annotations?, run }) } })`
or `mcpServer({ name, create: ctx => McpServer })`; `server.add(name, tool)` / `server.remove(name)` (tools
servers only). `ctx = { sessionId, agent, account?, server, signal }`. `SessionContext.mcpServers` is
`(ExternalMcpServer | HostMcpServer | { kind: "host", name })[]` on create, resume, fork and
`updateContext`. `createCore({ mcpServers })`, `core.mcp.{register, unregister, get, list, socket}`,
`core.capabilities(agent).hostToolChanges` ("live" | "reload"). Events `tool.called` / `tool.finished`;
tool changes show up in `context.updated` as `{ kind: "tools", op, item: "<server>/<tool>", how }`.
New error codes: `missing_mcp_servers`, `mcp_socket_in_use`.

Choices and where reality differed from the plan above:

- **Socket** is `<state>/mcp/mcp.sock` (not `<state>/mcp.sock`) so it sits in a 0700 directory with the
  0600 `secret`. Too long for sun_path (107 bytes Linux, 103 macOS) → `$XDG_RUNTIME_DIR` or tmpdir
  `/supermux-mcp-<sha256(state)[:16]>/mcp.sock`. Opened lazily at the first launch with a host server
  (a core without host servers opens nothing). Stale file removed; a live owner → `mcp_socket_in_use`.
- **Token** = HMAC-SHA256(secret, `sessionId\0server`), deterministic, so it is stable across launches and
  host restarts. The fingerprint does not include it anyway: a host server enters the keeper
  fingerprint as `{ name, host: true }` only, plus its sorted tool names when the agent ignores
  `list_changed` (so a host restart with a changed tool set relaunches Codex/Grok instead of
  re-attaching a process that would never see the change). C1b open question 5 is closed.
- **Bridge launch**: `process.execPath` + `dist/mcp/bridge.js` (or `src/mcp/bridge.ts` under Bun from
  source); verified under Node 24 from `dist`. Env `SUPERMUX_MCP_SOCKET/SESSION/TOKEN`.
- **A bridge can connect before the record exists** (Claude starts MCP servers inside `driver.open`):
  the core keeps a "launching" entry per session id until the record is stored, and the hello is
  checked against it, else against the stored record (+ core default). Refusals: bad token → the
  bridge exits; unknown server / session or a server the session does not have → it keeps retrying.
- **Interrupt and cancellation are done at the transport** (so they also cover `create` servers): the
  host tracks in-flight `tools/call` ids per connection; an agent `notifications/cancelled` ends the
  call (`tool.finished ok:false`, the SDK aborts `ctx.signal` and sends nothing); a session interrupt
  (`session.stateChanged` → `interrupting`) injects `notifications/cancelled` into the SDK AND answers
  the agent with an `isError` "Cancelled: The session was interrupted" result; a late SDK answer for
  it is dropped. Events are produced there too, never with arguments or results.
- **Bridge buffering**: messages are held only during the first connect attempt and during a replay;
  otherwise disconnected = immediate `-32000 "host unavailable"` for requests, notifications dropped.
  The replayed `initialize` gets id `supermux-bridge-replay-<n>`; its answer is swallowed.
- **Tool changes** relaunch at idle through the same per-session queue as `updateContext` (hold the
  queue, `whenIdle`, `reloadForContext`), one relaunch for every change made meanwhile. Drivers say
  `DriverContextSupport.mcpListChanged` (Claude, OpenCode true). `create` servers are never relaunched
  for (the core cannot see their tools).
- An empty tools server registers and removes a placeholder so the SDK installs `tools/list`.
- Records never hold the token; `mcp.json` (Claude) holds it in the 0600 context folder.

**Live check** (`bun scripts/context-live-mcp.ts`, haiku / gpt-5.6-luna low / grok low /
opencode-go qwen3.7-plus): 45/45 (claude run `run-2026-10-05T12-07-26-554Z` 12/12, codex + grok +
opencode run `run-2026-10-05T12-08-12-964Z` 33/33, under `~/.cache/context-c2/`).

| Agent | (a) 2 host + 1 external | (b) mid-session `orders.add` | (c) throw → error text | (d) subagent call, ctx.sessionId | (e) detached restart |
|---|---|---|---|---|---|
| Claude | all 3 tokens | **live**, same pid 157740 | yes | yes: `mcp__orders__whoami` with subagentId, ctx `mcp-claude` | same pid 157740, same bridge pids 157798/157799, new host's token |
| Codex | all 3 tokens | **reload**, pid 158879 → 159305 | yes | yes: `mcp-tool whoami` on the child thread, ctx `mcp-codex` | same pid 159305, same 3 bridge pids, new token |
| Grok | all 3 tokens | **reload**, pid 160419 → 160866 | yes ("Failed to call fail_probe: …") | yes: child `use_tool`, ctx `mcp-grok` | same pid 160866, same bridges, new token |
| OpenCode | all 3 tokens | **live**, same pid 161938 | yes | not run (no live child stream over ACP) | same pid 161938, same bridges, new token |

In every (e) the agent stayed alive while no core ran, the SAME bridge processes reconnected to the
new core, and the call returned the token of the NEW `orders` object (not the old one). After the
final shutdown no agent or bridge process was left; the real agent homes were untouched (only Claude
Code's own `~/.claude/backups` rotated, from the parent session, unrelated to the run).
Unit/integration: `tests/mcp-host.test.ts` (bridge against the real host and a raw fake host: auth,
refusal, results, isError, zod errors, output schema, list_changed, cancel + interrupt, `create`,
reconnect with "host unavailable", byte-level replay, long socket path, stale/live socket, token) and
`tests/mcp-core.test.ts` (registry, records, `missing_mcp_servers`, fingerprint, tool-change
live/reload/live-only, Claude-fixture keeper re-attach + call through the reconnected bridge,
"host unavailable" while unregistered, interrupt).

Open (for C3 / mux-shim):
- The broker root still pins zod `^3.23`; host tools must use zod 4 schemas (core's dependency). C3
  needs zod 4 where it builds tools (or its own `create` server on the v2 SDK).
- On Codex the token is in the app-server argv (`-c mcp_servers.<n>.env={…}`), readable by other local
  users via `ps`. They cannot use it (the socket is 0600 in a 0700 dir); same-user processes can read
  the secret anyway. A token file or Codex `env_vars` pass-through would hide it.
- `create` servers get no relaunch on tool changes for agents that ignore `list_changed`.
- The `server/discover` fallback is not special-cased (Claude falls back to `initialize`).
- ~~Cursor: `mcpListChanged` unknown~~: Cursor ignores `list_changed` (C0 2026-10-05): `hostToolChanges: "reload"`, proven through the core (C2 (b)).
- `core.mcp.unregister` closes live connections; a session keeps the reference and its next launch is
  `missing_mcp_servers`.

## C3a as built (2026-10-05)

The broker's five core-hosts (`src/core/agents/<agent>/core-host.ts`) now hand the core a
`SessionContext` from `prepare` and declare their driver's context support
(`createHost({ context, contextPolicy: "warn" })`). `prepare*Environment` keeps only what is not
context: credentials and accounts, HOME isolation, identity env (`MUX_SESSION_ID`…), `--add-dir`,
`nativeMemory`, permissions, Codex `[features]`, OpenCode provider / permission.

**Core changes.** `createHost`: `HostOptions.context` / `contextPolicy`; `HostRegistration.context`
(or `prepare`'s returned `context`); on an existing record the context's skills / plugins / MCP
servers replace the session's own and its instructions go in as `ResumeOptions.adoptInstructions`
(taken once by a record without `createdInstructions` and without own context, then fixed). The
ready handle follows `session.resumed`. `core.sessions.reopening(id)`. Codex policy per process
(below). Tests: `tests/host-context.test.ts`.

**Launch equivalence** (`tests/c3-launch-equivalence.test.ts` against `tests/c3-launch/before.json`,
recorded on the pre-C3 code by `scripts/c3-launch-capture.ts`; every agent, worker and PA, through
the real core-host and the core's own channel mapping). Identical everywhere: the instruction text
(except the Claude PA, below), every MCP server with its exact command / args / env, other args,
env (except OpenCode's `OPENCODE_CONFIG`), credential files. The intended differences:

| Agent | Instructions | Plugins | mux-shim / rpc MCP |
|---|---|---|---|
| Claude | same text; file in the core's session folder instead of the session home. PA: ONE value (below) | same plugins, via `--plugin-dir <ctx>/plugins` (symlinks) instead of one flag each | mux-shim unchanged (`~/.claude.json` / account `--mcp-config`); rpc servers → context (`--mcp-config <ctx>/mcp.json`), `--strict-mcp-config` kept as a host arg |
| Codex | `thread/start developerInstructions` instead of `<CODEX_HOME>/AGENTS.md` (pre-C3 sessions keep the file, below) | core mapping: `skills/` → `skills/extraRoots/set` (hooks / commands / agents dropped with `context.degraded`) instead of the `mux` marketplace + `codex plugin add` + `-c plugins."x@mux".enabled` | `app-server -c mcp_servers.mux-shim.*` + `default_tools_approval_mode="approve"` instead of `config.toml` |
| Cursor | since 2026-10-05: the same text as the first prompt's leading block instead of `<workdir>/.cursor/rules/mux.mdc` (the repo-rule fallback is deleted) | dropped with `context.degraded` (Cursor's ACP server ignores `--plugin-dir`, so they never loaded) | ACP `mcpServers` instead of `$HOME/.cursor/mcp.json` (proven) |
| Grok | ACP `session/new _meta.rules` instead of `AGENTS.md` / `AGENTS.override.md` written into the repo (+ `.git/info/exclude`) | whole plugin via `grok agent --plugin-dir` instead of its `skills/` in `[skills] paths`; a plugin `SessionStart` hook did NOT run (live) | ACP `mcpServers` instead of `config.toml` |
| OpenCode | session `OPENCODE_CONFIG` instead of the session XDG `opencode.json` + `<home>/AGENTS.md` | JS plugins as `file://…/.opencode/plugins/*.js` entries and every plugin's `skills/` in `skills.paths` (was: root dirs in `plugin`, `skills.paths` only for plugins without JS) | ACP `mcpServers` instead of the XDG `opencode.json` `mcp` |

Codex also loses the broker's static `-c approval_policy="never" -c sandbox_mode="danger-full-access"`:
the core passes the session's own policy (same values in the default mode; in another mode child
threads now get that mode instead of always full access).

**The PA prompt-file finding.** A Claude PA was launched with four `--append-system-prompt-file`
flags (instructions.md with header + reply rule + soul + environment.md + memory; environment.md
again; the per-session memory preamble; reply-fallback.md without the mux-core hook). Claude keeps
only the LAST one: `scripts/c3-pa-prompt-probe.ts` on the pre-C3 code (claude 2.1.289, haiku) with
a token in soul.md (instructions file) and one in focus.md (memory preamble) answered
`TOKENS=PA-TOKEN-FOCUS-4410; MEMORY=yes`: the soul, the header, the reply rule and environment.md
never reached a PA. Now one value: header, reply rule, soul, environment.md (once), the per-session
memory preamble (name, workdir soul / focus), then the reply fallback. Already-running PAs keep
their stored prompt (Claude ignores a new one on `--resume`); only new PAs get the full text.

**Existing sessions.** Instructions are fixed from their first C3 launch (`adoptInstructions`):
the text the broker generates then. Before C3 the broker regenerated them on every launch for
Codex (`AGENTS.md`), OpenCode (config), Grok and Cursor (repo files); that stops (deliberate). Per agent:
- Claude: unchanged in effect (Claude already kept its stored prompt).
- Codex: Codex re-reads `<CODEX_HOME>/AGENTS.md` on `thread/resume`
  (`packages/supermux-core/scripts/codex-agents-md-probe.ts`: rewritten → the resumed thread saw
  the new token; removed → none) and the core sends `developerInstructions` only at
  `thread/start`. So a pre-C3 session keeps the file, written with its creation snapshot on every
  launch (marker `<home>/.supermux-agents-md-session` = its session id: homes are keyed by name);
  a C3 session's home never has one (a stale file in a reused home is removed).
- OpenCode: the snapshot goes into the session `OPENCODE_CONFIG` every launch.
- Grok: `_meta.rules` only applies at `session/new`, so a pre-C3 session gets no rules; it still
  reads the `AGENTS.md` / `AGENTS.override.md` the old broker left in its repo (left in place:
  removing a file from the user's repo was out of scope). Open: a NEW Grok session in such a repo
  sees that stale file AND its rules.

**Cursor decision.** ~~`CURSOR_REPO_RULE_FALLBACK`~~ (deleted 2026-10-05). The C0 cursor cells
found no per-session system-prompt channel outside the workdir, so the core sends the instructions
as the leading text block of the session's first prompt (see "Cursor cells as built"); the broker
passes them as context and writes nothing into the repo. A rule file the old broker left at
`<workdir>/.cursor/rules/mux.mdc` stays (removing a file from the user's repo is out of scope): a
session in such a repo still loads it next to its preamble, until the user deletes it.
Existing Cursor sessions: their conversation already has a turn, so they get no preamble; they
keep reading the stale `mux.mdc` (regenerated no more).

**Claude, `~/.claude.json` and `--mcp-config`.** mux-shim is NOT a context server for Claude.
System-account sessions get `mux-shim` (tools) and `mux-channel` (channel-only, zero tools) from
the user's `~/.claude.json` (`session-manager/trust.ts`); a subscription account gets mux-shim
from `<home>/mcp-account.json` (`account-env.ts`). The context's `--mcp-config <ctx>/mcp.json` adds
to those (no `--strict-mcp-config` from the core), so putting mux-shim in the context too would
start a second copy and every tool call would run twice (the reason MUX_CHANNEL_ONLY exists).
rpc workers keep `--strict-mcp-config` as a host arg and get their servers from the context only.
No core-host passes `--dangerously-load-development-channels`. (C3b: see `c3b-mux-shim-plan.md`; in
its "host" mode Claude gets mux-shim from the context instead.)

**Codex policy fix.** `persistPolicyToConfig()` (`config/batchWrite` of `sandbox_mode` /
`approval_policy` into `CODEX_HOME`) is gone; the driver appends `app-server -c sandbox_mode=…
-c approval_policy=…`. Probe on 0.159.2 (`scripts/codex-policy-probe.ts`): a child of a parent
launched `-c sandbox_mode="read-only"` stays read-only even with a danger-full-access parent
thread/turn; `-c danger-full-access` lets it write outside the workspace: children read the process
config. Live through `core.sessions.create` (`scripts/codex-policy-live.ts`, gpt-5.6-luna low):
`CODEX_HOME/config.toml` pre-seeded with `sandbox_mode = "read-only"` (what another session's old
driver would leave in a shared account home); the danger-full-access session's spawn_agent child
ran `echo child-wrote > <outside>/child.txt` (file written, `childWriteLanded: true`) and
`config.toml` stayed byte-for-byte unchanged. A live `setPermissions` reaches the parent from its
next turn; children spawned by that process keep the launch policy until the next launch.

**Reloads in the broker.** `CoreAdapter` follows a core-side reopen (`core.sessions.reopening` /
`live`): `isAlive()` stays true, the old Session's `closed` is not a turn end, input sent meanwhile
waits for the new Session, state and requests come from it
(`core-adapter-reload.test.ts`). The supervisor's PA liveness uses `isAlive()`.

**Plugin settings.** The only plugin editor is the `bun run plugin` CLI (`scripts/plugin.ts` →
`plugins/lifecycle.ts`): registry-wide add / update / remove / enable / scopes. There is no
per-running-session toggle and no UI, so nothing is routed through `updateContext`; a registry
change applies at each session's next launch (also true before C3 for every agent but Codex,
whose marketplace install ran at once).

**Deleted.** `src/core/plugins/adapters/*` (claude, cursor, codex, grok, opencode,
plugin-dir-adapter + tests): their selection rule (enabled, scoped, per-session override, the
CLI's manifest / loadable parts) lives on as `isPluginCompatible` + `sessionPlugins` in
`plugins/index.ts`; how each agent loads a plugin is the core's. The Codex marketplace path:
`codexPrepareGlobal` (boot: `~/.agents/plugins/marketplace.json` + `codex plugin add` into
`~/.codex`), `codexPrepareSessionHome` (`codex plugin add` into every session `CODEX_HOME`),
lifecycle's `codex plugin remove` / re-prepare. `session-manager/spawn-command.ts` (+ test): the
tmux-era command builders, used by nothing but their test. `writeSessionMemoryPreamble` and the
`memory-preambles/` files. Left on disk, untouched: `~/.agents/plugins/marketplace.json` and the
`mux` plugins installed in `~/.codex` and in session homes.

**Open risks for the live rollout.**
- The live broker (`~/projects/supermux`, `dev`) predates supermux-core: rolling this branch out is
  the whole core cutover, not just C3. A preview broker writes core records (`context`,
  `createdInstructions`, keeper fingerprints) into the shared live state; the auto-revert broker
  does not read them.
- Keeper fingerprints: a detached agent whose recorded fingerprint differs from the C3 launch's
  (e.g. one launched by C1b-era code with fingerprint `"none"`) is relaunched on its next resume,
  cutting a running detached turn. Keepers without a recorded fingerprint re-attach as before.
- Grok: new sessions in a repo where the old broker wrote `AGENTS.md` / `AGENTS.override.md` see
  that stale file next to their `_meta.rules`. Codex/OpenCode: plugin skills are now plain skill
  roots (Codex extraRoots) instead of installed `mux@mux` plugins: skill names / slash-command
  lists may change (Codex had `mux:soul`-style plugin namespacing).
- Cursor (2026-10-05): ACP `mcpServers` and host servers are proven live; registry plugins are
  dropped (they never loaded under ACP); the instructions are a first-prompt preamble, not a system
  prompt (weaker than a rule: the model may give them less weight, subagents do not get them).
- Codex: a session in a non-default permission mode now gives its children that mode, not full
  access (fix, but visible); a live mode change reaches children only after a relaunch.
- A Claude PA created after C3 gets a much longer system prompt (soul, environment.md and the
  memory preamble together, which it never actually had before).

**Live** (`bun scripts/c3-live.ts`, broker core-hosts, scratch HOME / MUX_HOME / state under
`~/.cache/context-c3/`, token accounts for Claude / Codex, scratch credential copies for Grok /
OpenCode, a fake broker socket speaking the shim protocol): 32/32. Runs `live-2026-10-05T13-10-02-293Z`
(claude 8/8: haiku) and `live-2026-10-05T13-10-19-882Z` (codex gpt-5.6-luna low, grok low,
opencode qwen3.7-plus: 24/24). Per agent: the instruction (session name from the generated
header), a registry plugin's skill word, and mux-shim started by the agent with the broker's
command / env, registered with the fake broker and its `list_sessions` called (Claude: two shim
processes, mux-shim + mux-channel, one call). Nothing written into any workdir.

## Cursor cells as built (2026-10-05)

cursor-agent 2026.09.18, model **Auto** (a free plan refuses every named model: "Free plans can
only use Auto"; Cursor's ACP picker value is `default[]`, `session/set_config_option {value:"auto"}`
is "Invalid model value", the core's driver resolves "auto" by option name). Every run used a
session-private HOME with a copy of the credentials; the real `~/.cursor` / `~/.config/cursor` were
only read. Scratch: `~/.cache/context-cursor/`. The Auto model answers questions by searching the
filesystem, so every script now rejects a token the agent found by a search / shell call or read
from a file (only a listed skill's own SKILL.md may be read).

| Cell | Result | Evidence |
|---|---|---|
| Instructions: plugin `rules/`, `$HOME/.cursor/rules/*.mdc`, `$HOME/AGENTS.md`, `--add-dir <root>/AGENTS.md`, ACP `_meta.rules`, `$HOME/.cursor/hooks.json` `sessionStart` / `beforeSubmitPrompt` `additional_context` | **no** (all) | C0 `run-2026-10-05T16-54-30-589Z`: only the preamble token listed, no tool calls; same after a relaunch with changed words |
| Instructions: first-prompt block (text in pass 1, embedded resource since pass 2) | **yes**, kept by `session/load` | C0 same run (`BISON9055` before and after the relaunch); C1 `EMBER5224` through the core, recalled after a core restart; C1b `HERON7120` after two MCP reloads and a restart |
| Skills: generated wrapper plugin via `--plugin-dir` | **no** | C0 `…T16-57-26-636Z`: `NONE`; the agent's skill list did not contain it |
| Skills: `$HOME/.cursor/skills` (also a symlinked folder), `$HOME/.agents/skills` | **yes** | same run, each SKILL.md read from the listed path |
| Plugins: `--plugin-dir <plugin>` (create and after a relaunch) | **no** | same run: `NONE`; the ACP chunk set of the bundle has no plugin service |
| MCP: ACP `session/new mcpServers` | **yes** | C0 `LYNX2951`; C1 `GLACIER3890` |
| MCP reaches a subagent | **yes** (inferred) | C0: main session's only tool call `Task`, probesub logged a call; C2 (d): host tool `whoami` called from a Cursor subagent (`subagentId` on the tool event, `ctx.sessionId` the session's) |
| `tools/list_changed` | **no** → reload | C0 first run: "Tool … was not found", one `tools/list`; C2 (b): `context.updated how: "reload"`, new pid, the new tool answered |
| Add / remove MCP server | **reload** | C0 item 7; C1b: add `COBALT6628` + recall `FALCON3963`, remove → `NONE`, its process stopped |
| `session/load` keeps the conversation | **yes** | C0 item 9; C1 resume after a core restart; C1b |
| Host MCP servers through the bridge (C2) | **yes** for (a) 2 host + 1 external, (b) live tool add (reload), (c) a throwing tool, (d) a subagent, (e) detached re-attach (same pid, bridges kept). The (e) call through the reconnected bridge is **unproven**: the plan's Auto quota ran out on that turn ("Upgrade your plan to continue") | C2 `~/.cache/context-cursor/c2/run-2026-10-05T17-05-14-321Z` (11/12) |
| C3b host-mode mux-shim on Cursor (`scripts/c3b-live.ts cursor`) | **not run** (quota) | the harness supports cursor now |

**Instructions block (changed 2026-10-05, second pass).** The instructions are a SEPARATE content
block in front of the user's blocks in the first `session/prompt`: an ACP embedded resource
`{ type: "resource", resource: { uri: "supermux://instructions", mimeType: "text/markdown", text },
annotations: { audience: ["assistant"] } }` (`instructionsBlock`; `text` is the instructions wrapped
in `<session-instructions>…</session-instructions>` with a one-line note). Cursor accepts it although
`promptCapabilities.embeddedContext` is false, and the model reads it: C0
`run-2026-10-05T17-34-40-012Z` (`--instructions-shape resource`, `--only 1,8`) returned the token
`LYNX4615` with no tool call, and again after a relaunch + `session/load`; C1 `run-…T17-40-31-969Z`
10/10. The text-block fallback was not needed (the probe keeps `--instructions-shape text`).
Cursor stores the message flattened: its `session/load` replay echoes the first user message as ONE
text chunk `<user text>\n\nAdditional ACP context:\n[ACP embedded_resource] supermux://instructions\n<text>`
(a live turn echoes nothing). The ACP driver strips that section (`stripInstructionsEcho` /
`hideInstructions`, other embedded resources kept; a chunk that is only the block is dropped) from
every `user_message_chunk`, live and replayed, before it becomes a `session.update`; the normalizer
already drops user chunks and the broker drops replayed events, so neither transcripts, the
timeline nor activity rows can carry it. Live: the C1 replay check saw both user messages and no
instructions text. The hidden `cursor-agent --system-prompt <file>` flag ("Anysphere/OpenAI team
only") is **ignored** under `acp` for this account (one turn: "I don't have a system prompt probe
token", no tool calls).

**Core.** `cursorContext`: instructions **supported** (`firstPromptBlock`, above), skills and plugins **unsupported** (no `--plugin-dir` any more; nothing is
generated for them), MCP servers **supported**, updates: MCP **reload**, no `mcpListChanged`
(`hostToolChanges: "reload"`). The ACP driver sends the block in front of the user's blocks in the
conversation's first `session/prompt` only: on create; after a `session/load` whose replay had no
`user_message_chunk` (a relaunch before the first turn); after a keeper re-attach while the meta
says `preamblePending`. Fixed at creation like every agent's instructions (later launches carry the
`createdInstructions` snapshot but send nothing once the conversation has a turn).
Tests: `tests/context-drivers.test.ts` (no `--plugin-dir`; the resource block with its annotation,
first prompt only; load with / without a replayed user message; the echo hidden live and in a replay;
`stripInstructionsEcho`; a keeper re-attach before the first turn still sends the block, after one
it does not), broker `src/core/agents/cursor/instructions-hidden.test.ts` (run alone: sets HOME):
through the cursor core-host and `CoreAdapter`, a live echo and a flattened replay leave no
instructions text in the adapter events or the core's `session.event` / `session.update` stream.

**Broker.** `src/core/agents/cursor/core-host.ts` passes `cursorInstructions(...)` as context
instructions and `prepareCursorEnvironment({ instructions: null })`: nothing is written into the
workdir (`CURSOR_REPO_RULE_FALLBACK` deleted). Registry plugins are still handed over and dropped
with `context.degraded` (policy "warn"). `muxShimModeFor` no longer pins Cursor to "external": it
follows the `muxShim` setting like every agent. Launch equivalence: `tests/c3-launch-equivalence.test.ts`
and `tests/c3b-launch-modes.test.ts` (`CURSOR_C3`: the rule file and its git exclude gone, the
instructions channel and its wrapped text, plugins empty; the wrapped text is exactly
`cursorPreamble(<the old rule body>)`).

**Live re-runs.** `bun scripts/context-probe.ts cursor --cursor-model auto` (`CONTEXT_PROBE_DIR`
for the scratch root), `scripts/context-live.ts cursor` (9/9), `context-live-update.ts cursor`
(13/13), `context-live-mcp.ts cursor` (11/12, quota), all with `CONTEXT_LIVE_DIR` /
`CONTEXT_LIVE_CURSOR_MODEL`; `scripts/c3b-live.ts cursor` (root) not run yet.

## Slices

- **C0, live probe (no API yet), done (`43bd1ccc`; Cursor 2026-10-05):** a script that tries each "(C0)" cell above per agent with
  secret words. An instruction says word A, a skill says word B, a plugin's skill says word C,
  and an MCP tool returns word D. Then **mid-session**, it adds a second skill, plugin, MCP
  server and tool, each with a new word, through each "live" mechanism. The answers fill both
  tables and decide which items need a reload.
- **C1, context, done (see "C1 as built"):** `SessionContext` (instructions, skills, plugins, mcpServers), capability
  resolution, `context.degraded`, persistence plus resume/fork, and per-agent drivers using
  the per-session channels. The environment helpers become internal (the current exports
  are kept as deprecated). Live check: the C0 script through `core.sessions.create`.
- **C1b, in flight, done (see "Changing context in flight"):** `session.updateContext` /
  `core.sessions.updateContext` for skills, plugins and MCP servers, the live mechanisms proven per
  agent, reload otherwise, the keeper fingerprint relaunch; instructions fixed at creation for
  every agent (snapshot in `createdInstructions`).
- **C2, host MCP servers, done (see "C2 as built"):** `mcpServer()` / `tool()`, the bridge, the socket, live tool
  add/remove, cancellation, events, and keeper reconnect. Live check: each agent gets two host
  servers plus one external one, calls a tool on each and repeats the secret results; a tool
  is added mid-session; then a restart test with a detached session.
- **C3a, broker, done (see "C3a as built"):** the broker passes its instructions/plugins/MCP through `context`, its plugin
  adapters are deleted.
- **C3b, built behind `muxShim` (default "external"):** `mux-shim` / `mux-rpc` become host MCP
  servers the broker attaches itself; the dead inbound channel path is removed
  (`c3b-mux-shim-plan.md` "C3b as built").

## Open questions

1. ~~Cursor: all cells unproven~~: proven 2026-10-05 ("Cursor cells as built"). Open: skills for
   Cursor would need the host's session HOME (`$HOME/.cursor/skills`), which the core does not own.
2. ~~Grok `--rules`~~: answered no; use ACP `_meta.rules`, fixed at session creation.
3. ~~Codex `developerInstructions` on resume~~: ignored (neither replaces nor adds).
4. ~~Subagents~~: they inherit the session's MCP servers (Claude, Codex, Grok proven; OpenCode
   inferred), so host servers reach subagents for free; `ctx` should say when the caller is a
   subagent if the agent exposes that.
5. ~~(C1b) The keeper fingerprint covers MCP server `env`~~ (C2: host servers enter it by name only): C2 host servers must keep per-launch
   tokens out of it, or every re-attach after a host restart becomes a relaunch.
6. ~~(C1b) Codex appended instructions lost on a relaunch~~: the append path was removed.
7. (C1b) No explicit `mcpServerStatus` ready-wait after a Codex reload: in every live run the next
   turn called the new server (Codex starts it with the thread), but a slow server could still race.
8. ~~(C1b) A changed core default instruction text failing resumes~~: fixed, later launches use the
   creation snapshot.
