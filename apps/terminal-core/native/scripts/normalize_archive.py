#!/usr/bin/env python3
"""Rewrite a static archive so it carries no build-machine paths.

Upstream's `zig ar -M` combine step stores every member under the absolute
path of its object in the Zig cache, and the objects keep DWARF with absolute
source/cache paths. This tool:

  1. extracts the members in order under stable names `NNNN_<basename>`,
  2. strips debug sections from each object with llvm-objcopy (symbols stay),
  3. re-archives them deterministically with `zig ar` in the target's format.

usage: normalize_archive.py <zig> <llvm-objcopy> <gnu|darwin|coff> <in.a> <out.a> <workdir>
"""
import os
import shutil
import subprocess
import sys


def members(data):
    if not data.startswith(b"!<arch>\n"):
        raise SystemExit("not an ar archive")
    pos = 8
    longnames = b""
    while pos + 60 <= len(data):
        hdr = data[pos:pos + 60]
        name = hdr[0:16].decode("ascii", "replace").rstrip()
        size = int(hdr[48:58].decode().strip())
        body = data[pos + 60:pos + 60 + size]
        pos += 60 + size + (size & 1)
        if name in ("/", "/SYM64/", "//") or name.startswith("__.SYMDEF"):
            if name == "//":
                longnames = body
            continue
        if name.startswith("#1/"):  # BSD: name stored at start of body
            n = int(name[3:])
            real = body[:n].rstrip(b"\0").decode()
            body = body[n:]
        elif name.startswith("/") and name[1:].isdigit():  # GNU long name
            off = int(name[1:])
            # GNU terminates long names with "/\n", the COFF flavour with NUL.
            ends = [i for i in (longnames.find(b"\n", off), longnames.find(b"\0", off)) if i >= 0]
            end = min(ends) if ends else len(longnames)
            real = longnames[off:end].decode().rstrip("/")
        else:
            real = name.rstrip("/")
        if real.startswith("__.SYMDEF"):  # BSD/Darwin symbol table
            continue
        yield os.path.basename(real.replace("\\", "/").rstrip("/")) or "member.o", body


def main():
    zig, objcopy, fmt, src, dst, work = sys.argv[1:7]
    shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work)
    names = []
    for i, (base, body) in enumerate(members(open(src, "rb").read())):
        name = f"{i:04d}_{base}"
        path = os.path.join(work, name)
        with open(path, "wb") as f:
            f.write(body)
        subprocess.run([objcopy, "--strip-debug", path], check=True)
        names.append(name)
    if os.path.exists(dst):
        os.remove(dst)
    fmt_arg = {"gnu": "gnu", "darwin": "darwin", "coff": "coff"}[fmt]
    subprocess.run([zig, "ar", "rcsD", f"--format={fmt_arg}", os.path.abspath(dst), *names],
                   cwd=work, check=True)
    shutil.rmtree(work)
    print(f"normalized {len(names)} members -> {os.path.basename(dst)}", file=sys.stderr)


if __name__ == "__main__":
    main()
