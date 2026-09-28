package com.swithun.cmpmermaid.core.journey.upstream.mermaid

/**
 * Generated from Mermaid 12.0.0 journey.jison.
 * Upstream journey.jison SHA-256: 2230b9f958f7c2f2381149b0e484cd203ff919cf43abd065beba7ab6b5a660a5
 * Distribution chunk SHA-256: b2a84793cf6fea9b0c5cf7f680bcbfebf648dadf0535d4fdfd7f636c025e3c0d
 *
 * Do not edit manually. Run:
 *   cd tools/official-reference && npm run generate:journey-parser
 */
internal object JourneyJisonTables {
    val symbolIds: Map<String, Int> = mapOf(
        "error" to 2,
        "start" to 3,
        "journey" to 4,
        "document" to 5,
        "EOF" to 6,
        "line" to 7,
        "SPACE" to 8,
        "statement" to 9,
        "NEWLINE" to 10,
        "title" to 11,
        "acc_title" to 12,
        "acc_title_value" to 13,
        "acc_descr" to 14,
        "acc_descr_value" to 15,
        "acc_descr_multiline_value" to 16,
        "section" to 17,
        "taskName" to 18,
        "taskData" to 19,
        "\$accept" to 0,
        "\$end" to 1,
    )

    val terminalNames: Map<Int, String> = mapOf(
        2 to "error",
        4 to "journey",
        6 to "EOF",
        8 to "SPACE",
        10 to "NEWLINE",
        11 to "title",
        12 to "acc_title",
        13 to "acc_title_value",
        14 to "acc_descr",
        15 to "acc_descr_value",
        16 to "acc_descr_multiline_value",
        17 to "section",
        18 to "taskName",
        19 to "taskData",
    )

    val productions: Array<JourneyJisonProduction> = arrayOf(
        JourneyJisonProduction(symbol = 0, length = 0),
        JourneyJisonProduction(symbol = 3, length = 3),
        JourneyJisonProduction(symbol = 5, length = 0),
        JourneyJisonProduction(symbol = 5, length = 2),
        JourneyJisonProduction(symbol = 7, length = 2),
        JourneyJisonProduction(symbol = 7, length = 1),
        JourneyJisonProduction(symbol = 7, length = 1),
        JourneyJisonProduction(symbol = 7, length = 1),
        JourneyJisonProduction(symbol = 9, length = 1),
        JourneyJisonProduction(symbol = 9, length = 2),
        JourneyJisonProduction(symbol = 9, length = 2),
        JourneyJisonProduction(symbol = 9, length = 1),
        JourneyJisonProduction(symbol = 9, length = 1),
        JourneyJisonProduction(symbol = 9, length = 2),
    )

    val states: Array<Map<Int, JourneyJisonCell>> = buildList {
        addAll(stateChunk0())
    }.toTypedArray()

