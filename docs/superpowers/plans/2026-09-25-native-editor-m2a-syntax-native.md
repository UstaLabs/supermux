# Native editor M2a: the native tree-sitter binding and grammar pipeline. Implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `:editor-syntax`'s native half to the repo: our own C binding (`ses_*`) over tree-sitter v0.25.10, the
grammar pipeline that compiles every grammar's CODE into one native library per platform and moves its parse TABLES
into compressed blobs, and the Kotlin JNI/cinterop binding. It must pass the M0 golden highlight test on JVM, the iOS
simulator and Android, with no offset conversion.

**Architecture:** Mirrors `apps/terminal-core`: a C ABI (`native/include/supermux_syntax.h`), `native/build.sh <target>`,
staged natives with a sha256 `manifest.json`, JNI for Android and desktop, cinterop for iOS. Text is UTF-16 end to end:
tree-sitter parses with `TSInputEncodingUTF16LE`, and the bridge does the only ×2 / ÷2.
Grammars are fetched from npm at pinned versions (tarball sha256), transformed by `tools/sestables.py`, and
differential-tested against the untransformed grammar before they are shipped. Web (web-tree-sitter) and the
highlighting layer on top are **M2b**, not this plan.

**Tech stack:** C11, tree-sitter v0.25.10 (`da6fe9beb4f7f67beb75914ca8e0d48ae48d6406`), zlib, Python 3.9 standard
library, Kotlin 2.4.10 Multiplatform (jvm, android, iosArm64, iosSimulatorArm64), JNI, cinterop.
Xcode clang, the Android NDK 26.1, and zig (Linux and Windows desktop cross-compiles, installed on the Mac with Homebrew).

**Verified:** the C, Python and Kotlin code blocks below are the M2 prototype's files, copied byte for byte. On
2026-09-25, on the Mac:
- 47/48 grammars were differential-tested identical against their untransformed originals.
- 8/8 binding tests passed on the JVM, the iOS simulator and the Android arm64 emulator.

What is NEW in this plan, and so not yet verified, is only the repo integration: paths, the lock files, the Gradle
packaging, and the three extra desktop targets. The tasks adapting it say exactly what changes.

Spec: `docs/superpowers/specs/2026-09-25-native-editor-design.md` §5 (esp. §5.2a, §5.3).

## Ground rules
- **No builds on the Linux host.** Every build and test runs on the Mac, in `~/work/native-editor`: sync with
  `scripts/editor/mac-sync.sh`, then `ssh mac "$MACENV; …"` with
  `MACENV='export JAVA_HOME=/opt/homebrew/opt/openjdk@17; export PATH=$JAVA_HOME/bin:/opt/homebrew/bin:$PATH'`.
  Long runs use `nohup caffeinate -i … &`, then poll.
- `mac-sync.sh` excludes `build/` dirs, so everything generated (fetched grammars, transformed parsers, natives)
  lives under `apps/editor-syntax/build/` **on the Mac** and is never committed.
- Generated files are never committed: fetched npm tarballs, `parser_<lang>.c`, `blob_<lang>.c`, `.sesz`, libraries.
  Only the tools, lock files and hand-written sources are.
- Every commit ends with a blank line + `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Paths under
  `docs/` need `git add -f`.

## File map (`apps/editor-syntax/`)
| Path | What |
|---|---|
| `build.gradle.kts`, `gradle.properties` | the KMP module; cinterop commonization on |
| `native/upstream.lock.json` | tree-sitter repo, tag, commit |
| `native/grammars.lock.json` | per grammar: npm package, version, tarball sha256, parser dirs, `bundled`/`code`, licence |
| `native/include/supermux_syntax.h` | THE C ABI (`ses_*`) |
| `native/include/ses_grammar.h`, `ses_registry.h` | the contract between generated grammars, the loader and the registry |
| `native/src/syntax_bridge.c` | `ses_*` over tree-sitter: parser, tree, query (+ eq/any-of predicates) |
| `native/src/ses_grammar.c` | the tables-blob loader (validate, inflate, fix up, publish) |
| `native/src/syntax_jni.c` | JNI for `dev.supermux.editor.syntax.Ses` |
| `native/fetch.sh` | fetch tree-sitter + grammars per the lock files, verify sha256 |
| `native/build.sh` | `gen` (transform + difftest every grammar), `<target>` (one native library), `all` |
| `native/tests/difftest.c` | original vs transformed grammar, same runtime |
| `tools/sestables.py`, `tools/gen-registry.py`, `tools/build-grammar.sh`, `tools/make-lock.py` | the pipeline |
| `src/commonMain/.../syntax/SyntaxTypes.kt` | `TextSource`, `TextEdit`, `SyntaxException`, `SyntaxStatus` (platform-free) |
| `src/nativeBackedMain/.../syntax/Syntax.kt` | `SyntaxLanguages`, `SyntaxParser`, `SyntaxTree`, `SyntaxQuery`, `expect object Ses` |
| `src/jvmAndAndroidMain/.../Ses.jvmAndAndroid.kt`, `src/iosMain/.../Ses.ios.kt` | the two bindings |
| `src/nativeInterop/cinterop/syntax.def` | cinterop |
| `src/nativeBackedTest/...` | golden + binding tests |

`nativeBacked` is an intermediate source set shared by jvm, android and ios. `commonMain` only holds the
platform-free types, so M2b can add a wasmJs backend (web-tree-sitter) without touching them.

---

### Task 1: Pin the sources

**Files:**
- Create: `apps/editor-syntax/native/upstream.lock.json`
- Create: `apps/editor-syntax/tools/make-lock.py`
- Create: `apps/editor-syntax/native/grammars.lock.json` (generated once by `make-lock.py`, then committed)

- [ ] **Step 1: The upstream lock**

`apps/editor-syntax/native/upstream.lock.json`:
```json
{
  "tree-sitter": {
    "repo": "https://github.com/tree-sitter/tree-sitter",
    "tag": "v0.25.10",
    "commit": "da6fe9beb4f7f67beb75914ca8e0d48ae48d6406",
    "why": "matches web-tree-sitter 0.25.10 (the web backend) so every client parses with the same core"
  }
}
```

- [ ] **Step 2: A lock generator**

`apps/editor-syntax/tools/make-lock.py`:
```python
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
```

- [ ] **Step 3: Generate and review the lock**

Run (small, network-only, fine on either host; use the Mac for consistency):
```bash
scripts/editor/mac-sync.sh
ssh mac "cd ~/work/native-editor && python3 apps/editor-syntax/tools/make-lock.py docs/superpowers/notes/m0-artifacts/grammar-inventory.tsv docs/superpowers/notes/m0-artifacts/grammar-inventory-alternates.tsv" > apps/editor-syntax/native/grammars.lock.json
python3 -c "import json;g=json.load(open('apps/editor-syntax/native/grammars.lock.json'))['grammars'];print(len(g), sorted(x['lang'] for x in g if x['tables']=='bundled'))"
```
Expected:
- About 42 grammars; exactly which depends on the alternates.
- The bundled list is every core language that has a package: javascript, typescript, python, kotlin, swift, go,
  rust, java, c, cpp, json, yaml, toml, markdown, html, css, shell, sql.
- `clojure` is present, but its grammar is ABI 9. Task 3's difftest will flag it, so set its `"tables"` to
  `"excluded"` by hand, with `"note": "ABI 9 - regenerate from grammar.json with tree-sitter-cli, or replace"`.
- Remove the duplicate placeholder rows the alternates replace (`r`, `erlang` and `dockerfile` placeholders are skipped
  automatically, because their version is MISSING).

- [ ] **Step 4: Commit**

```bash
git add apps/editor-syntax/native/upstream.lock.json apps/editor-syntax/native/grammars.lock.json apps/editor-syntax/tools/make-lock.py
git commit -m "build(editor-syntax): pin tree-sitter v0.25.10 and every grammar by tarball sha256"
```

---

### Task 2: Fetch script

**Files:**
- Create: `apps/editor-syntax/native/fetch.sh`

- [ ] **Step 1: Write it**

`apps/editor-syntax/native/fetch.sh`:
```bash
#!/usr/bin/env bash
# fetch.sh: tree-sitter at the locked commit, and every grammar tarball at its locked sha256, into build/.
# Idempotent; refuses a tarball whose sha256 differs from native/grammars.lock.json.
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
        os.makedirs(dest)
        with tarfile.open(tgz) as t:
            for m in t.getmembers():
                m.name = m.name.split("/", 1)[1] if "/" in m.name else ""
                if m.name:
                    t.extract(m, dest)
print("fetched")
PY
```

- [ ] **Step 2: Run it on the Mac**

`chmod +x apps/editor-syntax/native/fetch.sh && scripts/editor/mac-sync.sh && ssh mac "cd ~/work/native-editor/apps/editor-syntax && native/fetch.sh"`
Expected: `fetched`; `build/tree-sitter/lib/src/lib.c` exists; `build/grammars/<lang>/` for each locked grammar.

- [ ] **Step 3: Commit**

```bash
git add apps/editor-syntax/native/fetch.sh && git commit -m "build(editor-syntax): fetch pinned sources with sha256 verification"
```

---

### Task 3: The table pipeline and the differential test

**Files:**
- Create: `apps/editor-syntax/tools/sestables.py` (verbatim from the prototype)
- Create: `apps/editor-syntax/tools/gen-registry.py` (verbatim)
- Create: `apps/editor-syntax/native/include/ses_grammar.h`, `apps/editor-syntax/native/include/ses_registry.h` (verbatim)
- Create: `apps/editor-syntax/native/tests/difftest.c` (verbatim)
- Create: `apps/editor-syntax/tools/build-grammar.sh` (the prototype's, with the path changes below)

- [ ] **Step 1: Add the verified files**

`apps/editor-syntax/tools/sestables.py`:
```python
#!/usr/bin/env python3
"""sestables: split a tree-sitter generated parser.c into CODE (lexers stay compiled) and DATA
(the parse tables, moved into a zlib-compressed blob that the runtime inflates on first use).

Two phases, because the table bytes come from the C compiler, never from parsing C initializers:

  sestables.py prepare <parser.c> <workdir>
      Finds tree_sitter_<lang>() and the static tables its TSLanguage initializer points at.
      Writes <workdir>/orig_<lang>.c: the ORIGINAL parser.c #included verbatim (tree_sitter_<lang>
      renamed to ses_orig_<lang>) plus ses_orig_dump_<lang>(FILE*), which fwrite()s every table
      with sizeof(). Also <workdir>/dump_<lang>.c (a main) and <workdir>/<lang>.plan.json.
      The caller compiles orig_<lang>.c (+ the grammar's scanner) for the HOST and runs the dumper:
          ./dump_<lang> <workdir>/<lang>.dump

  sestables.py emit <workdir> <lang>
      Reads <lang>.dump and writes:
        <lang>.sesz            the compressed blob (header + zlib stream of the raw payload)
        parser_<lang>.c        parser.c minus the moved tables; tree_sitter_<lang>() now returns
                               ses_grammar_language(&desc), which inflates + fixes up on first use
        blob_<lang>.c          the .sesz as a C byte array (for grammars whose tables are bundled)
        <lang>.tables.json     manifest: tables, sizes, hash

Table bytes are target-independent on every target we ship (all little-endian; the moved tables
hold only fixed-width integers, bools and structs of them) EXCEPT the `const char *` name arrays,
which are serialized as offsets into a string pool and rebuilt into pointer arrays at load time.

Raw payload ("SEST", little-endian):
  0  char[4] "SEST"   4 u16 format=1   6 u16 table_count   8 u32 abi   12 u32 reserved
  16 u64 content_hash (word-wise FNV-1a 64 of everything from byte 32 on; an identity
     tag compiled into parser_<lang>.c: a blob for another grammar version is refused)   24 u64 payload_size
  32 entries[table_count] of 16 bytes: u32 offset, u32 size, u32 count, u16 kind, u16 elem_size
  then table data, each table 16-byte aligned.  kind 0 = raw bytes, kind 1 = strings:
  u32 offsets[count] (0xFFFFFFFF = NULL) followed by NUL-terminated strings.
Compressed file (".sesz"):
  0 char[4] "SESZ"  4 u32 format=1  8 u32 raw_size  12 u32 z_size  16 u64 content_hash  24 zlib stream
"""
import json
import os
import re
import struct
import sys
import zlib

FORMAT = 1
KIND_BYTES, KIND_STRINGS = 0, 1

LANG_FN = re.compile(
    r'^(?P<head>(?:TS_PUBLIC|extern)\s+const\s+TSLanguage\s*\*\s*tree_sitter_(?P<lang>\w+)\s*\(\s*void\s*\)\s*)\{',
    re.M)
TABLE_DEF = re.compile(
    r'^static\s+(?P<const>const\s+)?(?P<type>[A-Za-z_][\w \t\*]*?)\s*\b(?P<name>ts_\w+)\s*'
    r'(?P<dims>(?:\[[^\]\n]*\])+)\s*=\s*\{', re.M)


def split_top(body):
    """Split an initializer body on commas at brace/paren depth 0."""
    items, depth, cur = [], 0, []
    for ch in body:
        if ch in '{(':
            depth += 1
        elif ch in '})':
            depth -= 1
        if ch == ',' and depth == 0:
            items.append(''.join(cur).strip()); cur = []
        else:
            cur.append(ch)
    tail = ''.join(cur).strip()
    if tail:
        items.append(tail)
    return [i for i in items if i]


def find_block_end(text, open_idx):
    """Index just past the '}' matching text[open_idx] == '{' (generated code: no braces in strings
    except inside string literals of the name tables, which we skip)."""
    depth, i, n = 0, open_idx, len(text)
    while i < n:
        c = text[i]
        if c == '"':
            i += 1
            while text[i] != '"':
                i += 2 if text[i] == '\\' else 1
        elif c == "'":
            i += 1
            while text[i] != "'":
                i += 2 if text[i] == '\\' else 1
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError('unbalanced braces')


def analyse(src):
    m = LANG_FN.search(src)
    if not m:
        raise SystemExit('no tree_sitter_<lang>() found')
    lang = m.group('lang')
    fn_start, fn_open = m.start(), m.end() - 1
    fn_end = find_block_end(src, fn_open)
    fn = src[fn_open:fn_end]
    lm = re.search(r'static\s+(?:const\s+)?TSLanguage\s+language\s*=\s*\{', fn)
    if not lm:
        raise SystemExit('no `static TSLanguage language = {` in tree_sitter_%s' % lang)
    init_open = fn_open + lm.end() - 1
    init_end = find_block_end(src, init_open)
    items = split_top(src[init_open + 1:init_end - 1])

    tables = {}
    for t in TABLE_DEF.finditer(src):
        if t.start() > fn_start:
            continue  # only file-scope tables before the language function
        end = find_block_end(src, t.end() - 1)
        assert src[end] == ';', t.group('name')
        tables[t.group('name')] = dict(name=t.group('name'), type=t.group('type').strip(),
                                       start=t.start(), end=end + 1)

    kept, moved = [], []   # moved: (c lvalue under the language, table name)

    def table_of(expr):
        names = [n for n in re.findall(r'\bts_\w+\b', expr) if n in tables]
        return names[0] if len(names) == 1 else None

    for it in items:
        fm = re.match(r'\.(\w+)\s*=\s*(.*)$', it, re.S)
        if not fm:
            raise SystemExit('unexpected initializer item: %r' % it[:80])
        field, expr = fm.group(1), fm.group(2).strip()
        if field == 'external_scanner':
            sub = split_top(expr.strip()[1:-1])
            names = ['states', 'symbol_map', 'create', 'destroy', 'scan', 'serialize', 'deserialize']
            parts = []
            for i, s in enumerate(sub):
                tn = table_of(s)
                if tn and i < 2:
                    moved.append(('external_scanner.' + names[i], tn)); parts.append('NULL')
                else:
                    parts.append(s)
            kept.append('.external_scanner = {\n      %s,\n    }' % ',\n      '.join(parts))
            continue
        tn = table_of(expr) if not expr.startswith('{') else None
        if tn:
            moved.append((field, tn))
        else:
            kept.append('.%s = %s' % (field, expr))

    # A table may only move if nothing but its own definition and the language initializer uses it.
    outside = src[:fn_start] + src[fn_end:]
    final_moved = []
    for field, tn in moved:
        t = tables[tn]
        rest = outside[:t['start']] + outside[t['end']:]
        if re.search(r'\b%s\b' % tn, rest):
            # keep it inline: put the original reference back
            raise SystemExit('table %s is referenced outside tree_sitter_%s(); not supported' % (tn, lang))
        final_moved.append((field, tn))
    seen = {}
    for field, tn in final_moved:
        seen.setdefault(tn, []).append(field)
    abi = int(re.search(r'#define LANGUAGE_VERSION (\d+)', src).group(1))
    order = []
    for _, tn in final_moved:
        if tn not in order:
            order.append(tn)
    return dict(lang=lang, abi=abi, fn_start=fn_start, fn_end=fn_end, head=m.group('head'),
                kept=kept, moved=final_moved,
                tables=[dict(name=tn, type=tables[tn]['type'],
                             kind=KIND_STRINGS if re.search(r'\bchar\b', tables[tn]['type']) else KIND_BYTES,
                             start=tables[tn]['start'], end=tables[tn]['end']) for tn in order])


def cmd_prepare(parser_c, workdir):
    parser_c = os.path.abspath(parser_c)
    src = open(parser_c, encoding='utf-8').read()
    a = analyse(src)
    lang = a['lang']
    os.makedirs(workdir, exist_ok=True)
    dump = ['#define tree_sitter_%s ses_orig_tree_sitter_%s' % (lang, lang),
            '#include "%s"' % parser_c,
            '#undef tree_sitter_%s' % lang,
            '#include <stdio.h>',
            '#include <string.h>',
            'static void ses_w32(FILE *f, unsigned v) { unsigned char b[4] = {v, v >> 8, v >> 16, v >> 24}; fwrite(b, 1, 4, f); }',
            'void ses_orig_dump_%s(FILE *f) {' % lang]
    for t in a['tables']:
        n = t['name']
        if t['kind'] == KIND_STRINGS:
            dump.append('  { unsigned c = (unsigned)(sizeof(%s) / sizeof(%s[0])); ses_w32(f, 1); ses_w32(f, c); ses_w32(f, (unsigned)sizeof(%s[0]));'
                        ' for (unsigned i = 0; i < c; i++) { const char *s = %s[i];'
                        ' if (!s) ses_w32(f, 0xFFFFFFFFu); else { unsigned l = (unsigned)strlen(s); ses_w32(f, l); fwrite(s, 1, l, f); } } }' % (n, n, n, n))
        else:
            dump.append('  ses_w32(f, 0); ses_w32(f, (unsigned)sizeof(%s)); ses_w32(f, (unsigned)sizeof(%s[0])); fwrite((const void *)%s, 1, sizeof(%s), f);' % (n, n, n, n))
    dump.append('}')
    open(os.path.join(workdir, 'orig_%s.c' % lang), 'w').write('\n'.join(dump) + '\n')
    open(os.path.join(workdir, 'dump_%s.c' % lang), 'w').write(
        '#include <stdio.h>\nvoid ses_orig_dump_%s(FILE *f);\n'
        'int main(int argc, char **argv) { FILE *f = fopen(argv[1], "wb"); if (!f) return 1;'
        ' ses_orig_dump_%s(f); return fclose(f) != 0; }\n' % (lang, lang))
    plan = dict(a, parser_c=parser_c)
    json.dump(plan, open(os.path.join(workdir, '%s.plan.json' % lang), 'w'), indent=1)
    print(lang)


