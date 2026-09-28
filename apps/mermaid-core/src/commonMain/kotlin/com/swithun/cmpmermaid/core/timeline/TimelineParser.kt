package com.swithun.cmpmermaid.core.timeline

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidPreprocessor

internal enum class TimelineDirection {
    LR,
    TD,
}

internal data class TimelineTask(
    val id: Int,
    val section: String,
    val type: String,
    val task: String,
    val score: Int = 0,
    val events: List<String>,
)

internal data class TimelineDocument(
    val direction: TimelineDirection,
    val title: String?,
    val accessibilityTitle: String?,
    val accessibilityDescription: String?,
    val sections: List<String>,
    val tasks: List<TimelineTask>,
)

/**
 * Kotlin translation of Mermaid 12.0.0 timeline/parser/timeline.jison
 * and timelineDb.js.
 */
internal class TimelineParser(
    private val diagramTitle: String?,
    private val lineOffset: Int,
) {
    fun parse(source: String): GMResult<TimelineDocument, MermaidError> {
        val lines = source.lines()
        val headerIndex = lines.indexOfFirst { line ->
            val text = line.trim()
            text.isNotEmpty() && !text.startsWith(COMMENT_PREFIX)
        }
        if (headerIndex < 0) {
            return parseError(1, 1, "Expected timeline")
        }
        val header = HEADER.matchEntire(lines[headerIndex].trim())
            ?: return parseError(headerIndex + 1, 1, "Expected timeline, timeline LR, or timeline TD")
        val direction = when (header.groupValues[1].uppercase()) {
            "TD" -> TimelineDirection.TD
            else -> TimelineDirection.LR
        }

        var title = diagramTitle
        var accessibilityTitle: String? = null
        var accessibilityDescription: String? = null
        var currentSection = ""
        val sections = mutableListOf<String>()
        val tasks = mutableListOf<MutableTimelineTask>()
        var multilineDescription: StringBuilder? = null
        var multilineDescriptionLine = 0

        lines.drop(headerIndex + 1).forEachIndexed { relativeIndex, sourceLine ->
            val line = headerIndex + relativeIndex + 2
            val pendingDescription = multilineDescription
            if (pendingDescription != null) {
                val closing = sourceLine.indexOf('}')
                if (closing < 0) {
                    if (pendingDescription.isNotEmpty()) pendingDescription.append('\n')
                    pendingDescription.append(sourceLine)
                    return@forEachIndexed
                }
                if (pendingDescription.isNotEmpty()) pendingDescription.append('\n')
                pendingDescription.append(sourceLine.substring(0, closing))
                accessibilityDescription = decode(pendingDescription.toString().trim())
                multilineDescription = null
                val suffix = sourceLine.substring(closing + 1)
                if (suffix.isNotBlank()) {
                    when (
                        val parsed = parseBodyLine(
                            rawLine = suffix,
                            line = line,
                            currentSection = currentSection,
                            tasks = tasks,
                            onTitle = { value -> title = value },
                            onAccessibilityTitle = { value -> accessibilityTitle = value },
                            onAccessibilityDescription = { value ->
                                accessibilityDescription = value
                            },
                            onSection = { value ->
                                currentSection = value
                                sections += value
                            },
                            onMultilineDescription = { initial ->
                                multilineDescription = StringBuilder(initial)
                                multilineDescriptionLine = line
                            },
                        )
                    ) {
                        is GMResult.Ok -> Unit
                        is GMResult.Err -> return parsed
                    }
                }
                return@forEachIndexed
            }

            when (
                val parsed = parseBodyLine(
                    rawLine = sourceLine,
                    line = line,
                    currentSection = currentSection,
                    tasks = tasks,
                    onTitle = { value -> title = value },
                    onAccessibilityTitle = { value -> accessibilityTitle = value },
                    onAccessibilityDescription = { value -> accessibilityDescription = value },
                    onSection = { value ->
                        currentSection = value
                        sections += value
                    },
                    onMultilineDescription = { initial ->
                        multilineDescription = StringBuilder(initial)
                        multilineDescriptionLine = line
                    },
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return parsed
            }
        }

        if (multilineDescription != null) {
            return parseError(
                multilineDescriptionLine,
                1,
                "Unterminated accDescr block",
            )
        }
        return GMResult.Ok(
            TimelineDocument(
                direction = direction,
                title = title?.takeIf(String::isNotBlank),
                accessibilityTitle = accessibilityTitle,
                accessibilityDescription = accessibilityDescription,
                sections = sections.toList(),
                tasks = tasks.map { task ->
                    TimelineTask(
                        id = task.id,
                        section = task.section,
                        type = task.section,
                        task = task.task,
                        events = task.events.toList(),
                    )
                },
            ),
        )
    }

    private fun parseBodyLine(
        rawLine: String,
        line: Int,
        currentSection: String,
        tasks: MutableList<MutableTimelineTask>,
        onTitle: (String) -> Unit,
        onAccessibilityTitle: (String) -> Unit,
        onAccessibilityDescription: (String) -> Unit,
        onSection: (String) -> Unit,
        onMultilineDescription: (String) -> Unit,
    ): GMResult<Unit, MermaidError> {
        val text = rawLine.trimStart()
        if (text.isBlank() || text.startsWith(COMMENT_PREFIX) || text.startsWith("#")) {
            return GMResult.Ok(Unit)
        }

        TITLE.matchEntire(text)?.let { match ->
            onTitle(decode(match.groupValues[1]))
            return GMResult.Ok(Unit)
        }
        SECTION.matchEntire(text)?.let { match ->
            onSection(decode(match.groupValues[1]))
            return GMResult.Ok(Unit)
        }
        ACCESSIBILITY_TITLE.matchEntire(text)?.let { match ->
            val value = match.groupValues[1].trim()
            if (value.isEmpty()) {
                return parseError(line, 1, "Expected accTitle value")
            }
            onAccessibilityTitle(decode(value))
            return GMResult.Ok(Unit)
        }
        ACCESSIBILITY_DESCRIPTION.matchEntire(text)?.let { match ->
            val value = match.groupValues[1].trim()
            if (value.isEmpty()) {
                return parseError(line, 1, "Expected accDescr value")
            }
            onAccessibilityDescription(decode(value))
            return GMResult.Ok(Unit)
        }
        ACCESSIBILITY_DESCRIPTION_BLOCK.find(text)?.let { match ->
            val contentStart = match.range.last + 1
            val closing = text.indexOf('}', contentStart)
            if (closing >= 0) {
                onAccessibilityDescription(decode(text.substring(contentStart, closing).trim()))
                val suffix = text.substring(closing + 1)
                return if (suffix.isBlank()) {
                    GMResult.Ok(Unit)
                } else {
                    parseBodyLine(
                        rawLine = suffix,
                        line = line,
                        currentSection = currentSection,
                        tasks = tasks,
                        onTitle = onTitle,
                        onAccessibilityTitle = onAccessibilityTitle,
                        onAccessibilityDescription = onAccessibilityDescription,
                        onSection = onSection,
                        onMultilineDescription = onMultilineDescription,
                    )
                }
            }
            onMultilineDescription(text.substring(contentStart))
            return GMResult.Ok(Unit)
        }

        val firstEvent = EVENT_DELIMITER.find(text)
        if (firstEvent?.range?.first == 0) {
            val task = tasks.lastOrNull()
                ?: return parseError(line, 1, "Timeline event must follow a time period")
            return appendEvents(text, line, task)
        }

        val periodEnd = firstEvent?.range?.first ?: text.indexOf('#').let { comment ->
            if (comment >= 0) comment else text.length
        }
        val period = text.substring(0, periodEnd)
        if (period.isBlank()) {
            return GMResult.Ok(Unit)
        }
        val task = MutableTimelineTask(
            id = tasks.size,
            section = currentSection,
            task = decode(period),
        )
        tasks += task
        if (firstEvent != null) {
            return appendEvents(text.substring(firstEvent.range.first), line, task)
        }
        return GMResult.Ok(Unit)
    }

    private fun appendEvents(
        source: String,
        line: Int,
        task: MutableTimelineTask,
    ): GMResult<Unit, MermaidError> {
        val delimiters = EVENT_DELIMITER.findAll(source).toList()
        if (delimiters.isEmpty() || delimiters.first().range.first != 0) {
            return parseError(line, 1, "Invalid timeline event")
        }
        delimiters.forEachIndexed { index, delimiter ->
            val start = delimiter.range.last + 1
            val end = delimiters.getOrNull(index + 1)?.range?.first ?: source.length
            val event = source.substring(start, end)
            if (event.isEmpty()) {
                return parseError(line, delimiter.range.first + 1, "Expected timeline event")
            }
            task.events += decode(event)
        }
        return GMResult.Ok(Unit)
    }

    private fun decode(source: String): String = MermaidPreprocessor.decodeEntities(source)

    private fun <T> parseError(
        line: Int,
        column: Int,
        message: String,
    ): GMResult<T, MermaidError> = GMResult.Err(
        MermaidError.Parse(
            line = line + lineOffset,
            column = column,
            message = message,
        ),
    )

    private data class MutableTimelineTask(
        val id: Int,
        val section: String,
        val task: String,
        val events: MutableList<String> = mutableListOf(),
    )

    private companion object {
        val HEADER = Regex("""(?i)^timeline(?:[ \t]+(LR|TD))?[ \t]*$""")
        val TITLE = Regex("""(?i)^title\s+(.+)$""")
        val SECTION = Regex("""(?i)^section\s+([^:\n]+)$""")
        val ACCESSIBILITY_TITLE = Regex("""(?i)^accTitle\s*:\s*(.*)$""")
        val ACCESSIBILITY_DESCRIPTION = Regex("""(?i)^accDescr\s*:\s*(.*)$""")
        val ACCESSIBILITY_DESCRIPTION_BLOCK = Regex("""(?i)^accDescr\s*\{\s*""")
        val EVENT_DELIMITER = Regex(""":\s""")
        const val COMMENT_PREFIX = "%%"
    }
}