    private fun stateChunk0(): Array<Map<Int, JourneyJisonCell>> = arrayOf(
        mapOf(
            3 to JourneyJisonCell.Goto(1),
            4 to JourneyJisonCell.Shift(2),
        ),
        mapOf(
            1 to JourneyJisonCell.Accept,
        ),
        mapOf(
            5 to JourneyJisonCell.Goto(3),
            6 to JourneyJisonCell.Reduce(2),
            8 to JourneyJisonCell.Reduce(2),
            10 to JourneyJisonCell.Reduce(2),
            11 to JourneyJisonCell.Reduce(2),
            12 to JourneyJisonCell.Reduce(2),
            14 to JourneyJisonCell.Reduce(2),
            16 to JourneyJisonCell.Reduce(2),
            17 to JourneyJisonCell.Reduce(2),
            18 to JourneyJisonCell.Reduce(2),
        ),
        mapOf(
            6 to JourneyJisonCell.Shift(4),
            7 to JourneyJisonCell.Goto(5),
            8 to JourneyJisonCell.Shift(6),
            9 to JourneyJisonCell.Goto(7),
            10 to JourneyJisonCell.Shift(8),
            11 to JourneyJisonCell.Shift(9),
            12 to JourneyJisonCell.Shift(10),
            14 to JourneyJisonCell.Shift(11),
            16 to JourneyJisonCell.Shift(12),
            17 to JourneyJisonCell.Shift(13),
            18 to JourneyJisonCell.Shift(14),
        ),
        mapOf(
            1 to JourneyJisonCell.Reduce(1),
            6 to JourneyJisonCell.Reduce(7),
            8 to JourneyJisonCell.Reduce(7),
            10 to JourneyJisonCell.Reduce(7),
            11 to JourneyJisonCell.Reduce(7),
            12 to JourneyJisonCell.Reduce(7),
            14 to JourneyJisonCell.Reduce(7),
            16 to JourneyJisonCell.Reduce(7),
            17 to JourneyJisonCell.Reduce(7),
            18 to JourneyJisonCell.Reduce(7),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(3),
            8 to JourneyJisonCell.Reduce(3),
            10 to JourneyJisonCell.Reduce(3),
            11 to JourneyJisonCell.Reduce(3),
            12 to JourneyJisonCell.Reduce(3),
            14 to JourneyJisonCell.Reduce(3),
            16 to JourneyJisonCell.Reduce(3),
            17 to JourneyJisonCell.Reduce(3),
            18 to JourneyJisonCell.Reduce(3),
        ),
        mapOf(
            9 to JourneyJisonCell.Goto(15),
            11 to JourneyJisonCell.Shift(9),
            12 to JourneyJisonCell.Shift(10),
            14 to JourneyJisonCell.Shift(11),
            16 to JourneyJisonCell.Shift(12),
            17 to JourneyJisonCell.Shift(13),
            18 to JourneyJisonCell.Shift(14),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(5),
            8 to JourneyJisonCell.Reduce(5),
            10 to JourneyJisonCell.Reduce(5),
            11 to JourneyJisonCell.Reduce(5),
            12 to JourneyJisonCell.Reduce(5),
            14 to JourneyJisonCell.Reduce(5),
            16 to JourneyJisonCell.Reduce(5),
            17 to JourneyJisonCell.Reduce(5),
            18 to JourneyJisonCell.Reduce(5),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(6),
            8 to JourneyJisonCell.Reduce(6),
            10 to JourneyJisonCell.Reduce(6),
            11 to JourneyJisonCell.Reduce(6),
            12 to JourneyJisonCell.Reduce(6),
            14 to JourneyJisonCell.Reduce(6),
            16 to JourneyJisonCell.Reduce(6),
            17 to JourneyJisonCell.Reduce(6),
            18 to JourneyJisonCell.Reduce(6),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(8),
            8 to JourneyJisonCell.Reduce(8),
            10 to JourneyJisonCell.Reduce(8),
            11 to JourneyJisonCell.Reduce(8),
            12 to JourneyJisonCell.Reduce(8),
            14 to JourneyJisonCell.Reduce(8),
            16 to JourneyJisonCell.Reduce(8),
            17 to JourneyJisonCell.Reduce(8),
            18 to JourneyJisonCell.Reduce(8),
        ),
        mapOf(
            13 to JourneyJisonCell.Shift(16),
        ),
        mapOf(
            15 to JourneyJisonCell.Shift(17),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(11),
            8 to JourneyJisonCell.Reduce(11),
            10 to JourneyJisonCell.Reduce(11),
            11 to JourneyJisonCell.Reduce(11),
            12 to JourneyJisonCell.Reduce(11),
            14 to JourneyJisonCell.Reduce(11),
            16 to JourneyJisonCell.Reduce(11),
            17 to JourneyJisonCell.Reduce(11),
            18 to JourneyJisonCell.Reduce(11),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(12),
            8 to JourneyJisonCell.Reduce(12),
            10 to JourneyJisonCell.Reduce(12),
            11 to JourneyJisonCell.Reduce(12),
            12 to JourneyJisonCell.Reduce(12),
            14 to JourneyJisonCell.Reduce(12),
            16 to JourneyJisonCell.Reduce(12),
            17 to JourneyJisonCell.Reduce(12),
            18 to JourneyJisonCell.Reduce(12),
        ),
        mapOf(
            19 to JourneyJisonCell.Shift(18),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(4),
            8 to JourneyJisonCell.Reduce(4),
            10 to JourneyJisonCell.Reduce(4),
            11 to JourneyJisonCell.Reduce(4),
            12 to JourneyJisonCell.Reduce(4),
            14 to JourneyJisonCell.Reduce(4),
            16 to JourneyJisonCell.Reduce(4),
            17 to JourneyJisonCell.Reduce(4),
            18 to JourneyJisonCell.Reduce(4),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(9),
            8 to JourneyJisonCell.Reduce(9),
            10 to JourneyJisonCell.Reduce(9),
            11 to JourneyJisonCell.Reduce(9),
            12 to JourneyJisonCell.Reduce(9),
            14 to JourneyJisonCell.Reduce(9),
            16 to JourneyJisonCell.Reduce(9),
            17 to JourneyJisonCell.Reduce(9),
            18 to JourneyJisonCell.Reduce(9),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(10),
            8 to JourneyJisonCell.Reduce(10),
            10 to JourneyJisonCell.Reduce(10),
            11 to JourneyJisonCell.Reduce(10),
            12 to JourneyJisonCell.Reduce(10),
            14 to JourneyJisonCell.Reduce(10),
            16 to JourneyJisonCell.Reduce(10),
            17 to JourneyJisonCell.Reduce(10),
            18 to JourneyJisonCell.Reduce(10),
        ),
        mapOf(
            6 to JourneyJisonCell.Reduce(13),
            8 to JourneyJisonCell.Reduce(13),
            10 to JourneyJisonCell.Reduce(13),
            11 to JourneyJisonCell.Reduce(13),
            12 to JourneyJisonCell.Reduce(13),
            14 to JourneyJisonCell.Reduce(13),
            16 to JourneyJisonCell.Reduce(13),
            17 to JourneyJisonCell.Reduce(13),
            18 to JourneyJisonCell.Reduce(13),
        ),
    )

