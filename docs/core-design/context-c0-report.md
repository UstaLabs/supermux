# Session context, slice C0: live probe report (2026-10-04)

This report answers the "(C0)" cells of `session-context.md`. Every result was run against the real
CLIs on this host: claude 2.1.289, codex 0.159.2, cursor-agent 2026.09.18, grok 1.0.46 and opencode 1.16.2.

**How a cell is proven.** Each mechanism carries its own random code word (`MAPLE4697`, `OTTER1801`, …).
The prompt never contains the word, so the agent can only repeat it if the mechanism worked. A
reply alone never counts as proof of *who* called a tool, so the subagent cells also check the
stream frames (Claude `parent_tool_use_id`, Codex child `threadId`, ACP child `sessionId`) and the
probe MCP server's own call log.

**Re-run:** `cd packages/supermux-core && bun scripts/context-probe.ts [claude|codex|cursor|grok|opencode …] [--only 1,4,7]`.
It prints one `PASS/FAIL/RELOAD/INFO` line per cell and writes `results.json`, `frames.log` (every
JSON line both ways) and `mcp-*.log` (every MCP message the probe server got) under
`~/.cache/context-c0/run-<stamp>/<agent>/`. The final full run that the evidence below quotes is
`run-2026-10-04T18-14-53-088Z` (73 cells). Earlier runs that are cited are named next to the cell.

Legend: **yes** = proven by the code word · **no** = the mechanism ran and the word did not come back ·
**unproven** = could not be decided · **live** = picked up by the running process in the same
conversation · **reload** = needs a new process that resumes the same conversation (proven that way) ·
**impossible** = no channel found.

## Startup (items 1–4)

| Agent | 1 Instructions | 2 Skills (folder of `<name>/SKILL.md`) | 3 Plugins | 4 MCP (stdio, v2 SDK server) | 4b MCP reaches a subagent |
|---|---|---|---|---|---|
| Claude | **yes**: `--append-system-prompt-file` | **yes**: generated plugin dir (manifest + `skills` → symlink to the folder) via `--plugin-dir` | **yes**: `--plugin-dir <plugin>`; **yes**: `--plugin-dir <folder of plugins>` loads each child (symlinked child) | **yes**: `--mcp-config` + `--strict-mcp-config` | **yes** |
| Codex | **yes**: `thread/start {developerInstructions}` | **yes**: `skills/extraRoots/set` before `thread/start`; **yes**: `<CODEX_HOME>/skills` | **yes**: local marketplace + `codex plugin add` into the session `CODEX_HOME` (HOME also private) | **yes**: session `config.toml [mcp_servers.*]` (needs an approval policy that can ask, see surprises) | **yes** (`spawnAgent` child thread) |
| Cursor | **unproven** (account out of quota). Channels tried: plugin `rules/`, `$HOME/.cursor/rules/*.mdc`, `$HOME/AGENTS.md`, `--add-dir <root>/AGENTS.md` | **unproven** (quota). Side evidence: `$HOME/.cursor/skills/<name>` IS advertised in ACP `available_commands_update`; `--plugin-dir` plugin skills are NOT | **unproven** (quota) | **unproven** at the model level (quota); the ACP `mcpServers` server WAS spawned, initialized and listed | **unproven** |
| Grok | **yes**: ACP `session/new` `_meta: {rules}`; **no**: `--rules` with `agent stdio` | **yes**: session `config.toml [skills] paths` | **yes**: `grok plugin install <local> --trust` with HOME = session home; **yes**: `grok agent --plugin-dir <plugin> stdio` (per process) | **yes**: ACP `session/new {mcpServers}` | **yes** (child session ran `use_tool probesub__get_code_word`) |
| OpenCode | **yes**: config `instructions` (session `XDG_CONFIG_HOME`) | **yes**: config `skills.paths` | **yes** (mapped part only): the plugin's `skills/` added to `skills.paths` | **yes**: ACP `session/new {mcpServers}` | **yes**, inferred (run `18-09-46`): see below |

