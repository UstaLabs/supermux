package dev.supermux.editor.spike

import kotlinx.coroutines.await
import kotlin.js.Promise

// Thin JS glue: web-tree-sitter's API is object-heavy, so do the work in JS and hand Kotlin plain
// strings/ints. The real editor-syntax module will keep this shape (data out, no JS objects leak).
@JsFun("""async () => {
  const TS = await import('web-tree-sitter');
  await TS.Parser.init({ locateFile: (f) => '/ts/tree-sitter.wasm' });
  const lang = await TS.Language.load('/ts/tree-sitter-json.wasm');
  const parser = new TS.Parser(); parser.setLanguage(lang);
  return { TS, lang, parser, tree: null, src: '' };
}""")
private external fun jsOpen(): Promise<JsAny>

@JsFun("(h, s) => { h.src = s; h.tree = h.parser.parse(s); }")
private external fun jsParse(h: JsAny, s: String)

// Flattened "start end capture" lines; JS string indexes ARE UTF-16 units.
@JsFun("""(h, q) => {
  const query = new h.TS.Query(h.lang, q);
  return query.captures(h.tree.rootNode).map(c => c.node.startIndex + ' ' + c.node.endIndex + ' ' + c.name).join('\n');
}""")
private external fun jsHighlights(h: JsAny, q: String): String

@JsFun("""(h, from, to, ins) => {
  const pos = (s, i) => { const b = s.slice(0, i); const r = b.split('\n').length - 1; return { row: r, column: i - (b.lastIndexOf('\n') + 1) }; };
  const old = h.src; const next = old.slice(0, from) + ins + old.slice(to);
  h.tree.edit({ startIndex: from, oldEndIndex: to, newEndIndex: from + ins.length,
    startPosition: pos(old, from), oldEndPosition: pos(old, to), newEndPosition: pos(next, from + ins.length) });
  h.src = next; h.tree = h.parser.parse(next, h.tree); return next;
}""")
private external fun jsEdit(h: JsAny, from: Int, to: Int, ins: String): String

class WebHighlighter(private val h: JsAny) : SpikeHighlighter {
    override fun parse(source: String) = jsParse(h, source)
    override fun highlights(query: String): List<Span> =
        jsHighlights(h, query).lineSequence().filter { it.isNotBlank() }.map {
            val (s, e, c) = it.split(' ', limit = 3); Span(s.toInt(), e.toInt(), c)
        }.toList().sortedForGolden()
    override fun edit(from: Int, to: Int, insert: String) = jsEdit(h, from, to, insert)
    override fun close() {}
}

actual suspend fun openJsonHighlighter(): SpikeHighlighter = WebHighlighter(jsOpen().await())
