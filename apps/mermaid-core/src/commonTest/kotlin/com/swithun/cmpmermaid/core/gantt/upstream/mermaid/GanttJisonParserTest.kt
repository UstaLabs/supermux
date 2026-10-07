package com.swithun.cmpmermaid.core.gantt.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidSecurityLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GanttJisonParserTest {
    @Test
    fun parsesConfigurationSectionsTasksAndDependencies() {
        val document = parse(
            """
            gantt
                title Release plan
                dateFormat YYYY-MM-DD
                axisFormat %b %d
                tickInterval 1week
                excludes weekends, 2025-01-08
                includes 2025-01-05
                weekday monday
                weekend friday
                inclusiveEndDates
                topAxis
                section Build
                Compile :done, crit, build, 2025-01-01, 2d
                Package :active, package, after build, 1d
                Ship :milestone, ship, after package, 0d
            """.trimIndent(),
        ).compile().value()

        assertEquals("Release plan", document.title)
        assertEquals("YYYY-MM-DD", document.dateFormat)
        assertEquals("%b %d", document.axisFormat)
        assertEquals("1week", document.tickInterval)
        assertEquals(listOf("weekends", "2025-01-08"), document.excludes)
        assertEquals(listOf("2025-01-05"), document.includes)
        assertEquals(GanttWeekday.Monday, document.weekday)
        assertEquals(GanttWeekday.Friday, document.weekendStart)
        assertTrue(document.inclusiveEndDates)
        assertTrue(document.topAxis)
        assertEquals(3, document.tasks.size)
        assertTrue(document.tasks[0].flags.done)
        assertTrue(document.tasks[0].flags.critical)
        assertTrue(document.tasks[1].flags.active)
        assertTrue(document.tasks[2].flags.milestone)
        assertEquals(document.tasks[0].endMillis, document.tasks[1].startMillis)
        assertEquals(document.tasks[1].endMillis, document.tasks[2].startMillis)
    }

    @Test
    fun parsesUntilVerticalMarkerAndAccessibility() {
        val document = parse(
            """
            gantt
                accTitle: Delivery schedule
                accDescr {
                  Important delivery dates
                }
                dateFormat YYYY-MM-DD
                section Delivery
                Deadline :vert, deadline, 2025-02-12, 1d
                Work :work, 2025-02-01, until deadline
            """.trimIndent(),
        ).compile().value()

        assertEquals("Delivery schedule", document.accessibilityTitle)
        assertEquals("Important delivery dates", document.accessibilityDescription)
        assertTrue(document.tasks[0].flags.vertical)
        assertEquals(document.tasks[0].startMillis, document.tasks[1].endMillis)
    }

    @Test
    fun sanitizesLinksAndKeepsCallbacksLooseOnly() {
        val strict = parse(
            """
            gantt
                dateFormat YYYY-MM-DD
                Task :task, 2025-01-01, 1d
                click task href "javascript:alert(1)"
                click task call run("value")
            """.trimIndent(),
        ).compile().value()
        val loose = parse(
            """
            gantt
                dateFormat YYYY-MM-DD
                Task :task, 2025-01-01, 1d
                click task href "https://example.com"
                click task call run("value")
            """.trimIndent(),
            MermaidSecurityLevel.Loose,
        ).compile().value()

        assertFalse(strict.interactions.getValue("task").link.orEmpty().startsWith("javascript:"))
        assertEquals(null, strict.interactions.getValue("task").callbackName)
        assertEquals("https://example.com", loose.interactions.getValue("task").link)
        assertEquals("run", loose.interactions.getValue("task").callbackName)
        assertEquals("\"value\"", loose.interactions.getValue("task").callbackArgs)
    }

    @Test
    fun parsesDayJsInputFormatsAndDurationUnits() {
        val day = GanttDatePort.parse("2024-02-29 11:05:07.250 PM +0530", "YYYY-MM-DD hh:mm:ss.SSS A ZZ")
            .value()

        assertEquals("2024-02-29", GanttDatePort.dateOnly(day))
        assertEquals("17:35:07", GanttDatePort.format(day, "%H:%M:%S"))
        assertEquals(
            "2024-02-29",
            GanttDatePort.dateOnly(
                GanttDatePort.add(
                    GanttDatePort.parse("2024-01-31", "YYYY-MM-DD").value(),
                    GanttDatePort.parseDuration("1M")!!,
                ).value(),
            ),
        )
        assertEquals(
            "2024-01-03",
            GanttDatePort.dateOnly(
                GanttDatePort.add(
                    GanttDatePort.parse("2024-01-01", "YYYY-MM-DD").value(),
                    GanttDatePort.parseDuration("1.5d")!!,
                ).value(),
            ),
        )
    }

    @Test
    fun anchorsMultiUnitTicksLikeD3TimeEvery() {
        assertEquals(
            listOf("2027-03-07", "2027-03-21"),
            tickDates(
                minimum = "2027-03-01",
                maximum = "2027-04-01",
                count = 2,
                unit = GanttTickUnit.Week,
            ),
        )
        assertEquals(
            listOf("2027-03-03", "2027-03-05", "2027-03-07", "2027-03-09", "2027-03-11"),
            tickDates(
                minimum = "2027-03-02",
                maximum = "2027-03-11",
                count = 2,
                unit = GanttTickUnit.Day,
            ),
        )
        assertEquals(
            listOf("2027-04-01", "2027-07-01", "2027-10-01"),
            tickDates(
                minimum = "2027-02-01",
                maximum = "2027-10-01",
                count = 3,
                unit = GanttTickUnit.Month,
            ),
        )
        assertEquals(
            listOf("2030-01-01", "2035-01-01"),
            tickDates(
                minimum = "2027-01-01",
                maximum = "2038-01-01",
                count = 5,
                unit = GanttTickUnit.Year,
            ),
        )
    }

    private fun tickDates(
        minimum: String,
        maximum: String,
        count: Int,
        unit: GanttTickUnit,
    ): List<String> = GanttDatePort.ticks(
        minimum = GanttDatePort.parse(minimum, "YYYY-MM-DD").value(),
        maximum = GanttDatePort.parse(maximum, "YYYY-MM-DD").value(),
        interval = GanttTickInterval(count, unit),
        weekStart = GanttWeekday.Sunday,
    ).map(GanttDatePort::dateOnly)

    private fun parse(
        source: String,
        securityLevel: MermaidSecurityLevel = MermaidSecurityLevel.Strict,
    ): GanttDb = when (
        val result = GanttJisonParser(securityLevel = securityLevel).parse("$source\n")
    ) {
        is GMResult.Ok -> result.value
        is GMResult.Err -> error("Expected Gantt parse success: ${result.error}")
    }

    private fun <T> GMResult<T, *>.value(): T = when (this) {
        is GMResult.Ok -> value
        is GMResult.Err -> error("Expected success: $error")
    }
}
