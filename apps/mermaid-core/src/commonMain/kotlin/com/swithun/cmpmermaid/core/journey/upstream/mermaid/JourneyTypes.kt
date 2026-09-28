package com.swithun.cmpmermaid.core.journey.upstream.mermaid

/**
 * Kotlin translation of the task objects created by Mermaid 12.0.0 journeyDb.js.
 */
internal data class JourneyTask(
    val section: String,
    val type: String,
    val people: List<String>,
    val task: String,
    val score: Double,
)