### MCP `initialize` per CLI (logged by the probe server)

| CLI | First methods | `protocolVersion` asked | `clientInfo` | Client capabilities | v2 server (2.3.0) negotiated |
|---|---|---|---|---|---|
| claude 2.1.289 | **`server/discover`** (→ `-32601 Method not found`), then `initialize` | `2025-11-25` | `{name:"claude-code", title:"Claude Code", version:"2.1.289"}` | `roots.listChanged`, `elicitation {form,url}` | `2025-11-25`, works after the discover fallback |
| codex 0.159.2 | `initialize` | `2025-06-18` | `{name:"codex-mcp-client", title:"Codex", version:"0.159.2"}` | `experimental."codex/auth-change"`, `elicitation {form,url}` | `2025-06-18` |
| cursor 2026.09.18 | `initialize` | `2025-11-25` | `{name:"Cursor", version:"1.0.0"}` | `elicitation {form}` | `2025-11-25` |
| grok 1.0.46 | `initialize` | `2025-11-25` | `{name:"grok-shell-<server name>", version:"1.0.46"}` | `extensions."io.modelcontextprotocol/ui"`, `elicitation` | `2025-11-25` |
| opencode 1.16.2 | `initialize` | `2025-11-25` | `{name:"opencode", version:"1.16.2"}` | `{}` | `2025-11-25` |

No CLI asked for `2026-07-28`, and every one negotiated cleanly with the v2 server. Nothing has to be
pinned to the v1 package.

## In flight (items 5–9)

| Agent | 5 Host server adds a tool + `tools/list_changed` | 6 Add a skill / a plugin | 7 Add an MCP server (conversation kept?) | 8 Change instructions | 9 Reload (new process, same conversation) |
|---|---|---|---|---|---|
| Claude | **live** (re-lists, calls the new tool next turn) | skill: **live** (new dir + `reload_skills`); plugin: **live** (new symlink in the folder + `reload_plugins`) | **live**: `mcp_set_servers`, conversation kept | **reload**: `apply_flag_settings {appendSystemPrompt}` is accepted (`success`) but has no effect, and a rewritten prompt file is not re-read | already proven in A1 (account switch) |
| Codex | **reload**: `list_changed` ignored (no re-list) and `config/mcpServer/reload` does not restart an unchanged server; a new app-server + `thread/resume` sees the new tool | skill: **live** (`skills/extraRoots/set` with the new root added); plugin: **live** (`plugin/install`) | **live**: `config/value/write` + `config/mcpServer/reload`, conversation kept. In 1 of 4 runs the model answered "NONE" on the first turn without calling the tool | **impossible as a replacement**: `thread/resume {developerInstructions}` is ignored on the same process AND in a new process (it is not even written to the rollout). **Add-only, live**: `turn/start {additionalContext}` (see below) | proven in A1; also here (the new process recalled the earlier MCP word) |
| Cursor | **unproven** (quota) | **unproven** (quota) | **unproven** | **unproven** | **unproven**: `session/load` → "Session not found" (no turn ever succeeded, so nothing was saved) |
| Grok | **reload**: grok gets `list_changed` and does not re-list (`search_tool` says there is no such tool); after `session/load` the tool exists | skill: **live** (rewriting `[skills] paths` in the session `config.toml` while it runs is seen on the next prompt, with a NEW path); plugin: **reload** (extra `--plugin-dir` + `session/load`) | **reload**: new process + `session/load {mcpServers:[…, probe2]}`, conversation kept | **no**: neither `_meta.rules` on `session/load` nor a new `--rules` changes the rules; the session keeps the rules it was created with | **yes**: new process `session/load` recalled the earlier word |
| OpenCode | **live** (re-lists 3 times; calls the new tool) | skill and plugin: **reload** (`skills.paths` rewritten + `session/load`) | **reload**: `session/load {mcpServers}` with the new server, conversation kept | **reload**: a changed `instructions` file + new process + `session/load` | **yes** |

