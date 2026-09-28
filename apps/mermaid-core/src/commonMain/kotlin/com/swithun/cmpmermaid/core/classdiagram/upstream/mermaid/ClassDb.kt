package com.swithun.cmpmermaid.core.classdiagram.upstream.mermaid

import com.swithun.cmpmermaid.core.MermaidSecurityLevel
import com.swithun.cmpmermaid.core.flowchart.upstream.mermaid.MermaidUrlSanitizer

/**
 * Kotlin translation of Mermaid 12.0.0 classDb.ts.
 *
 * The parser mutates this database through the same operations used by the
 * upstream Jison grammar. Rendering consumes typed snapshots from the getters.
 */
internal class ClassDb(
    private val securityLevel: MermaidSecurityLevel,
    diagramTitle: String? = null,
) {
    private val relations = mutableListOf<ClassRelation>()
    private val classes = linkedMapOf<String, ClassNode>()
    private val styleClasses = linkedMapOf<String, ClassStyleDefinition>()
    private val notes = linkedMapOf<String, ClassNote>()
    private val interfaces = mutableListOf<ClassInterface>()
    private val namespaces = linkedMapOf<String, ClassNamespace>()
    private val namespaceStack = mutableListOf<String>()
    private var namespaceCounter = 0

    var direction: String = "TB"
        private set
    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun getClasses(): Map<String, ClassNode> = classes

    fun getRelations(): List<ClassRelation> = relations

    fun getNotes(): Map<String, ClassNote> = notes

    fun getInterfaces(): List<ClassInterface> = interfaces

    fun getNamespaces(): Map<String, ClassNamespace> = namespaces

    fun getStyleClasses(): Map<String, ClassStyleDefinition> = styleClasses

    fun setDirection(value: String) {
        direction = value
    }

    fun setDiagramTitle(value: String) {
        diagramTitle = value
    }

    fun setAccessibilityTitle(value: String) {
        accessibilityTitle = value
    }

    fun setAccessibilityDescription(value: String) {
        accessibilityDescription = value
    }

    fun addClass(rawId: String): String {
        val (className, type) = splitClassNameAndType(rawId)
        classes.getOrPut(className) {
            ClassNode(
                id = className,
                type = type,
                label = className,
            )
        }
        return className
    }

    fun setClassLabel(rawId: String, label: String) {
        val className = addClass(rawId)
        classes[className]?.label = label
    }

    fun addAnnotation(rawClassName: String, annotation: String) {
        val className = addClass(rawClassName)
        classes[className]?.annotations?.add(annotation)
    }

    fun addMember(rawClassName: String, rawMember: String) {
        val className = addClass(rawClassName)
        val member = rawMember.trim()
        if (member.isEmpty()) {
            return
        }
        val node = classes[className] ?: return
        when {
            member.startsWith("<<") && member.endsWith(">>") ->
                node.annotations += member.substring(2, member.length - 2)
            ')' in member ->
                node.methods += ClassMember.parse(member, ClassMemberType.Method)
            else ->
                node.members += ClassMember.parse(member, ClassMemberType.Attribute)
        }
    }

    fun addMembers(rawClassName: String, rawMembers: List<String>) {
        rawMembers.asReversed().forEach { member ->
            addMember(rawClassName, member)
        }
    }

    fun addRelation(relation: ClassRelation) {
        val invalidLollipopPeers = setOf(
            ClassRelationType.LOLLIPOP,
            ClassRelationType.AGGREGATION,
            ClassRelationType.COMPOSITION,
            ClassRelationType.DEPENDENCY,
            ClassRelationType.EXTENSION,
        )
        when {
            relation.relation.type1 == ClassRelationType.LOLLIPOP &&
                relation.relation.type2 !in invalidLollipopPeers -> {
                val classId = addClass(relation.id2)
                val interfaceId = "interface${interfaces.size}"
                interfaces += ClassInterface(interfaceId, relation.id1, classId)
                relation.id1 = interfaceId
            }
            relation.relation.type2 == ClassRelationType.LOLLIPOP &&
                relation.relation.type1 !in invalidLollipopPeers -> {
                val classId = addClass(relation.id1)
                val interfaceId = "interface${interfaces.size}"
                interfaces += ClassInterface(interfaceId, relation.id2, classId)
                relation.id2 = interfaceId
            }
            else -> {
                relation.id1 = addClass(relation.id1)
                relation.id2 = addClass(relation.id2)
            }
        }
        relation.relationTitle1 = cleanupCardinality(relation.relationTitle1)
        relation.relationTitle2 = cleanupCardinality(relation.relationTitle2)
        relations += relation
    }

    fun addNote(
        text: String,
        className: String? = null,
    ): String {
        val index = notes.size
        val note = ClassNote(
            id = "note$index",
            className = className?.let(::splitClassNameAndType)?.first,
            text = text,
            index = index,
        )
        notes[note.id] = note
        return note.id
    }

    fun cleanupLabel(source: String): String =
        source.removePrefix(":").trim()

    fun setCssClass(
        ids: String,
        className: String,
    ) {
        ids.split(',').forEach { rawId ->
            val id = splitClassNameAndType(rawId).first
            classes[id]?.cssClasses?.add(className)
        }
    }

    fun defineClass(
        ids: List<String>,
        styles: List<String>,
    ) {
        ids.forEach { id ->
            val definition = styleClasses.getOrPut(id) { ClassStyleDefinition(id) }
            styles.forEach { style ->
                if ("color" in style) {
                    definition.textStyles += style.replace("fill", "bgFill")
                }
                definition.styles += style
            }
            classes.values
                .filter { id in it.cssClasses }
                .forEach { node ->
                    node.styles += styles.flatMap { style -> style.split(',') }
                }
        }
    }

    fun setCssStyle(
        rawId: String,
        styles: List<String>,
    ) {
        val id = splitClassNameAndType(rawId).first
        val node = classes[id] ?: return
        styles.forEach { style ->
            if (',' in style) {
                node.styles += style.split(',')
            } else {
                node.styles += style
            }
        }
    }

    fun setTooltip(
        ids: String,
        tooltip: String?,
    ) {
        if (tooltip == null) {
            return
        }
        ids.split(',').forEach { rawId ->
            classes[splitClassNameAndType(rawId).first]?.tooltip = tooltip
        }
    }

    fun setLink(
        ids: String,
        link: String,
        target: String? = null,
    ) {
        ids.split(',').forEach { rawId ->
            classes[splitClassNameAndType(rawId).first]?.let { node ->
                node.link = MermaidUrlSanitizer.sanitize(link)
                node.linkTarget = if (securityLevel == MermaidSecurityLevel.Sandbox) {
                    "_top"
                } else {
                    target ?: "_blank"
                }
            }
        }
        setCssClass(ids, "clickable")
    }

    fun setClickEvent(
        ids: String,
        functionName: String,
        functionArgs: String? = null,
    ) {
        if (securityLevel == MermaidSecurityLevel.Loose) {
            ids.split(',').forEach { rawId ->
                classes[splitClassNameAndType(rawId).first]?.let { node ->
                    node.callbackName = functionName
                    node.callbackArgs = functionArgs
                }
            }
        }
        setCssClass(ids, "clickable")
    }

    fun addNamespace(
        rawId: String,
        label: String? = null,
    ): String {
        val qualifiedId = namespaceStack.lastOrNull()?.let { "$it.$rawId" } ?: rawId
        namespaceStack += qualifiedId

        namespaces[qualifiedId]?.let { existing ->
            existing.explicit = true
            if (label != null) {
                existing.label = label
            }
            return qualifiedId
        }

        val parts = qualifiedId.split('.')
        val ancestorIds = buildList {
            parts.indices.forEach { index ->
                add(parts.take(index + 1).joinToString("."))
            }
        }
        ancestorIds.forEachIndexed { index, currentId ->
            val parentId = ancestorIds.getOrNull(index - 1)
            val leaf = index == ancestorIds.lastIndex
            val node = namespaces.getOrPut(currentId) {
                ClassNamespace(
                    id = currentId,
                    label = if (leaf && label != null) label else parts[index],
                    parent = parentId,
                    explicit = leaf,
                ).also { namespaceCounter += 1 }
            }
            if (leaf) {
                node.explicit = true
                if (label != null) {
                    node.label = label
                }
            }
            if (parentId != null) {
                val parent = namespaces[parentId]
                if (parent != null) {
                    if (currentId !in parent.children) {
                        parent.children[currentId] = node
                    }
                    node.parent = node.parent ?: parentId
                }
            }
        }
        return qualifiedId
    }

    fun popNamespace() {
        namespaceStack.removeLastOrNull()
    }

    fun addClassesToNamespace(
        id: String,
        classNames: List<String>,
        noteNames: List<String>,
    ) {
        val namespace = namespaces[id] ?: return
        classNames.forEach { rawName ->
            val className = splitClassNameAndType(rawName).first
            classes[className]?.let { node ->
                node.parent = id
                namespace.classes[className] = node
            }
        }
        noteNames.forEach { noteName ->
            notes[noteName]?.let { note ->
                note.parent = id
                namespace.notes[noteName] = note
            }
        }
    }

    private fun splitClassNameAndType(rawId: String): Pair<String, String> {
        val firstTilde = rawId.indexOf('~')
        if (firstTilde <= 0) {
            return rawId to ""
        }
        val parts = rawId.split('~')
        return parts.first() to parts.getOrElse(1) { "" }
    }

    private fun cleanupCardinality(source: String): String =
        if (source == "none") source else source.trim()
}
