package com.swithun.cmpmermaid.core.sequence.upstream.mermaid

internal object SequenceLineType {
    const val SOLID = 0
    const val DOTTED = 1
    const val NOTE = 2
    const val SOLID_CROSS = 3
    const val DOTTED_CROSS = 4
    const val SOLID_OPEN = 5
    const val DOTTED_OPEN = 6
    const val LOOP_START = 10
    const val LOOP_END = 11
    const val ALT_START = 12
    const val ALT_ELSE = 13
    const val ALT_END = 14
    const val OPT_START = 15
    const val OPT_END = 16
    const val ACTIVE_START = 17
    const val ACTIVE_END = 18
    const val PAR_START = 19
    const val PAR_AND = 20
    const val PAR_END = 21
    const val RECT_START = 22
    const val RECT_END = 23
    const val SOLID_POINT = 24
    const val DOTTED_POINT = 25
    const val AUTONUMBER = 26
    const val CRITICAL_START = 27
    const val CRITICAL_OPTION = 28
    const val CRITICAL_END = 29
    const val BREAK_START = 30
    const val BREAK_END = 31
    const val PAR_OVER_START = 32
    const val BIDIRECTIONAL_SOLID = 33
    const val BIDIRECTIONAL_DOTTED = 34
    const val SOLID_TOP = 41
    const val SOLID_BOTTOM = 42
    const val STICK_TOP = 43
    const val STICK_BOTTOM = 44
    const val SOLID_ARROW_TOP_REVERSE = 45
    const val SOLID_ARROW_BOTTOM_REVERSE = 46
    const val STICK_ARROW_TOP_REVERSE = 47
    const val STICK_ARROW_BOTTOM_REVERSE = 48
    const val SOLID_TOP_DOTTED = 51
    const val SOLID_BOTTOM_DOTTED = 52
    const val STICK_TOP_DOTTED = 53
    const val STICK_BOTTOM_DOTTED = 54
    const val SOLID_ARROW_TOP_REVERSE_DOTTED = 55
    const val SOLID_ARROW_BOTTOM_REVERSE_DOTTED = 56
    const val STICK_ARROW_TOP_REVERSE_DOTTED = 57
    const val STICK_ARROW_BOTTOM_REVERSE_DOTTED = 58
    const val CENTRAL_CONNECTION = 59
    const val CENTRAL_CONNECTION_REVERSE = 60
    const val CENTRAL_CONNECTION_DUAL = 61
}

internal object SequencePlacement {
    const val LEFT_OF = 0
    const val RIGHT_OF = 1
    const val OVER = 2
}

internal data class SequenceText(
    val text: String,
    val wrap: Boolean? = null,
)

internal enum class SequenceParticipantOperation {
    Reference,
    Add,
    Create,
    Destroy,
}

internal data class SequenceParticipantAction(
    val actor: String,
    val description: SequenceText? = null,
    val draw: String = "participant",
    val operation: SequenceParticipantOperation = SequenceParticipantOperation.Reference,
    val config: String? = null,
)

internal sealed interface SequenceAction {
    data class Participant(
        val value: SequenceParticipantAction,
    ) : SequenceAction

    data class AutoNumber(
        val start: Double? = null,
        val step: Double? = null,
        val visible: Boolean,
    ) : SequenceAction

    data class Signal(
        val from: String? = null,
        val to: String? = null,
        val message: SequenceText? = null,
        val lineType: Int,
        val activate: Boolean = false,
        val centralConnection: Int = 0,
    ) : SequenceAction

    data class Note(
        val actors: List<String>,
        val placement: Int,
        val message: SequenceText,
    ) : SequenceAction

    data class BoxStart(
        val data: SequenceBoxData,
    ) : SequenceAction

    object BoxEnd : SequenceAction

    data class Links(
        val actor: String,
        val text: SequenceText,
        val single: Boolean,
    ) : SequenceAction

    data class Properties(
        val actor: String,
        val text: SequenceText,
    ) : SequenceAction

    data class Details(
        val actor: String,
        val text: SequenceText,
    ) : SequenceAction
}

internal data class SequenceBoxData(
    val text: String?,
    val color: String,
    val wrap: Boolean? = null,
)

internal data class SequenceActor(
    val id: String,
    var name: String,
    var description: String,
    var wrap: Boolean,
    var type: String,
    var boxId: String? = null,
    val links: MutableMap<String, String> = linkedMapOf(),
    val properties: MutableMap<String, String> = linkedMapOf(),
)

internal data class SequenceBox(
    val id: String,
    val name: String?,
    val fill: String,
    val wrap: Boolean,
    val actorKeys: MutableList<String> = mutableListOf(),
)

internal data class SequenceMessage(
    val id: String,
    val from: String? = null,
    val to: String? = null,
    val message: String = "",
    val wrap: Boolean = false,
    val type: Int,
    val activate: Boolean = false,
    val centralConnection: Int = 0,
    val placement: Int? = null,
    val sequenceStart: Double? = null,
    val sequenceStep: Double? = null,
    val sequenceVisible: Boolean? = null,
)
