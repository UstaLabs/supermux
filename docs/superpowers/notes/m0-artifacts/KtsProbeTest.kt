package dev.supermux.editor.spike

import io.github.treesitter.ktreesitter.InputEncoding
import io.github.treesitter.ktreesitter.Language
import io.github.treesitter.ktreesitter.Parser
import io.github.treesitter.ktreesitter.Query
import io.github.treesitter.ktreesitter.Tree
import kotlin.test.Test

/** Evidence only (never asserts): what ktreesitter actually hands tree-sitter per encoding. */
class KtsProbeTest {
    private fun dump(label: String, tree: Tree) {
        val root = tree.rootNode
        val lang = Language(jsonLanguagePointer())
        val caps = Query(lang, JSON_HIGHLIGHTS)(root).captures().map { (i, m) ->
            val c = m.captures[i.toInt()]; "${c.node.startByte}-${c.node.endByte} ${c.name}"
        }.toList()
        println("PROBE $label: root=${root.type} bytes=${root.startByte}-${root.endByte} hasError=${root.hasError}")
        println("PROBE $label: sexp=${root.sexp().take(300)}")
        println("PROBE $label: captures(raw bytes)=$caps")
    }

    @Test fun probe() {
        val lang = Language(jsonLanguagePointer())
        val p = Parser(lang)
        println("PROBE SAMPLE utf16=${SAMPLE.length} utf8=${SAMPLE.encodeToByteArray().size}")
        dump("utf16le-callback", p.parse(InputEncoding.UTF_16LE, null) { byte, _ ->
            val i = (byte / 2u).toInt(); if (i >= SAMPLE.length) "" else SAMPLE.substring(i)
        })
        dump("utf16le-string", p.parse(SAMPLE, InputEncoding.UTF_16LE))
        dump("utf8-string", p.parse(SAMPLE, InputEncoding.UTF_8))
        val utf8 = SAMPLE.encodeToByteArray()
        dump("utf8-callback", p.parse(InputEncoding.UTF_8, null) { byte, _ ->
            val b = byte.toInt(); if (b >= utf8.size) "" else utf8.copyOfRange(b, utf8.size).decodeToString()
        })
    }
}
