#!/usr/bin/env bash
# For every language today's cm6 bundle highlights: which npm grammar package exists, its version and
# licence, whether it ships parser.c / scanner.c / queries/highlights.scm / a prebuilt .wasm, and the
# compiled size (-Os, ios arm64 object) + wasm size. Output: TSV on stdout.
set -uo pipefail
TMP="$(mktemp -d)"; cd "$TMP"
LANGS="${LANGS:-javascript:tree-sitter-javascript typescript:tree-sitter-typescript python:tree-sitter-python
rust:tree-sitter-rust go:tree-sitter-go java:tree-sitter-java c:tree-sitter-c cpp:tree-sitter-cpp
php:tree-sitter-php html:tree-sitter-html css:tree-sitter-css json:tree-sitter-json
yaml:@tree-sitter-grammars/tree-sitter-yaml markdown:@tree-sitter-grammars/tree-sitter-markdown
sql:@derekstride/tree-sitter-sql xml:@tree-sitter-grammars/tree-sitter-xml vue:tree-sitter-vue
wast:tree-sitter-wast shell:tree-sitter-bash kotlin:@tree-sitter-grammars/tree-sitter-kotlin
dart:tree-sitter-dart csharp:tree-sitter-c-sharp scala:tree-sitter-scala objc:tree-sitter-objc
ruby:tree-sitter-ruby swift:tree-sitter-swift groovy:tree-sitter-groovy lua:@tree-sitter-grammars/tree-sitter-lua
perl:tree-sitter-perl r:tree-sitter-r julia:tree-sitter-julia haskell:tree-sitter-haskell
erlang:tree-sitter-erlang ocaml:tree-sitter-ocaml fsharp:tree-sitter-fsharp clojure:tree-sitter-clojure
elm:@elm-tooling/tree-sitter-elm crystal:tree-sitter-crystal coffeescript:tree-sitter-coffeescript
fortran:tree-sitter-fortran pascal:tree-sitter-pascal vb:tree-sitter-vb haxe:tree-sitter-haxe
cmake:tree-sitter-cmake dockerfile:tree-sitter-dockerfile nginx:tree-sitter-nginx glsl:tree-sitter-glsl}"
printf "lang\tpackage\tversion\tlicense\tparser\tscanner\thighlights\twasm\tios_obj_bytes\twasm_bytes\n"
for pair in $LANGS; do
  L="${pair%%:*}"; P="${pair#*:}"
  META="$(npm view "$P" version license --json 2>/dev/null)" || { printf "%s\t%s\tMISSING\n" "$L" "$P"; continue; }
  # M0: a name npm holds as a security placeholder (0.0.1-security, no licence) prints a bare string.
  echo "$META" | python3 -c 'import json,sys;sys.exit(0 if isinstance(json.load(sys.stdin),dict) else 1)' ||
    { printf "%s\t%s\tMISSING\t(npm placeholder: %s)\n" "$L" "$P" "$(echo "$META" | tr -d '"\n ')"; continue; }
  V="$(echo "$META" | python3 -c 'import json,sys;print(json.load(sys.stdin)["version"])')"
  LIC="$(echo "$META" | python3 -c 'import json,sys;print(json.load(sys.stdin).get("license"))')"
  mkdir -p "$L" && (cd "$L" && npm pack "$P@$V" --silent >/dev/null 2>&1 && tar xzf ./*.tgz)
  D="$L/package"; S="$(find "$D" -path '*/src/parser.c' | head -1)"; SD="$(dirname "${S:-x}")"
  HAS_SC=$([ -f "$SD/scanner.c" ] && echo y || echo n)
  HAS_HL=$(find "$D" -name highlights.scm | grep -q . && echo y || echo n)
  W="$(find "$D" -maxdepth 2 -name '*.wasm' | head -1)"
  OBJ=0
  if [ -n "$S" ]; then
    xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -Os -I"$SD" -c "$S" -o p.o 2>/dev/null && OBJ=$(stat -f%z p.o)
    [ "$HAS_SC" = y ] && xcrun --sdk iphoneos clang -target arm64-apple-ios15.0 -Os -I"$SD" -c "$SD/scanner.c" -o s.o 2>/dev/null && OBJ=$((OBJ + $(stat -f%z s.o)))
  fi
  printf "%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n" "$L" "$P" "$V" "$LIC" "$([ -n "$S" ] && echo y || echo n)" "$HAS_SC" "$HAS_HL" "$([ -n "$W" ] && echo y || echo n)" "$OBJ" "$([ -n "$W" ] && stat -f%z "$W" || echo 0)"
done
rm -rf "$TMP"
