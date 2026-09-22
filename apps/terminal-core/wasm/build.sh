#!/usr/bin/env bash
# Build the pinned libghostty-vt WASM engine and (with --test) run the smoke
# fixture in Node and in headless Chrome.
#
#   bash apps/terminal-core/wasm/build.sh [--test]
#
# Output: apps/terminal-core/build/wasm/ghostty-vt.wasm + manifest.json.
# Only the VT engine is built; ghostty-web's renderer/DOM terminal is never
# used. The module exports its linear memory and a growable indirect function
# table (for effect callbacks); the host owns every buffer it passes in and
# frees it with ghostty_wasm_free.
#
# Environment overrides:
#   ST_NODE     node binary (default: node on PATH, else newest ~/.nvm version)
#   ST_CHROME   chrome/chromium binary (default: google-chrome / chromium on PATH)
#   ST_NO_BROWSER=1  skip the headless-browser leg (Node leg still runs)
set -euo pipefail

RUN_TEST=0
for arg in "$@"; do
  case "$arg" in
    --test) RUN_TEST=1 ;;
    *) echo "usage: build.sh [--test]" >&2; exit 2 ;;
  esac
done

TARGET=wasm32
WASM_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../native/common.sh
source "$WASM_DIR/../native/common.sh"
OUT_DIR="$BUILD_DIR/wasm"
WORK_DIR="$BUILD_DIR/work/wasm32"
ZIG_TARGET="$(lock targets.wasm32.zig_target)"
OPTIMIZE="ReleaseSmall"

ensure_zig
ensure_upstream

log "zig build libghostty-vt ($ZIG_TARGET, $OPTIMIZE, -j$ZIG_JOBS)"
rm -rf "$WORK_DIR/ghostty"
(cd "$UPSTREAM_DIR" && nice -n 15 "$ZIG" build \
    "-j$ZIG_JOBS" \
    --global-cache-dir "$ZIG_GLOBAL_CACHE" \
    --cache-dir "$BUILD_DIR/zig-cache/wasm32" \
    --prefix "$WORK_DIR/ghostty" \
    -Demit-lib-vt=true \
    "-Doptimize=$OPTIMIZE" \
    "-Dtarget=$ZIG_TARGET" \
    "-Dlib-version-string=$LIBVT_VERSION")

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"
cp "$WORK_DIR/ghostty/bin/ghostty-vt.wasm" "$OUT_DIR/ghostty-vt.wasm"

for n in "$HOME" "$PKG_DIR"; do
  if grep -aqF "$n" "$OUT_DIR/ghostty-vt.wasm"; then
    die "ghostty-vt.wasm contains absolute developer path '$n'"
  fi
done
log "no absolute developer paths in ghostty-vt.wasm"

# ---------------------------------------------------------------- test ----
find_node() {
  if [[ -n "${ST_NODE:-}" ]]; then echo "$ST_NODE"; return; fi
  if command -v node >/dev/null; then command -v node; return; fi
  local newest
  newest="$(ls -d "$HOME"/.nvm/versions/node/v*/bin/node 2>/dev/null | sort -V | tail -1)"
  [[ -n "$newest" ]] && echo "$newest"
}

find_chrome() {
  if [[ -n "${ST_CHROME:-}" ]]; then echo "$ST_CHROME"; return; fi
  local c
  for c in google-chrome google-chrome-stable chromium chromium-browser; do
    command -v "$c" >/dev/null && { command -v "$c"; return; }
  done
  [[ -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]] &&
    echo "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
}

TEST_RESULT="not-run"
RUNTIMES=()
status=0
if [[ $RUN_TEST -eq 1 ]]; then
  NODE="$(find_node)"
  [[ -n "$NODE" ]] || die "node not found (set ST_NODE)"
  node_major="$("$NODE" -p 'process.versions.node.split(".")[0]')"
  [[ "$node_major" -ge 22 ]] || die "node >= 22 required (global WebSocket/fetch); got $("$NODE" --version)"
  browser_args=()
  if [[ "${ST_NO_BROWSER:-0}" != 1 ]]; then
    CHROME="$(find_chrome)"
    [[ -n "$CHROME" ]] || die "no headless browser found (set ST_CHROME or ST_NO_BROWSER=1)"
    browser_args=(--browser "$CHROME")
    RUNTIMES+=("$("$CHROME" --version 2>/dev/null | head -1 | sed "s/ *$//")")
  fi
  RUNTIMES+=("node $("$NODE" --version)")
  log "running smoke fixture"
  if "$NODE" "$WASM_DIR/smoke.mjs" "$OUT_DIR/ghostty-vt.wasm" "${browser_args[@]}" \
       --work "$BUILD_DIR/wasm-smoke"; then
    TEST_RESULT="passed"
  else
    TEST_RESULT="failed"; status=1
  fi
fi

runtimes_json="$(printf '%s\n' "${RUNTIMES[@]+"${RUNTIMES[@]}"}" | python3 -c 'import json,sys; print(json.dumps([l for l in sys.stdin.read().splitlines() if l]))')"
python3 - "$OUT_DIR" <<PY
import hashlib, json, os, sys
out = sys.argv[1]
wasm = os.path.join(out, "ghostty-vt.wasm")
data = open(wasm, "rb").read()
manifest = {
    "schema": 1,
    "target": "wasm32",
    "zig_target": "$ZIG_TARGET",
    "abi_version": 0,
    "wrapper": False,
    "ghostty_commit": "$GHOSTTY_SHA",
    "libghostty_vt_version": "$LIBVT_VERSION",
    "zig_version": "$ZIG_VERSION",
    "optimize": "$OPTIMIZE",
    "build_host": "$HOST_KEY",
    "runtime_tested": "$TEST_RESULT" == "passed",
    "test_result": "$TEST_RESULT",
    "test_runtimes": $runtimes_json,
    "files": [{"path": "ghostty-vt.wasm", "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}],
}
json.dump(manifest, open(os.path.join(out, "manifest.json"), "w"), indent=2)
PY
log "manifest: ${OUT_DIR#"$PKG_DIR"/}/manifest.json (test: $TEST_RESULT)"
exit $status
