#!/usr/bin/env bash
# native/wasm/build.sh [--test]: the web client's syntax engine, the SAME ses_* binding as every
# native target (tree-sitter + the bridge + the tables loader + zlib + every grammar's code, and
# the bundled grammars' tables) compiled by zig to ONE wasm32-wasi reactor module:
#   build/natives/wasm32/lib/supermux-syntax.wasm   (listed in build/natives/manifest.json, with its
#                                                     gzip size and its imports)
# The wasm-only glue is native/src/syntax_wasm.c (callback trampolines, a scratch buffer). With
# --test, Node runs native/wasm/test.mjs against the module through the browser loader
# (src/wasmJsMain/resources/syntax-loader.mjs). Prerequisites: native/fetch.sh, native/build.sh gen,
# zig 0.16 and node >= 22 on PATH. Runs on the Mac, like every other target.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/../.." && pwd)"
TEST=0
for a in "$@"; do case "$a" in --test) TEST=1 ;; *) echo "usage: build.sh [--test]" >&2; exit 2 ;; esac; done

"$HERE/native/build.sh" wasm32
W="$HERE/build/natives/wasm32/lib/supermux-syntax.wasm"
for n in "$HOME" "$HERE"; do
  if grep -aqF "$n" "$W"; then echo "supermux-syntax.wasm contains the absolute path $n" >&2; exit 1; fi
done
echo "imports:"; python3 "$HERE/native/wasm/wasm-info.py" imports "$W" | sed 's/^/  /'
python3 - "$HERE/build/natives/manifest.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
w = [l for l in m["libraries"] if l["target"] == "wasm32"][0]
print("supermux-syntax.wasm: %d bytes, %d gzipped" % (w["size"], w["gzip_size"]))
PY
if [ "$TEST" = 1 ]; then
  node "$HERE/native/wasm/test.mjs" "$W" "$HERE/build/gen"
fi
