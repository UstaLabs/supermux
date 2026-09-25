#!/usr/bin/env bash
# fetch.sh: tree-sitter at the locked commit, zlib and every grammar tarball at its locked sha256, into build/.
# Idempotent; refuses a tarball whose sha256 differs from native/upstream.lock.json / native/grammars.lock.json.
# Each extracted directory carries a .sha256 stamp; a lock bump re-extracts it.
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
import hashlib, json, os, shutil, subprocess, sys, tarfile, urllib.request
here = sys.argv[1]; b = os.path.join(here, "build")

def sha256(path):
    return hashlib.sha256(open(path, "rb").read()).hexdigest()

def download(url, path, want, what):
    if not os.path.exists(path):
        urllib.request.urlretrieve(url, path)
    got = sha256(path)
    if got != want:
        sys.exit("sha256 mismatch for %s: %s != %s" % (what, got, want))

def extract(tgz, dest):
    """The tarball without its top directory, into a fresh dest.tmp. Never writes outside it:
    tarfile's "data" filter where Python has it (3.12, backports), else explicit checks (3.9)."""
    tmp = dest + ".tmp"
    shutil.rmtree(tmp, ignore_errors=True)
    os.makedirs(tmp)
    with tarfile.open(tgz) as t:
        members = []
        for m in t.getmembers():
            m.name = m.name.split("/", 1)[1] if "/" in m.name else ""
            if not m.name:
                continue
            if not hasattr(tarfile, "data_filter"):
                parts = m.name.split("/")
                if m.name.startswith("/") or ".." in parts or not (m.isfile() or m.isdir()):
                    sys.exit("%s: refusing member %r (absolute, .. or not a plain file)" % (tgz, m.name))
            members.append(m)
        if hasattr(tarfile, "data_filter"):
            t.extractall(tmp, members=members, filter="data")
        else:
            t.extractall(tmp, members=members)
    return tmp

def install(tmp, dest, stamp):
    """Replace dest with tmp, stamped: a later run re-extracts whenever the stamp differs."""
    open(os.path.join(tmp, ".sha256"), "w").write(stamp + "\n")
    shutil.rmtree(dest, ignore_errors=True)
    os.rename(tmp, dest)

def stamped(dest, stamp):
    try:
        return open(os.path.join(dest, ".sha256")).read().strip() == stamp
    except OSError:
        return False

z = json.load(open(os.path.join(here, "native/upstream.lock.json")))["zlib"]
tgz = os.path.join(b, "dl", "zlib-%s.tar.gz" % z["version"])
download(z["url"], tgz, z["sha256"], "zlib")
dest = os.path.join(b, "zlib")
if not stamped(dest, z["sha256"]):
    install(extract(tgz, dest), dest, z["sha256"])

for g in json.load(open(os.path.join(here, "native/grammars.lock.json")))["grammars"]:
    if g["tables"] == "excluded":
        continue
    tgz = os.path.join(b, "dl", "%s-%s.tgz" % (g["lang"], g["version"]))
    download(g["url"], tgz, g["sha256"], g["lang"])
    r = g.get("regenerate")
    stamp = g["sha256"] + ("" if not r else " regenerate %s --abi %d" % (r["cli"], r["abi"]))
    dest = os.path.join(b, "grammars", g["lang"])
    if stamped(dest, stamp):
        continue
    tmp = extract(tgz, dest)
    # "regenerate": the published parser.c is for an ABI this runtime refuses. Rebuild it from the
    # package's own grammar.json with the pinned tree-sitter CLI (npx downloads that exact version
    # from npm), then insist on the parser.c sha256 recorded in the lock: a different CLI build or
    # grammar.json fails here, loudly, before the difftest even sees it.
    if r:
        for d in g["parserDirs"]:
            subprocess.run(["npx", "-y", r["cli"], "generate", "--abi", str(r["abi"]), "src/grammar.json"],
                           cwd=os.path.dirname(os.path.join(tmp, d)), check=True)
            got, want = sha256(os.path.join(tmp, d, "parser.c")), r.get("parserSha256", {}).get(d)
            if got != want:
                sys.exit("%s: regenerated %s/parser.c has sha256 %s, the lock says %s" % (g["lang"], d, got, want))
    install(tmp, dest, stamp)
print("fetched")
PY
