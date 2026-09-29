package com.swithun.cmpmermaid.core.c4.upstream.mermaid

import com.swithun.cmpmermaid.core.SceneColor
import com.swithun.cmpmermaid.core.ScenePoint
import com.swithun.cmpmermaid.core.SceneRect

/**
 * Mermaid.js 12.0.0:
 * packages/mermaid/src/diagrams/c4/c4Types.ts and c4ShapeAdapter.ts.
 */
internal enum class C4DiagramType(
    val sourceName: String,
) {
    Context("C4Context"),
    Container("C4Container"),
    Component("C4Component"),
    Dynamic("C4Dynamic"),
    Deployment("C4Deployment"),
    ;

    companion object {
        fun fromSource(value: String): C4DiagramType? =
            values().firstOrNull { type -> type.sourceName == value }
    }
}

internal enum class C4ElementType(
    val sourceName: String,
    val stereotype: String,
    val defaultFill: SceneColor,
    val defaultStroke: SceneColor,
) {
    Person("person", "Person", SceneColor(0xFF08427B), SceneColor(0xFF073B6F)),
    ExternalPerson(
        "external_person",
        "Person",
        SceneColor(0xFF686868),
        SceneColor(0xFF8A8A8A),
    ),
    System("system", "Software System", SceneColor(0xFF1168BD), SceneColor(0xFF3C7FC0)),
    SystemDb(
        "system_db",
        "Software System",
        SceneColor(0xFF1168BD),
        SceneColor(0xFF3C7FC0),
    ),
    SystemQueue(
        "system_queue",
        "Software System",
        SceneColor(0xFF1168BD),
        SceneColor(0xFF3C7FC0),
    ),
    ExternalSystem(
        "external_system",
        "Software System",
        SceneColor(0xFF999999),
        SceneColor(0xFF8A8A8A),
    ),
    ExternalSystemDb(
        "external_system_db",
        "Software System",
        SceneColor(0xFF999999),
        SceneColor(0xFF8A8A8A),
    ),
    ExternalSystemQueue(
        "external_system_queue",
        "Software System",
        SceneColor(0xFF999999),
        SceneColor(0xFF8A8A8A),
    ),
    Container("container", "Container", SceneColor(0xFF438DD5), SceneColor(0xFF3C7FC0)),
    ContainerDb(
        "container_db",
        "Container",
        SceneColor(0xFF438DD5),
        SceneColor(0xFF3C7FC0),
    ),
    ContainerQueue(
        "container_queue",
        "Container",
        SceneColor(0xFF438DD5),
        SceneColor(0xFF3C7FC0),
    ),
    ExternalContainer(
        "external_container",
        "Container",
        SceneColor(0xFFB3B3B3),
        SceneColor(0xFFA6A6A6),
    ),
    ExternalContainerDb(
        "external_container_db",
        "Container",
        SceneColor(0xFFB3B3B3),
        SceneColor(0xFFA6A6A6),
    ),
    ExternalContainerQueue(
        "external_container_queue",
        "Container",
        SceneColor(0xFFB3B3B3),
        SceneColor(0xFFA6A6A6),
    ),
    Component("component", "Component", SceneColor(0xFF85BBF0), SceneColor(0xFF78A8D8)),
    ComponentDb(
        "component_db",
        "Component",
        SceneColor(0xFF85BBF0),
        SceneColor(0xFF78A8D8),
    ),
    ComponentQueue(
        "component_queue",
        "Component",
        SceneColor(0xFF85BBF0),
        SceneColor(0xFF78A8D8),
    ),
    ExternalComponent(
        "external_component",
        "Component",
        SceneColor(0xFFCCCCCC),
        SceneColor(0xFFBFBFBF),
    ),
    ExternalComponentDb(
        "external_component_db",
        "Component",
        SceneColor(0xFFCCCCCC),
        SceneColor(0xFFBFBFBF),
    ),
    ExternalComponentQueue(
        "external_component_queue",
        "Component",
        SceneColor(0xFFCCCCCC),
        SceneColor(0xFFBFBFBF),
    ),
    ;

    val isDatabase: Boolean
        get() = sourceName.endsWith("_db")

    val isQueue: Boolean
        get() = sourceName.endsWith("_queue")

    val isPerson: Boolean
        get() = this == Person || this == ExternalPerson

    companion object {
        fun fromSource(value: String): C4ElementType? =
            values().firstOrNull { type -> type.sourceName == value }
    }
}

internal data class C4Text(
    var text: String,
)

internal data class C4Shape(
    val alias: String,
    var label: C4Text,
    var typeC4Shape: C4ElementType,
    var parentBoundary: String,
    var wrap: Boolean,
    var descr: C4Text = C4Text(""),
    var techn: C4Text = C4Text(""),
    var sprite: String? = null,
    var tags: String? = null,
    var link: String? = null,
    var bgColor: String? = null,
    var fontColor: String? = null,
    var borderColor: String? = null,
    var shadowing: String? = null,
    var shape: String? = null,
    var legendText: String? = null,
    var legendSprite: String? = null,
    val attributes: MutableMap<String, String> = linkedMapOf(),
    var bounds: SceneRect? = null,
    var outline: List<ScenePoint> = emptyList(),
)

internal data class C4Boundary(
    val alias: String,
    var label: C4Text,
    var type: C4Text,
    var parentBoundary: String,
    var wrap: Boolean,
    var tags: String? = null,
    var link: String? = null,
    var descr: C4Text = C4Text(""),
    var techn: C4Text = C4Text(""),
    var nodeType: String? = null,
    var sprite: String? = null,
    var bgColor: String? = null,
    var fontColor: String? = null,
    var borderColor: String? = null,
    var shadowing: String? = null,
    var shape: String? = null,
    var legendText: String? = null,
    var legendSprite: String? = null,
    val attributes: MutableMap<String, String> = linkedMapOf(),
    var bounds: SceneRect? = null,
)

internal data class C4Relation(
    var type: String,
    val from: String,
    val to: String,
    var label: C4Text,
    var techn: C4Text = C4Text(""),
    var descr: C4Text = C4Text(""),
    var sprite: String? = null,
    var tags: String? = null,
    var link: String? = null,
    var wrap: Boolean,
    var textColor: String? = null,
    var lineColor: String? = null,
    var offsetX: Int = 0,
    var offsetY: Int = 0,
    val attributes: MutableMap<String, String> = linkedMapOf(),
)

internal sealed interface C4Attribute {
    data class Positional(
        val value: String,
    ) : C4Attribute

    data class Named(
        val key: String,
        val value: String,
    ) : C4Attribute
}