## Evidence (quotes from run 18-14-53 unless noted)

**Claude** (stream-json; `-p --input-format stream-json --output-format stream-json --verbose --model haiku --permission-mode bypassPermissions` + the `prepareClaudeEnvironment` args; `CLAUDE_CONFIG_DIR` private; `CLAUDE_CODE_OAUTH_TOKEN` = the current access token, read-only)
- `initialize` control request: commands listed `probe-skills:probe-skill-alpha`, `probe-plugin-a:probe-plugin-a-skill`, `probe-plugin-b:probe-plugin-b-skill`.
- 1 `JACKAL7887` · 2 `ZINNIA1010` · 3 `VIOLET8023` (plugin), `MAPLE5912` (folder child) · 4 `OTTER3495`.
- 4b `QUARTZ6405`: frames show `tool_use mcp__probesub__get_code_word` with `parent_tool_use_id` set and its `tool_result` "The code word is …"; the main agent never called it. Gotcha: haiku backgrounds the Agent tool, so the turn's `result` comes **before** the subagent finishes. The completion then starts a wake-up turn of its own.
- 5 `FALCON3942`, the server got 3 `tools/list`.
- 6 `{"type":"control_request","request_id":…,"request":{"subtype":"reload_skills"}}` → `CEDAR5340`; `{"subtype":"reload_plugins"}` → response `plugins: [probe-skills, probe-plugin-a, probe-plugin-b, probe-plugin-c, …]`, then `BISON3277`. Optional `hold_on_cache_impact: true` refuses a reload that would change the tool list under a prompt-cache dependency.
- 7 `{"subtype":"mcp_set_servers","servers":{"probe2":{"type":"stdio","command","args","env"}}}` → `{"added":["probe2"],"removed":[],"errors":{}}`; reply `COBALT6536 JACKAL7887` (new tool + the startup instruction word). `mcp_set_servers` replaces only the *dynamic* set: `--mcp-config` servers stay.
- 8 `{"subtype":"apply_flag_settings","settings":{"appendSystemPrompt":"…"}}` → `success`, but the reply `JACKAL7887` names only the old word.

**Codex** (`codex app-server` + `codexTokenArgs(account_id)` (access token in env, custom provider, no auth.json); `CODEX_HOME` and `HOME` both private; model gpt-5.6-luna, effort low)
- 1 `LYNX5219` · 2 `EMBER5821` (extraRoots), `WALRUS2891` (`<CODEX_HOME>/skills`) · 3 `SAFFRON4986` (`probe-plugin-a:probe-plugin-a-skill`) · 4 `GLACIER4717`.
- Plugin install: `codex plugin marketplace add <dir>` (dir holds `.agents/plugins/marketplace.json`, `name:"probe-mkt"`, entries `{name == manifest name, source:{source:"local", path:"./plugins/<name>"}, policy:{installation:"AVAILABLE"}}`) → "Added marketplace `probe-mkt`"; `codex plugin add probe-plugin-a@probe-mkt` → "Added plugin". Both write only to the session `CODEX_HOME`.
- 4b `MARLIN1986`: `item/completed {type:"mcpToolCall", server:"probesub"}` on the CHILD threadId with the word in `result`; no call on the parent thread; one approval `mcpServer/elicitation/request` answered `{action:"accept", content:{}}`.
- 5 `list_changed`: reply `NONE`, 1 `tools/list` in total. After `config/mcpServer/reload`: still `NONE`, still one server process. After a new app-server + `thread/resume`: `PEBBLE4111` (reload).
- 6 `skills/extraRoots/set {extraRoots:[rootA, rootB]}` → `ORCHID6670`; `plugin/install {pluginName:"probe-plugin-c", marketplacePath:<abs marketplace.json>}` → `{"authPolicy":"ON_INSTALL","appsNeedingAuth":[]}`, then `JACKAL3082`.
- 7 `config/value/write {keyPath:"mcp_servers.probe2", value:{command,args,env}, mergeStrategy:"upsert"}` → `{status:"ok", filePath:<session config.toml>}`; `config/mcpServer/reload {}`; reply `ZINNIA9184 LYNX5219`. The new server starts at the next `turn/start` (`mcpServer/startupStatus/updated` starting→ready, also for live child threads). Runs 17-43, 17-50, 17-51 and 18-14 passed; run 17-48 answered `NONE` without a tool call.
- 8 `thread/resume {threadId, developerInstructions:"…NEW…"}` (same process) → reply `LYNX5219` only. New process `thread/resume {threadId, cwd, model, approvalPolicy, sandbox, developerInstructions}` → `LYNX5219 MAPLE7964 OTTER2036 GLACIER4717` (old word, the additionalContext words, the earlier MCP word: conversation kept, new instructions ignored). The rollout `.jsonl` holds only the `thread/start` text.
- 8 `turn/start {additionalContext:{"supermux-instructions":{kind:"application", value:"…X…"}}}` → `LYNX5219 MAPLE7964`. The next turn without it still sees X; the same key with a new value Y → `… MAPLE7964 OTTER2036`. So it is a sticky message added to the history, not a replaceable slot.

