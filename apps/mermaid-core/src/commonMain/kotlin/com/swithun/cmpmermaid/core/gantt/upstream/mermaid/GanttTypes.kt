package com.swithun.cmpmermaid.core.gantt.upstream.mermaid

internal enum class GanttWeekday {
    Monday,
    Tuesday,
    Wednesday,
    Thursday,
    Friday,
    Saturday,
    Sunday,
}

internal data class GanttTaskFlags(
    val active: Boolean = false,
    val done: Boolean = false,
    val critical: Boolean = false,
    val milestone: Boolean = false,
    val vertical: Boolean = false,
)

internal sealed interface GanttTaskStart {
    data class PreviousTaskEnd(
        val taskId: String?,
    ) : GanttTaskStart

    data class Expression(
        val value: String,
    ) : GanttTaskStart
}

internal data class GanttRawTask(
    val section: String,
    val description: String,
    val id: String,
    val start: GanttTaskStart,
    val endExpression: String,
    val previousTaskId: String?,
    val flags: GanttTaskFlags,
    val order: Int,
    val classes: MutableList<String> = mutableListOf(),
)

internal data class GanttTask(
    val section: String,
    val description: String,
    val id: String,
    val startMillis: Long,
    val endMillis: Long,
    val renderEndMillis: Long?,
    val manualEndTime: Boolean,
    val flags: GanttTaskFlags,
    var order: Int,
    val classes: List<String>,
)

internal data class GanttTaskInteraction(
    val link: String? = null,
    val callbackName: String? = null,
    val callbackArgs: String? = null,
)

internal data class GanttCompiledDocument(
    val tasks: List<GanttTask>,
    val sections: List<String>,
    val dateFormat: String,
    val axisFormat: String,
    val tickInterval: String?,
    val todayMarker: String,
    val includes: List<String>,
    val excludes: List<String>,
    val inclusiveEndDates: Boolean,
    val topAxis: Boolean,
    val weekday: GanttWeekday,
    val weekendStart: GanttWeekday,
    val title: String?,
    val accessibilityTitle: String?,
    val accessibilityDescription: String?,
    val interactions: Map<String, GanttTaskInteraction>,
)
