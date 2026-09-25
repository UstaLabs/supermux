#!/usr/bin/env bash
# fetch.sh: tree-sitter at the locked commit, zlib and every grammar tarball at its locked sha256, into build/.
# Idempotent; refuses a tarball whose sha256 differs from native/upstream.lock.json / native/grammars.lock.json.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"          # apps/editor-syntax
B="$HERE/build"; mkdir -p "$B/dl" "$B/grammars"
TS_COMMIT=$(python3 -c "import json;print(json.load(open('$HERE/native/upstream.lock.json'))['tree-sitter']['commit'])")
if [ ! -d "$B/tree-sitter/.git" ] || [ "$(git -C "$B/tree-sitter" rev-parse HEAD)" != "$TS_COMMIT" ]; then
  rm -rf "$B/tree-sitter"
  git clone -q --filter=blob:none https://github.com/tree-sitter/tree-sitter "$B/tree-sitter"
  git -C "$B/tree-sitter" checkout -q "$TS_COMMIT"
fi
python3 - "$HERE" <<'PY'
import hashlib, json, os, subprocess, sys, tarfile, urllib.request
here = sys.argv[1]; b = os.path.join(here, "build")
z = json.load(open(os.path.join(here, "native/upstream.lock.json")))["zlib"]
tgz = os.path.join(b, "dl", "zlib-%s.tar.gz" % z["version"])
if not os.path.exists(tgz):
    urllib.request.urlretrieve(z["url"], tgz)
sha = hashlib.sha256(open(tgz, "rb").read()).hexdigest()
if sha != z["sha256"]:
    sys.exit("sha256 mismatch for zlib: %s != %s" % (sha, z["sha256"]))
if not os.path.isdir(os.path.join(b, "zlib")):
    with tarfile.open(tgz) as t:
        for m in t.getmembers():
            m.name = m.name.split("/", 1)[1] if "/" in m.name else ""
            if m.name:
                t.extract(m, os.path.join(b, "zlib"))
for g in json.load(open(os.path.join(here, "native/grammars.lock.json")))["grammars"]:
    if g["tables"] == "excluded":
        continue
    tgz = os.path.join(b, "dl", "%s-%s.tgz" % (g["lang"], g["version"]))
    if not os.path.exists(tgz):
        urllib.request.urlretrieve(g["url"], tgz)
    sha = hashlib.sha256(open(tgz, "rb").read()).hexdigest()
    if sha != g["sha256"]:
        sys.exit("sha256 mismatch for %s: %s != %s" % (g["lang"], sha, g["sha256"]))
    dest = os.path.join(b, "grammars", g["lang"])
    if not os.path.isdir(dest):
        tmp = dest + ".tmp"
        subprocess.run(["rm", "-rf", tmp], check=True)
        os.makedirs(tmp)
        with tarfile.open(tgz) as t:
            for m in t.getmembers():
                m.name = m.name.split("/", 1)[1] if "/" in m.name else ""
                if m.name:
                    t.extract(m, tmp)
        # "regenerate": the published parser.c is for an ABI this runtime refuses; rebuild it from the
        # package's own grammar.json with the pinned tree-sitter CLI (the difftest then checks the result).
        r = g.get("regenerate")
        if r:
            for d in g["parserDirs"]:
                subprocess.run(["npx", "-y", r["cli"], "generate", "--abi", str(r["abi"]), "src/grammar.json"],
                               cwd=os.path.dirname(os.path.join(tmp, d)), check=True)
        os.rename(tmp, dest)
print("fetched")
PY