**Cursor** (`cursor-agent --plugin-dir … --add-dir … --approve-mcps acp`; HOME = session home from `prepareCursorEnvironment` with `instructions: null`)
- Every prompt is answered "Upgrade your plan to continue" (Auto, `composer-2.5`, `gemini-3.7-flash`): the account is out of quota. `session/set_config_option` needs the full id (`composer-2.5[fast=true]`), not the short name.
- Proven without the model: ACP `mcpServers` → the probe server got `initialize` (2025-11-25, `clientInfo {name:"Cursor", version:"1.0.0"}`) and `tools/list`. A skill at `$HOME/.cursor/skills/probe-home-skill/SKILL.md` appears in `available_commands_update`, but a `--plugin-dir` plugin's skill does not, and a `--plugin-dir` plugin's `.mcp.json` server was not spawned within 8 s of `session/new`. So whether `--plugin-dir` before `acp` is honoured is still open (plugin skills may simply not be advertised).

**Grok** (`grok [--rules …] agent --no-leader --reasoning-effort low [--plugin-dir …] stdio`; HOME private, `GROK_AUTH_PATH` = a scratch copy of auth.json; `prepareGrokEnvironment` with `instructions: null`)
- 1 `_meta.rules` → `LYNX6289`; the `--rules` word never came back (runs 17-51, 17-55, 18-14). Grok's own docs (in the binary) list the `session/new` `_meta` keys: `rules`, `systemPromptOverride`, `agentProfile`, `yoloMode`, `autoMode`.
- 2 `QUARTZ5840` · 3 `CEDAR2167` (installed), `BISON2541` (`agent --plugin-dir`) · 4 `ZINNIA3787`.
- 4b `VIOLET5805`: main `spawn_subagent`; child session `01a10…` ran `search_tool` then `use_tool probesub__get_code_word`; the probesub log shows one `tools/call`.
- 5 the server logged `notifications/tools/list_changed` out and no new `tools/list`; the reply was "The probe server has no tool named `get_late_code_word`".
- 6 live `FALCON6419` (a new skills root written into `[skills] paths` while running); reload: plugin `COBALT1409`, skill `WALRUS4989` (a skill name first seen after the reload).
- 7 `OTTER3680` after `session/load` with `[probe, probesub, probe2]`; 9 `ZINNIA3787` recalled after `session/load`.
- 8 after `session/load` with `_meta.rules` = new text and a new `--rules`, the reply is the OLD `LYNX6289`.

