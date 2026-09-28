package com.swithun.cmpmermaid.core.sequence.upstream.mermaid

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlScalar
import com.swithun.cmpmermaid.core.CssColorParser
import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin translation of Mermaid 12's sequenceDb.ts state and apply pipeline.
 */
internal class SequenceDb {
    private val actors = linkedMapOf<String, SequenceActor>()
    private val boxes = mutableListOf<SequenceBox>()
    private val messages = mutableListOf<SequenceMessage>()
    private val createdActors = linkedMapOf<String, Int>()
    private val destroyedActors = linkedMapOf<String, Int>()
    private var previousActor: String? = null
    private var currentBox: SequenceBox? = null
    private var lastCreated: String? = null
    private var lastDestroyed: String? = null

    var sequenceNumbersEnabled: Boolean = false
        private set

    var accessibilityTitle: String? = null
        private set

    var accessibilityDescription: String? = null
        private set

    var diagramTitle: String? = null
        private set

    fun getActors(): Map<String, SequenceActor> = actors

    fun getBoxes(): List<SequenceBox> = boxes

    fun getMessages(): List<SequenceMessage> = messages

    fun getCreatedActors(): Map<String, Int> = createdActors

    fun getDestroyedActors(): Map<String, Int> = destroyedActors

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value.trim()
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value.trim()
    }

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun parseMessage(source: String): SequenceText {
        val trimmed = source.trim()
        val wrap = when {
            WRAP_PREFIX.containsMatchIn(trimmed) -> true
            NOWRAP_PREFIX.containsMatchIn(trimmed) -> false
            else -> null
        }
        val text = if (wrap == null) {
            trimmed
        } else {
            trimmed.replace(FORMAT_PREFIX, "").trim()
        }
        return SequenceText(text = text, wrap = wrap)
    }

    fun parseBoxData(source: String): SequenceBoxData {
        val match = BOX_DATA.matchEntire(source)
        var color = match?.groupValues?.getOrNull(1)?.trim().orEmpty()
        var title = match?.groupValues?.getOrNull(2)?.trim()?.takeIf(String::isNotEmpty)
        if (color.isEmpty() || CssColorParser.parse(color) == null) {
            color = "transparent"
            title = source.trim().takeIf(String::isNotEmpty)
        }
        val parsedTitle = title?.let(::parseMessage)
        return SequenceBoxData(
            text = parsedTitle?.text,
            color = color,
            wrap = parsedTitle?.wrap,
        )
    }

    fun apply(actions: List<SequenceAction>): GMResult<Unit, MermaidError> {
        actions.forEach { action ->
            when (val result = apply(action)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }
        return GMResult.Ok(Unit)
    }

    fun apply(action: SequenceAction): GMResult<Unit, MermaidError> = when (action) {
        is SequenceAction.Participant -> applyParticipant(action.value)
        is SequenceAction.AutoNumber -> {
            messages += SequenceMessage(
                id = messages.size.toString(),
                type = SequenceLineType.AUTONUMBER,
                sequenceStart = action.start,
                sequenceStep = action.step,
                sequenceVisible = action.visible,
            )
            sequenceNumbersEnabled = action.visible
            GMResult.Ok(Unit)
        }
        is SequenceAction.Signal -> addSignal(action)
        is SequenceAction.Note -> addNote(action)
        is SequenceAction.BoxStart -> {
            addBox(action.data)
            GMResult.Ok(Unit)
        }
        SequenceAction.BoxEnd -> {
            currentBox = null
            GMResult.Ok(Unit)
        }
        is SequenceAction.Links -> addLinks(action)
        is SequenceAction.Properties -> addProperties(action)
        is SequenceAction.Details -> GMResult.Err(
            MermaidError.UnsupportedFeature(
                feature = "sequence participant details DOM reference",
                message = "Sequence 'details' requires a browser DOM element and cannot be resolved natively.",
            ),
        )
    }

    private fun applyParticipant(
        participant: SequenceParticipantAction,
    ): GMResult<Unit, MermaidError> = when (participant.operation) {
        SequenceParticipantOperation.Reference,
        SequenceParticipantOperation.Add,
        -> addActor(participant)
        SequenceParticipantOperation.Create -> {
            if (participant.actor in actors) {
                sequenceError(
                    "It is not possible to have actors with the same id, even if one is destroyed " +
                        "before the next is created. Use 'AS' aliases to simulate the behavior.",
                )
            } else {
                lastCreated = participant.actor
                when (val result = addActor(participant)) {
                    is GMResult.Ok -> {
                        createdActors[participant.actor] = messages.size
                        result
                    }
                    is GMResult.Err -> result
                }
            }
        }
        SequenceParticipantOperation.Destroy -> {
            lastDestroyed = participant.actor
            destroyedActors[participant.actor] = messages.size
            GMResult.Ok(Unit)
        }
    }

    private fun addActor(
        participant: SequenceParticipantAction,
    ): GMResult<Unit, MermaidError> {
        val metadata = when (val parsed = parseParticipantMetadata(participant.config)) {
            is GMResult.Ok -> parsed.value
            is GMResult.Err -> return parsed
        }
        val id = participant.actor
        val existing = actors[id]
        val assignedBox = existing?.boxId ?: currentBox?.id
        if (
            existing?.boxId != null &&
            currentBox != null &&
            existing.boxId != currentBox?.id
        ) {
            return sequenceError(
                "A same participant should only be defined in one Box: ${existing.name} " +
                    "can't be in '${existing.boxId}' and in '${currentBox?.name}' at the same time.",
            )
        }

        val type = metadata["type"] ?: participant.draw
        val alias = metadata["alias"]
        val description = participant.description
            ?.takeUnless { it.text == id && alias != null }
            ?: alias?.let { SequenceText(it) }
            ?: SequenceText(id)
        val actor = existing ?: SequenceActor(
            id = id,
            name = id,
            description = description.text,
            wrap = description.wrap ?: false,
            type = type,
            boxId = assignedBox,
        )
        if (existing != null && participant.description == null) {
            actor.boxId = assignedBox
        } else {
            actor.name = id
            actor.description = description.text
            actor.wrap = description.wrap ?: false
            actor.type = type
            actor.boxId = assignedBox
        }
        actors[id] = actor
        currentBox?.actorKeys?.let { keys ->
            if (id !in keys) {
                keys += id
            }
        }
        previousActor = id
        return GMResult.Ok(Unit)
    }

    private fun addSignal(action: SequenceAction.Signal): GMResult<Unit, MermaidError> {
        if (action.typeRequiresInactiveValidation()) {
            val activeCount = messages.fold(0) { count, message ->
                count + when {
                    message.type == SequenceLineType.ACTIVE_START && message.from == action.from -> 1
                    message.type == SequenceLineType.ACTIVE_END && message.from == action.from -> -1
                    else -> 0
                }
            }
            if (activeCount < 1) {
                return sequenceError(
                    "Trying to inactivate an inactive participant (${action.from})",
                )
            }
        }
        if (action.from != null && action.to != null) {
            val pendingCreated = lastCreated
            val pendingDestroyed = lastDestroyed
            when {
                pendingCreated != null && action.to != pendingCreated ->
                    return sequenceError(
                        "The created participant $pendingCreated does not have an associated " +
                            "creating message after its declaration.",
                    )
                pendingCreated != null -> lastCreated = null
                pendingDestroyed != null &&
                    action.to != pendingDestroyed &&
                    action.from != pendingDestroyed ->
                    return sequenceError(
                        "The destroyed participant $pendingDestroyed does not have an associated " +
                            "destroying message after its declaration.",
                    )
                pendingDestroyed != null -> lastDestroyed = null
            }
        }
        messages += SequenceMessage(
            id = messages.size.toString(),
            from = action.from,
            to = action.to,
            message = action.message?.text.orEmpty(),
            wrap = action.message?.wrap ?: false,
            type = action.lineType,
            activate = action.activate,
            centralConnection = action.centralConnection,
        )
        return GMResult.Ok(Unit)
    }

    private fun SequenceAction.Signal.typeRequiresInactiveValidation(): Boolean =
        lineType == SequenceLineType.ACTIVE_END

    private fun addNote(action: SequenceAction.Note): GMResult<Unit, MermaidError> {
        val from = action.actors.firstOrNull()
            ?: return sequenceError("A Sequence note must reference at least one participant.")
        val to = action.actors.getOrNull(1) ?: from
        messages += SequenceMessage(
            id = messages.size.toString(),
            from = from,
            to = to,
            message = action.message.text,
            wrap = action.message.wrap ?: false,
            type = SequenceLineType.NOTE,
            placement = action.placement,
        )
        return GMResult.Ok(Unit)
    }

    private fun addBox(data: SequenceBoxData) {
        val box = SequenceBox(
            id = "box-${boxes.size}",
            name = data.text,
            fill = data.color,
            wrap = data.wrap ?: false,
        )
        boxes += box
        currentBox = box
    }

    private fun addLinks(action: SequenceAction.Links): GMResult<Unit, MermaidError> {
        val actor = actors[action.actor]
            ?: return sequenceError("Unknown Sequence participant '${action.actor}'.")
        val decoded = decodeJsonText(action.text.text)
        val values = if (action.single) {
            val separator = decoded.indexOf('@')
            if (separator <= 0 || separator >= decoded.lastIndex) {
                return GMResult.Ok(Unit)
            }
            mapOf(
                decoded.substring(0, separator).trim() to
                    decoded.substring(separator + 1).trim(),
            )
        } else {
            parseStringObject(decoded) ?: return GMResult.Ok(Unit)
        }
        actor.links.putAll(values)
        return GMResult.Ok(Unit)
    }

    private fun addProperties(
        action: SequenceAction.Properties,
    ): GMResult<Unit, MermaidError> {
        val actor = actors[action.actor]
            ?: return sequenceError("Unknown Sequence participant '${action.actor}'.")
        parseStringObject(decodeJsonText(action.text.text))?.let(actor.properties::putAll)
        return GMResult.Ok(Unit)
    }

    private fun parseParticipantMetadata(
        source: String?,
    ): GMResult<Map<String, String>, MermaidError> {
        if (source == null) {
            return GMResult.Ok(emptyMap())
        }
        val yamlSource = if ('\n' in source) "$source\n" else "{\n$source\n}"
        val node = try {
            Yaml.default.parseToYamlNode(yamlSource)
        } catch (failure: Exception) {
            return sequenceError(failure.message ?: "Invalid Sequence participant metadata.")
        }
        val map = node as? YamlMap
            ?: return sequenceError("Sequence participant metadata must be a map.")
        return GMResult.Ok(
            buildMap {
                map.entries.forEach { (key, value) ->
                    put(
                        key.content,
                        if (value is YamlScalar) value.content else value.contentToString(),
                    )
                }
            },
        )
    }

    private fun parseStringObject(source: String): Map<String, String>? {
        val objectValue = try {
            Json.parseToJsonElement(source) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return null
        return buildMap {
            objectValue.forEach { (key, value) ->
                put(
                    key,
                    (value as? JsonPrimitive)?.contentOrNull
                        ?: value.jsonPrimitive.content,
                )
            }
        }
    }

    private fun decodeJsonText(source: String): String = source
        .replace("&equals;", "=")
        .replace("&amp;", "&")

    private fun <T> sequenceError(message: String): GMResult<T, MermaidError> =
        GMResult.Err(MermaidError.Parse(1, 1, message))

    private companion object {
        val WRAP_PREFIX = Regex("""^:?wrap:""")
        val NOWRAP_PREFIX = Regex("""^:?nowrap:""")
        val FORMAT_PREFIX = Regex("""^:?(?:no)?wrap:""")
        val BOX_DATA = Regex("""^((?:rgba?|hsla?)\s*\(.*\)|\w*)(.*)$""")
    }
}
