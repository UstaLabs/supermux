package com.swithun.cmpmermaid.core.journey.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidPreprocessor

/**
 * Kotlin translation of Mermaid 12.0.0 journeyDb.js.
 */
internal class JourneyDb(
    diagramTitle: String? = null,
) {
    private val sections = mutableListOf<String>()
    private val rawTasks = mutableListOf<JourneyTask>()
    private var currentSection = ""

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun addSection(value: String) {
        currentSection = decode(value)
        sections += currentSection
    }

    fun getSections(): List<String> = sections.toList()

    fun addTask(
        description: String,
        taskData: String,
    ) {
        // Mermaid: journeyDb.js -> addTask
        val pieces = taskData.removePrefix(":").split(':')
        val score = jsNumber(pieces.firstOrNull().orEmpty())
        val people = if (pieces.size == 1) {
            emptyList()
        } else {
            pieces[1].split(',').map { person -> decode(person.trim()) }
        }
        rawTasks += JourneyTask(
            section = currentSection,
            type = currentSection,
            people = people,
            task = decode(description),
            score = score,
        )
    }

    fun getTasks(): List<JourneyTask> = rawTasks.toList()

    fun getActors(): List<String> = rawTasks
        .flatMap(JourneyTask::people)
        .distinct()
        .sorted()

    fun setDiagramTitle(value: String) {
        diagramTitle = decode(value)
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = decode(value.trim())
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = decode(value.trim())
    }

    private fun decode(value: String): String =
        MermaidPreprocessor.decodeEntities(value)

    private fun jsNumber(value: String): Double {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) {
            return 0.0
        }
        return trimmed.toDoubleOrNull() ?: Double.NaN
    }
}