**OpenCode** (`opencode acp --print-logs --log-level ERROR`; `XDG_CONFIG_HOME` from `prepareOpenCodeEnvironment`, plus private `XDG_DATA_HOME` (copy of the API-key auth.json), `XDG_STATE_HOME` and `XDG_CACHE_HOME`; model `opencode-go/qwen3.7-plus`)
- 1 `OTTER6922` · 2 `ORCHID3810` · 3 `ZINNIA3318` · 4 `SAFFRON6049` · 5 `MARLIN9729` (3 `tools/list`) · 9 `SAFFRON6049` · 7 `PEBBLE8346` · 6 `JACKAL1549` / `MAPLE1085` · 8 `QUARTZ2452`.
- 4b (run 18-09-46): reply `FALCON8916` (the probesub word); the only tool call in the main session was `task`, and probesub logged one call. OpenCode does not forward a child session's updates over ACP, so this is inferred, not seen. In run 18-14 the model skipped the subagent and repeated an old word: no call was logged, so that run does not count.
- Models that failed on this account: `opencode-go/deepseek-v4-flash` ("requires Global regions"), `alibaba-token-plan/*` ("Access to model denied"), `opencode/*` ("Insufficient account funds"), and `big-pickle`, the free tier ("OpenCode 1.18.0 or newer is required").

## What stayed unproven

- **Cursor, everything at the model level** (items 1–3, 4 tool call, 4b, 5–9): the account is out of quota. The instruction channels to try first once there is quota: a plugin `rules/` (alwaysApply), `$HOME/.cursor/rules/*.mdc`, `$HOME/AGENTS.md` and `--add-dir <root>/AGENTS.md` (the script already tests all four with distinct words). The skills channel to prefer is `$HOME/.cursor/skills` (advertised over ACP).
- **Claude 9 / Codex 9:** not re-run, as the brief asks (A1 proved them). Codex 9 also came back incidentally here: the new app-server recalled the earlier word.
- **Codex 7:** flaky on the first turn (1 of 4 runs).
- **OpenCode 4b:** inferred from the main session's tool calls plus the server's call log, not seen in child frames.

## Surprises

1. **Claude 2.1.289 sends `server/discover` (2026-07-28 spec) before `initialize`**, and the v2 SDK server 2.3.0 answers `-32601`. Claude then falls back to `initialize 2025-11-25`. The 2.3.0 package's `SUPPORTED_PROTOCOL_VERSIONS` tops out at `2025-11-25` (`LATEST_PROTOCOL_VERSION = "2025-11-25"`). It has 2026-07-28 *types* (e.g. `DiscoverRequestSchema`), but `McpServer` does not answer `server/discover`. The design's line "implements the 2026-07-28 spec" is wrong for the stdio `McpServer` path today. It works only because Claude falls back.
2. **Codex MCP tools never run with `approvalPolicy: "never"`:** "MCP tool call requires approval, but approval policy is never". The policy has to be one that can ask (`on-request`), and the host answers `mcpServer/elicitation/request` with `{action:"accept"}`. Child threads read the policy from `config.toml`, so it must be there too.
3. **Codex `developerInstructions` are fixed at `thread/start`.** `thread/resume` ignores them, in the same process and in a new one. The only in-flight channel is `turn/start.additionalContext`, which **adds** a sticky history entry (it cannot replace or remove).
4. **Codex ignores `tools/list_changed`**, and `config/mcpServer/reload` does not restart a server whose config did not change.
5. **Grok `--rules` (alias `--append-system-prompt`) does nothing in `agent stdio`.** The ACP channel is `session/new` `_meta.rules`. Rules are fixed at session creation: `session/load` ignores new `_meta.rules`. Per the binary's strings, only `systemPromptOverride` is re-applied on a cold load.
6. **Grok has `agent --plugin-dir <dir>`** ("for this process only … used by the Agent SDKs to inject per-connection plugins") and a config `[plugins] paths`. Both are per-session plugin channels that need no `grok plugin install`. Grok also accepts `.claude-plugin/plugin.json` as a manifest.
7. **Grok picks up a rewritten `[skills] paths` live**, including a new root.
8. **Codex `thread/start` has `dynamicTools`, and `turn/start` has `additionalContext` and `disabledPluginIds`.** These are possible native channels the design does not use yet.
9. **Claude `reload_plugins` also has `hold_on_cache_impact`.** It refuses a reload that would change the tool list under a cached prompt. Useful for "apply between turns without breaking the cache".
10. **Cursor ACP advertises `$HOME/.cursor/skills` but not `--plugin-dir` plugin skills.**

