package com.swithun.cmpmermaid.core.mindmap.upstream.mermaid

/**
 * Generated from Mermaid 12.0.0 mindmap.jison.
 * Upstream mindmap.jison SHA-256: 1114fbccc641f2ad59a56eec452e21dfb947d78283adf571a6ce24b09dce6993
 * Distribution chunk SHA-256: b2a2870e2bfcddeae16ee210d69b6cef003afbf6c2542d868e80daae73532510
 *
 * Do not edit manually. Run:
 *   cd tools/official-reference && npm run generate:mindmap-parser
 */
internal object MindmapJisonTables {
    val symbolIds: Map<String, Int> = mapOf(
        "error" to 2,
        "start" to 3,
        "mindMap" to 4,
        "spaceLines" to 5,
        "SPACELINE" to 6,
        "NL" to 7,
        "MINDMAP" to 8,
        "document" to 9,
        "stop" to 10,
        "EOF" to 11,
        "statement" to 12,
        "SPACELIST" to 13,
        "node" to 14,
        "ICON" to 15,
        "CLASS" to 16,
        "nodeWithId" to 17,
        "nodeWithoutId" to 18,
        "NODE_DSTART" to 19,
        "NODE_DESCR" to 20,
        "NODE_DEND" to 21,
        "NODE_ID" to 22,
        "\$accept" to 0,
        "\$end" to 1,
    )

    val terminalNames: Map<Int, String> = mapOf(
        2 to "error",
        6 to "SPACELINE",
        7 to "NL",
        8 to "MINDMAP",
        11 to "EOF",
        13 to "SPACELIST",
        15 to "ICON",
        16 to "CLASS",
        19 to "NODE_DSTART",
        20 to "NODE_DESCR",
        21 to "NODE_DEND",
        22 to "NODE_ID",
    )

    val productions: Array<MindmapJisonProduction> = arrayOf(
        MindmapJisonProduction(symbol = 0, length = 0),
        MindmapJisonProduction(symbol = 3, length = 1),
        MindmapJisonProduction(symbol = 3, length = 2),
        MindmapJisonProduction(symbol = 5, length = 1),
        MindmapJisonProduction(symbol = 5, length = 2),
        MindmapJisonProduction(symbol = 5, length = 2),
        MindmapJisonProduction(symbol = 4, length = 2),
        MindmapJisonProduction(symbol = 4, length = 3),
        MindmapJisonProduction(symbol = 10, length = 1),
        MindmapJisonProduction(symbol = 10, length = 1),
        MindmapJisonProduction(symbol = 10, length = 1),
        MindmapJisonProduction(symbol = 10, length = 2),
        MindmapJisonProduction(symbol = 10, length = 2),
        MindmapJisonProduction(symbol = 9, length = 3),
        MindmapJisonProduction(symbol = 9, length = 2),
        MindmapJisonProduction(symbol = 12, length = 2),
        MindmapJisonProduction(symbol = 12, length = 2),
        MindmapJisonProduction(symbol = 12, length = 2),
        MindmapJisonProduction(symbol = 12, length = 1),
        MindmapJisonProduction(symbol = 12, length = 1),
        MindmapJisonProduction(symbol = 12, length = 1),
        MindmapJisonProduction(symbol = 12, length = 1),
        MindmapJisonProduction(symbol = 12, length = 1),
        MindmapJisonProduction(symbol = 14, length = 1),
        MindmapJisonProduction(symbol = 14, length = 1),
        MindmapJisonProduction(symbol = 18, length = 3),
        MindmapJisonProduction(symbol = 17, length = 1),
        MindmapJisonProduction(symbol = 17, length = 4),
    )

    val states: Array<Map<Int, MindmapJisonCell>> = buildList {
        addAll(stateChunk0())
        addAll(stateChunk1())
    }.toTypedArray()

