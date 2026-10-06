# Dry run: migrating the main host (`mux.service`, dev) onto this branch (2026-10-06)

Nothing was migrated. The live broker (`mux.service`, PID 1910, `~/projects/supermux` on `dev`
`ae5464d6`), `~/.mux/state`, the tmux server and the live agents were left untouched. Branch:
`mux/supermux-core-exploratio` at `c6c8acb5`, with dev merged in. Scripts:
`scripts/migration-dry-run/`. Scratch: `~/.cache/migration-dry/`.

Safety measures:
- Every step that ran broker code ran inside `scripts/migration-dry-run/sandbox.sh` (bubblewrap).
  It mounts these read-only: `~/.mux`, `~/.claude`, `~/.claude.json`, `~/.codex`, `~/.grok`,
  `~/.cursor`, `~/.config/{opencode,cursor}` and `~/.local/share/opencode`.
- The DB snapshots were taken with `sqlite3 -readonly … .backup`.
- tmux was only read, with one `tmux list-windows -a` before the run.
- No agent resumed a live conversation id. Each conversation was copied under a new id into a
  scratch HOME.
- Credentials went into scratch without refresh tokens (Claude used an env access token), so no
  live login could be rotated.
- Afterwards: same broker PID, same tmux windows (plus one the live broker opened itself), and the
  original transcripts kept the same sha256 and mtime.
- All conversation copies were deleted. Only `report.json` files and the two DB copies are left
  (see Cleanup at the end).

## Step 1: migrations on a copy (`01-migrate-copy.ts`)