## What the design must change

- **Instructions column:** Claude in flight → `reload` (`apply_flag_settings` cannot do it). Codex → instructions are set **only at create**. On resume or in flight the core can only *add* (`additionalContext`, sticky, visible as context), so `updateContext({instructions})` on Codex must be `unsupported` for "replace", or opt-in "append-only". Grok → the channel is ACP `_meta.rules` (not `--rules`), fixed at create; in flight = `unsupported` (or a new session). OpenCode → `reload` works. Cursor → still open question 1.
- **Open question 2 (Grok `--rules`):** answered no. Use `_meta.rules`, and it does not survive a change through `session/load`.
- **Open question 3 (Codex resume):** answered: neither replace nor add. Resume ignores the field.
- **Open question 4 (subagents):** Claude, Codex and Grok pass the session's MCP servers to their subagents (proven), and OpenCode does too (inferred). So host servers reach subagents for free. Cursor is unproven.
- **"Tools of an attached host server" column:** Claude and OpenCode are `live` through `list_changed`. Codex and Grok ignore it, so it is `reload` for them. For Codex the reload must be a new app-server, because `config/mcpServer/reload` keeps an unchanged server. Changing `host.add(tool)` on those two means a relaunch between turns (or the bridge could restart its stdio process and change the server's config, which is untested).
- **Grok skills/plugins column:** skills `live` (rewrite `[skills] paths`); plugins → per-process `--plugin-dir` + reload, so `grok plugin install` is not needed at all.
- **Codex MCP:** the driver must use an asking approval policy and auto-accept host-server tool elicitations, or set a per-server approval mode. Adding a server in flight is `live` via `config/value/write` + `config/mcpServer/reload`. It takes effect when the next turn starts the server, so the core should wait for `mcpServer/startupStatus/updated ready` (or accept one racy first turn).
- **MCP SDK:** keep v2, but either answer `server/discover` in the host's server (a custom handler) or accept the Claude fallback. Do not claim 2026-07-28 support. All five CLIs negotiate 2025-06-18 (Codex) or 2025-11-25 (others).
- **Cursor skills:** prefer linking skills into the session `$HOME/.cursor/skills` over a generated `--plugin-dir` wrapper, pending a quota-backed re-run.

## Safety record

- The real homes were only read: `~/.claude/.credentials.json`, `~/.codex/auth.json`, `~/.config/cursor/auth.json`, `~/.grok/auth.json` and `~/.local/share/opencode/auth.json` have the same mtimes before and after (snapshot in `~/.cache/context-c0/snapshot-{before,after}.txt`). Grok and OpenCode used scratch copies of their auth files. Codex used the access token in env (no refresh token, no auth.json). Claude used the access token in env with a private `CLAUDE_CONFIG_DIR`.
- During the window, other live sessions on the host changed `~/.codex/config.toml` + `~/.codex/plugins/cache/mux` (17:41:25Z, the broker's `mux` plugin), `~/.cursor/cli-config.json` (17:57:48Z, before the cursor probe started), and `~/.grok/docs` + `~/.codex/models_cache.json` in the same second (18:21:33Z, the broker's model refresh). None of them contains `context-c0`, `probe-mkt` or `probe-plugin`. No probe artifacts are in `~/.grok/installed-plugins` or `~/.agents`.
- One stray read-only call, `cursor-agent --list-models` with the real HOME, wrote nothing (checked).
- The workdirs were scratch git repos under `~/.cache/context-c0/run-*/<agent>/work`. Every child is killed by process group, and none was left running.
