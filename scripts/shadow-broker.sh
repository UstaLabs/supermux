#!/usr/bin/env bash
# shadow-broker.sh — run a worktree's broker on an ALTERNATE port with a
# COPIED state dir (migrations apply to the copy only; live :9898 untouched).
#
# This is the safe counterpart to preview-broker (which swaps the live port).
# Phone/desktop clients must pair to the shadow URL (or multi-host add).
#
#   start  <worktree> [slug]   # start shadow; prints SHADOW_URL + pair hint
#   stop   [slug|all]
#   status [slug|all]
#   pair   <slug> [device-name]  # mint a device token on the shadow
#
# Layout: ~/.mux/previews/<slug>/{state,meta.env,broker.log}
# Unit:   mux-shadow-<slug> (systemd --user transient)
set -euo pipefail

_real_home() {
  local h
  for h in "${MUX_USER_HOME:-}" "$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6)" "$HOME"; do
    [[ -n "$h" && -d "$h/.mux/state" ]] && { echo "$h"; return; }
  done
  for h in /home/ahmet "$HOME"; do [[ -d "$h" ]] && { echo "$h"; return; }; done
  echo "$HOME"
}
USER_HOME="$(_real_home)"
PREVIEWS="${MUX_PREVIEWS_DIR:-$USER_HOME/.mux/previews}"
LIVE_STATE="${MUX_STATE_DIR:-$USER_HOME/.mux/state}"
BUN="$(command -v bun || echo "$USER_HOME/.local/bin/bun")"
# Prefer Tailscale IP for phone reachability; fall back to NetBird then LAN.
host_ip() {
  if ip -4 -o addr show tailscale0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1 | grep -q .; then
    ip -4 -o addr show tailscale0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1
    return
  fi
  if ip -4 -o addr show wt0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1 | grep -q .; then
    ip -4 -o addr show wt0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -1
    return
  fi
  hostname -I 2>/dev/null | awk '{print $1}'
}

log(){ echo "[shadow] $*"; }

slugify() {
  echo "$1" | tr '[:upper:]' '[:lower:]' | sed -E 's/[^a-z0-9]+/-/g; s/^-+|-+$//g; s/-+/-/g' | cut -c1-40
}

free_port() {
  local p
  for p in $(seq 9901 9999); do
    if ! ss -tln | awk '{print $4}' | grep -qE ":${p}\$"; then
      echo "$p"
      return 0
    fi
  done
  return 1
}

