package com.swithun.cmpmermaid.core.treeview.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.math.roundToInt

internal data class TreeViewPreprocessResult(
    val text: String,
    val lineMap: Map<Int, Int>,
)

/**
 * Kotlin translation of Mermaid.js 12.0.0
 * packages/mermaid/src/diagrams/treeView/boxDrawingPreprocessor.ts.
 */
internal object TreeViewBoxDrawingPreprocessor {
    fun isBoxDrawingFormat(lines: List<String>): Boolean =
        lines.any { line -> line.any(ALL_BOX_CHARS::contains) }

    fun preprocess(
        input: String,
        lineOffset: Int,
    ): GMResult<TreeViewPreprocessResult, MermaidError> {
        val lines = input.split('\n')
        val keywordIndex = lines.indexOfFirst { line -> line.trim() == HEADER }
        if (keywordIndex < 0) {
            return GMResult.Ok(TreeViewPreprocessResult(input, emptyMap()))
        }

        val contentLines = lines
            .drop(keywordIndex + 1)
            .filterNot(::isIgnoredForDetection)
            .map { line -> line.replace("\t", INDENT_UNIT) }
        if (!isBoxDrawingFormat(contentLines)) {
            return GMResult.Ok(TreeViewPreprocessResult(input, emptyMap()))
        }

        val segmentWidth = inferSegmentWidth(contentLines)
        val output = mutableListOf<String>()
        val lineMap = linkedMapOf<Int, Int>()

        fun append(line: String, originalLineIndex: Int) {
            output += line
            lineMap[output.size] = originalLineIndex + 1
        }

        for (index in 0..keywordIndex) {
            append(lines[index], index)
        }
        for (index in keywordIndex + 1 until lines.size) {
            val line = lines[index]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() || isComment(line) || isMetadata(line) -> append(line, index)
                isDecorationOnly(line) -> Unit
                else -> {
                    val normalized = line.replace("\t", INDENT_UNIT)
                    val branchColumn = normalized.indexOfFirst(BRANCH_CHARS::contains)
                    when {
                        branchColumn >= 0 -> {
                            var cursor = branchColumn + 1
                            while (
                                cursor < normalized.length &&
                                normalized[cursor] in DASH_CHARS
                            ) {
                                cursor += 1
                            }
                            while (
                                cursor < normalized.length &&
                                normalized[cursor] == ' '
                            ) {
                                cursor += 1
                            }
                            val content = normalized.substring(cursor).trimEnd()
                            if (content.isEmpty()) {
                                return parseError(
                                    line = lineOffset + index + 1,
                                    column = branchColumn + 1,
                                    message = "Empty node - expected a filename or directory " +
                                        "name after the box-drawing prefix",
                                )
                            }
                            val depth = (branchColumn.toFloat() / segmentWidth)
                                .roundToInt() + 1
                            append(INDENT_UNIT.repeat(depth) + content, index)
                        }
                        normalized.all { character ->
                            character.isWhitespace() || character in ALL_BOX_CHARS
                        } -> Unit
                        normalized.any(ALL_BOX_CHARS::contains) -> append(line, index)
                        normalized.firstOrNull()?.isWhitespace() == true -> {
                            return parseError(
                                line = lineOffset + index + 1,
                                column = 1,
                                message = "Unexpected indentation without box-drawing " +
                                    "characters. In box-drawing format, use branch prefixes " +
                                    "for indented nodes.",
                            )
                        }
                        else -> append(line, index)
                    }
                }
            }
        }
        return GMResult.Ok(
            TreeViewPreprocessResult(
                text = output.joinToString("\n"),
                lineMap = lineMap,
            ),
        )
    }

    private fun inferSegmentWidth(lines: List<String>): Int {
        lines.forEach { line ->
            val branchColumn = line.indexOfFirst(BRANCH_CHARS::contains)
            if (branchColumn > 0) {
                return branchColumn
            }
        }
        return 4
    }

    private fun isIgnoredForDetection(line: String): Boolean =
        line.isBlank() || isComment(line) || isMetadata(line) || isDecorationOnly(line)

    private fun isComment(line: String): Boolean = line.trimStart().startsWith("%%")

    private fun isMetadata(line: String): Boolean {
        val content = line.trimStart()
        return content.startsWith("title ") ||
            content.startsWith("title\t") ||
            content.startsWith("accTitle:") ||
            content.startsWith("accDescr:") ||
            content.startsWith("accDescr {")
    }

    private fun isDecorationOnly(line: String): Boolean =
        line.all { character ->
            character.isWhitespace() || character == '│' || character == '┃'
        }

    private fun parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line,
            column = column,
            message = "TreeView parse error at $line:$column: $message",
        ),
    )

    private const val HEADER = "treeView-beta"
    private const val INDENT_UNIT = "    "
    private val ALL_BOX_CHARS = setOf('─', '━', '│', '┃', '└', '┗', '├', '┣')
    private val BRANCH_CHARS = setOf('└', '┗', '├', '┣')
    private val DASH_CHARS = setOf('─', '━')
}
