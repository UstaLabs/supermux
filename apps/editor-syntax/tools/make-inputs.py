#!/usr/bin/env python3
"""make-inputs.py <outdir> <dir>...: every file under the dirs, plus each test-corpus case's
input section split out as its own file (the corpus format: ===\\nname\\n===\\n<input>\\n---\\n<sexp>)."""
import os, re, sys
out = sys.argv[1]; os.makedirs(out, exist_ok=True)
n = 0
def put(name, data):
    global n
    n += 1
    open(os.path.join(out, '%05d_%s' % (n, re.sub(r'[^\w.]+', '_', name)[-60:])), 'wb').write(data)
for d in sys.argv[2:]:
    for root, _, files in os.walk(d):
        for f in sorted(files):
            p = os.path.join(root, f)
            if os.path.getsize(p) > 2_000_000: continue
            data = open(p, 'rb').read()
            if b'\0' in data[:4096]: continue  # binary
            put(f, data)
            if '/corpus' in p or p.endswith('.txt'):
                text = data.decode('utf-8', 'replace')
                for m in re.finditer(r'^={3,}[^\n]*\n.*?\n={3,}[^\n]*\n(.*?)\n-{3,}[^\n]*\n', text, re.S | re.M):
                    put(f + '.case', m.group(1).encode())
print(n)
