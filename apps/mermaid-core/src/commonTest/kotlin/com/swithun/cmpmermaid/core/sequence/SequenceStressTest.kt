package com.swithun.cmpmermaid.core.sequence

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SequenceStressTest {
    private val engine = MermaidEngine()
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            val charactersPerLine = (request.maxWidth / 8f).toInt().coerceAtLeast(1)
            val lines = ceil(request.text.length.toDouble() / charactersPerLine)
                .toInt()
                .coerceAtLeast(1)
            TextMetrics(
                width = minOf(request.maxWidth, request.text.length * 8f),
                height = lines * request.fontSize * request.lineHeight,
            )
        },
        options = MermaidRenderOptions(look = "neo"),
    )

    @Test
    fun rendersDeterministicMixedSequenceCorpus() {
        repeat(CASE_COUNT) { caseIndex ->
            val generated = generateCase(caseIndex)
            val result = engine.render(generated.source, context)
            val scene = assertIs<GMResult.Ok<MermaidScene>>(
                result,
                "Generated Sequence case $caseIndex failed:\n${generated.source}",
            ).value

            assertTrue(scene.width.isFinite() && scene.width > 0f)
            assertTrue(scene.height.isFinite() && scene.height > 0f)
            assertEquals(
                generated.participantCount * 2,
                scene.elements
                    .filterIsInstance<SceneShape>()
                    .count { shape -> shape.id.startsWith("actor-") },
            )
            val renderedTexts = scene.elements
                .filterIsInstance<SceneText>()
                .map(SceneText::text)
                .toSet()
            generated.messageLabels.forEach { label ->
                assertTrue(
                    label in renderedTexts,
                    "Missing '$label' in generated Sequence case $caseIndex",
                )
            }
            assertEquals(
                generated.messageLabels.size,
                scene.elements
                    .filterIsInstance<ScenePath>()
                    .count { path -> path.id.startsWith("message-") },
            )
        }
    }

    private fun generateCase(caseIndex: Int): GeneratedSequenceCase {
        val random = Random(BASE_SEED + caseIndex)
        val participantCount = random.nextInt(from = 2, until = 8)
        val participants = List(participantCount) { index -> "P$index" }
        val messageLabels = mutableListOf<String>()
        var messageIndex = 0

        fun nextMessage(indent: String = "    "): String {
            val fromIndex = random.nextInt(participantCount)
            val selfMessage = random.nextInt(5) == 0
            val toIndex = if (selfMessage) {
                fromIndex
            } else {
                random.nextInt(participantCount - 1).let { candidate ->
                    if (candidate >= fromIndex) candidate + 1 else candidate
                }
            }
            val label = "Message $caseIndex.$messageIndex"
            messageLabels += label
            messageIndex += 1
            return "$indent${participants[fromIndex]}${MESSAGE_ARROWS.random(random)}" +
                "${participants[toIndex]}: $label"
        }

        val lines = mutableListOf("sequenceDiagram")
        if (caseIndex % 4 == 0) {
            lines += "    autonumber ${caseIndex + 1} 0.5"
        }
        participants.forEachIndexed { index, participant ->
            val declaration = if ((caseIndex + index) % 4 == 0) "actor" else "participant"
            lines += "    $declaration $participant as Participant ${caseIndex}_$index"
        }
        lines += nextMessage()

        when (caseIndex % 6) {
            0 -> {
                lines += "    loop Retry $caseIndex"
                repeat(3) { lines += nextMessage("        ") }
                lines += "    end"
            }
            1 -> {
                lines += "    alt Accepted $caseIndex"
                lines += nextMessage("        ")
                lines += "    else Rejected $caseIndex"
                lines += nextMessage("        ")
                lines += "    end"
            }
            2 -> {
                lines += "    par Primary $caseIndex"
                lines += nextMessage("        ")
                lines += "    and Secondary $caseIndex"
                lines += nextMessage("        ")
                lines += "    end"
            }
            3 -> {
                lines += "    critical Commit $caseIndex"
                lines += nextMessage("        ")
                lines += "    option Retry $caseIndex"
                lines += nextMessage("        ")
                lines += "    option Cancel $caseIndex"
                lines += nextMessage("        ")
                lines += "    end"
            }
            4 -> {
                lines += "    rect rgba(30, 144, 255, 0.15)"
                repeat(2) { lines += nextMessage("        ") }
                lines += "    end"
            }
            else -> {
                lines += "    opt Optional $caseIndex"
                lines += nextMessage("        ")
                lines += "    end"
            }
        }

        val first = participants.first()
        val last = participants.last()
        lines += "    Note left of $first: Left note $caseIndex"
        lines += "    Note over $first,$last: Shared note $caseIndex"
        lines += "    Note right of $last: Right note $caseIndex"
        lines += "    $first->>+$last: Activation $caseIndex"
        messageLabels += "Activation $caseIndex"
        lines += "    $last-->>-$first: Completion $caseIndex"
        messageLabels += "Completion $caseIndex"
        repeat(random.nextInt(from = 2, until = 8)) {
            lines += nextMessage()
        }

        return GeneratedSequenceCase(
            source = lines.joinToString("\n"),
            participantCount = participantCount,
            messageLabels = messageLabels,
        )
    }

    private data class GeneratedSequenceCase(
        val source: String,
        val participantCount: Int,
        val messageLabels: List<String>,
    )

    private companion object {
        const val CASE_COUNT = 256
        const val BASE_SEED = 0x5E_0E_12

        val MESSAGE_ARROWS = listOf(
            "->>",
            "-->>",
            "->",
            "-->",
            "-x",
            "--x",
            "-)",
            "--)",
            "<<->>",
            "<<-->>",
        )
    }
}
