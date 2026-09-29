package com.swithun.cmpmermaid.core.ishikawa.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderOptions

/**
 * Deterministic Kotlin translation of Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/ishikawa/parser/ishikawa.jison.
 */
internal class IshikawaParser(
    private val options: MermaidRenderOptions,
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<IshikawaDb, MermaidError> {
        val lines = source
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .lines()
        val headerIndex = lines.indexOfFirst { line ->
            stripFullLineComment(line).isNotBlank()
        }
        if (headerIndex < 0) {
            return parseError(line = 1, column = 1, detail = "Expected Ishikawa diagram header")
        }
        val headerLine = stripFullLineComment(lines[headerIndex]).trimStart()
        val header = HEADER.matchEntire(headerLine)
            ?: return parseError(
                line = headerIndex + 1,
                column = 1,
                detail = "Expected 'ishikawa' or 'ishikawa-beta' diagram header",
            )

        val db = IshikawaDb(
            config = options.ishikawa,
            diagramTitle = diagramTitle,
        )
        var edgeCount = 0
        val headerRemainder = header.groupValues[1]
        if (
            headerRemainder.isNotBlank() &&
            !headerRemainder.trimStart().startsWith(COMMENT_PREFIX)
        ) {
            when (
                val added = addStatement(
                    raw = headerRemainder,
                    line = headerIndex + 1,
                    db = db,
                    edgeCount = edgeCount,
                )
            ) {
                is GMResult.Ok -> edgeCount = added.value
                is GMResult.Err -> return added
            }
        }

        for (index in headerIndex + 1 until lines.size) {
            val raw = stripFullLineComment(lines[index])
            if (raw.isBlank()) {
                continue
            }
            when (
                val added = addStatement(
                    raw = raw,
                    line = index + 1,
                    db = db,
                    edgeCount = edgeCount,
                )
            ) {
                is GMResult.Ok -> edgeCount = added.value
                is GMResult.Err -> return added
            }
        }
        if (db.getRoot() == null) {
            return parseError(
                line = headerIndex + 1,
                column = header.value.length + 1,
                detail = "Expected an Ishikawa effect",
            )
        }
        return GMResult.Ok(db)
    }

    private fun addStatement(
        raw: String,
        line: Int,
        db: IshikawaDb,
        edgeCount: Int,
    ): GMResult<Int, MermaidError> {
        val indentation = raw.takeWhile(::isHorizontalWhitespace).length
        val text = raw.drop(indentation).trim()
        if (text.isEmpty()) {
            return GMResult.Ok(edgeCount)
        }
        val nextEdgeCount = if (db.getRoot() == null) edgeCount else edgeCount + 1
        if (nextEdgeCount > options.maxEdges) {
            return GMResult.Err(
                MermaidError.ResourceLimit(
                    resource = "Ishikawa edges",
                    actual = nextEdgeCount,
                    maximum = options.maxEdges,
                ),
            )
        }
        return when (val added = db.addNode(indentation, text)) {
            is GMResult.Ok -> GMResult.Ok(nextEdgeCount)
            is GMResult.Err -> added
        }
    }

    private fun stripFullLineComment(line: String): String =
        if (line.trimStart().startsWith(COMMENT_PREFIX)) "" else line

    private fun parseError(
        line: Int,
        column: Int,
        detail: String,
    ): GMResult.Err<MermaidError> {
        val resolvedLine = line + lineOffset
        return GMResult.Err(
            MermaidError.Parse(
                line = resolvedLine,
                column = column,
                message = "Parse error on line $resolvedLine, column $column: $detail",
            ),
        )
    }

    private companion object {
        const val COMMENT_PREFIX = "%%"
        val HEADER = Regex(
            pattern = "^ishikawa(?:-beta)?(?=\\s|$)(.*)$",
            option = RegexOption.IGNORE_CASE,
        )

        fun isHorizontalWhitespace(character: Char): Boolean =
            character == ' ' || character == '\t'
    }
}
