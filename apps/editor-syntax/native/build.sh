#!/usr/bin/env bash
# build.sh gen            : transform + differential-test every locked grammar  -> build/gen/<lang>/
#          build.sh <target> : one native library                               -> build/<target>/lib/
#          build.sh all      : gen + every target this host can build
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
gen() {
  python3 - "$HERE" <<'PY' | while IFS=$'\t' read -r lang dir; do
import json, sys, os
here = sys.argv[1]
for g in json.load(open(os.path.join(here, "native/grammars.lock.json")))["grammars"]:
    if g["tables"] != "excluded":
        for d in g["parserDirs"]:
            print("%s\t%s" % (g["lang"], os.path.join(here, "build/grammars", g["lang"], d)))
PY
    "$HERE/tools/build-grammar.sh" "$lang" "$dir" || { echo "GEN FAILED: $lang $dir" >&2; exit 1; }
  done
}
case "${1:-}" in
  gen) gen ;;
  *) echo "usage: build.sh gen|<target>|all" >&2; exit 2 ;;
esac