    private fun stateChunk0(): Array<Map<Int, MindmapJisonCell>> = arrayOf(
        mapOf(
            3 to MindmapJisonCell.Goto(1),
            4 to MindmapJisonCell.Goto(2),
            5 to MindmapJisonCell.Goto(3),
            6 to MindmapJisonCell.Shift(5),
            8 to MindmapJisonCell.Shift(4),
        ),
        mapOf(
            1 to MindmapJisonCell.Accept,
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(1),
        ),
        mapOf(
            4 to MindmapJisonCell.Goto(6),
            6 to MindmapJisonCell.Shift(7),
            7 to MindmapJisonCell.Shift(8),
            8 to MindmapJisonCell.Shift(4),
        ),
        mapOf(
            6 to MindmapJisonCell.Shift(13),
            7 to MindmapJisonCell.Shift(10),
            9 to MindmapJisonCell.Goto(9),
            12 to MindmapJisonCell.Goto(11),
            13 to MindmapJisonCell.Shift(12),
            14 to MindmapJisonCell.Goto(14),
            15 to MindmapJisonCell.Shift(15),
            16 to MindmapJisonCell.Shift(16),
            17 to MindmapJisonCell.Goto(17),
            18 to MindmapJisonCell.Goto(18),
            19 to MindmapJisonCell.Shift(20),
            22 to MindmapJisonCell.Shift(19),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(3),
            7 to MindmapJisonCell.Reduce(3),
            8 to MindmapJisonCell.Reduce(3),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(2),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(4),
            7 to MindmapJisonCell.Reduce(4),
            8 to MindmapJisonCell.Reduce(4),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(5),
            7 to MindmapJisonCell.Reduce(5),
            8 to MindmapJisonCell.Reduce(5),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(6),
            6 to MindmapJisonCell.Shift(13),
            12 to MindmapJisonCell.Goto(21),
            13 to MindmapJisonCell.Shift(12),
            14 to MindmapJisonCell.Goto(14),
            15 to MindmapJisonCell.Shift(15),
            16 to MindmapJisonCell.Shift(16),
            17 to MindmapJisonCell.Goto(17),
            18 to MindmapJisonCell.Goto(18),
            19 to MindmapJisonCell.Shift(20),
            22 to MindmapJisonCell.Shift(19),
        ),
        mapOf(
            6 to MindmapJisonCell.Shift(13),
            9 to MindmapJisonCell.Goto(22),
            12 to MindmapJisonCell.Goto(11),
            13 to MindmapJisonCell.Shift(12),
            14 to MindmapJisonCell.Goto(14),
            15 to MindmapJisonCell.Shift(15),
            16 to MindmapJisonCell.Shift(16),
            17 to MindmapJisonCell.Goto(17),
            18 to MindmapJisonCell.Goto(18),
            19 to MindmapJisonCell.Shift(20),
            22 to MindmapJisonCell.Shift(19),
        ),
        mapOf(
            6 to MindmapJisonCell.Shift(26),
            7 to MindmapJisonCell.Shift(24),
            10 to MindmapJisonCell.Goto(23),
            11 to MindmapJisonCell.Shift(25),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(22),
            7 to MindmapJisonCell.Reduce(22),
            11 to MindmapJisonCell.Reduce(22),
            14 to MindmapJisonCell.Goto(27),
            15 to MindmapJisonCell.Shift(28),
            16 to MindmapJisonCell.Shift(29),
            17 to MindmapJisonCell.Goto(17),
            18 to MindmapJisonCell.Goto(18),
            19 to MindmapJisonCell.Shift(20),
            22 to MindmapJisonCell.Shift(19),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(18),
            7 to MindmapJisonCell.Reduce(18),
            11 to MindmapJisonCell.Reduce(18),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(19),
            7 to MindmapJisonCell.Reduce(19),
            11 to MindmapJisonCell.Reduce(19),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(20),
            7 to MindmapJisonCell.Reduce(20),
            11 to MindmapJisonCell.Reduce(20),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(21),
            7 to MindmapJisonCell.Reduce(21),
            11 to MindmapJisonCell.Reduce(21),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(23),
            7 to MindmapJisonCell.Reduce(23),
            11 to MindmapJisonCell.Reduce(23),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(24),
            7 to MindmapJisonCell.Reduce(24),
            11 to MindmapJisonCell.Reduce(24),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(26),
            7 to MindmapJisonCell.Reduce(26),
            11 to MindmapJisonCell.Reduce(26),
            19 to MindmapJisonCell.Shift(30),
        ),
    )