def cmd_emit(workdir, lang):
    plan = json.load(open(os.path.join(workdir, '%s.plan.json' % lang)))
    raw = open(os.path.join(workdir, '%s.dump' % lang), 'rb').read()
    pos, recs = 0, []

    def u32():
        nonlocal pos
        v = struct.unpack_from('<I', raw, pos)[0]; pos += 4; return v

    for t in plan['tables']:
        kind = u32()
        assert kind == t['kind'], (t['name'], kind)
        if kind == KIND_BYTES:
            size, elem = u32(), u32()
            recs.append((KIND_BYTES, raw[pos:pos + size], size // elem if elem else 0, elem)); pos += size
        else:
            count, elem = u32(), u32()
            offs, pool = [], bytearray()
            for _ in range(count):
                ln = u32()
                if ln == 0xFFFFFFFF:
                    offs.append(0xFFFFFFFF)
                else:
                    offs.append(len(pool)); pool += raw[pos:pos + ln] + b'\0'; pos += ln
            data = struct.pack('<%dI' % count, *offs) + bytes(pool)
            recs.append((KIND_STRINGS, data, count, elem))
    assert pos == len(raw), 'trailing dump bytes'

    n = len(recs)
    hdr_size = 32 + 16 * n
    off = (hdr_size + 15) & ~15
    entries, body = [], bytearray(b'\0' * (off - hdr_size))
    for kind, data, count, elem in recs:
        entries.append(struct.pack('<IIIHH', off, len(data), count, kind, elem))
        body += data
        off += len(data)
        pad = (-off) & 15
        body += b'\0' * pad
        off += pad
    after_header = b''.join(entries) + bytes(body)
    h = fnv1a64_words(after_header)
    payload = struct.pack('<4sHHIIQQ', b'SEST', FORMAT, n, plan['abi'], 0, h, 32 + len(after_header)) + after_header
    z = zlib.compress(payload, 9)
    sesz = struct.pack('<4sIIIQ', b'SESZ', FORMAT, len(payload), len(z), h) + z
    open(os.path.join(workdir, '%s.sesz' % lang), 'wb').write(sesz)

    # C byte array of the .sesz (bundled tables)
    with open(os.path.join(workdir, 'blob_%s.c' % lang), 'w') as f:
        f.write('/* generated by sestables.py: %s tables, compressed */\n#include <stdint.h>\n' % lang)
        f.write('const uint32_t ses_blob_%s_size = %d;\n' % (lang, len(sesz)))
        f.write('__attribute__((aligned(16))) const uint8_t ses_blob_%s[%d] = {\n' % (lang, len(sesz)))
        for i in range(0, len(sesz), 32):
            f.write(','.join(str(b) for b in sesz[i:i + 32]) + ',\n')
        f.write('};\n')

    # transformed parser.c
    src = open(plan['parser_c'], encoding='utf-8').read()
    cuts = sorted([(t['start'], t['end'], i, t['name']) for i, t in enumerate(plan['tables'])])
    out, last = [], 0
    for s, e, i, name in cuts:
        out.append(src[last:s])
        out.append('/* %s: moved to the tables blob (table %d) */' % (name, i))
        last = e
    out.append(src[last:plan['fn_start']])
    specs = ',\n  '.join('{%d, %d, %d}' % (k, len(d), c) for k, d, c, _ in recs)
    fills = '\n'.join('  ses_language.%s = ses_tables_at(t, %d);' % (field, [x['name'] for x in plan['tables']].index(tn))
                      for field, tn in plan['moved'])
    out.append('''#include "ses_grammar.h"

/* ---- sestables.py: the TSLanguage is filled from the tables blob on first use ---- */
static TSLanguage ses_language = {
    %s,
};

static void ses_fill(const ses_tables *t) {
%s
}

static const ses_table_spec ses_specs[%d] = {
  %s
};

static ses_grammar ses_desc = {
  "%s", LANGUAGE_VERSION, 0x%016xULL, %d, ses_specs, ses_fill, &ses_language, NULL,
};

ses_grammar *ses_grammar_%s(void) { return &ses_desc; }

%s{
  return (const TSLanguage *)ses_grammar_language(&ses_desc);
}
''' % (',\n    '.join(plan['kept']), fills, n, specs, lang, h, n, lang, plan['head']))
    out.append(src[plan['fn_end']:])
    open(os.path.join(workdir, 'parser_%s.c' % lang), 'w').write(''.join(out))
    manifest = dict(lang=lang, abi=plan['abi'], hash='%016x' % h, raw_size=len(payload), z_size=len(sesz),
                    tables=[dict(name=t['name'], kind=t['kind'], size=len(r[1]), count=r[2])
                            for t, r in zip(plan['tables'], recs)])
    json.dump(manifest, open(os.path.join(workdir, '%s.tables.json' % lang), 'w'), indent=1)
    print('%s abi=%d tables=%d raw=%d sesz=%d hash=%016x' % (lang, plan['abi'], n, len(payload), len(sesz), h))


def fnv1a64_words(data):
    """FNV-1a 64 over 8-byte little-endian words (a word-wise variant: FAST in Python and C alike).
    Tail bytes are folded one by one. The runtime never recomputes it; it's an identity tag."""
    h = 0xcbf29ce484222325
    n8 = len(data) // 8
    words = struct.unpack_from('<%dQ' % n8, data)
    m = 0xFFFFFFFFFFFFFFFF
    for w in words:
        h = ((h ^ w) * 0x100000001b3) & m
    for b in data[n8 * 8:]:
        h = ((h ^ b) * 0x100000001b3) & m
    return h


if __name__ == '__main__':
    if len(sys.argv) >= 4 and sys.argv[1] == 'prepare':
        cmd_prepare(sys.argv[2], sys.argv[3])
    elif len(sys.argv) >= 4 and sys.argv[1] == 'emit':
        cmd_emit(sys.argv[2], sys.argv[3])
    else:
        raise SystemExit(__doc__)
```

`apps/editor-syntax/tools/gen-registry.py`:
```python
#!/usr/bin/env python3
"""gen-registry.py <out.c> <lang>[:bundled] ...  -> the compiled-in grammar list (ses_registry.c).
`lang:bundled` links ses_blob_<lang> (blob_<lang>.c from sestables.py); plain `lang` has code only
and needs ses_language_provide_tables() before first use."""
import sys
out, specs = sys.argv[1], sys.argv[2:]
lines = ['/* generated by tools/gen-registry.py */', '#include "ses_registry.h"', '']
rows = []
for s in specs:
    lang, _, mode = s.partition(':')
    lines.append('ses_grammar *ses_grammar_%s(void);' % lang)
    if mode == 'bundled':
        lines.append('extern const uint8_t ses_blob_%s[];\nextern const uint32_t ses_blob_%s_size;' % (lang, lang))
        rows.append('  {"%s", ses_grammar_%s, ses_blob_%s, &ses_blob_%s_size},' % (lang, lang, lang, lang))
    else:
        rows.append('  {"%s", ses_grammar_%s, 0, 0},' % (lang, lang))
lines += ['', 'const ses_registry_entry ses_registry[] = {'] + rows + ['};',
          'const uint32_t ses_registry_count = %d;' % len(rows)]
open(out, 'w').write('\n'.join(lines) + '\n')
```

`apps/editor-syntax/native/include/ses_grammar.h`:
```c
/*
 * The contract between a sestables.py-transformed parser_<lang>.c and the runtime loader
 * (native/src/ses_grammar.c). Included by the generated grammar AFTER its own tree_sitter/parser.h,
 * so the grammar fills its TSLanguage with the struct layout of the ABI it was generated for; the
 * loader never looks inside a TSLanguage (it only passes the pointer on).
 */
#ifndef SES_GRAMMAR_H
#define SES_GRAMMAR_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct ses_tables ses_tables;

#define SES_TABLE_BYTES 0u
#define SES_TABLE_STRINGS 1u

/** What the grammar's code expects of table i; the loader refuses a blob that differs. */
typedef struct ses_table_spec {
  uint32_t kind;  /* SES_TABLE_BYTES or SES_TABLE_STRINGS */
  uint32_t size;  /* serialized size in bytes */
  uint32_t count; /* elements (rows for 2-D tables) */
} ses_table_spec;

typedef struct ses_grammar {
  const char *name;
  uint32_t abi;           /* LANGUAGE_VERSION of the generated parser */
  uint64_t hash;          /* identity of the tables this code was generated with */
  uint32_t table_count;
  const ses_table_spec *specs;
  void (*fill)(const ses_tables *tables); /* store every table pointer into *language */
  void *language;         /* the grammar's static, non-const TSLanguage */
  void *_Atomic loaded;   /* NULL until filled; then == language (release/acquire) */
  int32_t status;         /* last load failure (SES_ERR_*), 0 if none; guarded by the loader lock */
} ses_grammar;

/**
 * Table [index]: its bytes, 16-byte aligned, inside the inflated payload; or, for a strings table,
 * a `const char *[count]` array (NULL entries preserved). Valid forever (never unloaded).
 */
const void *ses_tables_at(const ses_tables *tables, uint32_t index);

/** The filled TSLanguage, loading it first if needed (thread-safe, once). NULL if unavailable. */
const void *ses_grammar_language(ses_grammar *grammar);

#ifdef __cplusplus
}
#endif
#endif
```

`apps/editor-syntax/native/include/ses_registry.h`:
```c
/* The compiled-in grammar list, generated as ses_registry.c by tools/gen-registry.py. */
#ifndef SES_REGISTRY_H
#define SES_REGISTRY_H

#include <stddef.h>
#include <stdint.h>
#include "ses_grammar.h"

typedef struct ses_registry_entry {
  const char *name;
  ses_grammar *(*grammar)(void);
  const uint8_t *blob;       /* bundled .sesz, or NULL when the tables must be provided at runtime */
  const uint32_t *blob_size;
} ses_registry_entry;

extern const ses_registry_entry ses_registry[];
extern const uint32_t ses_registry_count;

/** The registry entry for [name], or NULL. */
const ses_registry_entry *ses_registry_find(const char *name);
/** Copy [sesz] as [name]'s tables (checked against [grammar]'s hash). 0 or SES_ERR_*. */
int32_t ses_tables_provide(ses_grammar *grammar, const uint8_t *sesz, size_t len);
/** 1 if [grammar]'s tables are bundled, provided or already loaded. */
int ses_tables_available(ses_grammar *grammar, const ses_registry_entry *entry);
/** Why the last load of [grammar] failed (SES_ERR_*), 0 if it did not. */
int32_t ses_grammar_status(ses_grammar *grammar);

#endif
```

`apps/editor-syntax/native/tests/difftest.c`:
```c
/*
 * Differential test: the ORIGINAL grammar (ses_orig_tree_sitter_<L>, tables inline) versus the
 * TRANSFORMED one (tree_sitter_<L>, tables from the .sesz blob), same runtime, same scanner.
 *   difftest <blob.sesz> <input files...>
 * 1. Language level: counts, every symbol name/type/metadata, every field name, and for EVERY
 *    (state, symbol) pair ts_language_next_state() plus every state's lookahead set.
 * 2. Tree level: each input parsed as UTF-16LE by both; a full cursor walk (every node incl.
 *    anonymous: symbol, type, field, byte range, points, parse state, flags) plus the S-expression
 *    must be byte-identical.
 * Also times the first-use load (inflate + fix-up) through the ses_* API.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "tree_sitter/api.h"
#include "language.h" /* runtime-internal: TSLanguage.symbol_count WITHOUT aliases */
#include "supermux_syntax.h"

#define CAT2(a, b) a##b
#define CAT(a, b) CAT2(a, b)
#define STR2(x) #x
#define STR(x) STR2(x)
const TSLanguage *CAT(ses_orig_tree_sitter_, LANG)(void);
const TSLanguage *CAT(tree_sitter_, LANG)(void);

static double now_ms(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1e3 + t.tv_nsec / 1e6;
}

static char *slurp(const char *path, size_t *len) {
  FILE *f = fopen(path, "rb");
  if (!f) return NULL;
  fseek(f, 0, SEEK_END);
  long n = ftell(f);
  fseek(f, 0, SEEK_SET);
  char *b = malloc(n + 1);
  *len = fread(b, 1, n, f);
  b[*len] = 0;
  fclose(f);
  return b;
}

/* UTF-8 -> UTF-16 (invalid bytes become U+FFFD) */
static uint16_t *to_utf16(const unsigned char *s, size_t n, uint32_t *out_len) {
  uint16_t *o = malloc((n + 1) * 2 * sizeof(uint16_t));
  uint32_t k = 0;
  for (size_t i = 0; i < n;) {
    uint32_t c = s[i], len = c < 0x80 ? 1 : (c >> 5) == 6 ? 2 : (c >> 4) == 14 ? 3 : (c >> 3) == 30 ? 4 : 0;
    if (!len || i + len > n) { o[k++] = 0xFFFD; i++; continue; }
    if (len == 2) c &= 0x1F; else if (len == 3) c &= 0x0F; else if (len == 4) c &= 0x07;
    int bad = 0;
    for (uint32_t j = 1; j < len; j++) { if ((s[i + j] & 0xC0) != 0x80) bad = 1; c = (c << 6) | (s[i + j] & 0x3F); }
    if (bad) { o[k++] = 0xFFFD; i++; continue; }
    i += len;
    if (c >= 0x10000) { c -= 0x10000; o[k++] = 0xD800 | (c >> 10); o[k++] = 0xDC00 | (c & 0x3FF); }
    else o[k++] = (uint16_t)c;
  }
  *out_len = k;
  return o;
}

typedef struct { char *b; size_t n, cap; } sb;
static void sb_add(sb *s, const char *fmt, ...) __attribute__((format(printf, 2, 3)));
#include <stdarg.h>
static void sb_add(sb *s, const char *fmt, ...) {
  va_list ap;
  for (;;) {
    va_start(ap, fmt);
    int w = vsnprintf(s->b + s->n, s->cap - s->n, fmt, ap);
    va_end(ap);
    if ((size_t)w < s->cap - s->n) { s->n += w; return; }
    s->cap = s->cap * 2 + w + 64;
    s->b = realloc(s->b, s->cap);
  }
}

static void walk(TSTree *t, sb *s) {
  TSTreeCursor c = ts_tree_cursor_new(ts_tree_root_node(t));
  for (;;) {
    TSNode n = ts_tree_cursor_current_node(&c);
    const char *f = ts_tree_cursor_current_field_name(&c);
    TSPoint a = ts_node_start_point(n), b = ts_node_end_point(n);
    sb_add(s, "%u %u:%s %s %u-%u (%u,%u)-(%u,%u) st=%u,%u %d%d%d%d%d\n", ts_tree_cursor_current_depth(&c),
           ts_node_grammar_symbol(n), ts_node_grammar_type(n), f ? f : "-", ts_node_start_byte(n), ts_node_end_byte(n),
           a.row, a.column, b.row, b.column, ts_node_parse_state(n), ts_node_next_parse_state(n), ts_node_is_named(n),
           ts_node_is_missing(n), ts_node_is_extra(n), ts_node_is_error(n), ts_node_has_error(n));
    if (ts_tree_cursor_goto_first_child(&c)) continue;
    while (!ts_tree_cursor_goto_next_sibling(&c)) {
      if (!ts_tree_cursor_goto_parent(&c)) { ts_tree_cursor_delete(&c); return; }
    }
  }
}

static int compare_languages(const TSLanguage *a, const TSLanguage *b) {
  int bad = 0;
#define EQ(x) if ((x(a)) != (x(b))) { printf("  LANG MISMATCH %s\n", #x); bad++; }
  EQ(ts_language_symbol_count) EQ(ts_language_state_count) EQ(ts_language_field_count) EQ(ts_language_abi_version)
  if (bad) return bad;
  uint32_t ns = ts_language_symbol_count(a), nst = ts_language_state_count(a), nf = ts_language_field_count(a);
  for (uint32_t i = 0; i < ns; i++) {
    const char *x = ts_language_symbol_name(a, i), *y = ts_language_symbol_name(b, i);
    if ((x == NULL) != (y == NULL) || (x && strcmp(x, y))) { printf("  symbol name %u\n", i); bad++; }
    if (ts_language_symbol_type(a, i) != ts_language_symbol_type(b, i)) { printf("  symbol type %u\n", i); bad++; }
    if (x && ts_language_symbol_for_name(a, x, strlen(x), ts_language_symbol_type(a, i) == TSSymbolTypeRegular) !=
                 ts_language_symbol_for_name(b, x, strlen(x), ts_language_symbol_type(b, i) == TSSymbolTypeRegular)) {
      printf("  symbol_for_name %u\n", i); bad++;
    }
  }
  for (uint32_t i = 0; i <= nf; i++) {
    const char *x = ts_language_field_name_for_id(a, i), *y = ts_language_field_name_for_id(b, i);
    if ((x == NULL) != (y == NULL) || (x && strcmp(x, y))) { printf("  field %u\n", i); bad++; }
  }
  unsigned long long checked = 0;
  for (uint32_t st = 0; st < nst && bad < 20; st++) {
    /* only real grammar symbols: ts_language_symbol_count() includes aliases, which index past
       the parse table (reading them is out of bounds in the ORIGINAL grammar too) */
    for (uint32_t sym = 0; sym < a->symbol_count; sym++) {
      if (ts_language_next_state(a, st, sym) != ts_language_next_state(b, st, sym)) { printf("  next_state %u %u\n", st, sym); bad++; }
      checked++;
    }
    TSLookaheadIterator *ia = ts_lookahead_iterator_new(a, st), *ib = ts_lookahead_iterator_new(b, st);
    if (ia && ib) {
      for (;;) {
        bool ha = ts_lookahead_iterator_next(ia), hb = ts_lookahead_iterator_next(ib);
        if (ha != hb || (ha && ts_lookahead_iterator_current_symbol(ia) != ts_lookahead_iterator_current_symbol(ib))) {
          printf("  lookahead state %u\n", st); bad++; break;
        }
        if (!ha) break;
      }
    }
    if (ia) ts_lookahead_iterator_delete(ia);
    if (ib) ts_lookahead_iterator_delete(ib);
  }
  printf("LANG %s: symbols=%u states=%u fields=%u next_state_pairs=%llu %s\n", STR(LANG), ns, nst, nf, checked, bad ? "FAIL" : "ok");
  return bad;
}

int main(int argc, char **argv) {
  if (argc < 2) { fprintf(stderr, "usage: difftest <blob.sesz> <inputs...>\n"); return 2; }
  size_t zl;
  char *z = slurp(argv[1], &zl);
  if (!z) { fprintf(stderr, "no blob\n"); return 2; }
  int st = ses_language_provide_tables(STR(LANG), (const uint8_t *)z, zl);
  if (st) { printf("provide_tables: %d\n", st); return 1; }
  double t0 = now_ms();
  st = ses_language_load(STR(LANG));
  double t1 = now_ms();
  if (st) { printf("load: %d\n", st); return 1; }
  const TSLanguage *orig = CAT(ses_orig_tree_sitter_, LANG)(), *xf = CAT(tree_sitter_, LANG)();
  printf("LOAD %s: first-use %.2f ms (blob %zu bytes)\n", STR(LANG), t1 - t0, zl);
  int bad = compare_languages(orig, xf);

  TSParser *pa = ts_parser_new(), *pb = ts_parser_new();
  if (!ts_parser_set_language(pa, orig) || !ts_parser_set_language(pb, xf)) { printf("set_language failed\n"); return 1; }
  size_t files = 0, nodes_bytes = 0, bytes = 0, errors = 0;
  double ta = 0, tb = 0;
  for (int i = 2; i < argc; i++) {
    size_t n;
    char *src = slurp(argv[i], &n);
    if (!src) continue;
    uint32_t ul;
    uint16_t *u = to_utf16((unsigned char *)src, n, &ul);
    double x0 = now_ms();
    TSTree *A = ts_parser_parse_string_encoding(pa, NULL, (const char *)u, ul * 2, TSInputEncodingUTF16LE);
    double x1 = now_ms();
    TSTree *B = ts_parser_parse_string_encoding(pb, NULL, (const char *)u, ul * 2, TSInputEncodingUTF16LE);
    double x2 = now_ms();
    ta += x1 - x0; tb += x2 - x1;
    sb sa = {malloc(1024), 0, 1024}, sbb = {malloc(1024), 0, 1024};
    walk(A, &sa);
    walk(B, &sbb);
    char *ea = ts_node_string(ts_tree_root_node(A)), *eb = ts_node_string(ts_tree_root_node(B));
    if (sa.n != sbb.n || memcmp(sa.b, sbb.b, sa.n) || strcmp(ea, eb)) { printf("  TREE MISMATCH %s\n", argv[i]); bad++; }
    if (ts_node_has_error(ts_tree_root_node(A))) errors++;
    files++; nodes_bytes += sa.n; bytes += ul;
    free(ea); free(eb); free(sa.b); free(sbb.b); free(u); free(src);
    ts_tree_delete(A); ts_tree_delete(B);
  }
  printf("TREES %s: files=%zu utf16_units=%zu walk_bytes=%zu files_with_errors=%zu parse_ms orig=%.1f xform=%.1f %s\n",
         STR(LANG), files, bytes, nodes_bytes, errors, ta, tb, bad ? "FAIL" : "ok");
  ts_parser_delete(pa); ts_parser_delete(pb);
  return bad ? 1 : 0;
}
```

The prototype's `build-grammar.sh`, to adapt as described in Step 2:
```bash
#!/usr/bin/env bash
# build-grammar.sh <package> <grammar src dir> : table extraction + differential test for ONE grammar (host, macOS).
# Writes out/<lang>/{orig_,parser_,blob_}<lang>.c, <lang>.sesz, <lang>.tables.json, difftest log.
set -euo pipefail
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PKG="$1"; SRC="$(cd "$2" && pwd)"
TS="$HERE/tree-sitter/lib"
W="$HERE/out/tmp.$$"; mkdir -p "$W"
LANG_NAME=$(python3 "$HERE/tools/sestables.py" prepare "$SRC/parser.c" "$W")
OUT="$HERE/out/$LANG_NAME"; rm -rf "$OUT"; mv "$W" "$OUT"; W="$OUT"
CC=(clang -w -std=gnu11 -I"$SRC")
SCAN=(); CXX_LINK=()
if [ -f "$SRC/scanner.c" ]; then "${CC[@]}" -O1 -c "$SRC/scanner.c" -o "$W/scanner.o"; SCAN=("$W/scanner.o");
elif [ -f "$SRC/scanner.cc" ]; then clang++ -w -std=c++14 -I"$SRC" -O1 -c "$SRC/scanner.cc" -o "$W/scanner.o"; SCAN=("$W/scanner.o"); CXX_LINK=(-lc++); fi
"${CC[@]}" -O0 -c "$W/orig_$LANG_NAME.c" -o "$W/orig.o"
clang "$W/dump_$LANG_NAME.c" "$W/orig.o" ${SCAN[@]+"${SCAN[@]}"} ${CXX_LINK[@]+"${CXX_LINK[@]}"} -o "$W/dump"
"$W/dump" "$W/$LANG_NAME.dump"
python3 "$HERE/tools/sestables.py" emit "$W" "$LANG_NAME"
"${CC[@]}" -O1 -I"$HERE/native/include" -c "$W/parser_$LANG_NAME.c" -o "$W/xform.o"
python3 "$HERE/tools/gen-registry.py" "$W/registry.c" "$LANG_NAME"
[ -f "$HERE/out/libts.o" ] || clang -O2 -w -std=gnu11 -I"$TS/include" -I"$TS/src" -c "$TS/src/lib.c" -o "$HERE/out/libts.o"
clang -O1 -w -std=gnu11 -DLANG="$LANG_NAME" -I"$TS/include" -I"$TS/src" -I"$HERE/native/include" \
  "$HERE/tests/difftest.c" "$HERE/native/src/syntax_bridge.c" "$HERE/native/src/ses_grammar.c" "$W/registry.c" \
  "$HERE/out/libts.o" "$W/orig.o" "$W/xform.o" ${SCAN[@]+"${SCAN[@]}"} ${CXX_LINK[@]+"${CXX_LINK[@]}"} -lz -o "$W/difftest"
INP="$W/inputs"; python3 "$HERE/tools/make-inputs.py" "$INP" $(ls -d "$HERE/corpora/$PKG" 2>/dev/null) \
  "$HERE/inputs/common" > /dev/null
"$W/difftest" "$W/$LANG_NAME.sesz" "$INP"/* | tee "$W/difftest.log"
rm -f "$W/orig.o" "$W/$LANG_NAME.dump" "$W/dump"
```

- [ ] **Step 2: Adapt `build-grammar.sh` to the repo layout**

Save it as `apps/editor-syntax/tools/build-grammar.sh`, changing ONLY paths:

| prototype | repo |
|---|---|
| `HERE="…/.."` (proto root) | `HERE="$(cd "$(dirname "$0")/.." && pwd)"` (= `apps/editor-syntax`) |
| `$HERE/tree-sitter/lib` | `$HERE/build/tree-sitter/lib` |
| `$HERE/out/...` | `$HERE/build/gen/...` |
| `$HERE/tests/difftest.c` | `$HERE/native/tests/difftest.c` |
| `$HERE/native/include` | unchanged |

Its inputs to the difftest are the grammar's own `test/corpus` files. The prototype also used GitHub corpora; npm
tarballs often ship no `test/` directory. So for each grammar, feed it every file under `build/grammars/<lang>/`
matching `*.txt` in `test/corpus` if present, **plus** `examples/*`, plus the M0 golden JSON sample. It must see at least one input,
or it fails loudly.

- [ ] **Step 3: A `gen` mode that runs it for every locked grammar**

Create `apps/editor-syntax/native/build.sh` with just the `gen` mode for now (Task 4 adds targets):
```bash
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
```

- [ ] **Step 4: Run gen on the Mac and check every grammar is identical**

```bash
chmod +x apps/editor-syntax/native/build.sh apps/editor-syntax/tools/build-grammar.sh
scripts/editor/mac-sync.sh
ssh mac "cd ~/work/native-editor/apps/editor-syntax && nohup caffeinate -i native/build.sh gen > build/gen.log 2>&1; tail -5 build/gen.log; grep -c 'identical\|OK' build/gen.log"
```
Expected: every grammar reports the difftest OK. That was 47/47 in the prototype, and the multi-parser packages
(typescript+tsx, php+php_only, markdown+inline, ocaml×3, fsharp×2) each produce several `lang`s.
Any failure is a stop: report the grammar and the first differing line.

- [ ] **Step 5: Commit**

```bash
git add apps/editor-syntax/tools apps/editor-syntax/native/include/ses_grammar.h apps/editor-syntax/native/include/ses_registry.h apps/editor-syntax/native/tests apps/editor-syntax/native/build.sh
git commit -m "build(editor-syntax): table extraction pipeline with a differential test per grammar"
```

---

### Task 4: The C binding and the native libraries for every target

**Files:**
- Create: `apps/editor-syntax/native/include/supermux_syntax.h`, `apps/editor-syntax/native/src/syntax_bridge.c`, `apps/editor-syntax/native/src/ses_grammar.c`,
  `apps/editor-syntax/native/src/syntax_jni.c` (all verbatim)
- Modify: `apps/editor-syntax/native/build.sh` (add the target modes)

- [ ] **Step 1: Add the verified C sources**

`apps/editor-syntax/native/include/supermux_syntax.h`:
```c
/*
 * supermux editor-syntax: the owned C ABI over the tree-sitter C runtime (pinned v0.25.10) and the
 * compiled-in grammars (code) whose parse tables live in compressed blobs (data).
 *
 * The ONLY native surface the Kotlin bindings (JNI on Android/JVM, cinterop on iOS) call. Web uses
 * web-tree-sitter and never sees this.
 *
 * UNITS: every offset, length, column and range in this ABI is in UTF-16 code units. Documents are
 * parsed with TSInputEncodingUTF16LE, so tree-sitter's byte offset is exactly 2 x the UTF-16 index
 * and its point column is 2 x the UTF-16 column; the conversion (x2 / /2) happens here and nowhere
 * else. There are no byte-width tables anywhere.
 *
 * Text input: a pull callback (ses_read_fn) that returns a UTF-16 chunk starting at a UTF-16 index,
 * which maps 1:1 onto Rope.chunkAt(pos); or, for convenience, one contiguous UTF-16 buffer.
 *
 * Ownership: every *_new / parse returns an object the caller frees with the matching *_free.
 * Trees are immutable snapshots except for ses_tree_edit; ses_tree_copy is O(1) (refcounted).
 * Threading: a parser, a tree or a query cursor must not be used from two threads at once. A
 * ses_query is immutable after creation and may be shared. Language loading (first use of a
 * grammar: inflate the tables blob, fix up the TSLanguage) is thread-safe and happens once.
 */
#ifndef SUPERMUX_SYNTAX_H
#define SUPERMUX_SYNTAX_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(__GNUC__) || defined(__clang__)
#define SES_API __attribute__((visibility("default")))
#else
#define SES_API
#endif

/** ABI version implemented by this header. Bumped on any incompatible change. */
#define SES_ABI_VERSION 1u

typedef int32_t ses_status;
#define SES_OK 0
#define SES_ERR_INVALID_ARGUMENT (-3)
#define SES_ERR_OUT_OF_MEMORY (-4)
/** No compiled-in grammar has that name. */
#define SES_ERR_UNKNOWN_LANGUAGE (-8)
/** The grammar's code is compiled in but its tables blob is neither bundled nor provided yet. */
#define SES_ERR_NO_TABLES (-9)
/** A tables blob was refused: bad magic/format, wrong grammar hash, corrupt zlib, size mismatch. */
#define SES_ERR_BAD_TABLES (-10)
/** ses_query_new: the query does not compile; see err_offset / err_type. */
#define SES_ERR_QUERY (-11)
/** ts_parser_set_language refused the grammar (ABI outside 13..15). */
#define SES_ERR_INCOMPATIBLE_LANGUAGE (-12)
/** The parse hit the timeout (ses_parser_set_timeout_micros) or was cancelled. */
#define SES_ERR_TIMEOUT (-13)

typedef struct ses_parser ses_parser;
typedef struct ses_tree ses_tree;
typedef struct ses_query ses_query;

/**
 * Pull callback: return a pointer to UTF-16 code units of the document starting at UTF-16 [index]
 * and store how many units it holds in *out_len (0 = end of document). The pointer must stay valid
 * until the next call on the same parse (or the end of it). A chunk may end in the middle of a
 * surrogate pair; tree-sitter asks again from the pair's start.
 */
typedef const uint16_t *(*ses_read_fn)(void *ctx, uint32_t index, uint32_t *out_len);

SES_API uint32_t ses_abi_version(void);

/* ------------------------------------------------------------ languages --- */

/** Number of compiled-in grammars, and the name of the i-th (static storage, never freed). */
SES_API uint32_t ses_language_count(void);
SES_API const char *ses_language_name(uint32_t index);
/** 1 if [name]'s tables are available (bundled or provided), 0 if not, <0 unknown name. */
SES_API ses_status ses_language_has_tables(const char *name);
/**
 * Hand the runtime a .sesz tables blob for [name] (e.g. downloaded on demand). The bytes are copied
 * and checked against the grammar's compiled-in hash on first use. Returns SES_ERR_BAD_TABLES for a
 * header / hash mismatch without inflating.
 */
SES_API ses_status ses_language_provide_tables(const char *name, const uint8_t *sesz, size_t len);
/** Load [name] now (inflate + fix up); a parser's set_language does this lazily. Idempotent. */
SES_API ses_status ses_language_load(const char *name);

/* --------------------------------------------------------------- parser --- */

SES_API ses_parser *ses_parser_new(void);
SES_API void ses_parser_free(ses_parser *parser);
SES_API ses_status ses_parser_set_language(ses_parser *parser, const char *name);
/** 0 = no limit. A parse over the limit returns NULL with SES_ERR_TIMEOUT. */
SES_API void ses_parser_set_timeout_micros(ses_parser *parser, uint64_t micros);

/**
 * Parse the document read through [read]. [old_tree] (may be NULL) must already carry every edit
 * (ses_tree_edit) since it was produced; tree-sitter then reuses its unchanged subtrees. Returns a
 * new tree, or NULL with *out_status set.
 */
SES_API ses_tree *ses_parser_parse(ses_parser *parser, const ses_tree *old_tree, ses_read_fn read,
                                   void *ctx, ses_status *out_status);
/** Same over one contiguous UTF-16 buffer of [len] units. */
SES_API ses_tree *ses_parser_parse_utf16(ses_parser *parser, const ses_tree *old_tree,
                                         const uint16_t *text, uint32_t len, ses_status *out_status);

/* ----------------------------------------------------------------- tree --- */

SES_API ses_tree *ses_tree_copy(const ses_tree *tree);
SES_API void ses_tree_free(ses_tree *tree);
/**
 * Record one edit, all in UTF-16 units: [start, old_end) became [start, new_end). Rows are 0-based
 * lines; columns are UTF-16 units from the start of that line.
 */
SES_API void ses_tree_edit(ses_tree *tree, uint32_t start, uint32_t old_end, uint32_t new_end,
                           uint32_t start_row, uint32_t start_col, uint32_t old_end_row,
                           uint32_t old_end_col, uint32_t new_end_row, uint32_t new_end_col);
/** The root node's S-expression (UTF-8, NUL-terminated). Free with ses_free. For tests. */
SES_API char *ses_tree_root_sexp(const ses_tree *tree);
/** 1 if the tree contains an ERROR or MISSING node. */
SES_API int32_t ses_tree_has_error(const ses_tree *tree);
/**
 * Ranges whose syntax changed between [old_tree] (edited) and [new_tree] (its reparse), as a packed
 * [start, end]* UTF-16 array in *out (free with ses_free) of *out_count ints (2 per range).
 */
SES_API ses_status ses_tree_changed_ranges(const ses_tree *old_tree, const ses_tree *new_tree,
                                           int32_t **out, uint32_t *out_count);

/* ---------------------------------------------------------------- query --- */

/**
 * Compile [source] (UTF-8, [len] bytes) against the grammar [language]. On SES_ERR_QUERY,
 * *err_offset is the byte offset and *err_type tree-sitter's TSQueryError.
 */
SES_API ses_query *ses_query_new(const char *language, const char *source, uint32_t len,
                                 uint32_t *err_offset, int32_t *err_type, ses_status *out_status);
SES_API void ses_query_free(ses_query *query);
SES_API uint32_t ses_query_capture_count(const ses_query *query);
/** The capture's name (UTF-8, NOT NUL-terminated: *out_len bytes); valid while the query lives. */
SES_API const char *ses_query_capture_name(const ses_query *query, uint32_t index, uint32_t *out_len);
/** Bit 0: the query uses #match?-family predicates, which this ABI does NOT evaluate (they pass). */
SES_API uint32_t ses_query_flags(const ses_query *query);

/**
 * Run [query] over the nodes of [tree] that intersect UTF-16 [start, end), in tree-sitter capture
 * order. Text predicates (#eq? #not-eq? #any-eq? #any-not-eq? #any-of? #not-any-of?) are evaluated
 * against the document read through [read] (may be NULL when the query has none). Result: a packed
 * [start, end, captureIndex]* int array in *out (free with ses_free), *out_count ints (3 per capture).
 */
SES_API ses_status ses_query_captures(const ses_query *query, const ses_tree *tree, uint32_t start,
                                      uint32_t end, ses_read_fn read, void *ctx, int32_t **out,
                                      uint32_t *out_count);
SES_API ses_status ses_query_captures_utf16(const ses_query *query, const ses_tree *tree,
                                            uint32_t start, uint32_t end, const uint16_t *text,
                                            uint32_t len, int32_t **out, uint32_t *out_count);

/** Frees any buffer this ABI returned (sexp strings, int arrays). NULL is a no-op. */
SES_API void ses_free(void *ptr);

#ifdef __cplusplus
}
#endif
#endif
```

`apps/editor-syntax/native/src/ses_grammar.c`:
```c
/*
 * Grammar tables loader: inflates a grammar's .sesz blob on first use, validates it against what
 * the grammar's code was generated with, builds the string-pointer arrays and lets the grammar fill
 * its TSLanguage. Blob formats: tools/sestables.py. Loaded tables are never freed (like the static
 * tables they replace).
 */
