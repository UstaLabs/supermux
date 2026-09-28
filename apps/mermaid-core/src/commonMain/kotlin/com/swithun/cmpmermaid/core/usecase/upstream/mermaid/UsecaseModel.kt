package com.swithun.cmpmermaid.core.usecase.upstream.mermaid

import kotlinx.serialization.json.JsonObject

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/usecase/usecaseTypes.ts.
 */
internal enum class UsecaseLabelType {
    Text,
    Markdown,
}

internal enum class UsecaseActorType {
    Normal,
    Hollow,
    Awesome,
    Icon,
}

internal enum class UsecaseShape {
    Ellipse,
    Rectangle,
}

internal enum class UsecaseBoundaryType {
    Rectangle,
    Package,
}

internal enum class UsecaseRelationshipType {
    Association,
    Include,
    Extend,
    Generalization,
    Note,
}

internal enum class UsecaseArrowType {
    SolidArrow,
    BackArrow,
    LineSolid,
    CircleArrow,
    CrossArrow,
    CircleArrowReversed,
    CrossArrowReversed,
}

internal enum class UsecaseDirection {
    TopToBottom,
    BottomToTop,
    LeftToRight,
    RightToLeft,
}

internal enum class UsecaseAnimation {
    Fast,
    Slow,
}

internal data class UsecaseLabel(
    val text: String,
    val type: UsecaseLabelType,
)

internal data class UsecaseActor(
    val id: String,
    val label: UsecaseLabel,
    val type: UsecaseActorType,
    val icon: String? = null,
    val business: Boolean = false,
    val stereotype: String? = null,
    val parentId: String? = null,
    val classes: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
)

internal data class UsecaseElement(
    val id: String,
    val label: UsecaseLabel,
    val shape: UsecaseShape,
    val business: Boolean = false,
    val stereotype: String? = null,
    val parentId: String? = null,
    val classes: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
)

internal data class UsecaseSystemBoundary(
    val id: String,
    val label: UsecaseLabel,
    val type: UsecaseBoundaryType,
    val members: List<String>,
    val classes: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
)

internal data class UsecaseRelationship(
    val id: String,
    val explicitId: Boolean,
    val source: String,
    val target: String,
    val type: UsecaseRelationshipType,
    val arrowType: UsecaseArrowType,
    val label: UsecaseLabel? = null,
    val minimumLength: Int = 1,
    val classes: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
    val animated: Boolean = false,
    val animation: UsecaseAnimation? = null,
)

internal data class UsecaseNote(
    val id: String,
    val target: String,
    val label: UsecaseLabel,
)

internal data class UsecaseJsonNode(
    val id: String,
    val value: JsonObject,
    val classes: List<String> = emptyList(),
    val styles: List<String> = emptyList(),
)

internal data class UsecaseClassDefinition(
    val id: String,
    val styles: List<String>,
)

internal data class UsecaseDocument(
    val direction: UsecaseDirection = UsecaseDirection.LeftToRight,
    val actors: Map<String, UsecaseActor> = emptyMap(),
    val useCases: Map<String, UsecaseElement> = emptyMap(),
    val systemBoundaries: Map<String, UsecaseSystemBoundary> = emptyMap(),
    val relationships: List<UsecaseRelationship> = emptyList(),
    val notes: Map<String, UsecaseNote> = emptyMap(),
    val jsonNodes: Map<String, UsecaseJsonNode> = emptyMap(),
    val classDefinitions: Map<String, UsecaseClassDefinition> = emptyMap(),
    val title: String? = null,
    val accessibilityTitle: String? = null,
    val accessibilityDescription: String? = null,
)
