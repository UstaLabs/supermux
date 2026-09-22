#!/usr/bin/env python3
"""Build one static archive from an (already normalized) libghostty-vt
archive plus the supermux wrapper objects, so consumers (cinterop, JNI
builds, tests) link a single self-contained library.

Members keep the normalized `NNNN_<object>` names of the input archive; the
wrapper objects follow as `wNN_<object>`. No build-machine paths are stored.

usage: combine_archive.py <zig> <gnu|darwin|coff> <ghostty.a> <out.a> <workdir> <obj>...
"""
import os
import shutil
import subprocess
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from normalize_archive import members  # noqa: E402


def main():
    zig, fmt, src, dst, work, *objs = sys.argv[1:]
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    names = []
    for i, (base, body) in enumerate(members(open(src, "rb").read())):
        name = base if base[:4].isdigit() and base[4:5] == "_" else f"{i:04d}_{base}"
        with open(os.path.join(work, name), "wb") as f:
            f.write(body)
        names.append(name)
    for i, obj in enumerate(objs):
        name = f"w{i:02d}_{os.path.basename(obj)}"
        shutil.copyfile(obj, os.path.join(work, name))
        names.append(name)
    if os.path.exists(dst):
        os.remove(dst)
    subprocess.run([zig, "ar", "rcsD", f"--format={fmt}", os.path.abspath(dst), *names], cwd=work, check=True)
    shutil.rmtree(work)
    print(f"combined {len(names)} members -> {os.path.basename(dst)}", file=sys.stderr)


if __name__ == "__main__":
    main()