#include <pthread.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <zlib.h>

#include "ses_grammar.h"
#include "ses_registry.h"
#include "supermux_syntax.h"

struct ses_tables {
  const uint8_t *payload;
  uint32_t count;
  const void **at; /* per table: bytes pointer, or the rebuilt const char *[] */
};

typedef struct provided {
  ses_grammar *grammar;
  uint8_t *bytes;
  size_t len;
  struct provided *next;
} provided;

static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;
static provided *g_provided; /* guarded by g_lock */

static uint32_t rd32(const uint8_t *p) { return (uint32_t)p[0] | (uint32_t)p[1] << 8 | (uint32_t)p[2] << 16 | (uint32_t)p[3] << 24; }
static uint16_t rd16(const uint8_t *p) { return (uint16_t)(p[0] | p[1] << 8); }
static uint64_t rd64(const uint8_t *p) { return (uint64_t)rd32(p) | (uint64_t)rd32(p + 4) << 32; }

#define SESZ_HEADER 24u
#define SEST_HEADER 32u

const void *ses_tables_at(const ses_tables *t, uint32_t index) {
  return index < t->count ? t->at[index] : NULL;
}

/* Header-only check (no inflate): magic, format, hash. */
static int32_t check_sesz(const ses_grammar *g, const uint8_t *z, size_t len) {
  if (len < SESZ_HEADER || memcmp(z, "SESZ", 4) != 0 || rd32(z + 4) != 1) return SES_ERR_BAD_TABLES;
  if (rd64(z + 16) != g->hash) return SES_ERR_BAD_TABLES;
  if ((size_t)rd32(z + 12) + SESZ_HEADER != len) return SES_ERR_BAD_TABLES;
  return SES_OK;
}

