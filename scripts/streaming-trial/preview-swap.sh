#!/usr/bin/env bash
# Card #76: run the live :9898 broker from a worktree for a while, then auto-revert. The safety design is the
# mux:preview-broker skill's (deadman revert timer armed FIRST, readiness-gated, auto-revert on failure), with
# what that August-era script lacks for the supermux-core era:
#   - the preview unit runs exactly like mux.service: `sg render`, the same PATH, KillMode=process (keepers
#     spawned during the preview survive its stop), OOMPolicy=continue, and the drop-ins' Environment=
#     (MUX_SHIM=external, the WhatsApp settings) read from the live unit's own drop-in files;
#   - MUX_ZMX_BIN_DIR points at the live checkout's built terminal helper (gitignored, absent in a worktree);
#   - the readiness wait covers today's boot resume (~4 min before :9898 answers), not 150 s.
# Must be launched DETACHED (it stops the broker this session's tools go through):
#   systemd-run --user --collect --unit=mux-swap-runner /bin/bash preview-swap.sh swap <worktree> <dur>
set -uo pipefail
PORT=9898; SERVICE=mux; PREVIEW_UNIT=mux-preview; REVERT_UNIT=mux-revert
BUN=/home/ahmet/.bun/bin/bun
LOG=/home/ahmet/streaming-probe/out/preview-swap.log
DROPINS=/home/ahmet/.config/systemd/user/mux.service.d
log(){ echo "[$(date '+%H:%M:%S')] $*" | tee -a "$LOG"; }
me_code(){ curl -s -o /dev/null -m 3 -w "%{http_code}" "http://127.0.0.1:$1/me"; }
wait_up(){ local port=$1 t=$2 c; for ((i=0;i<t;i++)); do c=$(me_code "$port"); [ "$c" != "000" ] && { echo "$c"; return 0; }; sleep 2; done; echo 000; return 1; }
wait_down(){ local port=$1 t=$2; for ((i=0;i<t;i++)); do [ "$(me_code "$port")" = "000" ] && return 0; sleep 1; done; return 1; }

# Every Environment= line of the live unit's drop-ins, as --setenv arguments (secrets stay in those files).
dropin_env(){
  local f line
  for f in "$DROPINS"/*.conf; do
    while IFS= read -r line; do
      case "$line" in Environment=*) printf -- '--setenv=%s\n' "${line#Environment=}";; esac
    done < "$f"
  done
}

swap(){ local wt=$1 dur=$2
  log "=== SWAP: $wt -> :$PORT for $dur ==="
  [ -f "$wt/src/main.ts" ] || { log "no $wt/src/main.ts — abort"; return 1; }
  [ -e "$wt/src/channels/web/static/index.html" ] || { log "no built web client in the worktree — abort"; return 1; }
  systemctl --user stop "${REVERT_UNIT}.timer" "${REVERT_UNIT}.service" 2>/dev/null
  log "arming deadman revert in $dur (fires even if this runner dies)…"
  systemd-run --user --on-active="$dur" --unit="$REVERT_UNIT" --collect \
    /bin/bash -c "systemctl --user stop $PREVIEW_UNIT 2>/dev/null; systemctl --user start $SERVICE" \
    || { log "could not arm the revert timer — ABORT (default broker untouched)"; return 1; }
  log "stopping default $SERVICE (KillMode=process: keepers stay)…"
  systemctl --user stop "$SERVICE"
  wait_down $PORT 30 || log "(warning) :$PORT still answered after stop"
  mapfile -t envargs < <(dropin_env)
  log "starting $PREVIEW_UNIT from the worktree (${#envargs[@]} drop-in env vars)…"
  systemd-run --user --unit="$PREVIEW_UNIT" --collect \
    -p KillMode=process -p OOMPolicy=continue \
    --setenv=NODE_ENV=production \
    --setenv=PATH="/home/ahmet/.local/bin:/home/ahmet/.bun/bin:/home/ahmet/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/snap/bin" \
    --setenv=MUX_ZMX_BIN_DIR=/home/ahmet/projects/supermux/build/zmx/out \
    "${envargs[@]}" \
    /usr/bin/sg render -c "exec $BUN $wt/src/main.ts" >>"$LOG" 2>&1
  local code; code=$(wait_up $PORT 300)   # up to 10 min: boot resume of every active session
  if [ "$code" = "000" ]; then
    log "preview did NOT come up — AUTO-REVERTING"
    systemctl --user stop "$PREVIEW_UNIT" 2>/dev/null
    systemctl --user stop "${REVERT_UNIT}.timer" 2>/dev/null
    systemctl --user start "$SERVICE"; wait_up $PORT 300 >/dev/null
    log "SWAP_FAILED_REVERTED (journalctl --user -u $PREVIEW_UNIT)"
    return 1
  fi
  log "PREVIEW_READY: worktree broker live on :$PORT (/me -> $code), auto-reverts in $dur"
}

revert(){
  log "manual revert…"
  systemctl --user stop "${REVERT_UNIT}.timer" "${REVERT_UNIT}.service" 2>/dev/null
  systemctl --user stop "$PREVIEW_UNIT" 2>/dev/null
  systemctl --user start "$SERVICE"
  if wait_up $PORT 300 >/dev/null; then log "back on default (/me -> $(me_code $PORT))"; else log "WARNING: default not answering on :$PORT"; fi
}

status(){
  echo "default  $SERVICE      : $(systemctl --user is-active "$SERVICE" 2>/dev/null)"
  echo "preview  $PREVIEW_UNIT : $(systemctl --user is-active "$PREVIEW_UNIT" 2>/dev/null || true)"
  echo "revert   timer         : $(systemctl --user is-active "${REVERT_UNIT}.timer" 2>/dev/null || true)  $(systemctl --user list-timers --all 2>/dev/null | grep "$REVERT_UNIT" | awk '{print $1, $2, $3}')"
  echo ":$PORT /me             : $(me_code $PORT)"
}

cmd=${1:-}; shift || true
case "$cmd" in
  swap)   swap "${1:?worktree}" "${2:-2h}";;
  revert) revert;;
  status) status;;
  *) echo "usage: $0 swap <worktree> [dur] | revert | status"; exit 2;;
esac
