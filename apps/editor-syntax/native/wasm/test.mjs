// node native/wasm/test.mjs <supermux-syntax.wasm> <build/gen dir>
//
// The wasm module through the same loader the browser uses (src/wasmJsMain/resources/syntax-loader.mjs),
// with the host callbacks played by plain JS instead of Kotlin:
// - its imports are exactly the expected WASI + env set;
// - M0's JSON sample, read in 3-unit chunks (surrogate pairs split on purpose), gives M0's golden
//   captures, through the read trampoline;
// - a #match? predicate goes through the match trampoline; an #eq? one through the reader;
// - an incremental edit shifts the later spans; a sliced (timed-out) parse resumes to the same tree;
// - a code-only grammar's tables are provided and parse; a tampered blob is refused;
// - every tree is freed (ses_debug_live_trees back to 0).
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { loadRuntime, setHost } from '../../src/wasmJsMain/resources/syntax-loader.mjs';

const [wasmPath, genDir] = process.argv.slice(2);

// The imports the module may have: a change here is a change to the loader's WASI shim.
const EXPECTED_IMPORTS = JSON.parse(readFileSync(new URL('./expected-imports.json', import.meta.url), 'utf8'));
const module = new WebAssembly.Module(readFileSync(wasmPath));
const imports = WebAssembly.Module.imports(module).map((i) => `${i.module}.${i.name}`).sort();
assert.deepEqual(imports, [...EXPECTED_IMPORTS].sort(), 'module imports');

const rt = await loadRuntime(pathToFileURL(wasmPath).href);

const ints = (s) => { const u = new Uint16Array(s.length); for (let i = 0; i < s.length; i++) u[i] = s.charCodeAt(i); return new Int32Array(u.buffer); };
const bytes = (s) => Buffer.from(s, 'latin1');

// host contexts: id -> text / regex list
const texts = new Map(), regexes = new Map();
let reads = 0, matches = 0;
setHost(
  (ctx, index) => { reads++; const t = texts.get(ctx); return index >= t.text.length ? null : t.text.slice(index, index + t.chunk); },
  (ctx, id, text) => { matches++; return regexes.get(ctx)[id].test(text) ? 1 : 0; },
);

const SAMPLE = '{"ağ": [1, true, null], "e😀": "x\\n"}';
const JSON_HIGHLIGHTS = `
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
`;
const GOLDEN = [
  '1-5 string', '1-5 string.special.key',
  '8-9 number',
  '11-15 constant.builtin',
  '17-21 constant.builtin',
  '24-29 string', '24-29 string.special.key',
  '31-36 string',
  '33-35 escape',
];

function query(lang, source) {
  const q = rt.queryNew(lang, source);
  assert.ok(q, `query: status ${rt.status()} at ${rt.errOffset()} type ${rt.errType()}`);
  const names = [];
  for (let i = 0; i < rt.queryCaptureCount(q); i++) names.push(bytes(rt.queryCaptureName(q, i)).toString('utf8'));
  const re = [];
  for (let i = 0; i < rt.queryRegexCount(q); i++) re.push(new RegExp(bytes(rt.queryRegex(q, i)).toString('utf8'), 'u'));
  return { q, names, re };
}

function spans(qq, tree, len, readCtx, matchCtx) {
  if (matchCtx) regexes.set(matchCtx, qq.re);
  const packed = rt.queryCaptures(qq.q, tree, 0, len, readCtx, matchCtx);
  assert.notEqual(packed, null, `captures status ${rt.status()}`);
  const a = ints(packed), out = [];
  for (let i = 0; i < a.length; i += 4) out.push([a[i], a[i + 1], qq.names[a[i + 2]]]);
  out.sort((x, y) => x[0] - y[0] || y[1] - x[1] || (x[2] < y[2] ? -1 : x[2] > y[2] ? 1 : 0));
  return out.map(([s, e, n]) => `${s}-${e} ${n}`);
}

function parse(p, old, ctx) {
  const t = rt.parse(p, old, ctx);
  assert.ok(t, `parse status ${rt.status()}`);
  return t;
}

const base = rt.debugLiveTrees();
const p = rt.parserNew();
assert.equal(rt.parserSetLanguage(p, 'json'), 0);

// M0 golden, 3-unit chunks
texts.set(1, { text: SAMPLE, chunk: 3 });
const t1 = parse(p, 0, 1);
assert.ok(reads > 5, `the reader trampoline was called (${reads} reads)`);
const jq = query('json', JSON_HIGHLIGHTS);
assert.deepEqual(spans(jq, t1, SAMPLE.length, 1, 0), GOLDEN);
console.log('M0 golden: ok');