static int32_t load_locked(ses_grammar *g, const uint8_t *z, size_t len) {
  int32_t st = check_sesz(g, z, len);
  if (st) return st;
  uLongf raw_size = rd32(z + 8);
  uint8_t *raw = malloc(raw_size ? raw_size : 1);
  if (!raw) return SES_ERR_OUT_OF_MEMORY;
  uLongf got = raw_size;
  if (uncompress(raw, &got, z + SESZ_HEADER, (uLong)(len - SESZ_HEADER)) != Z_OK || got != raw_size) goto bad;
  if (raw_size < SEST_HEADER || memcmp(raw, "SEST", 4) != 0 || rd16(raw + 4) != 1) goto bad;
  uint32_t n = rd16(raw + 6);
  if (n != g->table_count || rd32(raw + 8) != g->abi || rd64(raw + 16) != g->hash || rd64(raw + 24) != raw_size) goto bad;
  if (SEST_HEADER + 16ull * n > raw_size) goto bad;

  size_t strings = 0;
  for (uint32_t i = 0; i < n; i++) {
    const uint8_t *e = raw + SEST_HEADER + 16 * i;
    uint32_t off = rd32(e), size = rd32(e + 4), count = rd32(e + 8), kind = rd16(e + 12);
    const ses_table_spec *s = &g->specs[i];
    if (kind != s->kind || size != s->size || count != s->count) goto bad;
    if ((off & 15) || (uint64_t)off + size > raw_size) goto bad;
    if (kind == SES_TABLE_STRINGS) strings += count;
  }
  ses_tables *t = malloc(sizeof *t + n * sizeof(void *) + strings * sizeof(char *));
  if (!t) { free(raw); return SES_ERR_OUT_OF_MEMORY; }
  t->payload = raw;
  t->count = n;
  t->at = (const void **)(t + 1);
  const char **pool_ptrs = (const char **)(t->at + n);
  for (uint32_t i = 0; i < n; i++) {
    const uint8_t *e = raw + SEST_HEADER + 16 * i;
    uint32_t off = rd32(e), size = rd32(e + 4), count = rd32(e + 8), kind = rd16(e + 12);
    if (kind == SES_TABLE_BYTES) { t->at[i] = raw + off; continue; }
    /* strings: u32 offsets[count], then the pool; every string must end inside the table */
    if ((uint64_t)count * 4 > size) { free(t); goto bad; }
    const char *pool = (const char *)raw + off + 4ull * count;
    size_t pool_size = size - 4ull * count;
    for (uint32_t k = 0; k < count; k++) {
      uint32_t so = rd32(raw + off + 4 * k);
      if (so == 0xFFFFFFFFu) { pool_ptrs[k] = NULL; continue; }
      if (so >= pool_size || !memchr(pool + so, 0, pool_size - so)) { free(t); goto bad; }
      pool_ptrs[k] = pool + so;
    }
    t->at[i] = pool_ptrs;
    pool_ptrs += count;
  }
  g->fill(t); /* t and raw live forever: the TSLanguage points into them */
  atomic_store_explicit(&g->loaded, g->language, memory_order_release);
  return SES_OK;
bad:
  free(raw);
  return SES_ERR_BAD_TABLES;
}

static provided *find_provided(ses_grammar *g) {
  for (provided *p = g_provided; p; p = p->next)
    if (p->grammar == g) return p;
  return NULL;
}

const ses_registry_entry *ses_registry_find(const char *name) {
  if (!name) return NULL;
  for (uint32_t i = 0; i < ses_registry_count; i++)
    if (strcmp(ses_registry[i].name, name) == 0) return &ses_registry[i];
  return NULL;
}

static const ses_registry_entry *entry_for(ses_grammar *g) {
  for (uint32_t i = 0; i < ses_registry_count; i++)
    if (ses_registry[i].grammar() == g) return &ses_registry[i];
  return NULL;
}

const void *ses_grammar_language(ses_grammar *g) {
  void *lang = atomic_load_explicit(&g->loaded, memory_order_acquire);
  if (lang) return lang;
  pthread_mutex_lock(&g_lock);
  lang = atomic_load_explicit(&g->loaded, memory_order_relaxed);
  if (!lang) {
    provided *p = find_provided(g);
    const ses_registry_entry *e = p ? NULL : entry_for(g);
    int32_t st;
    if (p) st = load_locked(g, p->bytes, p->len);
    else if (e && e->blob) st = load_locked(g, e->blob, *e->blob_size);
    else st = SES_ERR_NO_TABLES;
    g->status = st;
    lang = atomic_load_explicit(&g->loaded, memory_order_relaxed);
  }
  pthread_mutex_unlock(&g_lock);
  return lang;
}

int32_t ses_tables_provide(ses_grammar *g, const uint8_t *z, size_t len) {
  int32_t st = check_sesz(g, z, len);
  if (st) return st;
  uint8_t *copy = malloc(len);
  if (!copy) return SES_ERR_OUT_OF_MEMORY;
  memcpy(copy, z, len);
  pthread_mutex_lock(&g_lock);
  if (atomic_load_explicit(&g->loaded, memory_order_relaxed)) { /* already loaded: nothing to do */
    pthread_mutex_unlock(&g_lock);
    free(copy);
    return SES_OK;
  }
  provided *p = find_provided(g);
  if (p) { free(p->bytes); }
  else {
    p = calloc(1, sizeof *p);
    if (!p) { pthread_mutex_unlock(&g_lock); free(copy); return SES_ERR_OUT_OF_MEMORY; }
    p->grammar = g; p->next = g_provided; g_provided = p;
  }
  p->bytes = copy; p->len = len;
  pthread_mutex_unlock(&g_lock);
  return SES_OK;
}

int ses_tables_available(ses_grammar *g, const ses_registry_entry *e) {
  if (atomic_load_explicit(&g->loaded, memory_order_acquire)) return 1;
  if (e && e->blob) return 1;
  pthread_mutex_lock(&g_lock);
  int r = find_provided(g) != NULL;
  pthread_mutex_unlock(&g_lock);
  return r;
}

int32_t ses_grammar_status(ses_grammar *g) {
  pthread_mutex_lock(&g_lock);
  int32_t st = g->status;
  pthread_mutex_unlock(&g_lock);
  return st;
}
```

`apps/editor-syntax/native/src/syntax_bridge.c`:
```c
/*
 * supermux editor-syntax: the ses_* ABI (include/supermux_syntax.h) over tree-sitter v0.25.10.
 * Everything is UTF-16: tree-sitter parses TSInputEncodingUTF16LE, so byte = 2 x unit, and this
 * file is the only place that multiplies or divides by 2.
 */
#include <stdbool.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#include "tree_sitter/api.h"
#include "ses_registry.h"
#include "supermux_syntax.h"

struct ses_parser {
  TSParser *ts;
  uint64_t timeout_us;
};

/* A ses_tree IS a TSTree (no wrapper allocation): cast only here. */
#define TREE(t) ((TSTree *)(t))

enum { P_EQ, P_ANY_OF, P_MATCH };

typedef struct {
  const uint16_t *s;
  uint32_t len;
} u16str;

typedef struct {
  int op;
  bool positive;      /* eq? vs not-eq? */
  bool match_all;     /* eq? (every node) vs any-eq? (some node) */
  uint32_t capture;   /* left-hand capture id */
  int32_t other;      /* right-hand capture id, or -1 when comparing against values */
  u16str *values;
  uint32_t value_count;
} predicate;

struct ses_query {
  TSQuery *ts;
  const TSLanguage *lang;
  uint32_t flags;
  uint32_t pattern_count;
  uint32_t *pred_start; /* pattern_count + 1 offsets into preds */
  predicate *preds;
  uint16_t *u16_pool;
  u16str *vals;
};

uint32_t ses_abi_version(void) { return SES_ABI_VERSION; }
void ses_free(void *p) { free(p); }

/* ---------------------------------------------------------------- languages --- */

uint32_t ses_language_count(void) { return ses_registry_count; }

const char *ses_language_name(uint32_t i) { return i < ses_registry_count ? ses_registry[i].name : NULL; }

ses_status ses_language_has_tables(const char *name) {
  const ses_registry_entry *e = ses_registry_find(name);
  if (!e) return SES_ERR_UNKNOWN_LANGUAGE;
  return ses_tables_available(e->grammar(), e);
}

ses_status ses_language_provide_tables(const char *name, const uint8_t *sesz, size_t len) {
  const ses_registry_entry *e = ses_registry_find(name);
  if (!e) return SES_ERR_UNKNOWN_LANGUAGE;
  if (!sesz) return SES_ERR_INVALID_ARGUMENT;
  return ses_tables_provide(e->grammar(), sesz, len);
}

static const TSLanguage *language_for(const char *name, ses_status *st) {
  const ses_registry_entry *e = ses_registry_find(name);
  if (!e) { *st = SES_ERR_UNKNOWN_LANGUAGE; return NULL; }
  ses_grammar *g = e->grammar();
  const TSLanguage *l = ses_grammar_language(g);
  *st = l ? SES_OK : (ses_grammar_status(g) ? ses_grammar_status(g) : SES_ERR_NO_TABLES);
  return l;
}

ses_status ses_language_load(const char *name) {
  ses_status st;
  language_for(name, &st);
  return st;
}

/* ------------------------------------------------------------------ parser --- */

ses_parser *ses_parser_new(void) {
  ses_parser *p = calloc(1, sizeof *p);
  if (!p) return NULL;
  p->ts = ts_parser_new();
  if (!p->ts) { free(p); return NULL; }
  return p;
}

void ses_parser_free(ses_parser *p) {
  if (!p) return;
  ts_parser_delete(p->ts);
  free(p);
}

ses_status ses_parser_set_language(ses_parser *p, const char *name) {
  if (!p) return SES_ERR_INVALID_ARGUMENT;
  ses_status st;
  const TSLanguage *l = language_for(name, &st);
  if (!l) return st;
  return ts_parser_set_language(p->ts, l) ? SES_OK : SES_ERR_INCOMPATIBLE_LANGUAGE;
}

void ses_parser_set_timeout_micros(ses_parser *p, uint64_t micros) { if (p) p->timeout_us = micros; }

typedef struct {
  ses_read_fn fn;
  void *ctx;
} reader;

static const char *ts_read(void *payload, uint32_t byte, TSPoint pt, uint32_t *bytes_read) {
  (void)pt;
  reader *r = payload;
  uint32_t n = 0;
  const uint16_t *c = r->fn(r->ctx, byte / 2, &n);
  *bytes_read = c ? n * 2 : 0;
  return c ? (const char *)c : "";
}

typedef struct {
  const uint16_t *text;
  uint32_t len;
} buffer;

static const uint16_t *buffer_read(void *ctx, uint32_t index, uint32_t *out_len) {
  buffer *b = ctx;
  if (index >= b->len) { *out_len = 0; return NULL; }
  *out_len = b->len - index;
  return b->text + index;
}

static uint64_t now_us(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (uint64_t)ts.tv_sec * 1000000u + (uint64_t)ts.tv_nsec / 1000u;
}

typedef struct {
  uint64_t deadline_us;
} progress;

static bool on_progress(TSParseState *s) {
  progress *pr = s->payload;
  return pr->deadline_us && now_us() > pr->deadline_us; /* true = cancel */
}

ses_tree *ses_parser_parse(ses_parser *p, const ses_tree *old, ses_read_fn fn, void *ctx, ses_status *st) {
  ses_status dummy;
  if (!st) st = &dummy;
  if (!p || !fn) { *st = SES_ERR_INVALID_ARGUMENT; return NULL; }
  if (!ts_parser_language(p->ts)) { *st = SES_ERR_UNKNOWN_LANGUAGE; return NULL; }
  reader r = {fn, ctx};
  TSInput in = {.payload = &r, .read = ts_read, .encoding = TSInputEncodingUTF16LE, .decode = NULL};
  progress pr = {p->timeout_us ? now_us() + p->timeout_us : 0};
  TSParseOptions opt = {.payload = &pr, .progress_callback = p->timeout_us ? on_progress : NULL};
  TSTree *t = ts_parser_parse_with_options(p->ts, old ? TREE(old) : NULL, in, opt);
  if (!t) {
    ts_parser_reset(p->ts); /* a cancelled parse would otherwise resume on the next call */
    *st = SES_ERR_TIMEOUT;
    return NULL;
  }
  *st = SES_OK;
  return (ses_tree *)t;
}

ses_tree *ses_parser_parse_utf16(ses_parser *p, const ses_tree *old, const uint16_t *text, uint32_t len,
                                 ses_status *st) {
  buffer b = {text, len};
  return ses_parser_parse(p, old, buffer_read, &b, st);
}

/* -------------------------------------------------------------------- tree --- */

ses_tree *ses_tree_copy(const ses_tree *t) { return t ? (ses_tree *)ts_tree_copy(TREE(t)) : NULL; }
void ses_tree_free(ses_tree *t) { if (t) ts_tree_delete(TREE(t)); }

void ses_tree_edit(ses_tree *t, uint32_t start, uint32_t old_end, uint32_t new_end, uint32_t sr, uint32_t sc,
                   uint32_t oer, uint32_t oec, uint32_t ner, uint32_t nec) {
  if (!t) return;
  TSInputEdit e = {
      .start_byte = start * 2, .old_end_byte = old_end * 2, .new_end_byte = new_end * 2,
      .start_point = {sr, sc * 2}, .old_end_point = {oer, oec * 2}, .new_end_point = {ner, nec * 2},
  };
  ts_tree_edit(TREE(t), &e);
}

char *ses_tree_root_sexp(const ses_tree *t) {
  if (!t) return NULL;
  return ts_node_string(ts_tree_root_node(TREE(t))); /* malloc'd by tree-sitter's default allocator */
}

int32_t ses_tree_has_error(const ses_tree *t) { return t ? ts_node_has_error(ts_tree_root_node(TREE(t))) : 0; }

ses_status ses_tree_changed_ranges(const ses_tree *old, const ses_tree *new_tree, int32_t **out, uint32_t *count) {
  if (!old || !new_tree || !out || !count) return SES_ERR_INVALID_ARGUMENT;
  uint32_t n = 0;
  TSRange *r = ts_tree_get_changed_ranges(TREE(old), TREE(new_tree), &n);
  int32_t *a = malloc((n ? n : 1) * 2 * sizeof(int32_t));
  if (!a) { free(r); return SES_ERR_OUT_OF_MEMORY; }
  for (uint32_t i = 0; i < n; i++) { a[2 * i] = (int32_t)(r[i].start_byte / 2); a[2 * i + 1] = (int32_t)(r[i].end_byte / 2); }
  free(r);
  *out = a;
  *count = n * 2;
  return SES_OK;
}

/* ------------------------------------------------------------------- query --- */

/* UTF-8 -> UTF-16, appended to a growing pool (the pool is fixed once the query is built). */
static uint32_t utf8_to_utf16(const char *s, uint32_t len, uint16_t *out) {
  uint32_t n = 0;
  for (uint32_t i = 0; i < len;) {
    uint32_t c = (unsigned char)s[i], k = c < 0x80 ? 1 : c < 0xE0 ? 2 : c < 0xF0 ? 3 : 4;
    if (k == 2) c &= 0x1F; else if (k == 3) c &= 0x0F; else if (k == 4) c &= 0x07;
    for (uint32_t j = 1; j < k && i + j < len; j++) c = (c << 6) | ((unsigned char)s[i + j] & 0x3F);
    i += k;
    if (c >= 0x10000) { c -= 0x10000; out[n++] = (uint16_t)(0xD800 | (c >> 10)); out[n++] = (uint16_t)(0xDC00 | (c & 0x3FF)); }
    else out[n++] = (uint16_t)c;
  }
  return n;
}

static bool is(const char *a, uint32_t alen, const char *b) { return strlen(b) == alen && memcmp(a, b, alen) == 0; }

