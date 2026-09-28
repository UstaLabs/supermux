package com.swithun.cmpmermaid.core.usecase.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlinx.serialization.json.JsonObject

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/usecase/parser/usecaseModelBuilder.ts.
 *
 * The parser records detached drafts. This builder resolves the global symbol namespace,
 * forward declarations, metadata, classes, and notes before publishing one immutable document.
 */
internal class UsecaseModelBuilder(
    private val diagramTitle: String?,
) {
    private val elements = mutableListOf<DraftElement>()
    private val boundaries = mutableListOf<DraftBoundary>()
    private val jsonNodes = mutableListOf<DraftJson>()
    private val relationships = mutableListOf<DraftRelationship>()
    private val notes = mutableListOf<DraftNote>()
    private val metadataAssignments = mutableListOf<DraftMetadataAssignment>()
    private val classAssignments = mutableListOf<DraftClassAssignment>()
    private val styleAssignments = mutableListOf<DraftStyleAssignment>()
    private val classDefinitions = mutableListOf<DraftClassDefinition>()
    private var direction: UsecaseDirection = UsecaseDirection.LeftToRight
    private var accessibilityTitle: String? = null
    private var accessibilityDescription: String? = null

    fun addElement(value: DraftElement) {
        elements += value
    }

    fun addBoundary(value: DraftBoundary) {
        boundaries += value
    }

    fun addJson(value: DraftJson) {
        jsonNodes += value
    }

    fun addRelationship(value: DraftRelationship) {
        relationships += value
    }

    fun addNote(value: DraftNote) {
        notes += value
    }

    fun addMetadataAssignment(value: DraftMetadataAssignment) {
        metadataAssignments += value
    }

    fun addClassAssignment(value: DraftClassAssignment) {
        classAssignments += value
    }

    fun addStyleAssignment(value: DraftStyleAssignment) {
        styleAssignments += value
    }

    fun addClassDefinition(value: DraftClassDefinition) {
        classDefinitions += value
    }

    fun setDirection(value: UsecaseDirection) {
        direction = value
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value
    }

    fun finalizeDocument(): GMResult<UsecaseDocument, MermaidError> {
        val symbols = linkedMapOf<String, SymbolOrigin>()
        val states = linkedMapOf<String, ElementState>()
        val boundaryStates = linkedMapOf<String, BoundaryState>()
        val jsonStates = linkedMapOf<String, JsonState>()
        val edgeStates = linkedMapOf<String, EdgeState>()
        val first = linkedMapOf<String, Int>()

        relationships.forEach { relationship ->
            recordFirst(first, relationship.source.id, relationship.source.location.offset)
            recordFirst(first, relationship.target.id, relationship.target.location.offset)
        }
        elements.forEach { recordFirst(first, it.id, it.location.offset) }
        boundaries.forEach { recordFirst(first, it.id, it.location.offset) }
        jsonNodes.forEach { recordFirst(first, it.id, it.location.offset) }

        val declarations = buildList {
            elements.forEach { add(Declaration.Element(it.location.offset, it)) }
            boundaries.forEach { add(Declaration.Boundary(it.location.offset, it)) }
            jsonNodes.forEach { add(Declaration.Json(it.location.offset, it)) }
            relationships
                .filter { it.explicitId != null }
                .forEach { relationship ->
                    add(
                        Declaration.Edge(
                            offset = relationship.explicitIdLocation?.offset
                                ?: relationship.location.offset,
                            value = relationship,
                        ),
                    )
                }
        }.sortedBy(Declaration::offset)

        for (declaration in declarations) {
            val result = when (declaration) {
                is Declaration.Element -> collectElement(declaration.value, symbols, states)
                is Declaration.Boundary ->
                    collectBoundary(declaration.value, symbols, boundaryStates)
                is Declaration.Json -> collectJson(declaration.value, symbols, jsonStates)
                is Declaration.Edge -> registerUnique(
                    symbols = symbols,
                    id = declaration.value.explicitId.orEmpty(),
                    kind = SymbolKind.Edge,
                    location = declaration.value.explicitIdLocation
                        ?: declaration.value.location,
                    generated = false,
                )
            }
            if (result is GMResult.Err) return result
        }

        var anonymousEdge = 0
        for (draft in relationships) {
            val sourceKind = when (
                val result = resolveEndpoint(draft.source, symbols, states, first)
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            val targetKind = when (
                val result = resolveEndpoint(draft.target, symbols, states, first)
            ) {
                is GMResult.Ok -> result.value
                is GMResult.Err -> return result
            }
            when (
                val result = validateRelationship(draft, sourceKind, targetKind)
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
            val id = draft.explicitId ?: "edge-${anonymousEdge++}"
            edgeStates[id] = EdgeState(
                id = id,
                explicitId = draft.explicitId != null,
                source = draft.source.id,
                target = draft.target.id,
                type = draft.type,
                arrowType = draft.arrowType,
                label = draft.label,
                minimumLength = draft.minimumLength,
            )
        }

        for (assignment in metadataAssignments) {
            when (
                val result = applyMetadata(
                    assignment = assignment,
                    symbols = symbols,
                    elements = states,
                    boundaries = boundaryStates,
                    edges = edgeStates,
                )
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }

        for (state in states.values) {
            when (val result = validateElement(state)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }

        val noteStates = linkedMapOf<String, UsecaseNote>()
        notes.forEachIndexed { index, draft ->
            val kind = symbols[draft.target]?.kind
                ?: return parseError(
                    draft.targetLocation,
                    "Note target '${draft.target}' is unresolved",
                )
            if (kind != SymbolKind.Actor && kind != SymbolKind.Usecase) {
                return parseError(
                    draft.targetLocation,
                    "Note target '${draft.target}' must be an actor or use case, not " +
                        kind.displayName,
                )
            }
            val id = "note-$index"
            noteStates[id] = UsecaseNote(id, draft.target, draft.label)
        }

        val definitions = linkedMapOf<String, UsecaseClassDefinition>()
        classDefinitions.forEach { definition ->
            definition.ids.forEach { id ->
                definitions[id] = UsecaseClassDefinition(id, definition.styles)
            }
        }
        for (assignment in classAssignments) {
            for (target in assignment.targets) {
                when (
                    val result = mutateStylable(
                        id = target.id,
                        location = target.location,
                        symbols = symbols,
                        elements = states,
                        boundaries = boundaryStates,
                        jsonNodes = jsonStates,
                        edges = edgeStates,
                    ) { stylable ->
                        assignment.classes.forEach { className ->
                            if (className !in stylable.classes) {
                                stylable.classes += className
                            }
                        }
                    }
                ) {
                    is GMResult.Ok -> Unit
                    is GMResult.Err -> return result
                }
            }
        }
        for (assignment in styleAssignments) {
            when (
                val result = mutateStylable(
                    id = assignment.target,
                    location = assignment.location,
                    symbols = symbols,
                    elements = states,
                    boundaries = boundaryStates,
                    jsonNodes = jsonStates,
                    edges = edgeStates,
                ) { stylable ->
                    stylable.styles += assignment.styles
                }
            ) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
        }

        val orderedStates = states.values.sortedBy { first[it.id] ?: it.location.offset }
        val resolvedActors = linkedMapOf<String, UsecaseActor>()
        val resolvedUseCases = linkedMapOf<String, UsecaseElement>()
        orderedStates.forEach { state ->
            when (state.kind) {
                SymbolKind.Actor -> resolvedActors[state.id] = UsecaseActor(
                    id = state.id,
                    label = state.label,
                    type = if (state.icon != null) {
                        UsecaseActorType.Icon
                    } else {
                        state.actorType ?: UsecaseActorType.Normal
                    },
                    icon = state.icon,
                    business = state.business ?: false,
                    stereotype = state.stereotype,
                    parentId = state.parentId,
                    classes = state.classes.toList(),
                    styles = state.styles.toList(),
                )
                SymbolKind.Usecase -> resolvedUseCases[state.id] = UsecaseElement(
                    id = state.id,
                    label = state.label,
                    shape = state.shape ?: UsecaseShape.Ellipse,
                    business = state.business ?: false,
                    stereotype = state.stereotype,
                    parentId = state.parentId,
                    classes = state.classes.toList(),
                    styles = state.styles.toList(),
                )
                else -> Unit
            }
        }

        val resolvedBoundaries = linkedMapOf<String, UsecaseSystemBoundary>()
        boundaryStates.values
            .sortedBy { first[it.id] ?: it.location.offset }
            .forEach { boundary ->
                val members = orderedStates
                    .filter { state -> state.parentId == boundary.id }
                    .map(ElementState::id)
                resolvedBoundaries[boundary.id] = UsecaseSystemBoundary(
                    id = boundary.id,
                    label = boundary.label,
                    type = boundary.type ?: UsecaseBoundaryType.Rectangle,
                    members = members,
                    classes = boundary.classes.toList(),
                    styles = boundary.styles.toList(),
                )
            }

        val resolvedJson = linkedMapOf<String, UsecaseJsonNode>()
        jsonStates.values
            .sortedBy { first[it.id] ?: it.location.offset }
            .forEach { node ->
                resolvedJson[node.id] = UsecaseJsonNode(
                    id = node.id,
                    value = node.value,
                    classes = node.classes.toList(),
                    styles = node.styles.toList(),
                )
            }

        return GMResult.Ok(
            UsecaseDocument(
                direction = direction,
                actors = resolvedActors,
                useCases = resolvedUseCases,
                systemBoundaries = resolvedBoundaries,
                relationships = edgeStates.values.map(EdgeState::toModel),
                notes = noteStates,
                jsonNodes = resolvedJson,
                classDefinitions = definitions,
                title = diagramTitle,
                accessibilityTitle = accessibilityTitle,
                accessibilityDescription = accessibilityDescription,
            ),
        )
    }

    private fun collectElement(
        draft: DraftElement,
        symbols: MutableMap<String, SymbolOrigin>,
        states: MutableMap<String, ElementState>,
    ): GMResult<Unit, MermaidError> {
        val expectedKind = if (draft.kind == DraftElementKind.Actor) {
            SymbolKind.Actor
        } else {
            SymbolKind.Usecase
        }
        val origin = symbols[draft.id]
        if (origin != null && origin.kind != expectedKind) {
            return conflict(
                "ID '${draft.id}' is declared as both ${origin.kind.displayName} and " +
                    expectedKind.displayName,
                draft.location,
                origin.location,
            )
        }
        if (origin != null && (origin.generated || draft.generated)) {
            return conflict(
                "Generated ID '${draft.id}' collides with another declaration",
                draft.location,
                origin.location,
            )
        }
        val existing = states[draft.id]
        if (existing == null) {
            val state = ElementState(
                kind = expectedKind,
                id = draft.id,
                label = draft.label,
                location = draft.location,
                generated = draft.generated,
                parentId = draft.parentId,
                parentLocation = draft.parentLocation,
                shape = draft.shape,
                stereotype = draft.stereotype,
                classes = draft.classes.toMutableList(),
            )
            when (val result = applyDeclarationMetadata(state, draft.metadata)) {
                is GMResult.Ok -> Unit
                is GMResult.Err -> return result
            }
            states[draft.id] = state
            symbols[draft.id] = SymbolOrigin(
                kind = expectedKind,
                location = draft.location,
                generated = draft.generated,
            )
            return GMResult.Ok(Unit)
        }
        if (existing.label != draft.label) {
            return conflict(
                "ID '${draft.id}' has conflicting labels",
                draft.location,
                existing.location,
            )
        }
        if (draft.shape != null && existing.shape != null && draft.shape != existing.shape) {
            return conflict(
                "Use case '${draft.id}' has conflicting shapes",
                draft.location,
                existing.location,
            )
        }
        if (
            draft.parentId != null &&
            existing.parentId != null &&
            draft.parentId != existing.parentId
        ) {
            return conflict(
                "Element '${draft.id}' belongs to more than one system boundary",
                draft.parentLocation ?: draft.location,
                existing.parentLocation ?: existing.location,
            )
        }
        if (
            draft.stereotype != null &&
            existing.stereotype != null &&
            draft.stereotype != existing.stereotype
        ) {
            return conflict(
                "Element '${draft.id}' has conflicting stereotypes",
                draft.location,
                existing.location,
            )
        }
        existing.shape = existing.shape ?: draft.shape
        existing.parentId = existing.parentId ?: draft.parentId
        existing.parentLocation = existing.parentLocation ?: draft.parentLocation
        existing.stereotype = existing.stereotype ?: draft.stereotype
        addUnique(existing.classes, draft.classes)
        return applyDeclarationMetadata(existing, draft.metadata)
    }

    private fun collectBoundary(
        draft: DraftBoundary,
        symbols: MutableMap<String, SymbolOrigin>,
        states: MutableMap<String, BoundaryState>,
    ): GMResult<Unit, MermaidError> {
        val origin = symbols[draft.id]
        if (origin != null && origin.kind != SymbolKind.Boundary) {
            return conflict(
                "ID '${draft.id}' is declared as both ${origin.kind.displayName} and boundary",
                draft.location,
                origin.location,
            )
        }
        if (origin != null && (origin.generated || draft.generated)) {
            return conflict(
                "Generated ID '${draft.id}' collides with another declaration",
                draft.location,
                origin.location,
            )
        }
        val existing = states[draft.id]
        if (existing != null) {
            if (existing.label != draft.label) {
                return conflict(
                    "Boundary '${draft.id}' has conflicting titles",
                    draft.location,
                    existing.location,
                )
            }
            addUnique(existing.classes, draft.classes)
            return applyBoundaryMetadata(existing, draft.metadata)
        }
        val state = BoundaryState(
            id = draft.id,
            label = draft.label,
            location = draft.location,
            classes = draft.classes.toMutableList(),
        )
        when (val result = applyBoundaryMetadata(state, draft.metadata)) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        states[draft.id] = state
        symbols[draft.id] = SymbolOrigin(
            kind = SymbolKind.Boundary,
            location = draft.location,
            generated = draft.generated,
        )
        return GMResult.Ok(Unit)
    }

    private fun collectJson(
        draft: DraftJson,
        symbols: MutableMap<String, SymbolOrigin>,
        states: MutableMap<String, JsonState>,
    ): GMResult<Unit, MermaidError> {
        when (
            val result = registerUnique(
                symbols,
                draft.id,
                SymbolKind.Json,
                draft.location,
                generated = false,
            )
        ) {
            is GMResult.Ok -> Unit
            is GMResult.Err -> return result
        }
        states[draft.id] = JsonState(
            id = draft.id,
            value = draft.value,
            location = draft.location,
            classes = draft.classes.toMutableList(),
        )
        return GMResult.Ok(Unit)
    }

    private fun resolveEndpoint(
        endpoint: DraftEndpoint,
        symbols: MutableMap<String, SymbolOrigin>,
        states: MutableMap<String, ElementState>,
        first: MutableMap<String, Int>,
    ): GMResult<SymbolKind, MermaidError> {
        if (endpoint.classesOnReference && !endpoint.declaration) {
            return parseError(
                endpoint.location,
                "Relationship endpoint '${endpoint.id}' uses ::: without declaring the node",
            )
        }
        val existing = symbols[endpoint.id]
        if (existing != null) {
            return GMResult.Ok(existing.kind)
        }
        states[endpoint.id] = ElementState(
            kind = SymbolKind.Usecase,
            id = endpoint.id,
            label = endpoint.label,
            location = endpoint.location,
            generated = endpoint.generated,
            shape = UsecaseShape.Ellipse,
        )
        symbols[endpoint.id] = SymbolOrigin(
            kind = SymbolKind.Usecase,
            location = endpoint.location,
            generated = endpoint.generated,
        )
        recordFirst(first, endpoint.id, endpoint.location.offset)
        return GMResult.Ok(SymbolKind.Usecase)
    }

    private fun validateRelationship(
        draft: DraftRelationship,
        source: SymbolKind,
        target: SymbolKind,
    ): GMResult<Unit, MermaidError> {
        val allowed = setOf(SymbolKind.Actor, SymbolKind.Usecase, SymbolKind.Json)
        if (source !in allowed) {
            return parseError(
                draft.source.location,
                "Relationship source '${draft.source.id}' cannot be ${source.displayName}",
            )
        }
        if (target !in allowed) {
            return parseError(
                draft.target.location,
                "Relationship target '${draft.target.id}' cannot be ${target.displayName}",
            )
        }
        if (
            draft.type in setOf(UsecaseRelationshipType.Include, UsecaseRelationshipType.Extend) &&
            (source != SymbolKind.Usecase || target != SymbolKind.Usecase)
        ) {
            return parseError(
                draft.location,
                "${draft.type.name.lowercase()} relationship requires use-case endpoints",
            )
        }
        if (
            draft.type == UsecaseRelationshipType.Generalization &&
            (source !in setOf(SymbolKind.Actor, SymbolKind.Usecase) || source != target)
        ) {
            return parseError(
                draft.location,
                "Generalization requires actor-to-actor or use-case-to-use-case endpoints",
            )
        }
        if (
            draft.type == UsecaseRelationshipType.Association &&
            (source == SymbolKind.Json || target == SymbolKind.Json) &&
            draft.arrowType !in setOf(
                UsecaseArrowType.SolidArrow,
                UsecaseArrowType.BackArrow,
                UsecaseArrowType.LineSolid,
            )
        ) {
            return parseError(
                draft.location,
                "JSON relationships permit only point, reversed-point, or markerless " +
                    "solid associations",
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun applyMetadata(
        assignment: DraftMetadataAssignment,
        symbols: Map<String, SymbolOrigin>,
        elements: MutableMap<String, ElementState>,
        boundaries: MutableMap<String, BoundaryState>,
        edges: MutableMap<String, EdgeState>,
    ): GMResult<Unit, MermaidError> {
        val kind = symbols[assignment.target]?.kind
            ?: return parseError(
                assignment.location,
                "Metadata target '${assignment.target}' is unresolved",
            )
        return when (kind) {
            SymbolKind.Actor,
            SymbolKind.Usecase,
            -> {
                val state = elements[assignment.target]
                    ?: return parseError(
                        assignment.location,
                        "Metadata target '${assignment.target}' is unresolved",
                    )
                applyStandaloneElementMetadata(state, assignment.metadata)
            }
            SymbolKind.Boundary -> {
                val state = boundaries[assignment.target]
                    ?: return parseError(
                        assignment.location,
                        "Metadata target '${assignment.target}' is unresolved",
                    )
                applyBoundaryMetadata(state, assignment.metadata)
            }
            SymbolKind.Edge -> {
                val edge = edges[assignment.target]
                    ?: return parseError(
                        assignment.location,
                        "Metadata target '${assignment.target}' is not an explicit edge",
                    )
                for (property in assignment.metadata.properties) {
                    when (property.key) {
                        "animate" -> {
                            val value = property.value as? Boolean
                                ?: return invalidMetadata(edge.id, kind, property)
                            edge.animated = value
                            if (!value) edge.animation = null
                        }
                        "animation" -> {
                            edge.animation = when (property.value) {
                                "fast" -> UsecaseAnimation.Fast
                                "slow" -> UsecaseAnimation.Slow
                                else -> return invalidMetadata(edge.id, kind, property)
                            }
                            edge.animated = true
                        }
                        else -> return invalidMetadata(edge.id, kind, property)
                    }
                }
                GMResult.Ok(Unit)
            }
            SymbolKind.Json -> {
                val first = assignment.metadata.properties.firstOrNull()
                if (first == null) {
                    GMResult.Ok(Unit)
                } else {
                    invalidMetadata(assignment.target, kind, first)
                }
            }
        }
    }

    private fun applyDeclarationMetadata(
        state: ElementState,
        metadata: DraftMetadata?,
    ): GMResult<Unit, MermaidError> {
        if (metadata == null) return GMResult.Ok(Unit)
        for (property in metadata.properties) {
            val result = if (state.kind == SymbolKind.Actor) {
                applyActorProperty(state, property, replace = false)
            } else if (property.key == "business" && property.value is Boolean) {
                if (state.business != null && state.business != property.value) {
                    conflict(
                        "Use case '${state.id}' has conflicting business metadata",
                        property.location,
                        state.location,
                    )
                } else {
                    state.business = property.value
                    GMResult.Ok(Unit)
                }
            } else {
                invalidMetadata(state.id, state.kind, property)
            }
            if (result is GMResult.Err) return result
        }
        return GMResult.Ok(Unit)
    }

    private fun applyStandaloneElementMetadata(
        state: ElementState,
        metadata: DraftMetadata,
    ): GMResult<Unit, MermaidError> {
        for (property in metadata.properties) {
            val result = if (state.kind == SymbolKind.Actor) {
                applyActorProperty(state, property, replace = true)
            } else if (property.key == "business" && property.value is Boolean) {
                state.business = property.value
                GMResult.Ok(Unit)
            } else {
                invalidMetadata(state.id, state.kind, property)
            }
            if (result is GMResult.Err) return result
        }
        return GMResult.Ok(Unit)
    }

    private fun applyActorProperty(
        state: ElementState,
        property: DraftMetadataProperty,
        replace: Boolean,
    ): GMResult<Unit, MermaidError> {
        when (property.key) {
            "type" -> {
                val parsed = when (property.value) {
                    "normal" -> UsecaseActorType.Normal
                    "hollow" -> UsecaseActorType.Hollow
                    "awesome" -> UsecaseActorType.Awesome
                    else -> return invalidMetadata(state.id, state.kind, property)
                }
                if (!replace && state.actorType != null && state.actorType != parsed) {
                    return conflict(
                        "Actor '${state.id}' has conflicting type metadata",
                        property.location,
                        state.location,
                    )
                }
                state.actorType = parsed
            }
            "icon" -> {
                val icon = property.value as? String
                    ?: return invalidMetadata(state.id, state.kind, property)
                if (!replace && state.icon != null && state.icon != icon) {
                    return conflict(
                        "Actor '${state.id}' has conflicting icon metadata",
                        property.location,
                        state.location,
                    )
                }
                state.icon = icon
            }
            "business" -> {
                val business = property.value as? Boolean
                    ?: return invalidMetadata(state.id, state.kind, property)
                if (!replace && state.business != null && state.business != business) {
                    return conflict(
                        "Actor '${state.id}' has conflicting business metadata",
                        property.location,
                        state.location,
                    )
                }
                state.business = business
            }
            else -> return invalidMetadata(state.id, state.kind, property)
        }
        return GMResult.Ok(Unit)
    }

    private fun applyBoundaryMetadata(
        state: BoundaryState,
        metadata: DraftMetadata?,
    ): GMResult<Unit, MermaidError> {
        if (metadata == null) return GMResult.Ok(Unit)
        for (property in metadata.properties) {
            if (property.key != "type") {
                return invalidMetadata(state.id, SymbolKind.Boundary, property)
            }
            state.type = when (property.value) {
                "rect" -> UsecaseBoundaryType.Rectangle
                "package" -> UsecaseBoundaryType.Package
                else -> return invalidMetadata(state.id, SymbolKind.Boundary, property)
            }
        }
        return GMResult.Ok(Unit)
    }

    private fun validateElement(state: ElementState): GMResult<Unit, MermaidError> {
        val actorType = if (state.icon != null) {
            UsecaseActorType.Icon
        } else {
            state.actorType ?: UsecaseActorType.Normal
        }
        if (
            state.kind == SymbolKind.Actor &&
            state.icon != null &&
            state.actorType != null &&
            state.actorType != UsecaseActorType.Normal
        ) {
            return parseError(
                state.location,
                "Actor '${state.id}' cannot combine icon with type " +
                    "'${state.actorType?.name?.lowercase()}'",
            )
        }
        if (
            state.kind == SymbolKind.Actor &&
            state.business == true &&
            actorType in setOf(UsecaseActorType.Icon, UsecaseActorType.Awesome)
        ) {
            return parseError(
                state.location,
                "Business actor '${state.id}' must use normal or hollow geometry",
            )
        }
        if (
            state.kind == SymbolKind.Usecase &&
            state.business == true &&
            state.shape == UsecaseShape.Rectangle
        ) {
            return parseError(
                state.location,
                "Rectangular use case '${state.id}' cannot be a business use case",
            )
        }
        return GMResult.Ok(Unit)
    }

    private fun mutateStylable(
        id: String,
        location: DraftLocation,
        symbols: Map<String, SymbolOrigin>,
        elements: MutableMap<String, ElementState>,
        boundaries: MutableMap<String, BoundaryState>,
        jsonNodes: MutableMap<String, JsonState>,
        edges: MutableMap<String, EdgeState>,
        block: (Stylable) -> Unit,
    ): GMResult<Unit, MermaidError> {
        val target: Stylable? = when (symbols[id]?.kind) {
            SymbolKind.Actor,
            SymbolKind.Usecase,
            -> elements[id]
            SymbolKind.Boundary -> boundaries[id]
            SymbolKind.Json -> jsonNodes[id]
            SymbolKind.Edge -> edges[id]
            null -> null
        }
        if (target == null) {
            return parseError(
                location,
                "Class/style target '$id' is unresolved or anonymous",
            )
        }
        block(target)
        return GMResult.Ok(Unit)
    }

    private fun registerUnique(
        symbols: MutableMap<String, SymbolOrigin>,
        id: String,
        kind: SymbolKind,
        location: DraftLocation,
        generated: Boolean,
    ): GMResult<Unit, MermaidError> {
        val previous = symbols[id]
        if (previous != null) {
            return conflict(
                "ID '$id' is declared more than once " +
                    "(${previous.kind.displayName} and ${kind.displayName})",
                location,
                previous.location,
            )
        }
        symbols[id] = SymbolOrigin(kind, location, generated)
        return GMResult.Ok(Unit)
    }

    private fun invalidMetadata(
        id: String,
        kind: SymbolKind,
        property: DraftMetadataProperty,
    ): GMResult<Unit, MermaidError> = parseError(
        property.location,
        "Metadata property '${property.key}' is invalid for ${kind.displayName} '$id'",
    )

    private fun conflict(
        message: String,
        current: DraftLocation,
        previous: DraftLocation,
    ): GMResult<Unit, MermaidError> = parseError(
        current,
        "$message; previous declaration at line ${previous.line}, column ${previous.column}",
    )

    private fun parseError(
        location: DraftLocation,
        message: String,
    ): GMResult.Err<MermaidError> = GMResult.Err(
        MermaidError.Parse(location.line, location.column, message),
    )

    private fun addUnique(target: MutableList<String>, values: List<String>) {
        values.forEach { value ->
            if (value !in target) target += value
        }
    }

    private fun recordFirst(target: MutableMap<String, Int>, id: String, offset: Int) {
        val previous = target[id]
        if (previous == null || offset < previous) target[id] = offset
    }

    private sealed interface Declaration {
        val offset: Int

        data class Element(
            override val offset: Int,
            val value: DraftElement,
        ) : Declaration

        data class Boundary(
            override val offset: Int,
            val value: DraftBoundary,
        ) : Declaration

        data class Json(
            override val offset: Int,
            val value: DraftJson,
        ) : Declaration

        data class Edge(
            override val offset: Int,
            val value: DraftRelationship,
        ) : Declaration
    }

    private enum class SymbolKind(val displayName: String) {
        Actor("actor"),
        Usecase("usecase"),
        Boundary("boundary"),
        Json("json"),
        Edge("edge"),
    }

    private data class SymbolOrigin(
        val kind: SymbolKind,
        val location: DraftLocation,
        val generated: Boolean,
    )

    private interface Stylable {
        val classes: MutableList<String>
        val styles: MutableList<String>
    }

    private data class ElementState(
        val kind: SymbolKind,
        val id: String,
        val label: UsecaseLabel,
        val location: DraftLocation,
        val generated: Boolean,
        var parentId: String? = null,
        var parentLocation: DraftLocation? = null,
        var shape: UsecaseShape? = null,
        var stereotype: String? = null,
        var actorType: UsecaseActorType? = null,
        var icon: String? = null,
        var business: Boolean? = null,
        override val classes: MutableList<String> = mutableListOf(),
        override val styles: MutableList<String> = mutableListOf(),
    ) : Stylable

    private data class BoundaryState(
        val id: String,
        val label: UsecaseLabel,
        val location: DraftLocation,
        var type: UsecaseBoundaryType? = null,
        override val classes: MutableList<String> = mutableListOf(),
        override val styles: MutableList<String> = mutableListOf(),
    ) : Stylable

    private data class JsonState(
        val id: String,
        val value: JsonObject,
        val location: DraftLocation,
        override val classes: MutableList<String> = mutableListOf(),
        override val styles: MutableList<String> = mutableListOf(),
    ) : Stylable

    private data class EdgeState(
        val id: String,
        val explicitId: Boolean,
        val source: String,
        val target: String,
        val type: UsecaseRelationshipType,
        val arrowType: UsecaseArrowType,
        val label: UsecaseLabel?,
        val minimumLength: Int,
        var animated: Boolean = false,
        var animation: UsecaseAnimation? = null,
        override val classes: MutableList<String> = mutableListOf(),
        override val styles: MutableList<String> = mutableListOf(),
    ) : Stylable {
        fun toModel(): UsecaseRelationship = UsecaseRelationship(
            id = id,
            explicitId = explicitId,
            source = source,
            target = target,
            type = type,
            arrowType = arrowType,
            label = label,
            minimumLength = minimumLength,
            classes = classes.toList(),
            styles = styles.toList(),
            animated = animated,
            animation = animation,
        )
    }
}

internal data class DraftLocation(
    val line: Int,
    val column: Int,
    val offset: Int,
)

internal data class DraftMetadataProperty(
    val key: String,
    val value: Any,
    val location: DraftLocation,
)

internal data class DraftMetadata(
    val properties: List<DraftMetadataProperty>,
)

internal enum class DraftElementKind {
    Actor,
    Usecase,
}

internal data class DraftElement(
    val id: String,
    val kind: DraftElementKind,
    val label: UsecaseLabel,
    val location: DraftLocation,
    val generated: Boolean,
    val parentId: String? = null,
    val parentLocation: DraftLocation? = null,
    val shape: UsecaseShape? = null,
    val metadata: DraftMetadata? = null,
    val stereotype: String? = null,
    val classes: List<String> = emptyList(),
)

internal data class DraftBoundary(
    val id: String,
    val label: UsecaseLabel,
    val location: DraftLocation,
    val generated: Boolean,
    val metadata: DraftMetadata? = null,
    val classes: List<String> = emptyList(),
)

internal data class DraftJson(
    val id: String,
    val value: JsonObject,
    val location: DraftLocation,
    val classes: List<String> = emptyList(),
)

internal data class DraftEndpoint(
    val id: String,
    val label: UsecaseLabel,
    val location: DraftLocation,
    val generated: Boolean,
    val declaration: Boolean,
    val classesOnReference: Boolean,
)

internal data class DraftRelationship(
    val source: DraftEndpoint,
    val target: DraftEndpoint,
    val location: DraftLocation,
    val explicitId: String? = null,
    val explicitIdLocation: DraftLocation? = null,
    val type: UsecaseRelationshipType,
    val arrowType: UsecaseArrowType,
    val label: UsecaseLabel? = null,
    val minimumLength: Int = 1,
)

internal data class DraftNote(
    val target: String,
    val targetLocation: DraftLocation,
    val label: UsecaseLabel,
    val location: DraftLocation,
)

internal data class DraftMetadataAssignment(
    val target: String,
    val location: DraftLocation,
    val metadata: DraftMetadata,
)

internal data class DraftTarget(
    val id: String,
    val location: DraftLocation,
)

internal data class DraftClassAssignment(
    val targets: List<DraftTarget>,
    val classes: List<String>,
)

internal data class DraftStyleAssignment(
    val target: String,
    val location: DraftLocation,
    val styles: List<String>,
)

internal data class DraftClassDefinition(
    val ids: List<String>,
    val styles: List<String>,
)