    private fun stateChunk1(): Array<Map<Int, MindmapJisonCell>> = arrayOf(
        mapOf(
            20 to MindmapJisonCell.Shift(31),
        ),
        mapOf(
            6 to MindmapJisonCell.Shift(26),
            7 to MindmapJisonCell.Shift(24),
            10 to MindmapJisonCell.Goto(32),
            11 to MindmapJisonCell.Shift(25),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(7),
            6 to MindmapJisonCell.Shift(13),
            12 to MindmapJisonCell.Goto(21),
            13 to MindmapJisonCell.Shift(12),
            14 to MindmapJisonCell.Goto(14),
            15 to MindmapJisonCell.Shift(15),
            16 to MindmapJisonCell.Shift(16),
            17 to MindmapJisonCell.Goto(17),
            18 to MindmapJisonCell.Goto(18),
            19 to MindmapJisonCell.Shift(20),
            22 to MindmapJisonCell.Shift(19),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(14),
            6 to MindmapJisonCell.Reduce(14),
            7 to MindmapJisonCell.Shift(33),
            11 to MindmapJisonCell.Shift(34),
            13 to MindmapJisonCell.Reduce(14),
            15 to MindmapJisonCell.Reduce(14),
            16 to MindmapJisonCell.Reduce(14),
            19 to MindmapJisonCell.Reduce(14),
            22 to MindmapJisonCell.Reduce(14),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(8),
            6 to MindmapJisonCell.Reduce(8),
            7 to MindmapJisonCell.Reduce(8),
            11 to MindmapJisonCell.Reduce(8),
            13 to MindmapJisonCell.Reduce(8),
            15 to MindmapJisonCell.Reduce(8),
            16 to MindmapJisonCell.Reduce(8),
            19 to MindmapJisonCell.Reduce(8),
            22 to MindmapJisonCell.Reduce(8),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(9),
            6 to MindmapJisonCell.Reduce(9),
            7 to MindmapJisonCell.Reduce(9),
            11 to MindmapJisonCell.Reduce(9),
            13 to MindmapJisonCell.Reduce(9),
            15 to MindmapJisonCell.Reduce(9),
            16 to MindmapJisonCell.Reduce(9),
            19 to MindmapJisonCell.Reduce(9),
            22 to MindmapJisonCell.Reduce(9),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(10),
            6 to MindmapJisonCell.Reduce(10),
            7 to MindmapJisonCell.Reduce(10),
            11 to MindmapJisonCell.Reduce(10),
            13 to MindmapJisonCell.Reduce(10),
            15 to MindmapJisonCell.Reduce(10),
            16 to MindmapJisonCell.Reduce(10),
            19 to MindmapJisonCell.Reduce(10),
            22 to MindmapJisonCell.Reduce(10),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(15),
            7 to MindmapJisonCell.Reduce(15),
            11 to MindmapJisonCell.Reduce(15),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(16),
            7 to MindmapJisonCell.Reduce(16),
            11 to MindmapJisonCell.Reduce(16),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(17),
            7 to MindmapJisonCell.Reduce(17),
            11 to MindmapJisonCell.Reduce(17),
        ),
        mapOf(
            20 to MindmapJisonCell.Shift(35),
        ),
        mapOf(
            21 to MindmapJisonCell.Shift(36),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(13),
            6 to MindmapJisonCell.Reduce(13),
            7 to MindmapJisonCell.Shift(33),
            11 to MindmapJisonCell.Shift(34),
            13 to MindmapJisonCell.Reduce(13),
            15 to MindmapJisonCell.Reduce(13),
            16 to MindmapJisonCell.Reduce(13),
            19 to MindmapJisonCell.Reduce(13),
            22 to MindmapJisonCell.Reduce(13),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(11),
            6 to MindmapJisonCell.Reduce(11),
            7 to MindmapJisonCell.Reduce(11),
            11 to MindmapJisonCell.Reduce(11),
            13 to MindmapJisonCell.Reduce(11),
            15 to MindmapJisonCell.Reduce(11),
            16 to MindmapJisonCell.Reduce(11),
            19 to MindmapJisonCell.Reduce(11),
            22 to MindmapJisonCell.Reduce(11),
        ),
        mapOf(
            1 to MindmapJisonCell.Reduce(12),
            6 to MindmapJisonCell.Reduce(12),
            7 to MindmapJisonCell.Reduce(12),
            11 to MindmapJisonCell.Reduce(12),
            13 to MindmapJisonCell.Reduce(12),
            15 to MindmapJisonCell.Reduce(12),
            16 to MindmapJisonCell.Reduce(12),
            19 to MindmapJisonCell.Reduce(12),
            22 to MindmapJisonCell.Reduce(12),
        ),
        mapOf(
            21 to MindmapJisonCell.Shift(37),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(25),
            7 to MindmapJisonCell.Reduce(25),
            11 to MindmapJisonCell.Reduce(25),
        ),
        mapOf(
            6 to MindmapJisonCell.Reduce(27),
            7 to MindmapJisonCell.Reduce(27),
            11 to MindmapJisonCell.Reduce(27),
        ),
    )