static ses_status build_predicates(ses_query *q) {
  TSQuery *tq = q->ts;
  uint32_t np = ts_query_pattern_count(tq), total = 0, u16_total = 0;
  for (uint32_t i = 0; i < np; i++) {
    uint32_t steps;
    const TSQueryPredicateStep *s = ts_query_predicates_for_pattern(tq, i, &steps);
    for (uint32_t k = 0; k < steps; k++) {
      if (s[k].type == TSQueryPredicateStepTypeDone) total++;
      if (s[k].type == TSQueryPredicateStepTypeString) {
        uint32_t l; ts_query_string_value_for_id(tq, s[k].value_id, &l); u16_total += l; /* utf16 <= utf8 bytes */
      }
    }
  }
  q->pattern_count = np;
  q->pred_start = calloc(np + 1, sizeof(uint32_t));
  q->preds = calloc(total ? total : 1, sizeof(predicate));
  q->u16_pool = malloc((u16_total ? u16_total : 1) * sizeof(uint16_t));
  u16str *vals = calloc(total + u16_total + 1, sizeof(u16str));
  q->vals = vals;
  if (!q->pred_start || !q->preds || !q->u16_pool || !vals) return SES_ERR_OUT_OF_MEMORY;
  uint32_t pc = 0, pool = 0, vc = 0;
  for (uint32_t i = 0; i < np; i++) {
    q->pred_start[i] = pc;
    uint32_t steps;
    const TSQueryPredicateStep *s = ts_query_predicates_for_pattern(tq, i, &steps);
    for (uint32_t k = 0; k < steps;) {
      uint32_t e = k;
      while (e < steps && s[e].type != TSQueryPredicateStepTypeDone) e++;
      uint32_t nl;
      const char *name = s[k].type == TSQueryPredicateStepTypeString ? ts_query_string_value_for_id(tq, s[k].value_id, &nl) : "";
      if (s[k].type != TSQueryPredicateStepTypeString) nl = 0;
      predicate pr = {0};
      pr.other = -1;
      bool keep = false;
      bool eq = is(name, nl, "eq?"), neq = is(name, nl, "not-eq?"), aeq = is(name, nl, "any-eq?"), aneq = is(name, nl, "any-not-eq?");
      bool anyof = is(name, nl, "any-of?"), nanyof = is(name, nl, "not-any-of?");
      bool match = is(name, nl, "match?") || is(name, nl, "not-match?") || is(name, nl, "any-match?") ||
                   is(name, nl, "any-not-match?") || is(name, nl, "lua-match?");
      if ((eq || neq || aeq || aneq) && e - k == 3 && s[k + 1].type == TSQueryPredicateStepTypeCapture) {
        pr.op = P_EQ; pr.positive = eq || aeq; pr.match_all = eq || neq; pr.capture = s[k + 1].value_id;
        if (s[k + 2].type == TSQueryPredicateStepTypeCapture) pr.other = (int32_t)s[k + 2].value_id;
        else {
          uint32_t l; const char *v = ts_query_string_value_for_id(tq, s[k + 2].value_id, &l);
          vals[vc].s = q->u16_pool + pool; vals[vc].len = utf8_to_utf16(v, l, q->u16_pool + pool); pool += vals[vc].len;
          pr.values = &vals[vc++]; pr.value_count = 1;
        }
        keep = true;
      } else if ((anyof || nanyof) && e - k >= 2 && s[k + 1].type == TSQueryPredicateStepTypeCapture) {
        pr.op = P_ANY_OF; pr.positive = anyof; pr.capture = s[k + 1].value_id; pr.values = &vals[vc];
        for (uint32_t j = k + 2; j < e; j++) {
          uint32_t l; const char *v = ts_query_string_value_for_id(tq, s[j].value_id, &l);
          vals[vc].s = q->u16_pool + pool; vals[vc].len = utf8_to_utf16(v, l, q->u16_pool + pool); pool += vals[vc].len; vc++;
        }
        pr.value_count = e - k - 2;
        keep = true;
      } else if (match) {
        q->flags |= 1u; /* not evaluated: see ses_query_flags */
      } /* directives (#set! #is? #offset! ...) and unknown predicates: ignored, like a filter-less engine */
      if (keep) q->preds[pc++] = pr;
      k = e + 1;
    }
  }
  q->pred_start[np] = pc;
  return SES_OK;
}

ses_query *ses_query_new(const char *language, const char *src, uint32_t len, uint32_t *err_offset,
                         int32_t *err_type, ses_status *st) {
  ses_status dummy;
  if (!st) st = &dummy;
  const TSLanguage *l = language_for(language, st);
  if (!l) return NULL;
  uint32_t off = 0;
  TSQueryError et = TSQueryErrorNone;
  TSQuery *tq = ts_query_new(l, src, len, &off, &et);
  if (err_offset) *err_offset = off;
  if (err_type) *err_type = (int32_t)et;
  if (!tq) { *st = SES_ERR_QUERY; return NULL; }
  ses_query *q = calloc(1, sizeof *q);
  if (!q) { ts_query_delete(tq); *st = SES_ERR_OUT_OF_MEMORY; return NULL; }
  q->ts = tq;
  q->lang = l;
  if ((*st = build_predicates(q)) != SES_OK) { ses_query_free(q); return NULL; }
  return q;
}

void ses_query_free(ses_query *q) {
  if (!q) return;
  ts_query_delete(q->ts);
  free(q->pred_start);
  free(q->preds);
  free(q->vals);
  free(q->u16_pool);
  free(q);
}

uint32_t ses_query_capture_count(const ses_query *q) { return q ? ts_query_capture_count(q->ts) : 0; }

const char *ses_query_capture_name(const ses_query *q, uint32_t i, uint32_t *len) {
  if (!q || i >= ts_query_capture_count(q->ts)) { if (len) *len = 0; return NULL; }
  uint32_t l;
  const char *n = ts_query_capture_name_for_id(q->ts, i, &l);
  if (len) *len = l;
  return n;
}

uint32_t ses_query_flags(const ses_query *q) { return q ? q->flags : 0; }

/* Text of UTF-16 [s, e) through the reader into a growing scratch buffer. */
typedef struct {
  reader r;
  uint16_t *buf;
  uint32_t cap;
} texter;

static bool text_of(texter *tx, uint32_t s, uint32_t e, u16str *out) {
  uint32_t n = e - s;
  if (n > tx->cap) {
    uint16_t *b = realloc(tx->buf, n * sizeof(uint16_t));
    if (!b) return false;
    tx->buf = b; tx->cap = n;
  }
  uint32_t got = 0;
  while (got < n) {
    uint32_t cl = 0;
    const uint16_t *c = tx->r.fn(tx->r.ctx, s + got, &cl);
    if (!c || cl == 0) break;
    uint32_t take = cl < n - got ? cl : n - got;
    memcpy(tx->buf + got, c, take * sizeof(uint16_t));
    got += take;
  }
  out->s = tx->buf; out->len = got;
  return true;
}

static bool u16eq(u16str a, u16str b) { return a.len == b.len && memcmp(a.s, b.s, a.len * 2) == 0; }

static bool node_text_is(texter *tx, TSNode n, u16str v) {
  uint32_t s = ts_node_start_byte(n) / 2, e = ts_node_end_byte(n) / 2;
  if (e - s != v.len) return false;
  u16str t;
  return text_of(tx, s, e, &t) && u16eq(t, v);
}

static bool predicates_pass(const ses_query *q, const TSQueryMatch *m, texter *tx) {
  for (uint32_t pi = q->pred_start[m->pattern_index]; pi < q->pred_start[m->pattern_index + 1]; pi++) {
    const predicate *p = &q->preds[pi];
    if (!tx->r.fn) continue; /* no text: cannot evaluate (documented) */
    bool any = false, all = true, seen = false;
    for (uint16_t c = 0; c < m->capture_count; c++) {
      if (m->captures[c].index != p->capture) continue;
      seen = true;
      TSNode n = m->captures[c].node;
      bool ok;
      if (p->op == P_EQ && p->other >= 0) {
        ok = true;
        for (uint16_t d = 0; d < m->capture_count; d++) {
          if (m->captures[d].index != (uint32_t)p->other) continue;
          TSNode o = m->captures[d].node;
          uint32_t os = ts_node_start_byte(o) / 2, oe = ts_node_end_byte(o) / 2;
          /* copy the other node's text first: text_of reuses one scratch buffer */
          u16str t; uint16_t small[256]; uint16_t *heap = NULL;
          if (!text_of(tx, os, oe, &t)) return false;
          uint16_t *keep = t.len <= 256 ? small : (heap = malloc(t.len * 2));
          if (!keep) return false;
          memcpy(keep, t.s, t.len * 2);
          u16str ov = {keep, t.len};
          ok = node_text_is(tx, n, ov);
          free(heap);
          break;
        }
        ok = ok == p->positive;
      } else if (p->op == P_EQ) {
        ok = node_text_is(tx, n, p->values[0]) == p->positive;
      } else {
        bool in = false;
        for (uint32_t v = 0; v < p->value_count && !in; v++) in = node_text_is(tx, n, p->values[v]);
        ok = in == p->positive;
      }
      any = any || ok; all = all && ok;
    }
    bool pass = p->op == P_ANY_OF ? all : (p->match_all ? all : (seen && any));
    if (!pass) return false;
  }
  return true;
}

ses_status ses_query_captures(const ses_query *q, const ses_tree *t, uint32_t start, uint32_t end, ses_read_fn fn,
                              void *ctx, int32_t **out, uint32_t *count) {
  if (!q || !t || !out || !count || end < start) return SES_ERR_INVALID_ARGUMENT;
  if (ts_tree_language(TREE(t)) != q->lang) return SES_ERR_INVALID_ARGUMENT;
  TSQueryCursor *cur = ts_query_cursor_new();
  if (!cur) return SES_ERR_OUT_OF_MEMORY;
  ts_query_cursor_set_byte_range(cur, start * 2, end * 2);
  ts_query_cursor_exec(cur, q->ts, ts_tree_root_node(TREE(t)));
  uint32_t cap = 3 * 64, n = 0;
  int32_t *a = malloc(cap * sizeof(int32_t));
  texter tx = {{fn, ctx}, NULL, 0};
  ses_status st = a ? SES_OK : SES_ERR_OUT_OF_MEMORY;
  TSQueryMatch m;
  uint32_t ci;
  while (st == SES_OK && ts_query_cursor_next_capture(cur, &m, &ci)) {
    if (q->pred_start[m.pattern_index] != q->pred_start[m.pattern_index + 1] && !predicates_pass(q, &m, &tx)) {
      ts_query_cursor_remove_match(cur, m.id);
      continue;
    }
    TSNode node = m.captures[ci].node;
    if (n + 3 > cap) {
      int32_t *b = realloc(a, (cap *= 2) * sizeof(int32_t));
      if (!b) { st = SES_ERR_OUT_OF_MEMORY; break; }
      a = b;
    }
    a[n++] = (int32_t)(ts_node_start_byte(node) / 2);
    a[n++] = (int32_t)(ts_node_end_byte(node) / 2);
    a[n++] = (int32_t)m.captures[ci].index;
  }
  free(tx.buf);
  ts_query_cursor_delete(cur);
  if (st != SES_OK) { free(a); return st; }
  *out = a;
  *count = n;
  return SES_OK;
}

ses_status ses_query_captures_utf16(const ses_query *q, const ses_tree *t, uint32_t start, uint32_t end,
                                    const uint16_t *text, uint32_t len, int32_t **out, uint32_t *count) {
  buffer b = {text, len};
  return ses_query_captures(q, t, start, end, text ? buffer_read : NULL, &b, out, count);
}
```

`apps/editor-syntax/native/src/syntax_jni.c`:
```c
/*
 * supermux editor-syntax: JNI glue for Android and the desktop JVM, bound to
 *   internal object dev.supermux.editor.syntax.Ses   (@JvmStatic external fun ...)
 * One thin layer over the ses_* ABI. Pointers cross as jlong. Text crosses as Java Strings, which
 * are already UTF-16: GetStringChars hands tree-sitter the code units directly (no UTF-8, no
 * modified UTF-8, no offset tables). Pull reads call back into Kotlin (TextSourceJni.chunk(int)),
 * one local ref per chunk, released before the next upcall.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

#include "supermux_syntax.h"

#define SES_JNI(ret, name) JNIEXPORT ret JNICALL Java_dev_supermux_editor_syntax_Ses_##name
#define P(x) ((void *)(intptr_t)(x))
#define J(x) ((jlong)(intptr_t)(x))

static void throw_new(JNIEnv *env, const char *cls, const char *msg) {
  if ((*env)->ExceptionCheck(env)) return;
  jclass c = (*env)->FindClass(env, cls);
  if (c) { (*env)->ThrowNew(env, c, msg); (*env)->DeleteLocalRef(env, c); }
}

/* Modified-UTF-8 from GetStringUTFChars is fine for language NAMES (ASCII identifiers only). */
static const char *name_chars(JNIEnv *env, jstring s) {
  return s ? (*env)->GetStringUTFChars(env, s, NULL) : NULL;
}

static jintArray to_int_array(JNIEnv *env, const int32_t *a, uint32_t n) {
  jintArray r = (*env)->NewIntArray(env, (jsize)n);
  if (r && n) (*env)->SetIntArrayRegion(env, r, 0, (jsize)n, (const jint *)a);
  return r;
}

/* ---------------------------------------------------------- pull reader --- */

typedef struct {
  JNIEnv *env;
  jobject source;  /* TextSourceJni */
  jmethodID chunk; /* String chunk(int) */
  jstring cur;
  const jchar *chars;
  int failed;
} jreader;

static void jreader_release(jreader *r) {
  if (r->chars) (*r->env)->ReleaseStringChars(r->env, r->cur, r->chars);
  if (r->cur) (*r->env)->DeleteLocalRef(r->env, r->cur);
  r->chars = NULL;
  r->cur = NULL;
}

static const uint16_t *jreader_read(void *ctx, uint32_t index, uint32_t *out_len) {
  jreader *r = ctx;
  JNIEnv *env = r->env;
  jreader_release(r);
  *out_len = 0;
  if (r->failed) return NULL;
  jstring s = (*env)->CallObjectMethod(env, r->source, r->chunk, (jint)index);
  if ((*env)->ExceptionCheck(env)) { r->failed = 1; return NULL; }
  if (!s) return NULL;
  r->cur = s;
  jsize n = (*env)->GetStringLength(env, s);
  if (n == 0) return NULL;
  r->chars = (*env)->GetStringChars(env, s, NULL);
  if (!r->chars) { r->failed = 1; return NULL; }
  *out_len = (uint32_t)n;
  return (const uint16_t *)r->chars; /* jchar is uint16_t UTF-16 */
}

static int jreader_init(JNIEnv *env, jreader *r, jobject source) {
  memset(r, 0, sizeof *r);
  r->env = env;
  r->source = source;
  if (!source) return 0;
  jclass c = (*env)->GetObjectClass(env, source);
  r->chunk = (*env)->GetMethodID(env, c, "chunk", "(I)Ljava/lang/String;");
  (*env)->DeleteLocalRef(env, c);
  return r->chunk ? 0 : -1;
}

/* ------------------------------------------------------------- languages --- */

SES_JNI(jint, abiVersion)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return (jint)ses_abi_version(); }
SES_JNI(jint, languageCount)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return (jint)ses_language_count(); }

SES_JNI(jstring, languageName)(JNIEnv *env, jclass cls, jint i) {
  (void)cls;
  const char *n = ses_language_name((uint32_t)i);
  return n ? (*env)->NewStringUTF(env, n) : NULL;
}

SES_JNI(jint, languageHasTables)(JNIEnv *env, jclass cls, jstring name) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n) return SES_ERR_INVALID_ARGUMENT;
  jint r = ses_language_has_tables(n);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

SES_JNI(jint, provideTables)(JNIEnv *env, jclass cls, jstring name, jbyteArray bytes) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n || !bytes) return SES_ERR_INVALID_ARGUMENT;
  jsize len = (*env)->GetArrayLength(env, bytes);
  jbyte *b = (*env)->GetByteArrayElements(env, bytes, NULL);
  jint r = b ? ses_language_provide_tables(n, (const uint8_t *)b, (size_t)len) : SES_ERR_OUT_OF_MEMORY;
  if (b) (*env)->ReleaseByteArrayElements(env, bytes, b, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

SES_JNI(jint, languageLoad)(JNIEnv *env, jclass cls, jstring name) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n) return SES_ERR_INVALID_ARGUMENT;
  jint r = ses_language_load(n);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

/* ---------------------------------------------------------------- parser --- */

SES_JNI(jlong, parserNew)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return J(ses_parser_new()); }
SES_JNI(void, parserFree)(JNIEnv *env, jclass cls, jlong p) { (void)env; (void)cls; ses_parser_free(P(p)); }

SES_JNI(jint, parserSetLanguage)(JNIEnv *env, jclass cls, jlong p, jstring name) {
  (void)cls;
  const char *n = name_chars(env, name);
  if (!n) return SES_ERR_INVALID_ARGUMENT;
  jint r = ses_parser_set_language(P(p), n);
  (*env)->ReleaseStringUTFChars(env, name, n);
  return r;
}

SES_JNI(void, parserSetTimeoutMicros)(JNIEnv *env, jclass cls, jlong p, jlong us) {
  (void)env; (void)cls;
  ses_parser_set_timeout_micros(P(p), (uint64_t)us);
}

/* Returns the tree (0 on failure, status in status[0]). */
SES_JNI(jlong, parse)(JNIEnv *env, jclass cls, jlong p, jlong old, jobject source, jintArray status) {
  (void)cls;
  jreader r;
  if (jreader_init(env, &r, source) != 0) return 0; /* NoSuchMethodError pending */
  ses_status st = SES_OK;
  ses_tree *t = ses_parser_parse(P(p), P(old), jreader_read, &r, &st);
  jreader_release(&r);
  if ((*env)->ExceptionCheck(env)) { ses_tree_free(t); return 0; } /* the source threw: propagate */
  jint s = st;
  (*env)->SetIntArrayRegion(env, status, 0, 1, &s);
  return J(t);
}

SES_JNI(jlong, parseString)(JNIEnv *env, jclass cls, jlong p, jlong old, jstring text, jintArray status) {
  (void)cls;
  jsize n = (*env)->GetStringLength(env, text);
  const jchar *c = (*env)->GetStringChars(env, text, NULL);
  if (!c) return 0;
  ses_status st = SES_OK;
  ses_tree *t = ses_parser_parse_utf16(P(p), P(old), (const uint16_t *)c, (uint32_t)n, &st);
  (*env)->ReleaseStringChars(env, text, c);
  jint s = st;
  (*env)->SetIntArrayRegion(env, status, 0, 1, &s);
  return J(t);
}

/* ------------------------------------------------------------------ tree --- */

SES_JNI(jlong, treeCopy)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; return J(ses_tree_copy(P(t))); }
SES_JNI(void, treeFree)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; ses_tree_free(P(t)); }

