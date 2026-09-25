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
import hashlib
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
                                       dims=t.group('dims'), start=t.start(), end=end + 1)

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
                tables=[dict(name=tn, type=tables[tn]['type'], dims=tables[tn]['dims'],
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
    # The blob's bytes were laid out by the HOST compiler: the target must agree on every byte table's
    # size (first dimension = the row count the dump saw, the rest as declared).
    asserts = '\n'.join('_Static_assert(sizeof(%s[%d]%s) == %d, "%s: layout differs from the host that generated its tables");'
                        % (t['type'], r[2], ''.join(re.findall(r'\[[^\]]*\]', t['dims'])[1:]), len(r[1]), t['name'])
                        for t, r in zip(plan['tables'], recs) if r[0] == KIND_BYTES and len(r[1]))
    sha = hashlib.sha256(sesz).digest()
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

%s

static const uint8_t ses_sesz_sha256[32] = {%s};

static ses_grammar ses_desc = {
  .name = "%s", .abi = LANGUAGE_VERSION, .hash = 0x%016xULL, .table_count = %d, .specs = ses_specs,
  .fill = ses_fill, .language = &ses_language, .sha256 = ses_sesz_sha256,
};

ses_grammar *ses_grammar_%s(void) { return &ses_desc; }

%s{
  return (const TSLanguage *)ses_grammar_language(&ses_desc);
}
''' % (',\n    '.join(plan['kept']), fills, n, specs, asserts, ','.join(str(b) for b in sha), lang, h, n, lang,
       plan['head']))
    out.append(src[plan['fn_end']:])
    open(os.path.join(workdir, 'parser_%s.c' % lang), 'w').write(''.join(out))
    manifest = dict(lang=lang, abi=plan['abi'], hash='%016x' % h, sha256=sha.hex(), raw_size=len(payload), z_size=len(sesz),
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
