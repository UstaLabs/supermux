package com.swithun.cmpmermaid.core.requirement.upstream.mermaid

/**
 * Kotlin translation of Mermaid 12.0.0 requirementDb.ts.
 */
internal class RequirementDb(
    diagramTitle: String? = null,
) {
    private val relations = mutableListOf<RequirementRelation>()
    private var latestRequirement = PendingRequirement()
    private val requirements = linkedMapOf<String, RequirementNode>()
    private var latestElement = PendingElement()
    private val elements = linkedMapOf<String, RequirementElement>()
    private val classes = linkedMapOf<String, RequirementClass>()
    private var direction = "TB"

    var diagramTitle: String? = diagramTitle
        private set
    var accessibilityTitle: String? = null
        private set
    var accessibilityDescription: String? = null
        private set

    fun getDirection(): String = direction

    fun setDirection(value: String) {
        direction = value
    }

    fun addRequirement(
        name: String,
        type: RequirementType,
    ): RequirementNode {
        val node = requirements.getOrPut(name) {
            RequirementNode(
                name = name,
                type = type,
                requirementId = latestRequirement.requirementId,
                text = latestRequirement.text,
                risk = latestRequirement.risk,
                verifyMethod = latestRequirement.verifyMethod,
            )
        }
        latestRequirement = PendingRequirement()
        return node
    }

    fun getRequirements(): Map<String, RequirementNode> = requirements

    fun setNewRequirementId(id: String) {
        latestRequirement = latestRequirement.copy(requirementId = id)
    }

    fun setNewRequirementText(text: String) {
        latestRequirement = latestRequirement.copy(text = text)
    }

    fun setNewRequirementRisk(risk: RequirementRisk) {
        latestRequirement = latestRequirement.copy(risk = risk)
    }

    fun setNewRequirementVerifyMethod(method: RequirementVerifyMethod) {
        latestRequirement = latestRequirement.copy(verifyMethod = method)
    }

    fun addElement(name: String): RequirementElement {
        val element = elements.getOrPut(name) {
            RequirementElement(
                name = name,
                type = latestElement.type,
                docRef = latestElement.docRef,
            )
        }
        latestElement = PendingElement()
        return element
    }

    fun getElements(): Map<String, RequirementElement> = elements

    fun setNewElementType(type: String) {
        latestElement = latestElement.copy(type = type)
    }

    fun setNewElementDocRef(docRef: String) {
        latestElement = latestElement.copy(docRef = docRef)
    }

    fun addRelationship(
        type: RequirementRelationshipType,
        source: String,
        destination: String,
    ) {
        relations += RequirementRelation(type, source, destination)
    }

    fun getRelationships(): List<RequirementRelation> = relations

    fun setCssStyle(
        ids: List<String>,
        styles: List<String>,
    ) {
        ids.forEach { id ->
            val nodeStyles = requirements[id]?.cssStyles ?: elements[id]?.cssStyles ?: return
            styles.forEach { style ->
                if (',' in style) {
                    nodeStyles += style.split(',')
                } else {
                    nodeStyles += style
                }
            }
        }
    }

    fun setClass(
        ids: List<String>,
        classNames: List<String>,
    ) {
        ids.forEach { id ->
            val nodeClasses = requirements[id]?.classes ?: elements[id]?.classes
            val nodeStyles = requirements[id]?.cssStyles ?: elements[id]?.cssStyles
            if (nodeClasses != null && nodeStyles != null) {
                classNames.forEach { className ->
                    nodeClasses += className
                    classes[className]?.styles?.let(nodeStyles::addAll)
                }
            }
        }
    }

    fun defineClass(
        ids: List<String>,
        styles: List<String>,
    ) {
        ids.forEach { id ->
            val styleClass = classes.getOrPut(id) { RequirementClass(id) }
            styles.forEach { style ->
                if ("color" in style) {
                    styleClass.textStyles += style.replace("fill", "bgFill")
                }
                styleClass.styles += style
            }
            requirements.values.forEach { requirement ->
                if (id in requirement.classes) {
                    requirement.cssStyles += styles.flatMap { style -> style.split(',') }
                }
            }
            elements.values.forEach { element ->
                if (id in element.classes) {
                    element.cssStyles += styles.flatMap { style -> style.split(',') }
                }
            }
        }
    }

    fun getClasses(): Map<String, RequirementClass> = classes

    fun setDiagramTitle(title: String) {
        diagramTitle = title
    }

    fun setAccessibilityTitle(title: String) {
        accessibilityTitle = title
    }

    fun setAccessibilityDescription(description: String) {
        accessibilityDescription = description
    }

    private data class PendingRequirement(
        val requirementId: String = "",
        val text: String = "",
        val risk: RequirementRisk? = null,
        val verifyMethod: RequirementVerifyMethod? = null,
    )

    private data class PendingElement(
        val type: String = "",
        val docRef: String = "",
    )
}