SES_JNI(void, treeEdit)(JNIEnv *env, jclass cls, jlong t, jint start, jint oldEnd, jint newEnd, jint sr, jint sc,
                        jint oer, jint oec, jint ner, jint nec) {
  (void)env; (void)cls;
  ses_tree_edit(P(t), (uint32_t)start, (uint32_t)oldEnd, (uint32_t)newEnd, (uint32_t)sr, (uint32_t)sc,
                (uint32_t)oer, (uint32_t)oec, (uint32_t)ner, (uint32_t)nec);
}

SES_JNI(jstring, treeSexp)(JNIEnv *env, jclass cls, jlong t) {
  (void)cls;
  char *s = ses_tree_root_sexp(P(t));
  if (!s) return NULL;
  jstring r = (*env)->NewStringUTF(env, s); /* node type names are ASCII */
  ses_free(s);
  return r;
}

SES_JNI(jboolean, treeHasError)(JNIEnv *env, jclass cls, jlong t) { (void)env; (void)cls; return ses_tree_has_error(P(t)) ? JNI_TRUE : JNI_FALSE; }

SES_JNI(jintArray, treeChangedRanges)(JNIEnv *env, jclass cls, jlong old, jlong nw) {
  (void)cls;
  int32_t *a = NULL;
  uint32_t n = 0;
  ses_status st = ses_tree_changed_ranges(P(old), P(nw), &a, &n);
  if (st) { throw_new(env, "java/lang/IllegalStateException", "ses_tree_changed_ranges failed"); return NULL; }
  jintArray r = to_int_array(env, a, n);
  ses_free(a);
  return r;
}

/* ----------------------------------------------------------------- query --- */

/* err[0] = status, err[1] = error byte offset, err[2] = TSQueryError. */
SES_JNI(jlong, queryNew)(JNIEnv *env, jclass cls, jstring language, jbyteArray utf8, jintArray err) {
  (void)cls;
  if (!utf8) return 0;
  const char *n = name_chars(env, language);
  if (!n) return 0;
  jsize len = (*env)->GetArrayLength(env, utf8);
  jbyte *b = (*env)->GetByteArrayElements(env, utf8, NULL);
  uint32_t off = 0;
  int32_t type = 0;
  ses_status st = SES_ERR_OUT_OF_MEMORY;
  ses_query *q = b ? ses_query_new(n, (const char *)b, (uint32_t)len, &off, &type, &st) : NULL;
  if (b) (*env)->ReleaseByteArrayElements(env, utf8, b, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, language, n);
  jint e[3] = {st, (jint)off, type};
  (*env)->SetIntArrayRegion(env, err, 0, 3, e);
  return J(q);
}

SES_JNI(void, queryFree)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; ses_query_free(P(q)); }
SES_JNI(jint, queryCaptureCount)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; return (jint)ses_query_capture_count(P(q)); }
SES_JNI(jint, queryFlags)(JNIEnv *env, jclass cls, jlong q) { (void)env; (void)cls; return (jint)ses_query_flags(P(q)); }

/* Capture names as UTF-8 bytes (a capture name may in theory be non-ASCII; NewStringUTF would mangle it). */
SES_JNI(jbyteArray, queryCaptureName)(JNIEnv *env, jclass cls, jlong q, jint i) {
  (void)cls;
  uint32_t len = 0;
  const char *s = ses_query_capture_name(P(q), (uint32_t)i, &len);
  if (!s) return NULL;
  jbyteArray r = (*env)->NewByteArray(env, (jsize)len);
  if (r) (*env)->SetByteArrayRegion(env, r, 0, (jsize)len, (const jbyte *)s);
  return r;
}

/* [start, end, captureIndex]* in UTF-16 units; source (nullable) feeds the text predicates. */
SES_JNI(jintArray, queryCaptures)(JNIEnv *env, jclass cls, jlong q, jlong t, jint start, jint end, jobject source) {
  (void)cls;
  jreader r;
  if (jreader_init(env, &r, source) != 0) return NULL;
  int32_t *a = NULL;
  uint32_t n = 0;
  ses_status st = ses_query_captures(P(q), P(t), (uint32_t)start, (uint32_t)end, source ? jreader_read : NULL, &r, &a, &n);
  jreader_release(&r);
  if ((*env)->ExceptionCheck(env)) { ses_free(a); return NULL; }
  if (st) { throw_new(env, "java/lang/IllegalArgumentException", "ses_query_captures failed"); return NULL; }
  jintArray res = to_int_array(env, a, n);
  ses_free(a);
  return res;
}
```


- [ ] **Step 2: Add the target modes to `build.sh`**

This is the prototype's target builder, verified for `macos-arm64`, `ios-arm64`, `ios-simulator-arm64`,
`android-arm64` and `android-x64`:
```bash
#!/usr/bin/env bash
# native/build.sh <target>: libsupermux_syntax for one target, from tree-sitter v0.25.10 (lib.c),
# the ses_* bridge, and every grammar in native/grammars.txt (transformed parser_<lang>.c + scanner,
# plus blob_<lang>.c when bundled). Output: build/<target>/lib/.
#   macos-arm64          libsupermux_syntax_jni.dylib (ses_* + JNI; JVM tests)
#   ios-arm64            libsupermux_syntax.a        (cinterop)
#   ios-simulator-arm64  libsupermux_syntax.a
#   android-arm64        libsupermux_syntax_jni.so   (arm64-v8a)
#   android-x64          libsupermux_syntax_jni.so   (x86_64)
# Prerequisite: tools/build-grammar.sh ran for each grammar (out/<lang>/parser_<lang>.c, blob_<lang>.c).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; T="$1"
TS="$ROOT/tree-sitter/lib"; OUT="$ROOT/build/$T"; OBJ="$OUT/obj"; rm -rf "$OUT"; mkdir -p "$OBJ" "$OUT/lib"
NDK="${ANDROID_NDK_HOME:-$HOME/devtools/android-sdk/ndk/26.1.10909125}"
NDKBIN="$NDK/toolchains/llvm/prebuilt/darwin-x86_64/bin"
JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"
case "$T" in
  macos-arm64) CC=(clang -arch arm64 -mmacosx-version-min=12.0); JNI=1 ;;
  ios-arm64) CC=(xcrun --sdk iphoneos clang -target arm64-apple-ios15.0); JNI=0 ;;
  ios-simulator-arm64) CC=(xcrun --sdk iphonesimulator clang -target arm64-apple-ios15.0-simulator); JNI=0 ;;
  android-arm64) CC=("$NDKBIN/aarch64-linux-android26-clang"); JNI=1 ;;
  android-x64) CC=("$NDKBIN/x86_64-linux-android26-clang"); JNI=1 ;;
  *) echo "unknown target $T" >&2; exit 2 ;;
esac
CXX=("${CC[@]}"); CXX[0]="${CC[0]/%clang/clang++}"; [ "${CC[0]}" = xcrun ] && CXX=("${CC[@]}")
FLAGS=(-Os -fPIC -w -std=gnu11 -ffunction-sections -fdata-sections -fvisibility=hidden -DTREE_SITTER_HIDE_SYMBOLS)
objs=()
cc() { local out="$OBJ/$1"; shift; "${CC[@]}" "${FLAGS[@]}" "$@" -c -o "$out"; objs+=("$out"); }
cc lib.o -I"$TS/include" -I"$TS/src" "$TS/src/lib.c"
cc bridge.o -I"$TS/include" -I"$ROOT/native/include" "$ROOT/native/src/syntax_bridge.c"
cc loader.o -I"$ROOT/native/include" "$ROOT/native/src/ses_grammar.c"
[ "$JNI" = 1 ] && { JI=(-I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin"); [[ "$T" == android-* ]] && JI=();
  cc jni.o "${JI[@]+"${JI[@]}"}" -I"$ROOT/native/include" "$ROOT/native/src/syntax_jni.c"; }
spec=(); CXXLIB=0
while read -r lang src mode; do
  [[ -z "$lang" || "$lang" == \#* ]] && continue
  G="$ROOT/$src"; W="$ROOT/out/$lang"
  cc "parser_$lang.o" -I"$G" -I"$ROOT/native/include" "$W/parser_$lang.c"
  if [ -f "$G/scanner.c" ]; then cc "scanner_$lang.o" -I"$G" "$G/scanner.c"
  elif [ -f "$G/scanner.cc" ]; then "${CXX[@]}" -Os -fPIC -w -std=c++14 -fvisibility=hidden -I"$G" -c "$G/scanner.cc" -o "$OBJ/scanner_$lang.o"; objs+=("$OBJ/scanner_$lang.o"); CXXLIB=1; fi
  if [ "$mode" = bundled ]; then cc "blob_$lang.o" "$W/blob_$lang.c"; spec+=("$lang:bundled"); else spec+=("$lang"); fi
done < "$ROOT/native/grammars.txt"
python3 "$ROOT/tools/gen-registry.py" "$OBJ/registry.c" "${spec[@]}"
cc registry.o -I"$ROOT/native/include" "$OBJ/registry.c"
LIBS=(-lz); [ "$CXXLIB" = 1 ] && LIBS+=(-lc++)
case "$T" in
  macos-arm64) "${CC[@]}" -dynamiclib -Wl,-dead_strip "${objs[@]}" "${LIBS[@]}" -o "$OUT/lib/libsupermux_syntax_jni.dylib" ;;
  ios-*) xcrun libtool -static -o "$OUT/lib/libsupermux_syntax.a" "${objs[@]}" 2>/dev/null ;;
  android-*) [ "$CXXLIB" = 1 ] && LIBS=(-lz -static-libstdc++)
    "${CC[@]}" -shared -Wl,--gc-sections -Wl,-z,max-page-size=16384 "${objs[@]}" "${LIBS[@]}" -o "$OUT/lib/libsupermux_syntax_jni.so"
    "$NDKBIN/llvm-strip" --strip-unneeded "$OUT/lib/libsupermux_syntax_jni.so" ;;
esac
ls -l "$OUT/lib"
```
Merge it into `apps/editor-syntax/native/build.sh` as a `build_target <target>` function, with these changes:
1. **Paths:**
   - tree-sitter comes from `$HERE/build/tree-sitter/lib`.
   - Generated grammars come from `$HERE/build/gen/<lang>/`.
   - Output goes to `$HERE/build/natives/<target>/lib/`.
   - The grammar list comes from `native/grammars.lock.json`, instead of `native/grammars.txt`: every non-excluded
     `lang` (including each multi-parser dir's lang, as `gen` produced them), `bundled` when the lock says so.
   - The grammar source dir for scanners is `build/grammars/<lang>/<parserDir>`.
2. **Three more desktop targets**, built with zig from the Mac (install once: `ssh mac 'brew install zig'`):

| target | compiler | output |
|---|---|---|
| `macos-x64` | `clang -arch x86_64 -mmacosx-version-min=12.0` | `libsupermux_syntax_jni.dylib` |
| `linux-x64` | `zig cc -target x86_64-linux-gnu.2.28` | `libsupermux_syntax_jni.so`, `-shared -Wl,--gc-sections` |
| `linux-arm64` | `zig cc -target aarch64-linux-gnu.2.28` | same |
| `windows-x64` | `zig cc -target x86_64-windows-gnu` | `supermux_syntax_jni.dll` |

   - JNI headers come from `$JAVA_HOME/include` plus the target's platform dir.
   - Copy `jni_md.h` for linux (`include/linux`) and win32 (`include/win32`). The Mac's JDK has only `darwin`, so
     vendor those two small `jni_md.h` files under `native/jni/{linux,win32}/`. They are GPLv2 with the
     Classpath exception, like every JDK header; note that in `native/README.md`.
   - zlib comes from `$HERE/build/zlib` (fetch zlib 1.3.1 in `fetch.sh`, add it to `upstream.lock.json` with its
     sha256) and is compiled in statically for the Linux and Windows targets. Apple and Android link the system `-lz`.
   - vue's C++ scanner: `zig c++` for Linux and Windows.
3. **`all` mode:** `gen`, then every target, then `build/natives/manifest.json` (target, file, sha256, size).

- [ ] **Step 3: Build everything on the Mac**

`scripts/editor/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps/editor-syntax && nohup caffeinate -i native/build.sh all > build/natives.log 2>&1; tail -20 build/natives.log; cat build/natives/manifest.json"`
Expected:
- 9 libraries in the manifest.
- The iOS `.a` holds every grammar's code: check with `nm` that `_tree_sitter_fsharp` and `_ses_parser_parse` are
  present.
- The stripped Android arm64 `.so` is under 8 MB. The prototype measured 4.65 MB of grammar code plus the runtime
  plus ~1.8 MB of core blobs.

- [ ] **Step 4: Commit**

```bash
git add apps/editor-syntax/native && git commit -m "feat(editor-syntax): ses_* C binding and native libraries for all nine targets"
```

---

### Task 5: The Gradle module and the Kotlin binding

**Files:**
- Modify: `apps/settings.gradle.kts` (add `include(":editor-syntax")` after `:editor-core`, with a one-line comment)
- Create: `apps/editor-syntax/build.gradle.kts`, `apps/editor-syntax/gradle.properties`
- Create: `apps/editor-syntax/src/commonMain/kotlin/dev/supermux/editor/syntax/SyntaxTypes.kt`
- Create: `apps/editor-syntax/src/nativeBackedMain/kotlin/dev/supermux/editor/syntax/Syntax.kt`
- Create: `apps/editor-syntax/src/jvmAndAndroidMain/kotlin/dev/supermux/editor/syntax/Ses.jvmAndAndroid.kt` (verbatim)
- Create: `apps/editor-syntax/src/iosMain/kotlin/dev/supermux/editor/syntax/Ses.ios.kt` (verbatim)
- Create: `apps/editor-syntax/src/nativeInterop/cinterop/syntax.def` (verbatim)
- Test: `apps/editor-syntax/src/nativeBackedTest/kotlin/dev/supermux/editor/syntax/{SesHighlighter,GoldenHighlightTest}.kt` (verbatim)

- [ ] **Step 1: Split the prototype's `Syntax.kt`**

The prototype's `Syntax.kt` (verified):
```kotlin
package dev.supermux.editor.syntax

/**
 * The native tree-sitter binding (ses_* C ABI) as plain Kotlin objects. Every offset, length, row
 * and column here is in UTF-16 code units, exactly like String/Rope indexes and web-tree-sitter's
 * startIndex/endIndex: there is no byte encoding anywhere on the Kotlin side.
 *
 * Not thread-safe per object (a parser, tree or query cursor is used by one thread at a time); a
 * [SyntaxQuery] may be shared once built. Grammar loading is thread-safe inside the C runtime.
 */

/** The parser pulls text through this; `Rope.chunkAt` fits as-is ("" at the end). */
fun interface TextSource {
    /** The document's text from [index] to the end of some chunk, or "" at/after the end. */
    fun chunkAt(index: Int): CharSequence
}

/** One edit: UTF-16 [start, oldEnd) became [start, newEnd). Rows 0-based, columns UTF-16 from line start. */
data class TextEdit(
    val start: Int, val oldEnd: Int, val newEnd: Int,
    val startRow: Int, val startColumn: Int,
    val oldEndRow: Int, val oldEndColumn: Int,
    val newEndRow: Int, val newEndColumn: Int,
)

class SyntaxException(message: String, val status: Int) : RuntimeException("$message (ses status $status)")

object SyntaxStatus {
    const val OK = 0
    const val UNKNOWN_LANGUAGE = -8
    const val NO_TABLES = -9
    const val BAD_TABLES = -10
    const val QUERY = -11
    const val INCOMPATIBLE_LANGUAGE = -12
    const val TIMEOUT = -13
}

object SyntaxLanguages {
    const val ABI_VERSION = 1

    /** Every compiled-in grammar (code); [hasTables] says whether it is usable yet. */
    fun names(): List<String> = List(Ses.languageCount()) { Ses.languageName(it) }
    fun hasTables(name: String): Boolean = Ses.languageHasTables(name) == 1
    /** Tables for a grammar whose blob is not bundled (e.g. downloaded). Checked against the code's hash. */
    fun provideTables(name: String, sesz: ByteArray) = check(Ses.provideTables(name, sesz), "provideTables($name)")
    fun load(name: String) = check(Ses.languageLoad(name), "load($name)")

    init {
        val abi = Ses.abiVersion()
        if (abi != ABI_VERSION) throw SyntaxException("native ses ABI $abi, binding needs $ABI_VERSION", -2)
    }
}

internal fun check(status: Int, what: String) {
    if (status != SyntaxStatus.OK) throw SyntaxException("$what failed", status)
}

class SyntaxParser(val language: String) : AutoCloseable {
    private var ptr: Long = Ses.parserNew().also { if (it == 0L) throw SyntaxException("parserNew", -4) }

    init {
        SyntaxLanguages // ABI check
        val st = Ses.parserSetLanguage(ptr, language)
        if (st != SyntaxStatus.OK) { close(); throw SyntaxException("setLanguage($language)", st) }
    }

    fun setTimeoutMicros(micros: Long) = Ses.parserSetTimeoutMicros(live(), micros)

    /** Parse, reusing [old] (which must already carry every [SyntaxTree.edit] since it was made). */
    fun parse(source: TextSource, old: SyntaxTree? = null): SyntaxTree {
        val status = IntArray(1)
        val t = Ses.parse(live(), old?.live() ?: 0L, source, status)
        if (t == 0L) throw SyntaxException("parse", status[0])
        return SyntaxTree(t)
    }

    fun parse(text: String, old: SyntaxTree? = null): SyntaxTree {
        val status = IntArray(1)
        val t = Ses.parseString(live(), old?.live() ?: 0L, text, status)
        if (t == 0L) throw SyntaxException("parse", status[0])
        return SyntaxTree(t)
    }

    private fun live(): Long { check(ptr != 0L) { "parser closed" }; return ptr }

    override fun close() { if (ptr != 0L) { Ses.parserFree(ptr); ptr = 0L } }
}

class SyntaxTree internal constructor(private var ptr: Long) : AutoCloseable {
    internal fun live(): Long { check(ptr != 0L) { "tree closed" }; return ptr }

    fun copy(): SyntaxTree = SyntaxTree(Ses.treeCopy(live()))

    fun edit(e: TextEdit) = Ses.treeEdit(
        live(), e.start, e.oldEnd, e.newEnd, e.startRow, e.startColumn, e.oldEndRow, e.oldEndColumn,
        e.newEndRow, e.newEndColumn,
    )

    fun sexp(): String = Ses.treeSexp(live())
    val hasError: Boolean get() = Ses.treeHasError(live())

    /** Packed [start, end]* (UTF-16) of what changed from this (edited) tree to its reparse [new]. */
    fun changedRanges(new: SyntaxTree): IntArray = Ses.treeChangedRanges(live(), new.live())

    override fun close() { if (ptr != 0L) { Ses.treeFree(ptr); ptr = 0L } }
}

class SyntaxQuery(val language: String, source: String) : AutoCloseable {
    private var ptr: Long
    val captureNames: List<String>
    /** Bit 0: uses #match?-family predicates, which the native side does not evaluate yet. */
    val flags: Int

    init {
        SyntaxLanguages
        val err = IntArray(3)
        ptr = Ses.queryNew(language, source.encodeToByteArray(), err)
        if (ptr == 0L) throw SyntaxException("query: error type ${err[2]} at byte ${err[1]}", err[0])
        captureNames = List(Ses.queryCaptureCount(ptr)) { Ses.queryCaptureName(ptr, it).decodeToString() }
        flags = Ses.queryFlags(ptr)
    }

    /**
     * Captures of nodes intersecting UTF-16 [start, end): packed [start, end, captureIndex]* in
     * UTF-16 units, in tree-sitter's capture order. [text] feeds #eq?/#any-of? predicates.
     */
    fun captures(tree: SyntaxTree, start: Int, end: Int, text: TextSource? = null): IntArray {
        check(ptr != 0L) { "query closed" }
        return Ses.queryCaptures(ptr, tree.live(), start, end, text)
    }

    override fun close() { if (ptr != 0L) { Ses.queryFree(ptr); ptr = 0L } }
}

/** The raw ses_* ABI, one actual per binding (JNI / cinterop). Pointers are Longs, 0 = null. */
internal expect object Ses {
    fun abiVersion(): Int
    fun languageCount(): Int
    fun languageName(index: Int): String
    fun languageHasTables(name: String): Int
    fun provideTables(name: String, bytes: ByteArray): Int
    fun languageLoad(name: String): Int
    fun parserNew(): Long
    fun parserFree(parser: Long)
    fun parserSetLanguage(parser: Long, name: String): Int
    fun parserSetTimeoutMicros(parser: Long, micros: Long)
    fun parse(parser: Long, old: Long, source: TextSource, status: IntArray): Long
    fun parseString(parser: Long, old: Long, text: String, status: IntArray): Long
    fun treeCopy(tree: Long): Long
    fun treeFree(tree: Long)
    fun treeEdit(tree: Long, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int)
    fun treeSexp(tree: Long): String
    fun treeHasError(tree: Long): Boolean
    fun treeChangedRanges(old: Long, new: Long): IntArray
    fun queryNew(language: String, utf8: ByteArray, err: IntArray): Long
    fun queryFree(query: Long)
    fun queryCaptureCount(query: Long): Int
    fun queryCaptureName(query: Long, index: Int): ByteArray
    fun queryFlags(query: Long): Int
    fun queryCaptures(query: Long, tree: Long, start: Int, end: Int, source: TextSource?): IntArray
}
```
- Move `TextSource`, `TextEdit`, `SyntaxException` and `SyntaxStatus` **unchanged** into
  `src/commonMain/.../SyntaxTypes.kt`.
