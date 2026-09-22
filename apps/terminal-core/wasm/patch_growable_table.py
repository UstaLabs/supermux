#!/usr/bin/env python3
"""Make a wasm module's function table growable (drop its maximum).

Zig's wasm linker (like upstream's ghostty-vt.wasm build, which uses
src/build/wasm_patch_growable_table.zig for the same job) emits the table
with max == min, so JS cannot `table.grow()` to install effect callbacks.
This rewrites every table limit in the table section (id 4) from
"flag 0x01 min max" to "flag 0x00 min".

usage: patch_growable_table.py <in.wasm> <out.wasm>
"""
import sys


def uleb(data, pos):
    result = shift = 0
    while True:
        b = data[pos]
        pos += 1
        result |= (b & 0x7F) << shift
        shift += 7
        if not b & 0x80:
            return result, pos


def enc(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def main():
    src, dst = sys.argv[1:3]
    data = open(src, "rb").read()
    if data[:8] != b"\0asm\x01\0\0\0":
        raise SystemExit("not a wasm v1 module")
    out = bytearray(data[:8])
    pos = 8
    patched = 0
    while pos < len(data):
        sid = data[pos]
        size, body_start = uleb(data, pos + 1)
        body = data[body_start:body_start + size]
        if sid == 4:
            count, p = uleb(body, 0)
            new = bytearray(enc(count))
            for _ in range(count):
                new.append(body[p])  # reftype
                p += 1
                flag = body[p]
                p += 1
                mn, p = uleb(body, p)
                if flag == 0x01:
                    _, p = uleb(body, p)
                    patched += 1
                elif flag != 0x00:
                    raise SystemExit(f"unsupported table limits flag {flag:#x}")
                new.append(0x00)
                new += enc(mn)
            body = bytes(new)
        out.append(sid)
        out += enc(len(body))
        out += body
        pos = body_start + size
    open(dst, "wb").write(out)
    print(f"growable table: {patched} table limit(s) patched", file=sys.stderr)


if __name__ == "__main__":
    main()