    val lexerPatterns: List<JourneyJisonLexerPattern> = buildList {
        addAll(lexerChunk0())
    }

    private fun lexerChunk0(): List<JourneyJisonLexerPattern> = listOf(
        JourneyJisonLexerPattern(0, "^(?:%(?!\\{)[^\\n]*)"),
        JourneyJisonLexerPattern(1, "^(?:[^\\}]%%[^\\n]*)"),
        JourneyJisonLexerPattern(2, "^(?:[\\n]+)"),
        JourneyJisonLexerPattern(3, "^(?:\\s+)"),
        JourneyJisonLexerPattern(4, "^(?:#[^\\n]*)"),
        JourneyJisonLexerPattern(5, "^(?:journey\\b)"),
        JourneyJisonLexerPattern(6, "^(?:title\\s[^#\\n;]+)"),
        JourneyJisonLexerPattern(7, "^(?:accTitle\\s*:\\s*)"),
        JourneyJisonLexerPattern(8, "^(?:(?!\\n||)*[^\\n]*)"),
        JourneyJisonLexerPattern(9, "^(?:accDescr\\s*:\\s*)"),
        JourneyJisonLexerPattern(10, "^(?:(?!\\n||)*[^\\n]*)"),
        JourneyJisonLexerPattern(11, "^(?:accDescr\\s*\\{\\s*)"),
        JourneyJisonLexerPattern(12, "^(?:[\\}])"),
        JourneyJisonLexerPattern(13, "^(?:[^\\}]*)"),
        JourneyJisonLexerPattern(14, "^(?:section\\s[^#:\\n;]+)"),
        JourneyJisonLexerPattern(15, "^(?:[^#:\\n;]+)"),
        JourneyJisonLexerPattern(16, "^(?::[^#\\n;]+)"),
        JourneyJisonLexerPattern(17, "^(?::)"),
        JourneyJisonLexerPattern(18, "^(?:\$)"),
        JourneyJisonLexerPattern(19, "^(?:.)"),
    )

    val lexerConditions: Map<String, IntArray> = mapOf(
        "acc_descr_multiline" to intArrayOf(12, 13),
        "acc_descr" to intArrayOf(10),
        "acc_title" to intArrayOf(8),
        "INITIAL" to intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 9, 11, 14, 15, 16, 17, 18, 19),
    )
}