    val lexerPatterns: List<MindmapJisonLexerPattern> = buildList {
        addAll(lexerChunk0())
        addAll(lexerChunk1())
    }

    private fun lexerChunk0(): List<MindmapJisonLexerPattern> = listOf(
        MindmapJisonLexerPattern(0, "^(?:\\s*%%.*)"),
        MindmapJisonLexerPattern(1, "^(?:mindmap\\b)"),
        MindmapJisonLexerPattern(2, "^(?::::)"),
        MindmapJisonLexerPattern(3, "^(?:.+)"),
        MindmapJisonLexerPattern(4, "^(?:\\n)"),
        MindmapJisonLexerPattern(5, "^(?:::icon\\()"),
        MindmapJisonLexerPattern(6, "^(?:[\\s]+[\\n])"),
        MindmapJisonLexerPattern(7, "^(?:[\\n]+)"),
        MindmapJisonLexerPattern(8, "^(?:[^\\)]+)"),
        MindmapJisonLexerPattern(9, "^(?:\\))"),
        MindmapJisonLexerPattern(10, "^(?:-\\))"),
        MindmapJisonLexerPattern(11, "^(?:\\(-)"),
        MindmapJisonLexerPattern(12, "^(?:\\)\\))"),
        MindmapJisonLexerPattern(13, "^(?:\\))"),
        MindmapJisonLexerPattern(14, "^(?:\\(\\()"),
        MindmapJisonLexerPattern(15, "^(?:\\{\\{)"),
        MindmapJisonLexerPattern(16, "^(?:\\()"),
        MindmapJisonLexerPattern(17, "^(?:\\[)"),
        MindmapJisonLexerPattern(18, "^(?:[\\s]+)"),
        MindmapJisonLexerPattern(19, "^(?:[^\\(\\[\\n\\)\\{\\}]+)"),
    )

    private fun lexerChunk1(): List<MindmapJisonLexerPattern> = listOf(
        MindmapJisonLexerPattern(20, "^(?:\$)"),
        MindmapJisonLexerPattern(21, "^(?:[\"][`])"),
        MindmapJisonLexerPattern(22, "^(?:[^`\"]+)"),
        MindmapJisonLexerPattern(23, "^(?:[`][\"])"),
        MindmapJisonLexerPattern(24, "^(?:[\"])"),
        MindmapJisonLexerPattern(25, "^(?:[^\"]+)"),
        MindmapJisonLexerPattern(26, "^(?:[\"])"),
        MindmapJisonLexerPattern(27, "^(?:[\\)]\\))"),
        MindmapJisonLexerPattern(28, "^(?:[\\)])"),
        MindmapJisonLexerPattern(29, "^(?:[\\]])"),
        MindmapJisonLexerPattern(30, "^(?:\\}\\})"),
        MindmapJisonLexerPattern(31, "^(?:\\(-)"),
        MindmapJisonLexerPattern(32, "^(?:-\\))"),
        MindmapJisonLexerPattern(33, "^(?:\\(\\()"),
        MindmapJisonLexerPattern(34, "^(?:\\()"),
        MindmapJisonLexerPattern(35, "^(?:[^\\)\\]\\(\\}]+)"),
        MindmapJisonLexerPattern(36, "^(?:.+(?!\\(\\())"),
    )

    val lexerConditions: Map<String, IntArray> = mapOf(
        "CLASS" to intArrayOf(3, 4),
        "ICON" to intArrayOf(8, 9),
        "NSTR2" to intArrayOf(22, 23),
        "NSTR" to intArrayOf(25, 26),
        "NODE" to intArrayOf(21, 24, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36),
        "INITIAL" to intArrayOf(0, 1, 2, 5, 6, 7, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20),
    )
}