`runMigrations(db, MIGRATIONS)` (the broker's embedded manifest) on a copy of a `.backup` snapshot:

- **Result:** succeeded in 47 ms. Versions 31–35 were applied, so the max version is 35 (34
  migrations; 005 never existed). `integrity_check` = ok.
- **New `sessions` columns:** `prompts`, `core` (0 for every row), `permission_mode` (NULL
  everywhere), `account`.
- **Existing data:** values unchanged (hash of every pre-existing column). Messages: 32 160 before
  and after.
- **Schema stamp:** the live stamp is `29`. After migration the broker writes `34`. A dev binary
  then refuses to start (`schema_downgrade_refused`) until the stamp is set back (see Rollback).

| agent | status | before | after (core) |
|---|---|---|---|
| claude | active | 14 (13 tmux + 1 draft) | 14 (0) |
| claude | suspended | 54 | 54 (0) |
| claude | archived | 798 | 798 (0) |
| codex | active / archived | 1 / 141 | same (0) |
| cursor | archived | 50 | 50 (0) |
| grok | active / archived | 6 / 164 | same (0) |
| opencode | active / archived | 6 / 87 | same (0) |

The counts have moved a little since the brief: 14 active Claude including a draft, 54 suspended,
1240 archived.

## Step 2: boot simulation (`02-boot-sim.ts`)

**Setup.** Real `Registry` and `SessionManager`, and the real `createSupervisor`. Then
`reconcileOnStartup` followed by `resumeAtBoot`, in the same order as `main.ts`.
- Every host is the real `createXCoreHost` (prepare, context, core) with a fake driver that records
  each open.
- The session backend answers `livePid` / `resolve` from the captured window list
  (`tmux-windows.tsv`), and its `kill()` only records the call.
- Agent homes were rewritten to scratch in the sim copy.
- A second run kept the live agent-home paths in the read-only sandbox (`--live-homes`). Every
  non-Claude resume then failed with EROFS, which shows what a real boot writes into those homes:
  `grok/<name>/.grok/config.toml`, `opencode/<name>/config/opencode/opencode.json` and
  `codex/<name>/config.toml` + `AGENTS.md` (the legacy-instructions path). dev writes the same
  files on every launch.

**Result.** Boot took 528 ms with fake drivers. No `boot_resume_timeout`, and no resume failed.

- **Resumed at boot: 30 sessions.** 6 grok, 6 opencode and 1 codex (every active row; each opened
  with its own `agent_session_id`), plus 17 Claude:
  - the 13 active tmux-era rows;
  - **the draft row `supermux-2`, started as a FRESH agent** (bug 1);
  - **three SUSPENDED rows woken by a reused tmux window id** (bug 2): `personal-7` (@0, now
    "Greenmate Production Deployment Plan"), `greenmate-2` (@14, now "Kargo Web Servis Doc
    Page"), `Admin User Role Permission Management` (@15, now "Mobilisim SMS Provider").
- **Still suspended:** 51 Claude rows. None were started.
- **tmux windows that would be retired:** all 13 windows of session `mux`. The list is @0, @1, @3,
  @4, @5, @7, @12, @14, @15, @23, @27, @34, @38.
  - The stale rows produce extra `kill` calls on @0, @14 and @15. Those windows belong to sessions
    that are retired anyway.
  - @34 is the session that ran this dry run ("Supermux Core Accounts Broker Wiring"). Don't run
    the real migration from a tmux Claude session.
- **Core flags after boot:** 17 Claude rows `core=1`, 51 Claude rows `core=0`.
- **Archived rows:** 1240, hash identical before and after. No archived row was touched.
- **Resume ids:** every resumed session opened with its stored native id (no mismatch).

**Agent processes and memory.** Boot starts **30 agents** (26 if bugs 1 and 2 are fixed), each
under a keeper. The figures below were measured on this host today.

| Process | Measured RSS |
|---|---|
| Claude pane tree (claude + mux-shim + mux-channel) | 376 MB average (294–625) |
| grok agent | ≈ 140 MB |
| opencode serve | ≈ 235 MB |
| codex app-server | ≈ 185–280 MB |
| keeper (bun) | 56 MB |
| host-mode bridge | 53 MB |

- **Today:** 13 Claude trees (4.9 GB) + broker tree (2.9 GB) ≈ **7.8 GB**.
- **After boot, unfixed:** 17 × 376 MB (6.4 GB) + 13 other agents (2.4 GB) + 30 keepers (1.7 GB)
  + broker and LSPs (0.45 GB) ≈ **11 GB**.
- **With bugs 1 and 2 fixed:** ≈ **9.2 GB**.
- The host has 28.5 GB RAM. 16.6 GB was in use and 10.9 GB of swap already used.

## Step 3: real agents continue their conversations (`03-continuation.ts`)

One live row per agent. Each conversation was copied under a new id into a scratch HOME, and the
scratch DB row points at the copy. The resume ran through this branch's broker path with
production core-hosts and real CLIs, in `MUX_SHIM=host` mode (no socket server in the harness).

| | Claude | Grok | OpenCode | Codex |
|---|---|---|---|---|
| live row | `vibecoding-egitimi-5` (suspended, tmux-era, @87) | `Dummy Test Workspace` (active) | `dummytestproject` (active) | `Greenmate Jenkins Access` (active) |
| copy | `.jsonl` under a new uuid, in `<scratch HOME>/.claude/projects/<slug>` | session folder under a new id in `<scratch home>/.grok/sessions/<cwd>/` | `opencode export` from a scratch copy of `opencode.db` → new `ses_` id → `opencode import` into scratch | rollout under a new thread id in a scratch `CODEX_HOME` (+ the old `AGENTS.md`) |
| path | `resumeSuspended` (lazy) | `resumeAtBoot` | `resumeAtBoot` | `resumeAtBoot` |
| model | haiku | grok-4.6, low | `opencode/fledge-alpha-free` (see below) | gpt-5.6-luna, low |
| resumed | yes, 1.7 s; row → `core=1`; window @87 retired (fake) | yes, 0.9 s | yes, 4.8 s | yes, 1.5 s |
| history question | button "▶ SUNUM BAŞLAT", `hackathon-slides/index.html`, 3:00: **correct** | `test`=hiii, `test.md`=hello, removed `test`: **correct** | commit `8354634`, `haha` + `test.md`, had to find the git identity: **correct** | Jenkins host:port and username: **correct** |
| new instructions (session renamed to a fresh token) | **no**: answers its old name `vibecoding-egitimi-5` | **no**: answers the old repo `AGENTS.md` name | **yes** | **yes** (legacy `AGENTS.md` path) |
| native id after | the copy's id | the copy's | the copy's | the copy's |
| original transcript | unchanged (sha + mtime) | unchanged | unchanged | unchanged |

- **Expected results.** The instruction column matches `session-context.md`:
  - Claude keeps the system prompt stored with the conversation.
  - Grok applies `_meta.rules` only at `session/new`.
  - Codex re-reads `CODEX_HOME/AGENTS.md`.
  - OpenCode gets its `OPENCODE_CONFIG` on every launch.

  So every pre-existing Claude and Grok session keeps its tmux-era / dev-era instructions for good.
- **Conversation ids.** Native ids never change through Core, so after a rollback dev resumes the
  same conversation ids.
- **OpenCode model.** The row's own model, `opencode-go/glm-5.3-flash`, failed with "An active
  OpenCode Go subscription is required to use Go models". That was with the live keys
  (`auth.json` + `account.json`), so it is a subscription problem, not a migration problem: the live
  opencode sessions on `opencode-go/*` models are affected today too.

## Bugs found (NOT fixed: both change boot behaviour)

1. **`resumeAtBoot` starts an agent for a draft.** `manager.ts` `resumeAtBoot` /
   `resumeOneAtBoot` don't skip `isDraftSession(s)`.
   - The draft row `supermux-2` (`user_status='draft'`, no `agent_session_id`) gets a fresh Claude
     at every boot, plus `core=1` and an `agent_home`.
   - The draft's first message then hard-deletes the row and spawns a new session (`main.ts`
     ~3480), which leaves that Core agent and record orphaned.
   - Fix: `if (isDraftSession(s)) continue` in `resumeAtBoot`.
2. **`reconcileOnStartup` revives SUSPENDED tmux-era rows through reused window ids.**
   `supervisor.ts` `reconcileOnStartup` checks the stored `tmux_window_id` of every listed row,
   suspended ones included.
   - tmux reuses window ids after a server restart (the current server started on 10-05), so a
     suspended row's old id can name another session's live pane. It is then `activate`d.
   - On dev this only mislabels the row (pre-existing). On this branch `resumeAtBoot` then resumes
     it through Core: 3 extra agents, a `kill` on another session's window id, and `greenmate-2`
     has no transcript, so its resume fails.
   - Fix: skip `status === "suspended"` there, or check that the window's name still matches.
3. **Latent, same root.** `retireTmuxWindow` trusts the stored id.
   - A lazy resume of any of the 51 suspended tmux-era rows (ids like @9, @11, @20, @21…) runs
     `tmux kill-window -t @N` on whatever holds that id on the default server at that moment.
   - After the migration no supermux agent windows are left, so the risk is only the user's own
     windows on the default socket.
   - Fix as in 2, or null `tmux_window_id` on suspended Claude rows during the migration.
4. **Latent, not on this DB (no active PA).** The supervisor's `respawnPA` resumes a Claude PA
   through Core (`resumeClaudeSession`) without `retireTmuxWindow`.
   - A tmux-era PA with a live pane would get two writers.
   - It also sets `core=1` first, so `resumeAtBoot`'s retire is skipped.
5. **Minor.**
   - `retireTmuxWindow` returns as soon as `kill-window` is sent, and the Core resume starts at
     once. There is a short window in which the dying TUI can still flush to the transcript.
   - `resumeAtBoot` is not idempotent: a second call logs "already live" failures. This is
     harmless and reachable via `resumeDeferredBoot`.

## Other things the real migration will meet

- **8 suspended Claude rows have no transcript file** (`Bisiklet…`, `Build Release APK`,
  `Supercomment JS Library`, `Work Item 928…`, `greenmate-2`, `personal`, `vibecoding-egitimi`,
  `vibecoding-egitimi-2`).
  - Their next message fails to resume, on dev as well.
  - 604 of 766 archived Claude rows have none either.
  - Likely cause: Claude's own transcript cleanup (`cleanupPeriodDays`, default 30).
- **Claude rows have no `agent_home`** (all of them). Core uses `~/.mux/state/agents/claude/<name>`
  and stores it. That works; homes are keyed by name, and 4 archived names contain `/`, which
  makes a nested folder.
- **Grok, OpenCode and Codex data were found where the core-hosts expect them:**
  - all 6 grok sessions under `<agent_home>/.grok/sessions/<urlencoded workdir>/<id>`;
  - all 6 opencode sessions in the shared `~/.local/share/opencode/opencode.db`;
  - the codex rollout in `<agent_home>/sessions/…`.
  - Nothing is missing an `agent_home` or `agent_session_id` among non-archived non-Claude rows.
    Archived rows missing them: cursor 4, opencode 8.
- **Every active Claude TUI is killed and relaunched headless.** Any in-flight turn is cut, and the
  "terminal" of a Claude session no longer shows the TUI.
- **`~/.claude.json` mux entries.** `mux-shim` / `mux-channel` point at
  `bun run /home/ahmet/projects/supermux/src/shim/index.ts`.
  - In `external` mode every Claude Core session starts both, as today.
  - Deploy the branch IN `~/projects/supermux` so those entries run the branch's shim.
- **The clients changed** (72 files under `apps/` since dev). Restage the web static bundle; the
  installed desktop and Android builds may lack the accounts UI.
- **Restarts and keepers.** `mux.service` uses the default `KillMode=control-group`, so a stop or
  restart kills every keeper and agent. That defeats detach, and an in-flight turn dies on every
  redeploy. Add `KillMode=process` (the preview's gotcha).

## Runbook

**0. Choose the moment.** Pick a time when no session is mid-turn. Run from a plain shell or ssh,
not from a supermux Claude session (its tmux window gets killed). Fix bugs 1 and 2 first
(recommended), or use the workarounds in step 2.

**1. Back up.** Stop the broker first so the backup is final. tmux panes survive the stop because
they live in their own scope.
```sh
systemctl --user stop mux.service
B=~/.mux/backups/pre-core-$(date +%F-%H%M); mkdir -p "$B"
sqlite3 ~/.mux/state/db.sqlite3 ".backup '$B/db.sqlite3'"
cp ~/.mux/state/schema-version ~/.claude.json "$B/"
git -C ~/projects/supermux rev-parse HEAD > "$B/dev-rev"     # ae5464d6
```

**2. Workarounds, only if bugs 1 and 2 are not fixed.**
```sh
sqlite3 ~/.mux/state/db.sqlite3 "UPDATE sessions SET tmux_window_id=NULL WHERE agent='claude' AND status='suspended'"
# draft supermux-2: open it and close it in the app first, or accept one orphan agent per boot
```

**3. Deploy the code.**
```sh
git -C ~/projects/supermux checkout --detach <branch-commit>  # branch is checked out in the worktree
cd ~/projects/supermux && bun install                        # packages/supermux-core workspace deps
cd apps && ./gradlew :web:stageForBroker                     # web static (~10 min)
```

**4. Unit drop-in.** Create `~/.config/systemd/user/mux.service.d/core.conf`. ExecStart, PATH and
the WhatsApp env stay as they are.
```ini
[Service]
KillMode=process
Environment=MUX_SHIM=external
```
Then:
```sh
systemctl --user daemon-reload && systemctl --user start mux.service
```

**5. Verify** (`journalctl --user -u mux -f`):
- no `storage_init_failed` / `schema_downgrade_refused`, and `schema-version` = 34;
- `mux_shim_mode {"mode":"external"}`;
- `claude_tmux_window_retired` ×13 and `claude_core_resume_ok` ×13;
- `grok_resume_ok` ×6, `opencode_resume_ok` ×6, `codex_resume_ok` ×1;
- no `boot_resume_timeout`;
- `tmux list-windows -t mux` → no agent windows;
- one message to one session of each kind.

The flip to `MUX_SHIM=host` is a later, separate restart, done when idle (see
`c3b-mux-shim-plan.md`).

**Rollback.** No core agent may outlive the switch: dev resumes the same conversation ids in tmux,
which would give two writers.
```sh
systemctl --user kill --kill-whom=all mux.service; systemctl --user stop mux.service
pgrep -af 'supermux-core/src/keeper/keeper.ts'                 # must be empty
rm ~/.config/systemd/user/mux.service.d/core.conf && systemctl --user daemon-reload
git -C ~/projects/supermux checkout dev && (cd ~/projects/supermux && bun install)  # restage dev's web static if it was replaced
# a) exact restore (messages since the migration are lost):
cp "$B/db.sqlite3" ~/.mux/state/db.sqlite3 && rm -f ~/.mux/state/db.sqlite3-wal ~/.mux/state/db.sqlite3-shm
echo 29 > ~/.mux/state/schema-version
# b) keep the migrated DB (dev ignores the extra columns; UNTESTED here): only `echo 29 > ~/.mux/state/schema-version`
systemctl --user start mux.service
```
dev then:
- finds no panes, so its Claude rows are suspended and resume lazily via `claude --resume <same id>`;
- relaunches grok, opencode and codex with their unchanged ids.

Files left behind, all harmless to dev: `~/.mux/state/core/`, `~/.mux/state/agents/claude/<name>`
and the codex `.supermux-agents-md-session` markers.

**What the user will notice.**
- Every active Claude restarts headless, and its tmux TUI disappears.
- Old Claude and Grok sessions keep their old instructions; Codex and OpenCode sessions pick up the
  new generated ones (with their name).
- Unfixed: three long-suspended chats come back as active, and the draft quietly gets an agent.
- About 1.4–3 GB more RAM, from the keepers and the extra agents.
- The suspended rows without a transcript still fail to resume, as on dev today.
- OpenCode Go models fail until the subscription is renewed, as today.

## Cleanup

`~/.cache/migration-dry/` keeps `db-live-snapshot.sqlite3` and `db.sqlite3` (the migrated copy),
the logs and the `report.json` files. The two DB copies contain all live messages, including
secrets that were pasted into chats: delete them when you are done. All conversation copies,
scratch homes and the 3.8 GB opencode DB copy were deleted.
