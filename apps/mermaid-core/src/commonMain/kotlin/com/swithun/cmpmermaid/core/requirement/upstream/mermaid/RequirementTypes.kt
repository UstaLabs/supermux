package com.swithun.cmpmermaid.core.requirement.upstream.mermaid

internal enum class RequirementType(
    val sourceName: String,
) {
    Requirement("Requirement"),
    Functional("Functional Requirement"),
    Interface("Interface Requirement"),
    Performance("Performance Requirement"),
    Physical("Physical Requirement"),
    DesignConstraint("Design Constraint"),
}

internal enum class RequirementRisk(
    val sourceName: String,
) {
    Low("Low"),
    Medium("Medium"),
    High("High"),
}

internal enum class RequirementVerifyMethod(
    val sourceName: String,
) {
    Analysis("Analysis"),
    Demonstration("Demonstration"),
    Inspection("Inspection"),
    Test("Test"),
}

internal enum class RequirementRelationshipType(
    val sourceName: String,
) {
    Contains("contains"),
    Copies("copies"),
    Derives("derives"),
    Satisfies("satisfies"),
    Verifies("verifies"),
    Refines("refines"),
    Traces("traces"),
}

internal data class RequirementNode(
    val name: String,
    val type: RequirementType,
    val requirementId: String,
    val text: String,
    val risk: RequirementRisk?,
    val verifyMethod: RequirementVerifyMethod?,
    val cssStyles: MutableList<String> = mutableListOf(),
    val classes: MutableList<String> = mutableListOf("default"),
)

internal data class RequirementElement(
    val name: String,
    val type: String,
    val docRef: String,
    val cssStyles: MutableList<String> = mutableListOf(),
    val classes: MutableList<String> = mutableListOf("default"),
)

internal data class RequirementRelation(
    val type: RequirementRelationshipType,
    val source: String,
    val destination: String,
)

internal data class RequirementClass(
    val id: String,
    val styles: MutableList<String> = mutableListOf(),
    val textStyles: MutableList<String> = mutableListOf(),
)
