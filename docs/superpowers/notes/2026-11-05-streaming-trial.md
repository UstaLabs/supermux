# Streaming replies — Ahmet's first try, and how to put it back (card #76)

What ran on 2026-11-05, so it can be set up again exactly.

## The build

- Branch `mux/streaming-replies`, commit `edcb34e6a`: today's `dev` (`d89011521`) merged in, plus streaming and
  the HostStore startup-race fix (`4f36c3e69`).
- Only the **desktop** app asks for live replies (`LIVE_REPLY_SURFACES = setOf("desktop")` in `HostStore.kt`).
  Phones, the web app and the released desktop app keep showing finished messages.

## Broker: the live :9898 runs the branch for a while (`scripts/streaming-trial/preview-swap.sh`)

The `mux:preview-broker` skill is stale for the supermux-core era, so use this script. It keeps the skill's
safety design: a deadman revert timer armed before anything stops, a readiness gate, and launching detached. It
runs the preview exactly like `mux.service`: `sg render`, the same PATH, `KillMode=process` (sessions spawned
during the preview survive its stop), `OOMPolicy=continue`, and the drop-ins' `Environment=` (`MUX_SHIM=external`,
WhatsApp).

1. The worktree needs the live web client and terminal helper, both gitignored:
   `ln -sfn ~/projects/supermux/src/channels/web/static <worktree>/src/channels/web/static`. The script passes
   `MUX_ZMX_BIN_DIR=~/projects/supermux/build/zmx/out` itself.
2. The worktree's `node_modules` and `packages/supermux-core/node_modules` are symlinks to `~/projects/supermux`'s.
3. Launch detached (it stops the broker this session's tools go through):
   `systemd-run --user --collect --unit=mux-swap-runner /bin/bash scripts/streaming-trial/preview-swap.sh swap <worktree> 2h`
4. Boot resume took 7 min with 132 sessions (a cold reboot ~9 min). The runner waits up to 10 min; if it gets
   close, stop only `mux-swap-runner`. The `mux-revert` timer stays armed.
5. `preview-swap.sh status` shows what is running; `revert` goes back to `mux.service` at once.
6. Never boot-check a scratch broker from inside a session without `env -i` and `MUX_SHIM=host`. It inherits the
   live broker's `MUX_*` and rewrote `~/.claude.json`'s mux-shim entries to its own checkout (repaired; see
   `claudemux.md`).

## Mac: the desktop hot run (`mac:~/work/supermux-hotreload`, leased)

1. Wait until `.lease` is free (or names you), then write
   `<session name> <session id> until <time>` to `.lease`, and the branch and commit to `SOURCE_COMMIT`.
2. Copy the branch's `apps/` over:
   ```
   S=$(mktemp -d); git archive <commit> apps | tar -x -C $S
   rsync -az --delete --exclude 'build/' --exclude '.gradle/' --exclude '.kotlin/' --exclude 'local.properties' \
     --exclude 'google-services.json' --exclude 'keystore.properties' --exclude '*.jks' $S/apps/ mac:work/supermux-hotreload/apps/
   ```
3. `ssh mac 'open -a Terminal ~/run-hotrun-supermux-hotreload.sh'` (`:desktop:hotRun --auto`; it was incremental,
   up in ~1 min).
4. When done: stop the script's process tree (the zsh, the gradlew clients, their daemons, and the app's JBR
   JVMs), delete `.lease`, and mark `SOURCE_COMMIT` "hot run STOPPED, lease released". Tell the next session.

## Scratch tools (outside git, in `~/streaming-probe/`, which survives a reboot)

| Tool | What it does |
|---|---|
| `probe.ts` + `run.sh` | One real-agent turn through supermux-core, every normalized event timestamped (`bun probe.ts <agent> <model> [effort] [text\|tool\|poem] [extraArgsJson]`) |
| `coalesce_stats.py` | Per-agent frame sizes and gaps after the broker's 50 ms coalescing |
| `e2e.ts` | A WS client against a shadow broker (`~/.mux/previews/stream76/start.sh`, port 9976): `reply_stream` frames vs the stored rows |
| `desktop-e2e.sh` | The desktop distributable on Xvfb against the shadow broker, recorded with ffmpeg (sandboxed profile, hosting off, delayed proxy) |
| `out/flow/poem-frames.json` | The recorded Opus 5.5 "write a long poem" stream as the desktop receives it |

The flow-design clips use it with `apps/ui/src/jvmTest/.../StreamFlowClips.kt`.
