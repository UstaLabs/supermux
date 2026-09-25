#!/usr/bin/env bash
# build-grammar.sh <package> <grammar src dir> : table extraction + differential test for ONE grammar (host, macOS).
# Writes build/gen/<lang>/{orig_,parser_,blob_}<lang>.c, <lang>.sesz, <lang>.tables.json, difftest log.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$1"; SRC="$(cd "$2" && pwd)"
TS="$HERE/build/tree-sitter/lib"
W="$HERE/build/gen/tmp.$$"; mkdir -p "$W"
LANG_NAME=$(python3 "$HERE/tools/sestables.py" prepare "$SRC/parser.c" "$W")
OUT="$HERE/build/gen/$LANG_NAME"; rm -rf "$OUT"; mv "$W" "$OUT"; W="$OUT"
CC=(clang -w -std=gnu11 -I"$SRC")
SCAN=(); CXX_LINK=()
if [ -f "$SRC/scanner.c" ]; then "${CC[@]}" -O1 -c "$SRC/scanner.c" -o "$W/scanner.o"; SCAN=("$W/scanner.o");
elif [ -f "$SRC/scanner.cc" ]; then clang++ -w -std=c++14 -I"$SRC" -O1 -c "$SRC/scanner.cc" -o "$W/scanner.o"; SCAN=("$W/scanner.o"); CXX_LINK=(-lc++); fi
"${CC[@]}" -O0 -c "$W/orig_$LANG_NAME.c" -o "$W/orig.o"
clang "$W/dump_$LANG_NAME.c" "$W/orig.o" ${SCAN[@]+"${SCAN[@]}"} ${CXX_LINK[@]+"${CXX_LINK[@]}"} -o "$W/dump"
"$W/dump" "$W/$LANG_NAME.dump"
python3 "$HERE/tools/sestables.py" emit "$W" "$LANG_NAME"
"${CC[@]}" -O1 -I"$HERE/native/include" -c "$W/parser_$LANG_NAME.c" -o "$W/xform.o"
python3 "$HERE/tools/gen-registry.py" "$W/registry.c" "$LANG_NAME"
[ -f "$HERE/build/gen/libts.o" ] || clang -O2 -w -std=gnu11 -I"$TS/include" -I"$TS/src" -c "$TS/src/lib.c" -o "$HERE/build/gen/libts.o"
clang -O1 -w -std=gnu11 -DLANG="$LANG_NAME" -I"$TS/include" -I"$TS/src" -I"$HERE/native/include" \
  "$HERE/native/tests/difftest.c" "$HERE/native/src/syntax_bridge.c" "$HERE/native/src/ses_grammar.c" "$W/registry.c" \
  "$HERE/build/gen/libts.o" "$W/orig.o" "$W/xform.o" ${SCAN[@]+"${SCAN[@]}"} ${CXX_LINK[@]+"${CXX_LINK[@]}"} -lz -o "$W/difftest"
# Inputs: the package's own test corpus (test/corpus or corpus) and examples when the npm tarball ships them (most do not),
# plus the M0 golden sample (native/tests/inputs), so every grammar sees at least one input.
G="$HERE/build/grammars/$PKG"
INP="$W/inputs"; python3 "$HERE/tools/make-inputs.py" "$INP" $(ls -d "$G/test/corpus" "$G/corpus" "$G/examples" 2>/dev/null) \
  "$HERE/native/tests/inputs" > /dev/null
[ -n "$(ls -A "$INP")" ] || { echo "no difftest inputs for $LANG_NAME" >&2; exit 1; }
"$W/difftest" "$W/$LANG_NAME.sesz" "$INP"/* | tee "$W/difftest.log"
rm -f "$W/orig.o" "$W/$LANG_NAME.dump" "$W/dump"