// incremental edit: `1` (8..9) -> `12345`
const next = SAMPLE.slice(0, 8) + '12345' + SAMPLE.slice(9);
rt.treeEdit(t1, 8, 9, 13, 0, 8, 0, 9, 0, 13);
texts.set(2, { text: next, chunk: 3 });
const t2 = parse(p, t1, 2);
const s2 = spans(jq, t2, next.length, 2, 0);
assert.equal(s2[2], '8-13 number');
assert.equal(s2[7], '35-40 string');
assert.equal(s2[8], '37-39 escape');
const changed = ints(rt.treeChangedRanges(t1, t2));
assert.ok(changed.length % 2 === 0);
rt.treeFree(t1);
rt.treeFree(t2);
console.log('incremental edit: ok');

// predicates: #match? through the match trampoline, #eq? through the reader
const pq = query('json', '((string (string_content) @k) @s (#match? @s "^\\"a\\\\p{L}"))\n((number) @one (#eq? @one "1"))');
const t3 = parse(p, 0, 1);
const before = matches;
assert.deepEqual(spans(pq, t3, SAMPLE.length, 1, 3), ['1-5 s', '2-4 k', '8-9 one']);
assert.ok(matches > before, 'the match trampoline was called');
assert.equal(spans(pq, t3, SAMPLE.length, 0, 0).length, 7, 'no reader, no matcher: predicates pass');
rt.treeFree(t3);
rt.queryFree(pq.q);
console.log('predicates: ok');

// a sliced parse: time out, resume with the same text, get the same tree as one full parse
const big = '[' + Array.from({ length: 60000 }, (_, i) => `{"k${i}": [${i}, "v${i}", true]}`).join(',\n') + ']';
texts.set(4, { text: big, chunk: 4096 });
rt.parserSetTimeoutMicros(p, 1000n);
let t4 = 0, slices = 0;
while (!t4) {
  t4 = rt.parse(p, 0, 4);
  if (!t4) { assert.equal(rt.status(), -13, 'SES_ERR_TIMEOUT'); slices++; }
  assert.ok(slices < 100000);
}
rt.parserSetTimeoutMicros(p, 0n);
const t5 = parse(p, 0, 4);
assert.ok(slices >= 2, `the parse was sliced (${slices} timeouts)`);
assert.equal(rt.treeSexp(t4), rt.treeSexp(t5));
rt.treeFree(t4);
rt.treeFree(t5);
console.log(`sliced parse: ok (${slices} slices)`);

// a code-only grammar: no tables, then provided, then parsing; a tampered blob refused
const names = Array.from({ length: rt.languageCount() }, (_, i) => rt.languageName(i));
assert.ok(names.length >= 40, `${names.length} grammars`);
const codeOnly = names.filter((n) => rt.languageHasTables(n) === 0);
assert.ok(codeOnly.includes('fsharp'), `fsharp is code-only: ${codeOnly}`);
const fs = readFileSync(`${genDir}/fsharp/fsharp.sesz`);
const bad = Buffer.from(fs); bad[bad.length - 1] ^= 0x5a;
assert.equal(rt.provideTables('fsharp', bad), -10, 'SES_ERR_BAD_TABLES');
assert.equal(rt.languageHasTables('fsharp'), 0);
assert.equal(rt.provideTables('fsharp', new Uint8Array(fs)), 0);
assert.equal(rt.languageLoad('fsharp'), 0);
const pf = rt.parserNew();
assert.equal(rt.parserSetLanguage(pf, 'fsharp'), 0);
texts.set(5, { text: 'let x = 1\n', chunk: 4096 });
const t6 = parse(pf, 0, 5);
assert.ok(!rt.treeHasError(t6), rt.treeSexp(t6));
rt.treeFree(t6);
rt.parserFree(pf);
console.log(`code-only tables: ok (${codeOnly.length} code-only grammars)`);

// every grammar compiled in can be set on a parser once it has tables (bundled ones now)
for (const n of names.filter((n) => rt.languageHasTables(n) === 1)) {
  const pp = rt.parserNew();
  assert.equal(rt.parserSetLanguage(pp, n), 0, n);
  texts.set(6, { text: 'x = 1\n', chunk: 4096 });
  rt.treeFree(parse(pp, 0, 6));
  rt.parserFree(pp);
}
console.log('bundled grammars parse: ok');

rt.queryFree(jq.q);
rt.parserFree(p);
assert.equal(rt.debugLiveTrees(), base, 'every tree freed');
console.log(`live trees back to ${base}: ok; memory ${(rt.memoryBytes() / 1048576).toFixed(1)} MiB`);
console.log('wasm module tests: PASSED');