- Put the rest (`SyntaxLanguages`, `SyntaxParser`, `SyntaxTree`, `SyntaxQuery`, `check`, `expect object Ses`)
  **unchanged** into `src/nativeBackedMain/.../Syntax.kt`.

- [ ] **Step 2: Add the verified bindings and tests**

`apps/editor-syntax/src/jvmAndAndroidMain/kotlin/dev/supermux/editor/syntax/Ses.jvmAndAndroid.kt`:
```kotlin
package dev.supermux.editor.syntax

/**
 * JNI actual (native/src/syntax_jni.c, lib `supermux_syntax_jni`), shared by Android and the desktop
 * JVM. Strings cross as java.lang.String, i.e. UTF-16 already: the C side passes GetStringChars
 * straight to tree-sitter as UTF-16LE.
 *
 * Loading: `-Deditor.syntax.lib=<absolute path>` (JVM tests) or System.loadLibrary (Android/jar).
 */
internal actual object Ses {
    init {
        val path = System.getProperty("editor.syntax.lib")
        if (path != null) System.load(path) else System.loadLibrary("supermux_syntax_jni")
    }

    /** What the C reader calls back: `String chunk(int)`. One String per chunk, no copies on our side. */
    private class TextSourceJni(private val source: TextSource) {
        @Suppress("unused") // called from JNI
        fun chunk(index: Int): String = source.chunkAt(index).toString()
    }

    @JvmStatic actual external fun abiVersion(): Int
    @JvmStatic actual external fun languageCount(): Int
    @JvmStatic actual external fun languageName(index: Int): String
    @JvmStatic actual external fun languageHasTables(name: String): Int
    @JvmStatic actual external fun provideTables(name: String, bytes: ByteArray): Int
    @JvmStatic actual external fun languageLoad(name: String): Int
    @JvmStatic actual external fun parserNew(): Long
    @JvmStatic actual external fun parserFree(parser: Long)
    @JvmStatic actual external fun parserSetLanguage(parser: Long, name: String): Int
    @JvmStatic actual external fun parserSetTimeoutMicros(parser: Long, micros: Long)

    @JvmStatic private external fun parse(parser: Long, old: Long, source: Any, status: IntArray): Long
    actual fun parse(parser: Long, old: Long, source: TextSource, status: IntArray): Long =
        parse(parser, old, TextSourceJni(source) as Any, status)

    @JvmStatic actual external fun parseString(parser: Long, old: Long, text: String, status: IntArray): Long
    @JvmStatic actual external fun treeCopy(tree: Long): Long
    @JvmStatic actual external fun treeFree(tree: Long)
    @JvmStatic actual external fun treeEdit(
        tree: Long, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int,
    )
    @JvmStatic actual external fun treeSexp(tree: Long): String
    @JvmStatic actual external fun treeHasError(tree: Long): Boolean
    @JvmStatic actual external fun treeChangedRanges(old: Long, new: Long): IntArray
    @JvmStatic actual external fun queryNew(language: String, utf8: ByteArray, err: IntArray): Long
    @JvmStatic actual external fun queryFree(query: Long)
    @JvmStatic actual external fun queryCaptureCount(query: Long): Int
    @JvmStatic actual external fun queryCaptureName(query: Long, index: Int): ByteArray
    @JvmStatic actual external fun queryFlags(query: Long): Int

    @JvmStatic private external fun queryCaptures(query: Long, tree: Long, start: Int, end: Int, source: Any?): IntArray
    actual fun queryCaptures(query: Long, tree: Long, start: Int, end: Int, source: TextSource?): IntArray =
        queryCaptures(query, tree, start, end, source?.let { TextSourceJni(it) } as Any?)
}
```

`apps/editor-syntax/src/iosMain/kotlin/dev/supermux/editor/syntax/Ses.ios.kt`:
```kotlin
@file:OptIn(ExperimentalForeignApi::class)

package dev.supermux.editor.syntax

import dev.supermux.editor.syntax.cinterop.ses_abi_version
import dev.supermux.editor.syntax.cinterop.ses_free
import dev.supermux.editor.syntax.cinterop.ses_language_count
import dev.supermux.editor.syntax.cinterop.ses_language_has_tables
import dev.supermux.editor.syntax.cinterop.ses_language_load
import dev.supermux.editor.syntax.cinterop.ses_language_name
import dev.supermux.editor.syntax.cinterop.ses_language_provide_tables
import dev.supermux.editor.syntax.cinterop.ses_parser_free
import dev.supermux.editor.syntax.cinterop.ses_parser_new
import dev.supermux.editor.syntax.cinterop.ses_parser_parse
import dev.supermux.editor.syntax.cinterop.ses_parser_parse_utf16
import dev.supermux.editor.syntax.cinterop.ses_parser_set_language
import dev.supermux.editor.syntax.cinterop.ses_parser_set_timeout_micros
import dev.supermux.editor.syntax.cinterop.ses_query_capture_count
import dev.supermux.editor.syntax.cinterop.ses_query_capture_name
import dev.supermux.editor.syntax.cinterop.ses_query_captures
import dev.supermux.editor.syntax.cinterop.ses_query_flags
import dev.supermux.editor.syntax.cinterop.ses_query_free
import dev.supermux.editor.syntax.cinterop.ses_query_new
import dev.supermux.editor.syntax.cinterop.ses_tree_changed_ranges
import dev.supermux.editor.syntax.cinterop.ses_tree_copy
import dev.supermux.editor.syntax.cinterop.ses_tree_edit
import dev.supermux.editor.syntax.cinterop.ses_tree_free
import dev.supermux.editor.syntax.cinterop.ses_tree_has_error
import dev.supermux.editor.syntax.cinterop.ses_tree_root_sexp
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pin
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toLong
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value

/**
 * cinterop actual over the static libsupermux_syntax.a (ses_* + tree-sitter + grammars). Kotlin
 * Strings are UTF-16; each chunk is copied into a pinned CharArray that stays pinned until the C
 * reader asks for the next one, so tree-sitter reads the code units in place.
 */
private class Reader(val source: TextSource) {
    private var pinned: Pinned<CharArray>? = null
    var failure: Throwable? = null

    fun next(index: Int, outLen: CPointer<UIntVar>): CPointer<UShortVar>? {
        release()
        outLen.pointed.value = 0u
        if (failure != null) return null
        val chunk = try { source.chunkAt(index) } catch (t: Throwable) { failure = t; return null }
        if (chunk.isEmpty()) return null
        val chars = chunk.toString().toCharArray()
        val p = chars.pin()
        pinned = p
        outLen.pointed.value = chars.size.toUInt()
        return p.addressOf(0).reinterpret()
    }

    fun release() { pinned?.unpin(); pinned = null }
}

// A C function pointer: no captures, so the Reader travels through ctx as a StableRef.
private val readChunk = staticCFunction { ctx: COpaquePointer?, index: UInt, outLen: CPointer<UIntVar>? ->
    ctx!!.asStableRef<Reader>().get().next(index.toInt(), outLen!!)
}

private inline fun <R> withReader(source: TextSource?, block: (COpaquePointer?) -> R): R {
    if (source == null) return block(null)
    val reader = Reader(source)
    val ref = StableRef.create(reader)
    try {
        val r = block(ref.asCPointer())
        reader.failure?.let { throw it }
        return r
    } finally {
        reader.release()
        ref.dispose()
    }
}

internal actual object Ses {
    actual fun abiVersion(): Int = ses_abi_version().toInt()
    actual fun languageCount(): Int = ses_language_count().toInt()
    actual fun languageName(index: Int): String = ses_language_name(index.toUInt())!!.toKString()
    actual fun languageHasTables(name: String): Int = ses_language_has_tables(name)
    actual fun provideTables(name: String, bytes: ByteArray): Int =
        if (bytes.isEmpty()) SyntaxStatus.BAD_TABLES
        else bytes.usePinned { ses_language_provide_tables(name, it.addressOf(0).reinterpret(), bytes.size.convert()) }
    actual fun languageLoad(name: String): Int = ses_language_load(name)

    actual fun parserNew(): Long = ses_parser_new().toLong()
    actual fun parserFree(parser: Long) = ses_parser_free(parser.toCPointer())
    actual fun parserSetLanguage(parser: Long, name: String): Int = ses_parser_set_language(parser.toCPointer(), name)
    actual fun parserSetTimeoutMicros(parser: Long, micros: Long) =
        ses_parser_set_timeout_micros(parser.toCPointer(), micros.toULong())

    actual fun parse(parser: Long, old: Long, source: TextSource, status: IntArray): Long = memScoped {
        val st = alloc<IntVar>()
        val t = withReader(source) { ctx -> ses_parser_parse(parser.toCPointer(), old.toCPointer(), readChunk, ctx, st.ptr) }
        status[0] = st.value
        t.toLong()
    }

    actual fun parseString(parser: Long, old: Long, text: String, status: IntArray): Long = memScoped {
        val st = alloc<IntVar>()
        val chars = text.toCharArray()
        val t = if (chars.isEmpty()) ses_parser_parse_utf16(parser.toCPointer(), old.toCPointer(), null, 0u, st.ptr)
        else chars.usePinned {
            ses_parser_parse_utf16(parser.toCPointer(), old.toCPointer(), it.addressOf(0).reinterpret(), chars.size.toUInt(), st.ptr)
        }
        status[0] = st.value
        t.toLong()
    }

    actual fun treeCopy(tree: Long): Long = ses_tree_copy(tree.toCPointer()).toLong()
    actual fun treeFree(tree: Long) = ses_tree_free(tree.toCPointer())
    actual fun treeEdit(tree: Long, start: Int, oldEnd: Int, newEnd: Int, sr: Int, sc: Int, oer: Int, oec: Int, ner: Int, nec: Int) =
        ses_tree_edit(
            tree.toCPointer(), start.toUInt(), oldEnd.toUInt(), newEnd.toUInt(), sr.toUInt(), sc.toUInt(),
            oer.toUInt(), oec.toUInt(), ner.toUInt(), nec.toUInt(),
        )

    actual fun treeSexp(tree: Long): String {
        val s = ses_tree_root_sexp(tree.toCPointer()) ?: return ""
        try { return s.toKString() } finally { ses_free(s) }
    }

    actual fun treeHasError(tree: Long): Boolean = ses_tree_has_error(tree.toCPointer()) != 0

    actual fun treeChangedRanges(old: Long, new: Long): IntArray = memScoped {
        val out = alloc<CPointerVar<IntVar>>()
        val n = alloc<UIntVar>()
        check(ses_tree_changed_ranges(old.toCPointer(), new.toCPointer(), out.ptr, n.ptr), "changedRanges")
        takeInts(out.value, n.value.toInt())
    }

    actual fun queryNew(language: String, utf8: ByteArray, err: IntArray): Long = memScoped {
        val off = alloc<UIntVar>()
        val type = alloc<IntVar>()
        val st = alloc<IntVar>()
        // cinterop maps `const char *` to String (it re-encodes as UTF-8: the same bytes, NUL-terminated)
        val q = ses_query_new(language, utf8.decodeToString(), utf8.size.toUInt(), off.ptr, type.ptr, st.ptr)
        err[0] = st.value; err[1] = off.value.toInt(); err[2] = type.value
        q.toLong()
    }

    actual fun queryFree(query: Long) = ses_query_free(query.toCPointer())
    actual fun queryCaptureCount(query: Long): Int = ses_query_capture_count(query.toCPointer()).toInt()
    actual fun queryCaptureName(query: Long, index: Int): ByteArray = memScoped {
        val len = alloc<UIntVar>()
        val s = ses_query_capture_name(query.toCPointer(), index.toUInt(), len.ptr) ?: return ByteArray(0)
        s.readBytes(len.value.toInt())
    }
    actual fun queryFlags(query: Long): Int = ses_query_flags(query.toCPointer()).toInt()

    actual fun queryCaptures(query: Long, tree: Long, start: Int, end: Int, source: TextSource?): IntArray = memScoped {
        val out = alloc<CPointerVar<IntVar>>()
        val n = alloc<UIntVar>()
        val st = withReader(source) { ctx ->
            ses_query_captures(
                query.toCPointer(), tree.toCPointer(), start.toUInt(), end.toUInt(),
                if (ctx == null) null else readChunk, ctx, out.ptr, n.ptr,
            )
        }
        check(st, "queryCaptures")
        takeInts(out.value, n.value.toInt())
    }

    /** Copy a ses_*-returned int buffer into an IntArray and free it. */
    private fun takeInts(p: CPointer<IntVar>?, n: Int): IntArray {
        try { return IntArray(n) { p!![it] } } finally { ses_free(p) }
    }
}
```

`apps/editor-syntax/src/nativeInterop/cinterop/syntax.def`:
```
# cinterop for the ses_* ABI (SES_ABI_VERSION in native/include/supermux_syntax.h). The static
# archive (ses_* + tree-sitter + grammars + bundled table blobs) is built by native/build.sh
# ios-arm64 / ios-simulator-arm64; build.gradle.kts passes its directory with -libraryPath.
package = dev.supermux.editor.syntax.cinterop
headers = supermux_syntax.h
headerFilter = supermux_syntax.h
staticLibraries = libsupermux_syntax.a
linkerOpts = -lz
```

`apps/editor-syntax/src/nativeBackedTest/kotlin/dev/supermux/editor/syntax/SesHighlighter.kt`:
```kotlin
package dev.supermux.editor.syntax

/** One highlight capture, in UTF-16 code units. */
data class Span(val start: Int, val end: Int, val capture: String) {
    override fun toString() = "$start-$end $capture"
}

/** A String read in [size]-unit chunks (size 3 splits surrogate pairs on purpose). */
class ChunkedSource(private val text: String, private val size: Int = Int.MAX_VALUE) : TextSource {
    override fun chunkAt(index: Int): CharSequence =
        if (index >= text.length) "" else text.substring(index, minOf(text.length.toLong(), index.toLong() + size).toInt())
}

/**
 * The M0 SpikeHighlighter contract on the ses_* binding: parse, highlight, one edit + incremental
 * reparse. NO offset conversion of any kind: tree-sitter's UTF-16 results are used as they come.
 */
class SesHighlighter(language: String, private val chunk: Int = Int.MAX_VALUE) : AutoCloseable {
    private val parser = SyntaxParser(language)
    private val lang = language
    var source = ""; private set
    var tree: SyntaxTree? = null; private set
    var lastChangedRanges = IntArray(0); private set

    fun parse(source: String) {
        tree?.close()
        this.source = source
        tree = parser.parse(ChunkedSource(source, chunk))
    }

    fun highlights(query: String, from: Int = 0, to: Int = source.length): List<Span> =
        SyntaxQuery(lang, query).use { q ->
            val a = q.captures(tree!!, from, to, ChunkedSource(source, chunk))
            List(a.size / 3) { Span(a[3 * it], a[3 * it + 1], q.captureNames[a[3 * it + 2]]) }
                .sortedWith(compareBy<Span>({ it.start }, { -it.end }, { it.capture }))
        }

    fun edit(from: Int, to: Int, insert: String): String {
        val old = source
        val next = old.replaceRange(from, to, insert)
        val (sr, sc) = point(old, from)
        val (oer, oec) = point(old, to)
        val (ner, nec) = point(next, from + insert.length)
        val t = tree!!
        t.edit(TextEdit(from, to, from + insert.length, sr, sc, oer, oec, ner, nec))
        val fresh = parser.parse(ChunkedSource(next, chunk), t)
        lastChangedRanges = t.changedRanges(fresh)
        t.close()
        tree = fresh
        source = next
        return next
    }

    /** (row, column) of a UTF-16 index; column in UTF-16 units from the line start. */
    private fun point(text: String, index: Int): Pair<Int, Int> {
        val lineStart = text.lastIndexOf('\n', index - 1) + 1
        return text.subSequence(0, index).count { it == '\n' } to (index - lineStart)
    }

    override fun close() { tree?.close(); tree = null; parser.close() }
}
```