prepare_state() {
  local dest=$1
  local mode=${2:-fresh}   # fresh | copy-db
  mkdir -p "$dest/sockets" "$dest/inbox" "$dest/files" "$dest/cache"
  # Default = FRESH db. Copying the live sessions DB makes the shadow re-attach
  # to the same tmux/shim panes as the live broker (bad). Use copy-db only when
  # you intentionally want a data snapshot AND accept that risk.
  if [[ "$mode" == "copy-db" && -f "$LIVE_STATE/db.sqlite3" ]]; then
    log "WARNING: copying live db — shadow may contend for live session panes"
    if command -v sqlite3 >/dev/null; then
      sqlite3 "$LIVE_STATE/db.sqlite3" ".backup '$dest/db.sqlite3'" 2>/dev/null \
        || cp -a "$LIVE_STATE/db.sqlite3" "$dest/db.sqlite3"
    else
      cp -a "$LIVE_STATE/db.sqlite3" "$dest/db.sqlite3"
    fi
  else
    rm -f "$dest/db.sqlite3" "$dest/db.sqlite3-wal" "$dest/db.sqlite3-shm" 2>/dev/null || true
    # Broker runs migrations on empty file at boot → schema only, no sessions.
  fi
  # Host identity so multi-host / pair paths don't crash. Distinct host-key would
  # be safer for multi-host identity; reusing is OK for feel-test pair tokens.
  for f in host-key push-keys.json supermux-apns-key.p8 supermux-fcm-sa.json; do
    [[ -e "$LIVE_STATE/$f" ]] && cp -a "$LIVE_STATE/$f" "$dest/$f" 2>/dev/null || true
  done
  # Fresh devices — force re-pair on shadow (never reuse live tokens).
  echo '[]' > "$dest/devices.json"
  # Web-only .env: strip Telegram/WhatsApp so the shadow cannot steal channels.
  # process.env set at start overrides these placeholders.
  {
    echo "MUX_UPDATE_CHECK=0"
    echo "MUX_CURATOR_ENABLED=0"
  } > "$dest/.env"
  rm -f "$dest/broker.pid" 2>/dev/null || true
  rm -rf "$dest/sockets"/* 2>/dev/null || true
}

ensure_deps() {
  local wt=$1
  if [[ ! -d "$wt/node_modules" ]]; then
    log "bun install (root) in worktree…"
    ( cd "$wt" && "$BUN" install ) >/tmp/shadow-install.log 2>&1 \
      || { log "bun install failed → /tmp/shadow-install.log"; return 1; }
  fi
}

me_code() {
  # curl's %{http_code} is already "000" on connect failure — never append || echo 000
  # (that produced "000000" and false positives). See preview-broker.sh.
  local c
  c=$(curl -s -o /dev/null -m 3 -w "%{http_code}" "$1" 2>/dev/null)
  echo "${c:-000}"
}

start() {
  local wt=$1
  local slug=${2:-}
  local state_mode=${SHADOW_STATE_MODE:-fresh}
  [[ -d "$wt" ]] || { log "worktree not found: $wt"; return 1; }
  wt="$(cd "$wt" && pwd)"
  if [[ -z "$slug" ]]; then
    local br
    br="$(git -C "$wt" rev-parse --abbrev-ref HEAD 2>/dev/null || basename "$wt")"
    slug="$(slugify "$br")"
  else
    slug="$(slugify "$slug")"
  fi
  local dir="$PREVIEWS/$slug"
  local unit="mux-shadow-$slug"
  if systemctl --user is-active --quiet "$unit" 2>/dev/null; then
    log "already running: $unit"
    status_one "$slug"
    return 0
  fi

  ensure_deps "$wt"
  mkdir -p "$dir"
  prepare_state "$dir/state" "$state_mode"
  local port
  port="$(free_port)" || { log "no free port in 9901–9999"; return 1; }
  local ip
  ip="$(host_ip)"
  # Prefer loopback for readiness; advertise mesh IP for phones.
  local url="http://${ip}:${port}"
  local local_url="http://127.0.0.1:${port}"

  cat > "$dir/meta.env" <<EOF
SLUG=$slug
WORKTREE=$wt
PORT=$port
URL=$url
STARTED_AT=$(date -Iseconds)
UNIT=$unit
EOF

  log "starting $unit from $wt on :$port (state=$dir/state mode=$state_mode)"
  # Env vars override the shadow .env (broker only fills undefined keys).
  # Bind via public URL host IP so phone can reach; HOME=USER_HOME for agent creds.
  systemd-run --user --unit="$unit" --collect \
    --setenv=NODE_ENV=production \
    --setenv=PATH="$USER_HOME/.local/bin:/usr/local/bin:/usr/bin:/bin" \
    --setenv=HOME="$USER_HOME" \
    --setenv=MUX_STATE_DIR="$dir/state" \
    --setenv=MUX_WEB_PORT="$port" \
    --setenv=MUX_WEB_PUBLIC_URL="$url" \
    --setenv=MUX_UPDATE_CHECK=0 \
    --setenv=MUX_CURATOR_ENABLED=0 \
    -p WorkingDirectory="$wt" \
    -p StandardOutput=append:"$dir/broker.log" \
    -p StandardError=append:"$dir/broker.log" \
    "$BUN" "$wt/src/main.ts" >/dev/null 2>&1

  local code=000 i
  for i in $(seq 1 60); do
    code="$(me_code "$local_url/me")"
    [[ "$code" != "000" ]] && break
    sleep 0.5
  done
  if [[ "$code" == "000" ]]; then
    log "shadow failed to answer /me — see $dir/broker.log"
    tail -30 "$dir/broker.log" 2>/dev/null || true
    systemctl --user stop "$unit" 2>/dev/null || true
    return 1
  fi
  log "SHADOW_READY  slug=$slug  url=$url  /me=$code"
  log "live broker on :9898 is UNTOUCHED"
  log "pair a device:  scripts/shadow-broker.sh pair $slug phone-feel"
  echo "SHADOW_URL=$url"
  echo "SHADOW_SLUG=$slug"
  echo "SHADOW_PORT=$port"
}

stop() {
  local target=${1:-all}
  if [[ "$target" == "all" ]]; then
    for d in "$PREVIEWS"/*/meta.env; do
      [[ -f "$d" ]] || continue
      # shellcheck disable=SC1090
      source "$d"
      systemctl --user stop "$UNIT" 2>/dev/null || true
      log "stopped $SLUG"
    done
    return 0
  fi
  local slug
  slug="$(slugify "$target")"
  local unit="mux-shadow-$slug"
  systemctl --user stop "$unit" 2>/dev/null || true
  log "stopped $slug"
}

