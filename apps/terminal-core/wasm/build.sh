#!/usr/bin/env bash
# Build the pinned libghostty-vt WASM engine and (with --test) run the smoke
# fixture and the loader test (terminal-loader.mjs) in Node and in headless Chrome.
#
#   bash apps/terminal-core/wasm/build.sh [--test]
#
# Output: apps/terminal-core/build/wasm/supermux-terminal.wasm (libghostty-vt
# + the st_* wrapper, native/src/terminal_bridge.c, linked into one module
# exporting st_* and, for now, ghostty_*), the raw upstream ghostty-vt.wasm,
# and manifest.json. Only the VT engine is built; ghostty-web's renderer/DOM
# terminal is never used. The module exports its linear memory and a growable
# indirect function table (for effect callbacks); the host owns every buffer
# it passes in and frees it with ghostty_wasm_free (st_* output buffers with
# st_free_buffer). The smoke fixture runs against supermux-terminal.wasm.
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

# st_* wrapper: the same terminal_bridge.c as every native target, compiled
# freestanding (it uses no libc) and linked with the wasm libghostty-vt.a.
NATIVE_DIR="$WASM_DIR/../native"
ST_ABI_VERSION="$(sed -n 's/^#define ST_ABI_VERSION \([0-9]*\)u$/\1/p' "$NATIVE_DIR/include/supermux_terminal.h")"
export ZIG_GLOBAL_CACHE_DIR="$ZIG_GLOBAL_CACHE" ZIG_LOCAL_CACHE_DIR="$BUILD_DIR/zig-cache/wasm32"
log "compile st_* wrapper for $ZIG_TARGET (ABI $ST_ABI_VERSION)"
"$ZIG" cc -target "$ZIG_TARGET" -mcpu=generic+simd128 -std=c11 -Os -g0 -Wall -Wextra -Werror \
  -fvisibility=hidden -DGHOSTTY_STATIC "-ffile-prefix-map=$PKG_DIR/=" \
  -I "$NATIVE_DIR/include" -I "$UPSTREAM_DIR/include" \
  -c "$NATIVE_DIR/src/terminal_bridge.c" -o "$WORK_DIR/terminal_bridge.o"
log "link supermux-terminal.wasm (st_* + ghostty_* exports, growable table)"
"$ZIG" cc -target "$ZIG_TARGET" -mcpu=generic+simd128 -nostdlib \
  "$WORK_DIR/terminal_bridge.o" "$WORK_DIR/ghostty/lib/libghostty-vt.a" \
  -Wl,--no-entry -Wl,--export-dynamic -Wl,--export-table -Wl,-z,stack-size=131072 -Wl,--strip-all \
  -o "$WORK_DIR/supermux-terminal.unpatched.wasm"
python3 "$WASM_DIR/patch_growable_table.py" "$WORK_DIR/supermux-terminal.unpatched.wasm" \
  "$OUT_DIR/supermux-terminal.wasm"

for f in ghostty-vt.wasm supermux-terminal.wasm; do
  for n in "$HOME" "$PKG_DIR"; do
    if grep -aqF "$n" "$OUT_DIR/$f"; then
      die "$f contains absolute developer path '$n'"
    fi
  done
done
log "no absolute developer paths in the wasm modules"

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
  if "$NODE" "$WASM_DIR/smoke.mjs" "$OUT_DIR/supermux-terminal.wasm" "${browser_args[@]}" \
       --work "$BUILD_DIR/wasm-smoke"; then
    TEST_RESULT="passed"
  else
    TEST_RESULT="failed"; status=1
  fi
  # The browser loader (terminal-loader.mjs) over real HTTP: typed load failures, compile cache,
  # the initialize() singleton, two handles across memory growth, two instances.
  log "running loader test"
  if ! "$NODE" "$WASM_DIR/loader-test.mjs" "$OUT_DIR/supermux-terminal.wasm" "${browser_args[@]}" \
       --work "$BUILD_DIR/wasm-loader-test"; then
    TEST_RESULT="failed"; status=1
  fi
fi

runtimes_json="$(printf '%s\n' "${RUNTIMES[@]+"${RUNTIMES[@]}"}" | python3 -c 'import json,sys; print(json.dumps([l for l in sys.stdin.read().splitlines() if l]))')"
python3 - "$OUT_DIR" <<PY
import hashlib, json, os, sys
out = sys.argv[1]
def entry(name):
    data = open(os.path.join(out, name), "rb").read()
    return {"path": name, "sha256": hashlib.sha256(data).hexdigest(), "size": len(data)}
manifest = {
    "schema": 1,
    "target": "wasm32",
    "zig_target": "$ZIG_TARGET",
    "abi_version": $ST_ABI_VERSION,
    "wrapper": True,
    "ghostty_commit": "$GHOSTTY_SHA",
    "libghostty_vt_version": "$LIBVT_VERSION",
    "zig_version": "$ZIG_VERSION",
    "optimize": "$OPTIMIZE",
    "build_host": "$HOST_KEY",
    "runtime_tested": "$TEST_RESULT" == "passed",
    "test_result": "$TEST_RESULT",
    "test_runtimes": $runtimes_json,
    "files": [entry("supermux-terminal.wasm"), entry("ghostty-vt.wasm")],
}
json.dump(manifest, open(os.path.join(out, "manifest.json"), "w"), indent=2)
PY
log "manifest: ${OUT_DIR#"$PKG_DIR"/}/manifest.json (test: $TEST_RESULT)"
exit $status
