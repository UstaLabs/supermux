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
report: `context-c0-report.md`, commit `43bd1ccc`). Cursor is **unproven**: the account was out of
quota during the probe; the script tries four instruction channels and re-runs as-is.

| Agent | Instructions | Skills | Plugins | MCP | Subagents get the MCP servers |
|---|---|---|---|---|---|
| Claude | `--append-system-prompt-file` | generated plugin dir → `--plugin-dir` | `--plugin-dir <plugin>`, or `--plugin-dir <folder of plugins>` (each child loads) | `--mcp-config` + `--strict-mcp-config` | yes |
| Codex | `thread/start {developerInstructions}` | `skills/extraRoots/set`, or `<CODEX_HOME>/skills` | local marketplace + `codex plugin add` into the session `CODEX_HOME` | session `config.toml`; needs approval policy `on-request` + auto-accept `mcpServer/elicitation/request` (with `never`, MCP tools never run) | yes |
| Cursor | unproven (candidates: plugin `rules/`, `$HOME/.cursor/rules`, `$HOME/AGENTS.md`, `--add-dir`) | unproven; ACP lists `$HOME/.cursor/skills` | unproven (`--plugin-dir`) | unproven (ACP `mcpServers`) | unproven |
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
- **Instructions fixed at creation** (Codex, Grok) are compared with the record's
  `createdInstructions`; a different merged text on resume/fork is unsupported.
- `createHost` drivers declare no context support yet (C3). Cursor: instructions unsupported,
  the rest unverified (no quota).
- **`CODEX_HOME` sharing (open, not fixed in C1):** under a Codex subscription account (A1)
  `CODEX_HOME` is the account home, shared by every session of that account. Two Codex driver writes
  assume it is session-private: `persistPolicyToConfig()` (`config/batchWrite` of `sandbox_mode` /
  `approval_policy`, so sessions of one account overwrite each other's child-thread policy) and the
  multi-agent v1 catalog (`<CODEX_HOME>/supermux-model-catalog.json`, same content for all, so
  harmless). The context code writes nothing there. The broker refuses Codex subscription accounts
  today (A3a), which hides the conflict.
- A detached (keeper) session that is re-attached keeps the args it was launched with: a resume with
  a changed context on a still-running keeper process does not reach the agent until it relaunches.

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
core.sessions.resume(id, { context?: SessionContext })    // replace for this and later launches
core.sessions.fork(id, { context?: SessionContext })      // inherits parent's, overridable
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

## Changing context in flight

```ts
const r = await session.updateContext({
  skills: { add: ["/p/new-skills"], remove: ["/p/old"] },
  plugins: { add: ["/p/my-plugin"] },
  mcpServers: { add: [orders, githubMcp], remove: ["old"] },  // external or host servers
  // a host server's own tools can also change: orders.add(tool) / orders.remove("name")
  instructions: "…",                    // replaces this session's added instructions
})
// r.applied: per item "live" | "reload" | "unsupported"; r.effective: the next turn, or now
```

The core picks the cheapest way per agent and item:

- **live:** the running agent picks the change up with no restart, and the conversation and
  prompt cache are untouched.
- **reload:** the core relaunches the agent on the same conversation between turns. This is the
  same mechanism as an account switch (A1), already proven live for Claude and Codex. It waits
  for the turn to end and never cancels it; queued input runs after the reload.
- **unsupported:** reported, never faked.

How each agent does it is designed around **one session-owned context folder** (under the
session dir, never the repo) that the core edits, after which it tells the agent to rescan:

| Agent | Add skill | Add plugin | Add/remove MCP server | Tools of an attached host server (`list_changed`) | Change instructions |
|---|---|---|---|---|---|
| Claude | **live**: write into the session plugin, `reload_skills` | **live**: symlink into `<session>/plugins`, `reload_plugins` | **live**: `mcp_set_servers` | **live** | reload (`apply_flag_settings {appendSystemPrompt}` returns success, does nothing) |
| Codex | **live**: `skills/extraRoots/set` | **live**: app-server `plugin/install` | **live**: `config/value/write` + `config/mcpServer/reload`, then wait for the server's ready status before the next turn | reload, as a **new app-server** (notification ignored; `config/mcpServer/reload` keeps an unchanged server) | **cannot replace**: `developerInstructions` are fixed at `thread/start` (resume ignores them, even in a new process); append-only via `turn/start.additionalContext`, which stays in history |
| Cursor | unproven | unproven | unproven | unproven | unproven |
| Grok | **live**: rewrite `[skills] paths` | reload (extra `--plugin-dir`) | reload | reload (notification ignored) | **cannot**: `_meta.rules` is fixed at `session/new`; `session/load` ignores new rules |
| OpenCode | reload | reload | reload | **live** | reload |

Reload (new process + `session/load` / `thread/resume`) keeps the conversation on Codex, Grok and
OpenCode (C0), and on Claude (A1). Notes:
- Claude's `reload_plugins` has a `hold_on_cache_impact` option that refuses a reload that would
  change the tool list under a cached prompt. The core passes it through as an
  `updateContext` option, default off.
- Instructions are therefore **not uniformly changeable**: `updateContext({ instructions })` is
  reload on Claude and OpenCode, `append` (Codex `additionalContext`, opt-in since it lands in
  history) or `unsupported` on Codex, and `unsupported` on Grok. `r.applied` says which.
- Unused native Codex channels worth a later look: `thread/start.dynamicTools` (host tools
  without MCP), `turn/start.disabledPluginIds`.

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
connects to a Unix socket owned by the core (`<state>/mcp.sock`, 0600; a per-session,
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

## Slices

- **C0, live probe (no API yet), done (`43bd1ccc`; Cursor pending quota):** a script that tries each "(C0)" cell above per agent with
  secret words. An instruction says word A, a skill says word B, a plugin's skill says word C,
  and an MCP tool returns word D. Then **mid-session**, it adds a second skill, plugin, MCP
  server and tool, each with a new word, through each "live" mechanism. The answers fill both
  tables and decide which items need a reload.
- **C1, context, done (see "C1 as built"):** `SessionContext` (instructions, skills, plugins, mcpServers), capability
  resolution, `context.degraded`, persistence plus resume/fork, and per-agent drivers using
  the per-session channels. The environment helpers become internal (the current exports
  are kept as deprecated). Live check: the C0 script through `core.sessions.create`.
- **C1b, in flight:** `session.updateContext`, the session context folder, the live mechanisms
  that C0 proved, and reload otherwise.
- **C2, host MCP servers:** `mcpServer()` / `tool()`, the bridge, the socket, live tool
  add/remove, cancellation, events, and keeper reconnect. Live check: each agent gets two host
  servers plus one external one, calls a tool on each and repeats the secret results; a tool
  is added mid-session; then a restart test with a detached session.
- **C3, broker:** the broker passes its instructions/plugins/MCP through `context`, its plugin
  adapters are deleted, and `mux-shim` becomes a host MCP server the broker attaches itself.

## Open questions

1. Cursor: all cells unproven (account out of quota). Re-run `bun scripts/context-probe.ts cursor`
   once it has quota; until then the core reports Cursor's context capabilities as unknown.
   Never write into the repo as a fallback.
2. ~~Grok `--rules`~~: answered no; use ACP `_meta.rules`, fixed at session creation.
3. ~~Codex `developerInstructions` on resume~~: ignored (neither replaces nor adds).
4. ~~Subagents~~: they inherit the session's MCP servers (Claude, Codex, Grok proven; OpenCode
   inferred), so host servers reach subagents for free; `ctx` should say when the caller is a
   subagent if the agent exposes that.
