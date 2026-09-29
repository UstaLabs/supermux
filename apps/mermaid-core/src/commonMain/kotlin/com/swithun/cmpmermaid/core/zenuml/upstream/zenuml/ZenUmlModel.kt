package com.swithun.cmpmermaid.core.zenuml.upstream.zenuml

internal const val ZEN_UML_STARTER: String = "_STARTER_"

internal data class ZenUmlParticipant(
    val name: String,
    val label: String? = null,
    val type: String? = null,
    val stereotype: String? = null,
    val color: String? = null,
    val emoji: String? = null,
    val groupId: String? = null,
    val explicit: Boolean = false,
    val isStarter: Boolean = false,
) {
    val displayName: String
        get() = label ?: name
}

internal data class ZenUmlAssignment(
    val assignee: String,
    val type: String? = null,
) {
    val text: String
        get() = listOfNotNull(type, assignee).joinToString("")
}

internal enum class ZenUmlMessageKind {
    Sync,
    Async,
    Creation,
    Return,
}

internal enum class ZenUmlFragmentKind {
    Loop,
    Alt,
    Par,
    Opt,
    Section,
    Critical,
    TryCatchFinally,
    Ref,
}

internal sealed interface ZenUmlStatement {
    val id: Int
    val line: Int
    val comment: String?
}

internal data class ZenUmlMessage(
    override val id: Int,
    override val line: Int,
    override val comment: String?,
    val kind: ZenUmlMessageKind,
    val from: String,
    val to: String,
    val label: String,
    val providedFrom: Boolean,
    val assignment: ZenUmlAssignment? = null,
    val body: List<ZenUmlStatement> = emptyList(),
) : ZenUmlStatement {
    val isSelf: Boolean
        get() = from == to
}

internal data class ZenUmlFragmentSection(
    val label: String,
    val statements: List<ZenUmlStatement>,
)

internal data class ZenUmlFragment(
    override val id: Int,
    override val line: Int,
    override val comment: String?,
    val kind: ZenUmlFragmentKind,
    val label: String,
    val sections: List<ZenUmlFragmentSection>,
) : ZenUmlStatement

internal data class ZenUmlDivider(
    override val id: Int,
    override val line: Int,
    override val comment: String?,
    val label: String,
) : ZenUmlStatement

internal data class ZenUmlDocument(
    val title: String?,
    val participants: List<ZenUmlParticipant>,
    val statements: List<ZenUmlStatement>,
    val accessibilityTitle: String?,
    val accessibilityDescription: String?,
)