`apps/editor-syntax/src/nativeBackedTest/kotlin/dev/supermux/editor/syntax/GoldenHighlightTest.kt`:
```kotlin
package dev.supermux.editor.syntax

import dev.supermux.editor.core.Rope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// tree-sitter-json 0.24.8 queries/highlights.scm, verbatim.
const val JSON_HIGHLIGHTS = """
(pair
  key: (_) @string.special.key)

(string) @string

(number) @number

[
  (null)
  (true)
  (false)
] @constant.builtin

(escape_sequence) @escape

(comment) @comment
"""

// Raw string: the \n below is a backslash + n in the JSON (an escape_sequence), not a newline.
const val SAMPLE = """{"ağ": [1, true, null], "e😀": "x\n"}"""

private val GOLDEN = listOf(
    "1-5 string", "1-5 string.special.key",
    "8-9 number",
    "11-15 constant.builtin",
    "17-21 constant.builtin",
    "24-29 string", "24-29 string.special.key",
    "31-36 string",
    "33-35 escape",
)

/** M0's golden contract, verbatim, on the ses_* binding (JVM, iOS simulator, Android). */
class GoldenHighlightTest {
    @Test
    fun highlightsAreUtf16AndIdenticalOnEveryBackend() = SesHighlighter("json").use { h ->
        h.parse(SAMPLE)
        assertEquals(GOLDEN, h.highlights(JSON_HIGHLIGHTS).map { it.toString() })
    }

    @Test
    fun incrementalEditShiftsLaterSpans() = SesHighlighter("json").use { h ->
        h.parse(SAMPLE)
        // Replace `1` (8..9) with `12345`: +4 units. Everything after index 9 moves by 4.
        val next = h.edit(8, 9, "12345")
        assertEquals(SAMPLE.replaceRange(8, 9, "12345"), next)
        val spans = h.highlights(JSON_HIGHLIGHTS).map { it.toString() }
        assertEquals("8-13 number", spans[2])
        assertEquals("35-40 string", spans[7])
        assertEquals("37-39 escape", spans[8])
    }
}

/** Beyond M0: the pull reader, the Rope, predicates, changed ranges and table loading. */
class SesBindingTest {
    @Test
    fun threeUnitChunksSplitTheSurrogatePairAndChangeNothing() = SesHighlighter("json", chunk = 3).use { h ->
        h.parse(SAMPLE)
        assertEquals(GOLDEN, h.highlights(JSON_HIGHLIGHTS).map { it.toString() })
        h.edit(8, 9, "12345")
        assertEquals("37-39 escape", h.highlights(JSON_HIGHLIGHTS).map { it.toString() }[8])
    }

    @Test
    fun ropeChunkAtIsTheTextSource() {
        val doc = buildString {
            append('[')
            for (i in 0 until 4000) { if (i > 0) append(",\n"); append("{\"k$i ağ\": \"😀$i\", \"n\": $i}") }
            append(']')
        }
        val rope = Rope.of(doc)
        SyntaxParser("json").use { p ->
            val fromRope = p.parse(TextSource { rope.chunkAt(it) })
            val fromString = p.parse(doc)
            try {
                assertEquals(fromString.sexp(), fromRope.sexp())
                assertFalse(fromRope.hasError)
                SyntaxQuery("json", JSON_HIGHLIGHTS).use { q ->
                    // a window in the middle of the document, like visible lines + overscan
                    val from = doc.length / 2
                    val a = q.captures(fromRope, from, from + 500, TextSource { rope.chunkAt(it) })
                    val b = q.captures(fromString, from, from + 500, ChunkedSource(doc))
                    assertTrue(a.isNotEmpty())
                    assertEquals(b.toList(), a.toList())
                    for (i in a.indices step 3) {
                        val text = doc.substring(a[i], a[i + 1])
                        if (q.captureNames[a[i + 2]] == "number") assertTrue(text.all { it.isDigit() }, text)
                        if (q.captureNames[a[i + 2]] == "string") assertTrue(text.startsWith('"') && text.endsWith('"'), text)
                    }
                }
            } finally { fromRope.close(); fromString.close() }
        }
    }

    @Test
    fun textPredicatesCompareUtf16() = SesHighlighter("json", chunk = 3).use { h ->
        h.parse(SAMPLE)
        val q = """
            ((pair key: (string (string_content) @emoji)) (#eq? @emoji "e😀"))
            ((pair key: (string (string_content) @other)) (#not-eq? @other "e😀"))
            ((string (string_content) @listed) (#any-of? @listed "ağ" "zz"))
        """
        assertEquals(listOf("2-4 listed", "2-4 other", "25-28 emoji"), h.highlights(q).map { it.toString() })
    }

    @Test
    fun changedRangesAreUtf16() = SesHighlighter("json").use { h ->
        h.parse(SAMPLE)
        h.edit(24, 29, "\"k\"") // replace the key "e😀" (24..29) with "k"
        val r = h.lastChangedRanges
        assertTrue(r.isEmpty() || (r[0] >= 0 && r.last() <= h.source.length), r.toList().toString())
        h.edit(8, 9, "[2]") // number -> array: a structural change must be reported
        val r2 = h.lastChangedRanges
        assertTrue(r2.isNotEmpty() && r2[0] <= 8 && r2[1] >= 11, r2.toList().toString())
    }

    @Test
    fun codeOnlyGrammarNeedsTables() {
        assertTrue("python" in SyntaxLanguages.names())
        assertFalse(SyntaxLanguages.hasTables("python"))
        val e = assertFailsWith<SyntaxException> { SyntaxParser("python") }
        assertEquals(SyntaxStatus.NO_TABLES, e.status)
        val bad = assertFailsWith<SyntaxException> { SyntaxLanguages.provideTables("python", ByteArray(40)) }
        assertEquals(SyntaxStatus.BAD_TABLES, bad.status)
        assertEquals(SyntaxStatus.UNKNOWN_LANGUAGE, assertFailsWith<SyntaxException> { SyntaxParser("cobol") }.status)
    }

    @Test
    fun fsharpTablesInflateOnFirstUse() {
        val t0 = kotlin.time.TimeSource.Monotonic.markNow()
        SyntaxLanguages.load("fsharp")
        val loadMs = t0.elapsedNow().inWholeMicroseconds / 1000.0
        SyntaxParser("fsharp").use { p ->
            val t1 = kotlin.time.TimeSource.Monotonic.markNow()
            p.parse(FSHARP_SAMPLE).use { t ->
                val parseMs = t1.elapsedNow().inWholeMicroseconds / 1000.0
                println("SES fsharp first-use load=${loadMs}ms parse=${parseMs}ms units=${FSHARP_SAMPLE.length}")
                assertFalse(t.hasError, t.sexp().take(400))
                assertTrue(t.sexp().startsWith("(file (named_module"), t.sexp().take(200))
            }
        }
    }
}

const val FSHARP_SAMPLE = """module Supermux.Sample

open System

type Shape =
    | Circle of radius: float
    | Rect of width: float * height: float

let area shape =
    match shape with
    | Circle r -> Math.PI * r * r
    | Rect (w, h) -> w * h

type Account(owner: string, initial: decimal) =
    let mutable balance = initial
    member _.Owner = owner
    member this.Deposit(amount: decimal) =
        balance <- balance + amount
        this

let greeting = "ağ 😀"

[<EntryPoint>]
let main argv =
    for s in [ Circle 1.0; Rect(2.0, 3.0) ] do
        printfn "%A -> %.2f %s" s (area s) greeting
    0
"""
```


- [ ] **Step 3: The build file**

The prototype's throwaway module (verified on JVM, the iOS simulator and the Android emulator):
```kotlin
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSetTree

plugins {
    alias(libs.plugins.multiplatform)
    alias(libs.plugins.android.library)
}

// THROWAWAY (native-editor M2 editor-syntax prototype). Nothing may depend on this module. Its
// sources live OUTSIDE the repo in ~/work/editor-syntax-proto/kotlin/src (the prototype scratch dir).
val proto = File(System.getProperty("user.home"), "work/editor-syntax-proto")
val src = File(proto, "kotlin/src")
val nativeBuild = File(proto, "build")

kotlin {
    jvmToolchain(17)
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common { group("jvmAndAndroid") { withJvm(); withAndroidTarget() } }
    }
    jvm()
    androidTarget {
        instrumentedTestVariant.sourceSetTree.set(KotlinSourceSetTree.test)
        unitTestVariant.sourceSetTree.set(KotlinSourceSetTree.unitTest)
    }
    listOf(iosArm64() to "ios-arm64", iosSimulatorArm64() to "ios-simulator-arm64").forEach { (t, dir) ->
        t.compilations.getByName("main").cinterops.create("syntax") {
            definitionFile.set(File(src, "nativeInterop/cinterop/syntax.def"))
            includeDirs(File(proto, "native/include"))
            extraOpts("-libraryPath", File(nativeBuild, "$dir/lib").absolutePath)
        }
    }
    sourceSets {
        commonMain { kotlin.srcDir(File(src, "commonMain/kotlin")) }
        commonTest {
            kotlin.srcDir(File(src, "commonTest/kotlin"))
            dependencies { implementation(kotlin("test")); implementation(project(":editor-core")) }
        }
        getByName("jvmAndAndroidMain") { kotlin.srcDir(File(src, "jvmAndAndroidMain/kotlin")) }
        iosMain { kotlin.srcDir(File(src, "iosMain/kotlin")) }
        val androidInstrumentedTest by getting {
            dependencies {
                implementation("androidx.test:runner:1.6.2")
                implementation("androidx.test.ext:junit:1.2.1")
                implementation(kotlin("test-junit"))
            }
        }
    }
}

android {
    namespace = "dev.supermux.editor.syntaxproto"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        minSdk = libs.versions.androidMinSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

// Stage native/build.sh's .so files as jniLibs/<abi>/ through the variant API (AGP 9 + KMP).
val jniStage = layout.buildDirectory.dir("jniLibs")
val stageJni by tasks.registering(Copy::class) {
    from(File(nativeBuild, "android-arm64/lib")) { into("arm64-v8a") }
    from(File(nativeBuild, "android-x64/lib")) { into("x86_64") }
    into(jniStage)
}
androidComponents {
    onVariants { v -> v.sources.jniLibs?.addStaticSourceDirectory(jniStage.get().asFile.absolutePath) }
}
tasks.matching { it.name.startsWith("merge") && it.name.contains("JniLibFolders") }.configureEach { dependsOn(stageJni) }

tasks.named<Test>("jvmTest") {
    systemProperty("editor.syntax.lib", File(nativeBuild, "macos-arm64/lib/libsupermux_syntax_jni.dylib").absolutePath)
    testLogging { showStandardStreams = true; events("passed", "failed"); exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
```
Write `apps/editor-syntax/build.gradle.kts` from it, with these changes:
- **Sources:** normal module-relative dirs. Delete the `proto`/`src` indirection.
- **Source-set hierarchy:** `common { group("nativeBacked") { group("jvmAndAndroid") { withJvm(); withAndroidTarget() }; group("ios") { withIos() } } }`.
  Also declare `nativeBackedTest` the same way. commonTest keeps the `:editor-core` test dependency; the rope tests
  use `Rope`.
- **cinterop:** `includeDirs(file("native/include"))`, and the library path is
  `build/natives/<ios-target>/lib`. Add a `-lz` linker option for the iOS test and framework binaries; the `.def`
  already has `linkerOpts = -lz`.
- **Android:** stage `build/natives/android-*/lib/*.so` into `jniLibs/<abi>` through the same `androidComponents`
  code. Add `consumerProguardFiles("consumer-rules.pro")` with a `consumer-rules.pro` file:
  `-keepclassmembers class dev.supermux.editor.syntax.Ses$TextSourceJni { java.lang.String chunk(int); }` and
  `-keep class dev.supermux.editor.syntax.Ses { native <methods>; }`.
- **Desktop JVM:** copy terminal-core's pattern (`stageJvmNativeResources`, `apps/terminal-core/build.gradle.kts`
  ~lines 200–250). Stage every present desktop target's JNI library as a jvmMain resource under
  `natives/<target>/`, and extend `Ses`'s `init` so that, when `editor.syntax.lib` is unset, it extracts the right
  resource for `os.name`/`os.arch` to a temp file and loads it. Otherwise it falls back to
  `System.loadLibrary("supermux_syntax_jni")`, which is the Android path.
  - Keep the `editor.syntax.lib` override; jvmTest sets it to `build/natives/macos-arm64/lib/libsupermux_syntax_jni.dylib`.
- `gradle.properties`: `kotlin.mpp.enableCInteropCommonization=true`.

- [ ] **Step 4: Run the tests on JVM and the iOS simulator**

`scripts/editor/mac-sync.sh && ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-syntax:jvmTest :editor-syntax:iosSimulatorArm64Test --console=plain"`
Expected: `GoldenHighlightTest` 2 + `SesBindingTest` 6 = **8 pass on each**.

- [ ] **Step 5: Run the tests on the Android emulator**

Boot the Mac's API 35 arm64 emulator read-only; the recipe is in `~/.mux/domains/infra.md` from the M0 run. Then run:
`ssh mac "$MACENV; cd ~/work/native-editor/apps && ./gradlew :editor-syntax:connectedAndroidTest --console=plain"`
Expected: 8 pass. Shut the emulator down afterwards.

- [ ] **Step 6: Commit**

```bash
git add apps/settings.gradle.kts apps/editor-syntax/build.gradle.kts apps/editor-syntax/gradle.properties apps/editor-syntax/consumer-rules.pro apps/editor-syntax/src
git commit -m "feat(editor-syntax): Kotlin JNI/cinterop binding; M0 golden test passes with no offset conversion"
```

---

### Task 6: Desktop resource loading, every grammar, and the remaining grammar gaps

**Files:**
- Test: `apps/editor-syntax/src/jvmTest/kotlin/dev/supermux/editor/syntax/DesktopLoadTest.kt`
- Test: `apps/editor-syntax/src/nativeBackedTest/kotlin/dev/supermux/editor/syntax/AllGrammarsTest.kt`
- Modify: `apps/editor-syntax/native/grammars.lock.json` (clojure)

- [ ] **Step 1: The desktop loading test**

`DesktopLoadTest.kt` runs in a `jvmTest` task **without** `editor.syntax.lib`. Register a second `Test` task,
`jvmResourceLoadTest`, that clears the property and filters to this class. It asserts that
`SyntaxLanguages.names()` contains `"json"`, which proves the jar-resource extraction path.
```kotlin
package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertTrue

class DesktopLoadTest {
    @Test fun loadsTheLibraryFromJarResources() {
        assertTrue(System.getProperty("editor.syntax.lib") == null, "run through jvmResourceLoadTest")
        assertTrue("json" in SyntaxLanguages.names())
    }
}
```

- [ ] **Step 2: Every grammar parses a sample with its tables**

```kotlin
package dev.supermux.editor.syntax

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every compiled-in grammar loads (bundled tables, or code-only reports NO_TABLES) and parses "". */
class AllGrammarsTest {
    @Test fun everyGrammarLoadsOrReportsNoTables() {
        val names = SyntaxLanguages.names()
        assertTrue(names.size >= 40, "only ${names.size} grammars compiled in: $names")
        for (n in names) {
            if (SyntaxLanguages.hasTables(n)) {
                SyntaxParser(n).use { p -> p.parse("").close() }
            } else {
                val e = runCatching { SyntaxLanguages.load(n) }.exceptionOrNull() as SyntaxException
                assertEquals(SyntaxStatus.NO_TABLES, e.status, n)
            }
        }
    }
}
```
The tables of `code` grammars are provided at runtime from resources, and loading them is M2b's job. For now they
must report `NO_TABLES` cleanly.

- [ ] **Step 3: clojure**

Try regenerating it: `npx tree-sitter-cli@0.25.10 generate --abi 14 src/grammar.json` inside
`build/grammars/clojure` on the Mac. If `gen`'s difftest then passes, record in the lock that the build regenerates
it, and make `fetch.sh` do that step, pinned to the same CLI version. If it doesn't pass, leave it `excluded` with
the reason. Either outcome is fine; record which.

- [ ] **Step 4: Run and commit**

Run `:editor-syntax:jvmTest :editor-syntax:jvmResourceLoadTest :editor-syntax:iosSimulatorArm64Test` on the Mac.
Expected: all pass.
```bash
git add apps/editor-syntax && git commit -m "test(editor-syntax): every grammar loads; desktop loads natives from jar resources"
```

---

### Task 7: Docs and memory

- [ ] **Step 1:** `apps/editor-syntax/native/README.md`. Cover:
  - the pipeline (fetch → gen/difftest → target libraries)
  - the lock files and how to bump a grammar
  - the tables-as-data design, and why native grammars must never be downloaded (store rules)
  - the licences: tree-sitter MIT; grammars per lock; `jni_md.h` GPLv2 + Classpath exception; zlib
  - `THIRD-PARTY-NOTICES.md`, in the style of terminal-core
- [ ] **Step 2:** Append a dated entry to `~/.mux/domains/_inbox.md`. Cover:
  - the module, its targets, the manifest
  - the `#match?` predicate decision, which M2b implements through a Kotlin `Regex` filter
  - that clojure regenerated or was excluded
  - the zig-on-Mac requirement
- [ ] **Step 3:** Commit the README and notices.

## Not in M2a (M2b)
- The web backend (web-tree-sitter) behind the same public API: M2b turns `SyntaxParser`/`SyntaxTree`/`SyntaxQuery`
  into a small `SyntaxBackend` interface in `commonMain`.
- The `#match?`-family predicates, evaluated by a Kotlin `Regex` filter on the returned captures (flag bit 0 tells
  M2b when that's needed).
- Providing `code` grammars' tables from resources; later, on-demand download.
- The highlighting session wired to `editor-core`: `ChangeSet` → `TextEdit`s, background reparse on a rope
  snapshot, spans mapped through edits until the reparse lands, visible-range queries, injections (Vue/HTML/Markdown),
  folds/indents queries, capture → theme-token mapping, and the query sources and their licences (Kotlin from
  nvim-treesitter).