status_one() {
  local slug=$1
  local dir="$PREVIEWS/$slug"
  if [[ ! -f "$dir/meta.env" ]]; then
    echo "$slug: no meta"
    return
  fi
  # shellcheck disable=SC1090
  source "$dir/meta.env"
  local active
  active="$(systemctl --user is-active "$UNIT" 2>/dev/null || echo inactive)"
  local code
  code="$(me_code "http://127.0.0.1:${PORT}/me")"
  echo "$slug  unit=$active  url=$URL  /me=$code  worktree=$WORKTREE"
}

status() {
  local target=${1:-all}
  if [[ "$target" != "all" ]]; then
    status_one "$(slugify "$target")"
    return
  fi
  local any=0
  for d in "$PREVIEWS"/*/meta.env; do
    [[ -f "$d" ]] || continue
    any=1
    status_one "$(basename "$(dirname "$d")")"
  done
  [[ $any -eq 0 ]] && echo "(no shadow brokers)"
}

pair() {
  local slug
  slug="$(slugify "${1:?slug required}")"
  local name=${2:-feel-test}
  local dir="$PREVIEWS/$slug"
  [[ -f "$dir/meta.env" ]] || { log "unknown slug $slug"; return 1; }
  # shellcheck disable=SC1090
  source "$dir/meta.env"
  # Mint via a tiny bun one-liner against the shadow DeviceStore file.
  local token
  token="$(
    MUX_STATE_DIR="$dir/state" "$BUN" -e '
      import { DeviceStore } from "./src/channels/web/device-store.ts";
      const s = new DeviceStore(process.env.MUX_STATE_DIR + "/devices.json");
      const r = s.mint(process.argv[1] || "feel-test");
      console.log(r.token);
    ' -- "$name" 2>/dev/null
  )"
  # DeviceStore path is absolute; run from worktree for import
  if [[ -z "$token" ]]; then
    token="$(
      cd "$WORKTREE" && MUX_STATE_DIR="$dir/state" "$BUN" -e '
        import { DeviceStore } from "./src/channels/web/device-store.ts";
        const s = new DeviceStore(`${process.env.MUX_STATE_DIR}/devices.json`);
        const r = s.mint(Bun.argv[2] || "feel-test");
        console.log(r.token);
      ' -- "$name"
    )"
  fi
  [[ -n "$token" ]] || { log "mint failed"; return 1; }
  local pair_url="${URL}/pair?t=${token}"
  echo "PAIR_URL=$pair_url"
  echo "TOKEN=$token"
  echo "BASE=$URL"
  log "pair URL (open in app or paste): $pair_url"
}

cmd=${1:-}; shift || true
case "$cmd" in
  start)  start "${1:?worktree}" "${2:-}";;
  stop)   stop "${1:-all}";;
  status) status "${1:-all}";;
  pair)   pair "${1:?slug}" "${2:-feel-test}";;
  *)
    echo "usage: $0 {start|stop|status|pair} …" >&2
    exit 2
    ;;
esac
