#!/usr/bin/env python3
"""make-lock.py <inventory.tsv> [<alternates.tsv>] > grammars.lock.json

Resolves every language of the M0 inventory to an npm tarball, downloads it, and records its sha256 plus the
directories containing src/parser.c. The committed lock is the ONLY place versions are chosen; fetch.sh refuses
a tarball whose sha256 differs. Re-run to bump a grammar, then review the diff."""
import hashlib, io, json, sys, tarfile, urllib.request

CORE = {"javascript", "typescript", "python", "kotlin", "swift", "go", "rust", "java", "c", "cpp", "json",
        "yaml", "toml", "markdown", "html", "css", "shell", "sql"}
SKIP = {"MISSING"}

def rows(path):
    with open(path) as f:
        next(f)
        for line in f:
            c = line.rstrip("\n").split("\t")
            if len(c) >= 3 and c[2] not in SKIP and c[2]:
                yield c[0], c[1], c[2], (c[3] if len(c) > 3 else "")

def main():
    seen, out = set(), []
    extra = [("toml", "@tree-sitter-grammars/tree-sitter-toml", "0.7.0", "MIT")]
    srcs = list(rows(sys.argv[1])) + (list(rows(sys.argv[2])) if len(sys.argv) > 2 else []) + extra
    for lang, pkg, ver, lic in srcs:
        if lang in seen:
            continue
        base = pkg.split("/")[-1]
        url = "https://registry.npmjs.org/%s/-/%s-%s.tgz" % (pkg, base, ver)
        data = urllib.request.urlopen(url).read()
        sha = hashlib.sha256(data).hexdigest()
        with tarfile.open(fileobj=io.BytesIO(data)) as t:
            dirs = sorted({n[len("package/"):-len("/parser.c")] for n in t.getnames()
                           if n.endswith("/src/parser.c") and "/node_modules/" not in n})
        if not dirs:
            continue
        seen.add(lang)
        out.append({"lang": lang, "package": pkg, "version": ver, "url": url, "sha256": sha, "license": lic,
                    "parserDirs": dirs, "tables": "bundled" if lang in CORE else "code"})
    json.dump({"format": 1, "grammars": out}, sys.stdout, indent=1)
    print()

main()
