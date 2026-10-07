package com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid

internal object ClassLineType {
    const val LINE = 0
    const val DOTTED_LINE = 1
}

internal object ClassRelationType {
    const val NONE = -1
    const val AGGREGATION = 0
    const val EXTENSION = 1
    const val COMPOSITION = 2
    const val DEPENDENCY = 3
    const val LOLLIPOP = 4
}

internal data class ClassRelationDefinition(
    val type1: Int,
    val type2: Int,
    val lineType: Int,
)

internal data class ClassRelation(
    var id1: String,
    var id2: String,
    val relation: ClassRelationDefinition,
    var relationTitle1: String = "none",
    var relationTitle2: String = "none",
    var title: String? = null,
    val styles: MutableList<String> = mutableListOf(),
)

internal enum class ClassMemberType {
    Method,
    Attribute,
}

internal data class ClassMember(
    val id: String,
    val memberType: ClassMemberType,
    val visibility: Char?,
    val classifier: Char?,
    val parameters: String = "",
    val returnType: String = "",
) {
    val displayText: String
        get() {
            val prefix = visibility?.toString().orEmpty()
            val name = parseGenericTypes(id)
            if (memberType == ClassMemberType.Attribute) {
                return (prefix + name).trim()
            }
            val result = buildString {
                append(prefix)
                append(name)
                append('(')
                append(parseGenericTypes(parameters.trim()))
                append(')')
                if (returnType.isNotEmpty()) {
                    append(" : ")
                    append(parseGenericTypes(returnType))
                }
            }
            return result.trim()
        }

    val italic: Boolean get() = classifier == '*'
    val underline: Boolean get() = classifier == '$'

    companion object {
        fun parse(
            input: String,
            memberType: ClassMemberType,
        ): ClassMember {
            val source = input.trim()
            if (memberType == ClassMemberType.Method) {
                val match = METHOD_PATTERN.matchEntire(source)
                if (match != null) {
                    val visibility = match.groupValues[1]
                        .singleOrNull()
                        ?.takeIf(VISIBILITY::contains)
                    var returnType = match.groupValues[5].trim()
                    var classifier = match.groupValues[4].trim().singleOrNull()
                    if (classifier == null) {
                        val suffix = returnType.lastOrNull()
                        if (suffix == '*' || suffix == '$') {
                            classifier = suffix
                            returnType = returnType.dropLast(1).trimEnd()
                        }
                    }
                    return ClassMember(
                        id = preserveLeadingSpace(match.groupValues[2]),
                        memberType = memberType,
                        visibility = visibility,
                        classifier = classifier,
                        parameters = match.groupValues[3].trim(),
                        returnType = returnType,
                    )
                }
            }

            val visibility = source.firstOrNull()?.takeIf(VISIBILITY::contains)
            val classifier = source.lastOrNull()?.takeIf { it == '*' || it == '$' }
            val start = if (visibility == null) 0 else 1
            val end = if (classifier == null) source.length else source.length - 1
            return ClassMember(
                id = preserveLeadingSpace(source.substring(start, end.coerceAtLeast(start))),
                memberType = memberType,
                visibility = visibility,
                classifier = classifier,
            )
        }

        private fun preserveLeadingSpace(source: String): String =
            if (source.startsWith(' ')) " ${source.trim()}" else source.trim()

        private val VISIBILITY = setOf('#', '+', '~', '-')
        private val METHOD_PATTERN =
            Regex("""([#+~-])?(.+)\((.*)\)([\s$*])?(.*)([$*])?""")
    }
}

internal data class ClassNode(
    val id: String,
    var type: String = "",
    var label: String = id,
    val methods: MutableList<ClassMember> = mutableListOf(),
    val members: MutableList<ClassMember> = mutableListOf(),
    val annotations: MutableList<String> = mutableListOf(),
    val cssClasses: MutableList<String> = mutableListOf("default"),
    val styles: MutableList<String> = mutableListOf(),
    var parent: String? = null,
    var link: String? = null,
    var linkTarget: String? = null,
    var callbackName: String? = null,
    var callbackArgs: String? = null,
    var tooltip: String? = null,
)

internal data class ClassNote(
    val id: String,
    val className: String?,
    val text: String,
    val index: Int,
    var parent: String? = null,
)

internal data class ClassInterface(
    val id: String,
    val label: String,
    val classId: String,
)

internal data class ClassNamespace(
    val id: String,
    var label: String,
    val classes: LinkedHashMap<String, ClassNode> = linkedMapOf(),
    val notes: LinkedHashMap<String, ClassNote> = linkedMapOf(),
    val children: LinkedHashMap<String, ClassNamespace> = linkedMapOf(),
    var parent: String? = null,
    var explicit: Boolean = false,
)

internal data class ClassStyleDefinition(
    val id: String,
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
)

internal fun parseGenericTypes(input: String): String {
    val inputSets = buildList {
        var start = 0
        input.forEachIndexed { index, character ->
            if (character == ',') {
                add(input.substring(start, index))
                add(",")
                start = index + 1
            }
        }
        add(input.substring(start))
    }
    val output = mutableListOf<String>()
    var index = 0
    while (index < inputSets.size) {
        var current = inputSets[index]
        if (current == "," && index > 0 && index + 1 < inputSets.size) {
            val previous = inputSets[index - 1]
            val next = inputSets[index + 1]
            if (previous.count { it == '~' } == 1 && next.count { it == '~' } == 1) {
                current = previous + current + next
                output.removeLastOrNull()
                index += 1
            }
        }
        output += processGenericSet(current)
        index += 1
    }
    return output.joinToString("")
}

private fun processGenericSet(source: String): String {
    val tildeCount = source.count { it == '~' }
    if (tildeCount <= 1) {
        return source
    }
    val startsWithTilde = tildeCount % 2 != 0 && source.startsWith('~')
    val characters = (if (startsWithTilde) source.drop(1) else source).toMutableList()
    var first = characters.indexOf('~')
    var last = characters.lastIndexOf('~')
    while (first >= 0 && last >= 0 && first != last) {
        characters[first] = '<'
        characters[last] = '>'
        first = characters.indexOf('~')
        last = characters.lastIndexOf('~')
    }
    if (startsWithTilde) {
        characters.add(0, '~')
    }
    return characters.joinToString("")
}
