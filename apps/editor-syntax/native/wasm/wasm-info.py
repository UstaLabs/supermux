#!/usr/bin/env python3
"""wasm-info.py exports|imports <module.wasm>: one name per line (imports as module.name).

A minimal reader of a wasm binary's import and export sections, so the build needs no wabt /
wasm-tools: build.sh checks the export list, and the manifest and README record the imports.
"""
import sys


def leb(b, i):
    r = s = 0
    while True:
        x = b[i]
        i += 1
        r |= (x & 0x7F) << s
        s += 7
        if x < 0x80:
            return r, i


def name(b, i):
    n, i = leb(b, i)
    return b[i:i + n].decode(), i + n


def sections(b):
    if b[:4] != b"\0asm":
        sys.exit("not a wasm module")
    i = 8
    while i < len(b):
        sid = b[i]
        n, i = leb(b, i + 1)
        yield sid, b[i:i + n]
        i += n


def imports(b):
    for sid, s in sections(b):
        if sid != 2:
            continue
        count, i = leb(s, 0)
        for _ in range(count):
            mod, i = name(s, i)
            nm, i = name(s, i)
            kind = s[i]
            i += 1
            if kind == 0:  # func: type index
                _, i = leb(s, i)
            elif kind == 1:  # table: reftype + limits
                i += 1
                flag = s[i]; i += 1
                _, i = leb(s, i)
                if flag & 1:
                    _, i = leb(s, i)
            elif kind == 2:  # memory: limits
                flag = s[i]; i += 1
                _, i = leb(s, i)
                if flag & 1:
                    _, i = leb(s, i)
            elif kind == 3:  # global: valtype + mut
                i += 2
            else:
                sys.exit("unknown import kind %d" % kind)
            yield "%s.%s" % (mod, nm)


def exports(b):
    for sid, s in sections(b):
        if sid != 7:
            continue
        count, i = leb(s, 0)
        for _ in range(count):
            nm, i = name(s, i)
            i += 1  # kind
            _, i = leb(s, i)
            yield nm


if __name__ == "__main__":
    what, path = sys.argv[1], sys.argv[2]
    data = open(path, "rb").read()
    for n in (imports if what == "imports" else exports)(data):
        print(n)
