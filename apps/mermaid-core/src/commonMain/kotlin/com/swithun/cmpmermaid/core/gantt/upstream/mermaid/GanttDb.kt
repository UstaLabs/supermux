package com.swithun.cmpmermaid.core.gantt.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidSecurityLevel
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidUrlSanitizer
import kotlin.time.Clock

/**
 * Kotlin translation of Mermaid 12.0.0 ganttDb.js.
 */
internal class GanttDb(
    diagramTitle: String? = null,
    private val securityLevel: MermaidSecurityLevel = MermaidSecurityLevel.Strict,
    defaultWeekday: String = "sunday",
    private val nowMillis: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val sections = mutableListOf<String>()
    private val rawTasks = mutableListOf<GanttRawTask>()
    private val taskIndexes = linkedMapOf<String, Int>()
    private val interactions = linkedMapOf<String, GanttTaskInteraction>()
    private var currentSection = ""
    private var taskCounter = 0
    private var lastTaskId: String? = null
    private var lastOrder = 0
    private var dateFormat = ""
    private var axisFormat = ""
    private var tickInterval: String? = null
    private var todayMarker = ""
    private var includes = emptyList<String>()
    private var excludes = emptyList<String>()
    private var inclusiveEndDates = false
    private var topAxis = false
    private var weekday = GanttWeekday.values().firstOrNull {
        it.name.equals(defaultWeekday, ignoreCase = true)
    } ?: GanttWeekday.Sunday
    private var weekendStart = GanttWeekday.Saturday

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun setDateFormat(value: String) {
        dateFormat = value.trim()
    }

    fun enableInclusiveEndDates() {
        inclusiveEndDates = true
    }

    fun enableTopAxis() {
        topAxis = true
    }

    fun setAxisFormat(value: String) {
        axisFormat = value.trim()
    }

    fun setTickInterval(value: String) {
        tickInterval = value.trim()
    }

    fun setTodayMarker(value: String) {
        todayMarker = value.trim()
    }

    fun setIncludes(value: String) {
        includes = mergeTokens(includes, value)
    }

    fun setExcludes(value: String) {
        excludes = mergeTokens(excludes, value)
    }

    fun setWeekday(value: GanttWeekday) {
        weekday = value
    }

    fun setWeekend(value: GanttWeekday) {
        weekendStart = value
    }

    fun setDiagramTitle(value: String) {
        diagramTitle = value.trim()
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value.trim()
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value.trim()
    }

    fun addSection(value: String) {
        currentSection = value.trim()
        sections += currentSection
    }

    fun addTask(
        description: String,
        source: String,
    ): GMResult<Unit, MermaidError> {
        val data = source.removePrefix(":")
            .split(',')
            .map(String::trim)
            .toMutableList()
        val flags = takeFlags(data)
        if (data.isEmpty()) {
            return GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Gantt task '${description.trim()}' has no date data",
                ),
            )
        }
        val taskId: String
        val start: GanttTaskStart
        val endExpression: String
        when (data.size) {
            1 -> {
                taskId = nextTaskId()
                start = GanttTaskStart.PreviousTaskEnd(lastTaskId)
                endExpression = data[0]
            }
            2 -> {
                taskId = nextTaskId()
                start = GanttTaskStart.Expression(data[0])
                endExpression = data[1]
            }
            3 -> {
                taskId = data[0].ifEmpty(::nextTaskId)
                start = GanttTaskStart.Expression(data[1])
                endExpression = data[2]
            }
            else -> return GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Gantt task '${description.trim()}' has ${data.size} data fields",
                ),
            )
        }
        if (endExpression.isEmpty()) {
            return GMResult.Err(
                MermaidError.Parse(
                    line = 1,
                    column = 1,
                    message = "Gantt task '${description.trim()}' has no end date or duration",
                ),
            )
        }
        val rawTask = GanttRawTask(
            section = currentSection,
            description = description.trim(),
            id = taskId,
            start = start,
            endExpression = endExpression,
            previousTaskId = lastTaskId,
            flags = flags,
            order = if (flags.vertical) -1 else lastOrder++,
        )
        rawTasks += rawTask
        taskIndexes[taskId] = rawTasks.lastIndex
        lastTaskId = taskId
        return GMResult.Ok(Unit)
    }

    fun setLink(
        ids: String,
        link: String,
    ) {
        val resolved = if (securityLevel == MermaidSecurityLevel.Loose) {
            link
        } else {
            MermaidUrlSanitizer.sanitize(link)
        }
        ids.split(',').map(String::trim).forEach { id ->
            val task = findRawTask(id) ?: return@forEach
            task.classes += "clickable"
            val existing = interactions[id] ?: GanttTaskInteraction()
            interactions[id] = existing.copy(link = resolved)
        }
    }

    fun setClickEvent(
        ids: String,
        callbackName: String,
        callbackArgs: String?,
    ) {
        ids.split(',').map(String::trim).forEach { id ->
            val task = findRawTask(id) ?: return@forEach
            task.classes += "clickable"
            if (securityLevel == MermaidSecurityLevel.Loose) {
                val existing = interactions[id] ?: GanttTaskInteraction()
                interactions[id] = existing.copy(
                    callbackName = callbackName.trim(),
                    callbackArgs = callbackArgs?.trim(),
                )
            }
        }
    }

    fun compile(): GMResult<GanttCompiledDocument, MermaidError> {
        val compiledByIndex = arrayOfNulls<GanttTask>(rawTasks.size)
        repeat(MAX_DEPENDENCY_DEPTH) {
            var progressed = false
            rawTasks.indices.forEach { index ->
                if (compiledByIndex[index] != null) return@forEach
                when (val task = compileTask(rawTasks[index], compiledByIndex)) {
                    is GanttTaskCompileResult.Ready -> {
                        compiledByIndex[index] = task.task
                        progressed = true
                    }
                    GanttTaskCompileResult.Waiting -> Unit
                    is GanttTaskCompileResult.Failed -> return GMResult.Err(task.error)
                }
            }
            if (compiledByIndex.all { it != null }) {
                return GMResult.Ok(
                    GanttCompiledDocument(
                        tasks = compiledByIndex.filterNotNull(),
                        sections = sections.toList(),
                        dateFormat = dateFormat,
                        axisFormat = axisFormat,
                        tickInterval = tickInterval,
                        todayMarker = todayMarker,
                        includes = includes,
                        excludes = excludes,
                        inclusiveEndDates = inclusiveEndDates,
                        topAxis = topAxis,
                        weekday = weekday,
                        weekendStart = weekendStart,
                        title = diagramTitle,
                        accessibilityTitle = accessibilityTitle,
                        accessibilityDescription = accessibilityDescription,
                        interactions = interactions.toMap(),
                    ),
                )
            }
            if (!progressed) {
                return GMResult.Err(
                    MermaidError.Layout(
                        "Unable to resolve Gantt task dependencies within " +
                            "$MAX_DEPENDENCY_DEPTH passes",
                    ),
                )
            }
        }
        return GMResult.Err(
            MermaidError.Layout(
                "Unable to resolve Gantt task dependencies within $MAX_DEPENDENCY_DEPTH passes",
            ),
        )
    }

    internal fun isInvalidDate(millis: Long): Boolean {
        val date = GanttDatePort.dateOnly(millis)
        val formatted = GanttDatePort.formatInput(
            millis,
            dateFormat.ifEmpty { "YYYY-MM-DD" },
        )
        if (formatted.lowercase() in includes || date.lowercase() in includes) {
            return false
        }
        val day = GanttDatePort.weekday(millis)
        if ("weekends" in excludes) {
            val secondWeekendDay = GanttWeekday.values()[(weekendStart.ordinal + 1) % 7]
            if (day == weekendStart || day == secondWeekendDay) return true
        }
        if (day.name.lowercase() in excludes) return true
        return formatted.lowercase() in excludes || date.lowercase() in excludes
    }

    private fun compileTask(
        rawTask: GanttRawTask,
        compiled: Array<GanttTask?>,
    ): GanttTaskCompileResult {
        val start = when (val expression = rawTask.start) {
            is GanttTaskStart.PreviousTaskEnd -> {
                val previousId = expression.taskId
                    ?: return GanttTaskCompileResult.Failed(
                        MermaidError.Parse(
                            line = 1,
                            column = 1,
                            message = "The first Gantt task requires an explicit start date",
                        ),
                    )
                resolveTask(previousId, compiled)?.endMillis
                    ?: return GanttTaskCompileResult.Waiting
            }
            is GanttTaskStart.Expression -> when (
                val resolved = resolveStart(expression.value, compiled)
            ) {
                is DateResolution.Ready -> resolved.millis
                DateResolution.Waiting -> return GanttTaskCompileResult.Waiting
                is DateResolution.Failed -> return GanttTaskCompileResult.Failed(resolved.error)
            }
        }
        val end = when (val resolved = resolveEnd(rawTask.endExpression, start, compiled)) {
            is DateResolution.Ready -> resolved.millis
            DateResolution.Waiting -> return GanttTaskCompileResult.Waiting
            is DateResolution.Failed -> return GanttTaskCompileResult.Failed(resolved.error)
        }
        val manualEndTime = GanttDatePort.parse(rawTask.endExpression, "YYYY-MM-DD") is GMResult.Ok
        val checked = if (excludes.isEmpty() || manualEndTime) {
            GMResult.Ok(AdjustedEnd(endMillis = end, renderEndMillis = null))
        } else {
            adjustForExcludedDates(start, end)
        }
        val adjusted = when (checked) {
            is GMResult.Ok -> checked.value
            is GMResult.Err -> return GanttTaskCompileResult.Failed(checked.error)
        }
        return GanttTaskCompileResult.Ready(
            GanttTask(
                section = rawTask.section,
                description = rawTask.description,
                id = rawTask.id,
                startMillis = start,
                endMillis = adjusted.endMillis,
                renderEndMillis = adjusted.renderEndMillis,
                manualEndTime = manualEndTime,
                flags = rawTask.flags,
                order = rawTask.order,
                classes = rawTask.classes.toList(),
            ),
        )
    }

    private fun resolveStart(
        expression: String,
        compiled: Array<GanttTask?>,
    ): DateResolution {
        val after = AFTER.matchEntire(expression.trim())
        if (after != null) {
            return resolveReferences(
                ids = after.groupValues[1].split(' ').filter(String::isNotBlank),
                compiled = compiled,
                select = { tasks -> tasks.maxOf(GanttTask::endMillis) },
            )
        }
        return when (val parsed = GanttDatePort.parse(expression, dateFormat)) {
            is GMResult.Ok -> DateResolution.Ready(parsed.value)
            is GMResult.Err -> DateResolution.Failed(parsed.error)
        }
    }

    private fun resolveEnd(
        expression: String,
        start: Long,
        compiled: Array<GanttTask?>,
    ): DateResolution {
        val until = UNTIL.matchEntire(expression.trim())
        if (until != null) {
            return resolveReferences(
                ids = until.groupValues[1].split(' ').filter(String::isNotBlank),
                compiled = compiled,
                select = { tasks -> tasks.minOf(GanttTask::startMillis) },
            )
        }
        when (val parsed = GanttDatePort.parse(expression, dateFormat)) {
            is GMResult.Ok -> {
                if (!inclusiveEndDates) return DateResolution.Ready(parsed.value)
                return when (val adjusted = GanttDatePort.addDays(parsed.value, 1)) {
                    is GMResult.Ok -> DateResolution.Ready(adjusted.value)
                    is GMResult.Err -> DateResolution.Failed(adjusted.error)
                }
            }
            is GMResult.Err -> Unit
        }
        val duration = GanttDatePort.parseDuration(expression)
            ?: return DateResolution.Ready(start)
        return when (val added = GanttDatePort.add(start, duration)) {
            is GMResult.Ok -> DateResolution.Ready(added.value)
            is GMResult.Err -> DateResolution.Failed(added.error)
        }
    }

    private fun resolveReferences(
        ids: List<String>,
        compiled: Array<GanttTask?>,
        select: (List<GanttTask>) -> Long,
    ): DateResolution {
        val knownIds = ids.filter(taskIndexes::containsKey)
        if (knownIds.isEmpty()) return DateResolution.Ready(today())
        val tasks = knownIds.map { id ->
            resolveTask(id, compiled) ?: return DateResolution.Waiting
        }
        return DateResolution.Ready(select(tasks))
    }

    private fun resolveTask(
        id: String,
        compiled: Array<GanttTask?>,
    ): GanttTask? = taskIndexes[id]?.let(compiled::getOrNull)

    private fun adjustForExcludedDates(
        start: Long,
        originalEnd: Long,
    ): GMResult<AdjustedEnd, MermaidError> {
        var cursor = when (val result = GanttDatePort.addDays(start, 1)) {
            is GMResult.Ok -> result.value
            is GMResult.Err -> return result
        }
        var end = originalEnd
        var renderEnd: Long? = null
        var previousWasInvalid = false
        var extensions = 0
        while (cursor <= end) {
            if (!previousWasInvalid) renderEnd = end
            previousWasInvalid = isInvalidDate(cursor)
            if (previousWasInvalid) {
                extensions += 1
                if (extensions > MAX_EXCLUSION_EXTENSIONS) {
                    return GMResult.Err(
                        MermaidError.ResourceLimit(
                            resource = "Gantt excluded date extensions",
                            actual = extensions,
                            maximum = MAX_EXCLUSION_EXTENSIONS,
                        ),
                    )
                }
                end = when (val result = GanttDatePort.addDays(end, 1)) {
                    is GMResult.Ok -> result.value
                    is GMResult.Err -> return result
                }
            }
            cursor = when (val result = GanttDatePort.addDays(cursor, 1)) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
        }
        return GMResult.Ok(AdjustedEnd(endMillis = end, renderEndMillis = renderEnd))
    }

    private fun today(): Long = GanttDatePort.startOfDay(nowMillis())

    private fun findRawTask(id: String): GanttRawTask? =
        taskIndexes[id]?.let(rawTasks::getOrNull)

    private fun nextTaskId(): String {
        taskCounter += 1
        return "task$taskCounter"
    }

    private fun takeFlags(data: MutableList<String>): GanttTaskFlags {
        var active = false
        var done = false
        var critical = false
        var milestone = false
        var vertical = false
        while (data.isNotEmpty()) {
            when (data.first().trim()) {
                "active" -> active = true
                "done" -> done = true
                "crit" -> critical = true
                "milestone" -> milestone = true
                "vert" -> vertical = true
                else -> break
            }
            data.removeAt(0)
        }
        return GanttTaskFlags(active, done, critical, milestone, vertical)
    }

    private fun mergeTokens(
        existing: List<String>,
        source: String,
    ): List<String> = (existing + source.lowercase().split(Regex("""[\s,]+""")))
        .filter(String::isNotEmpty)
        .distinct()

    private sealed interface DateResolution {
        data class Ready(val millis: Long) : DateResolution

        object Waiting : DateResolution

        data class Failed(val error: MermaidError) : DateResolution
    }

    private sealed interface GanttTaskCompileResult {
        data class Ready(val task: GanttTask) : GanttTaskCompileResult

        object Waiting : GanttTaskCompileResult

        data class Failed(val error: MermaidError) : GanttTaskCompileResult
    }

    private data class AdjustedEnd(
        val endMillis: Long,
        val renderEndMillis: Long?,
    )

    private companion object {
        val AFTER = Regex("""^after\s+([\d\w- ]+)""")
        val UNTIL = Regex("""^until\s+([\d\w- ]+)""")
        const val MAX_DEPENDENCY_DEPTH = 10
        const val MAX_EXCLUSION_EXTENSIONS = 10_000
    }
}
