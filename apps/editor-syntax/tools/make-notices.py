#!/usr/bin/env python3
"""make-notices.py [<apps/editor-syntax>] -> THIRD-PARTY-NOTICES.md, from the lock files and the licence files
that native/fetch.sh unpacked under build/. Rerun after bumping a grammar (on the host that fetched)."""
import glob, json, os, sys
here = sys.argv[1] if len(sys.argv) > 1 else os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
b = os.path.join(here, 'build')
lock = json.load(open(os.path.join(here, 'native/grammars.lock.json')))['grammars']
up = json.load(open(os.path.join(here, 'native/upstream.lock.json')))
def lic(d):
    fs = sorted(glob.glob(os.path.join(d, 'LICEN[CS]E*')) + glob.glob(os.path.join(d, 'COPYING*')))
    return open(fs[0], encoding='utf-8', errors='replace').read().strip() if fs else None
out = []
w = out.append
w('# Third-party notices: `dev.supermux.editor:editor-syntax`\n')
w('editor-syntax itself is MIT licensed, like the rest of this repository. Its native libraries additionally')
w('**contain compiled third-party code**: the tree-sitter runtime, every grammar listed below (its generated')
w('parser and external scanner; for bundled grammars also its parse tables, compressed), and, in the Linux and')
w('Windows libraries only, zlib. This file lists what is inside the libraries built from the pinned sources')
w('(`native/upstream.lock.json`, `native/grammars.lock.json`) and reproduces the required notices.\n')
w('Which artifacts: every `libsupermux_syntax*.{so,dylib,dll,a}` that `native/build.sh` produces, the Android')
w('`jni/<abi>/libsupermux_syntax_jni.so`, and the JVM jar\'s `dev/supermux/editor/syntax/natives/<target>/` resources.\n')
w('Also linked, from the toolchain: LLVM libc++ (Apache-2.0 with LLVM exceptions) statically into the Android,')
w('Linux and Windows libraries, for tree-sitter-vue\'s C++ scanner; zig\'s mingw-w64 CRT startup objects and')
w('winpthreads into the Windows DLL. **Build-time only, not linked:** the JDK\'s `jni.h` and the vendored `native/jni/*/jni_md.h`')
w('(GPLv2 with the Classpath exception), the Android NDK, zig, and tree-sitter-cli (used to regenerate clojure).\n')
w('| component | version | licence | tables |')
w('|---|---|---|---|')
w('| tree-sitter (`lib/`) | %s (`%s`) | MIT | |' % (up['tree-sitter']['tag'], up['tree-sitter']['commit']))
w('| ICU data in tree-sitter `lib/src/unicode/` | (vendored by tree-sitter) | Unicode-3.0 / ICU | |')
w('| zlib | %s | Zlib | (Linux and Windows only) |' % up['zlib']['version'])
for g in lock:
    if g['tables'] == 'excluded': continue
    w('| `%s` | %s | %s | %s |' % (g['package'], g['version'], g['license'], g['tables']))
w('\n---\n')
w('## tree-sitter: MIT\n\n<%s>, tag `%s`.\n\n```\n%s\n```\n' % (up['tree-sitter']['repo'], up['tree-sitter']['tag'], lic(os.path.join(b, 'tree-sitter'))))
u = lic(os.path.join(b, 'tree-sitter/lib/src/unicode'))
if u is None: sys.exit('no licence file in tree-sitter/lib/src/unicode')
w('## tree-sitter lib/src/unicode: the ICU / Unicode licence\n\ntree-sitter vendors ICU headers (UTF-8/16 macros, '
  'character property data) under `lib/src/unicode/`, which are compiled into every library.\n\n```\n%s\n```\n' % u)
w('## mingw-w64 winpthreads and runtime (Windows DLL only)\n\n`zig cc -target x86_64-windows-gnu` links winpthreads '
  'statically (the DLL\'s pthread mutexes; it imports no winpthread DLL) and the mingw-w64 CRT startup code. The texts '
  'are from zig 0.16.0\'s bundled mingw-w64: the header of `libc/mingw/winpthreads/mutex.c` (winpthreads ships no '
  'COPYING there) and `libc/mingw/COPYING`.\n\n```\n%s\n```\n\n```\n%s\n```\n'
  % (open(os.path.join(here, 'native/licenses/winpthreads.COPYING')).read().strip(),
     open(os.path.join(here, 'native/licenses/mingw-w64.COPYING')).read().strip()))
z = lic(os.path.join(b, 'zlib'))
w('## zlib: Zlib licence\n\n<https://zlib.net>, version %s.\n\n```\n%s\n```\n' % (up['zlib']['version'], z))
w('## Grammars\n')
for g in lock:
    if g['tables'] == 'excluded': continue
    t = lic(os.path.join(b, 'grammars', g['lang']))
    if t is None and g.get('licenseFile'):
        w('### %s %s (%s)\n\n%s\n\n```\n%s\n```\n' % (g['package'], g['version'], g['license'], g['licenseSource'],
                                                   open(os.path.join(here, g['licenseFile'])).read().strip()))
        continue
    if t is None:
        pj = json.load(open(os.path.join(b, 'grammars', g['lang'], 'package.json')))
        a = pj.get('author'); a = a.get('name') if isinstance(a, dict) else a
        r = pj.get('repository'); r = r.get('url') if isinstance(r, dict) else r
        w('### %s %s (%s)\n\n**The npm tarball ships no licence text.** Its package.json declares `%s`; author: %s; '
          'repository: <%s>. The MIT notice must be taken from the repository before a release.\n'
          % (g['package'], g['version'], g['license'], pj.get('license'), a, r))
        continue
    w('### %s %s (%s)\n\n```\n%s\n```\n' % (g['package'], g['version'], g['license'], t))
open(os.path.join(here, 'THIRD-PARTY-NOTICES.md'), 'w').write('\n'.join(out) + '\n')
print('ok', len(out))
