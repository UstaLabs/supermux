package com.swithun.cmpmermaid.core.zenuml.upstream.zenuml

internal const val ZEN_UML_STATEMENT_MARGIN: Double = 16.0
internal const val ZEN_UML_FRAGMENT_HEADER_HEIGHT: Double = 25.0
internal const val ZEN_UML_FRAGMENT_PADDING_BOTTOM: Double = 10.0
internal const val ZEN_UML_PARTICIPANT_TOP: Double = 28.0
internal const val ZEN_UML_PARTICIPANT_HEIGHT: Double = 40.0

internal enum class ZenUmlStatementKind {
    Loop,
    Alt,
    Par,
    Opt,
    Section,
    Critical,
    TryCatchFinally,
    Ref,
    Creation,
    Sync,
    Async,
    Divider,
    Return,
}

internal data class ZenUmlStatementCoordinate(
    val top: Double,
    val height: Double,
    val kind: ZenUmlStatementKind,
)

/**
 * @zenuml/core 3.49.2:
 * src/positioning/VerticalCoordinates.ts and the src/positioning/vertical/vm classes.
 */
internal class ZenUmlVerticalCoordinates(
    document: ZenUmlDocument,
) {
    private val statementCoordinates = mutableMapOf<Int, ZenUmlStatementCoordinate>()
    private val creationTops = mutableMapOf<String, Double>()
    private val totalHeight = layoutBlock(
        statements = document.statements,
        startTop = 56.0,
        insideOccurrence = false,
        parentKind = null,
    )

    fun getCreationTop(participant: String): Double? = creationTops[participant]

    fun getStatementCoordinate(statement: ZenUmlStatement): ZenUmlStatementCoordinate? =
        statementCoordinates[statement.id]

    fun entries(): List<Pair<Int, ZenUmlStatementCoordinate>> =
        statementCoordinates.map { (id, coordinate) -> id to coordinate }

    fun getTotalHeight(): Double = totalHeight

    private fun layoutBlock(
        statements: List<ZenUmlStatement>,
        startTop: Double,
        insideOccurrence: Boolean,
        parentKind: ZenUmlStatementKind?,
    ): Double {
        if (statements.isEmpty()) return startTop
        var cursor = startTop + ZEN_UML_STATEMENT_MARGIN
        statements.forEachIndexed { index, statement ->
            if (parentKind == ZenUmlStatementKind.Par && index != 0) {
                cursor += 1.0
            }
            val coordinate = measureStatement(
                statement = statement,
                top = cursor,
                isLastInBlock = index == statements.lastIndex,
                insideOccurrence = insideOccurrence,
            )
            statementCoordinates[statement.id] = coordinate
            cursor = coordinate.top + coordinate.height + ZEN_UML_STATEMENT_MARGIN
        }
        return cursor
    }

    private fun measureStatement(
        statement: ZenUmlStatement,
        top: Double,
        isLastInBlock: Boolean,
        insideOccurrence: Boolean,
    ): ZenUmlStatementCoordinate = when (statement) {
        is ZenUmlMessage -> measureMessage(statement, top, isLastInBlock, insideOccurrence)
        is ZenUmlFragment -> measureFragment(statement, top, insideOccurrence)
        is ZenUmlDivider -> ZenUmlStatementCoordinate(
            top = top,
            height = 40.0,
            kind = ZenUmlStatementKind.Divider,
        )
    }

    private fun measureMessage(
        message: ZenUmlMessage,
        top: Double,
        isLastInBlock: Boolean,
        insideOccurrence: Boolean,
    ): ZenUmlStatementCoordinate = when (message.kind) {
        ZenUmlMessageKind.Sync -> {
            val commentHeight = measureComment(message.comment)
            var cursor = top + commentHeight + if (message.isSelf) 30.0 else 16.0
            cursor = if (message.body.isNotEmpty()) {
                layoutBlock(
                    statements = message.body,
                    startTop = cursor,
                    insideOccurrence = true,
                    parentKind = ZenUmlStatementKind.Sync,
                ) + 2.0
            } else {
                cursor + 22.0
            }
            if (message.assignment != null && !message.isSelf) {
                cursor += 12.0
            }
            ZenUmlStatementCoordinate(top, cursor - top, ZenUmlStatementKind.Sync)
        }
        ZenUmlMessageKind.Async -> {
            val height = measureComment(message.comment) + if (message.isSelf) 44.0 else 16.0
            ZenUmlStatementCoordinate(top, height, ZenUmlStatementKind.Async)
        }
        ZenUmlMessageKind.Creation -> {
            val commentHeight = measureComment(message.comment)
            creationTops[message.to] = top + commentHeight - 8.0
            var cursor = top + commentHeight + 40.0
            cursor = if (message.body.isNotEmpty()) {
                layoutBlock(
                    statements = message.body,
                    startTop = cursor,
                    insideOccurrence = true,
                    parentKind = ZenUmlStatementKind.Creation,
                ) + 2.0
            } else {
                cursor + 22.0
            }
            if (message.assignment != null) {
                cursor += 12.0
            }
            ZenUmlStatementCoordinate(top, cursor - top, ZenUmlStatementKind.Creation)
        }
        ZenUmlMessageKind.Return -> {
            var height = measureComment(message.comment)
            height += if (message.isSelf) {
                20.0
            } else if (!isLastInBlock || !insideOccurrence) {
                16.0
            } else {
                0.0
            }
            ZenUmlStatementCoordinate(top, height, ZenUmlStatementKind.Return)
        }
    }

    private fun measureFragment(
        fragment: ZenUmlFragment,
        top: Double,
        insideOccurrence: Boolean,
    ): ZenUmlStatementCoordinate {
        val commentHeight = measureComment(fragment.comment)
        return when (fragment.kind) {
            ZenUmlFragmentKind.Alt -> {
                var cursor = top + 1.0 + ZEN_UML_FRAGMENT_HEADER_HEIGHT + commentHeight
                fragment.sections.forEachIndexed { index, section ->
                    cursor += if (index == 0) 20.0 else 29.0
                    cursor = layoutBlock(
                        statements = section.statements,
                        startTop = cursor,
                        insideOccurrence = insideOccurrence,
                        parentKind = ZenUmlStatementKind.Alt,
                    )
                }
                cursor += ZEN_UML_FRAGMENT_PADDING_BOTTOM + 1.0
                ZenUmlStatementCoordinate(top, cursor - top, ZenUmlStatementKind.Alt)
            }
            ZenUmlFragmentKind.TryCatchFinally -> {
                var cursor = top + 1.0 + ZEN_UML_FRAGMENT_HEADER_HEIGHT + commentHeight
                fragment.sections.forEachIndexed { index, section ->
                    if (index > 0) cursor += 29.0
                    cursor = layoutBlock(
                        statements = section.statements,
                        startTop = cursor,
                        insideOccurrence = insideOccurrence,
                        parentKind = ZenUmlStatementKind.TryCatchFinally,
                    )
                }
                cursor += ZEN_UML_FRAGMENT_PADDING_BOTTOM + 1.0
                ZenUmlStatementCoordinate(
                    top,
                    cursor - top,
                    ZenUmlStatementKind.TryCatchFinally,
                )
            }
            ZenUmlFragmentKind.Ref -> ZenUmlStatementCoordinate(
                top = top,
                height = commentHeight +
                    ZEN_UML_FRAGMENT_HEADER_HEIGHT +
                    ZEN_UML_FRAGMENT_PADDING_BOTTOM,
                kind = ZenUmlStatementKind.Ref,
            )
            else -> {
                val kind = fragment.kind.toStatementKind()
                var cursor = top + 1.0 + ZEN_UML_FRAGMENT_HEADER_HEIGHT + commentHeight
                if (fragment.label.isNotBlank()) {
                    cursor += 20.0
                }
                cursor = layoutBlock(
                    statements = fragment.sections.firstOrNull()?.statements.orEmpty(),
                    startTop = cursor,
                    insideOccurrence = insideOccurrence,
                    parentKind = kind,
                )
                cursor += ZEN_UML_FRAGMENT_PADDING_BOTTOM + 1.0
                ZenUmlStatementCoordinate(top, cursor - top, kind)
            }
        }
    }

    private fun measureComment(comment: String?): Double =
        comment
            ?.takeIf { value -> value.isNotBlank() }
            ?.trim()
            ?.lines()
            ?.size
            ?.times(20.0)
            ?: 0.0
}

private fun ZenUmlFragmentKind.toStatementKind(): ZenUmlStatementKind = when (this) {
    ZenUmlFragmentKind.Loop -> ZenUmlStatementKind.Loop
    ZenUmlFragmentKind.Alt -> ZenUmlStatementKind.Alt
    ZenUmlFragmentKind.Par -> ZenUmlStatementKind.Par
    ZenUmlFragmentKind.Opt -> ZenUmlStatementKind.Opt
    ZenUmlFragmentKind.Section -> ZenUmlStatementKind.Section
    ZenUmlFragmentKind.Critical -> ZenUmlStatementKind.Critical
    ZenUmlFragmentKind.TryCatchFinally -> ZenUmlStatementKind.TryCatchFinally
    ZenUmlFragmentKind.Ref -> ZenUmlStatementKind.Ref
}
