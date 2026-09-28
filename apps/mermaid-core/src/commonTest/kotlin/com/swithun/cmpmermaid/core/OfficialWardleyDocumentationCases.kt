package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/wardley.md.
 */
internal data class MermaidWardleyDocCase(
    val id: String,
    val source: String,
)

internal val officialWardleyDocumentationCases: List<MermaidWardleyDocCase> = listOf(
    MermaidWardleyDocCase(
        "001_tea_shop",
        """
        wardley-beta
        title Tea Shop Value Chain

        anchor Business [0.95, 0.63]
        component Cup of Tea [0.79, 0.61]
        component Tea [0.63, 0.81]
        component Hot Water [0.52, 0.80]
        component Kettle [0.43, 0.35]
        component Power [0.10, 0.70]

        Business -> Cup of Tea
        Cup of Tea -> Tea
        Cup of Tea -> Hot Water
        Hot Water -> Kettle
        Kettle -> Power

        evolve Kettle 0.62
        evolve Power 0.89

        note "Standardising power allows Kettles to evolve faster" [0.30, 0.49]
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "002_components",
        """
        wardley-beta
        title Components

        component API [0.60, 0.70]
        component Database [0.40, 0.85] label [-50, 10]
        component "Custom Service" [0.55, 0.35]
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "003_hyphenated_names",
        """
        wardley-beta
        title Hyphenated Names

        component real-time processing [0.55, 0.40]
        component end-user [0.90, 0.95]

        end-user -> real-time processing
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "004_anchors",
        """
        wardley-beta
        title Anchors

        anchor Customer [0.90, 0.95]
        anchor Business [0.85, 0.90]

        component Service [0.70, 0.75]

        Customer -> Service
        Business -> Service
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "005_inertia",
        """
        wardley-beta
        title Inertia

        component Legacy System [0.45, 0.40] (inertia)
        component New Platform [0.65, 0.45]

        Legacy System -> New Platform
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "006_sourcing_strategy",
        """
        wardley-beta
        title Sourcing Strategy

        anchor Customer [0.80, 0.95]
        component Custom App [0.45, 0.85] (build)
        component Off-the-shelf Tool [0.85, 0.65] (buy)
        component Managed Service [0.60, 0.40] (outsource)
        component Cloud Platform [0.95, 0.25] (market)

        Customer -> Custom App
        Custom App -> Off-the-shelf Tool
        Custom App -> Managed Service
        Off-the-shelf Tool -> Cloud Platform
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "007_link_types",
        """
        wardley-beta
        title Link Types

        component User [0.90, 0.95]
        component App [0.75, 0.75]
        component API [0.60, 0.60]
        component Cache [0.65, 0.45]
        component Database [0.15, 0.80]

        User -> App
        App +> API
        API -> Database
        API +<> Cache
        Cache +'backup'> Database
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "008_evolution",
        """
        wardley-beta
        title Evolution

        component Database [0.40, 0.50]
        component API [0.55, 0.60]

        Database -> API

        evolve Database 0.75
        evolve API 0.80
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "009_pipeline",
        """
        wardley-beta
        title Pipeline Evolution

        component Database [0.40, 0.60]

        pipeline Database {
          component "File System" [0.25]
          component "SQL DB" [0.50]
          component "NoSQL" [0.70]
          component "Cloud DB" [0.85]
        }
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "010_custom_stages",
        """
        wardley-beta
        title Custom Stages

        evolution Unmodelled -> Divergent -> Convergent -> Modelled

        component Raw Data [0.15, 0.20]
        component Analysis [0.45, 0.50]
        component Reports [0.75, 0.70]
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "011_dual_label_stages",
        """
        wardley-beta
        title Dual Label Stages

        evolution Genesis / Concept -> Custom / Emerging -> Product / Converging -> Commodity / Accepted

        component Novel Idea [0.05, 0.20]
        component Custom Solution [0.35, 0.50]
        component Product [0.65, 0.70]
        component Utility [0.95, 0.90]
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "012_custom_widths",
        """
        wardley-beta
        title Custom Widths

        evolution Genesis@0.2 -> Custom@0.4 -> Product@0.75 -> Commodity@1.0

        component Novel [0.75, 0.15]
        component Bespoke [0.70, 0.35]
        component Product [0.65, 0.65]
        component Utility [0.60, 0.90]
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "013_notes",
        """
        wardley-beta
        title Notes

        component API [0.60, 0.70]
        component Database [0.40, 0.50]

        API -> Database

        note "Critical decision point" [0.65, 0.55]
        note "High risk area" [0.40, 0.35]
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "014_annotations",
        """
        wardley-beta
        title Annotations

        component API [0.60, 0.70]
        component Cache [0.50, 0.55]
        component Database [0.40, 0.40]

        API -> Cache
        Cache -> Database

        annotations [0.10, 0.90]
        annotation 1,[0.60, 0.65] "Critical component"
        annotation 2,[0.50, 0.50] "Performance layer"
        annotation 3,[0.40, 0.35] "Data persistence"
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "015_forces",
        """
        wardley-beta
        title Forces

        component Legacy [0.20, 0.85]
        component Modern [0.55, 0.60]
        component AI [0.70, 0.35]

        Legacy -> Modern
        Modern -> AI

        accelerator "AI Adoption" [0.55, 0.25]
        deaccelerator "Legacy Constraints" [0.15, 0.75]
        """.trimIndent(),
    ),
    MermaidWardleyDocCase(
        "016_complete",
        """
        wardley-beta
        title Software Platform Strategy
        size [1100, 800]

        evolution Genesis@0.25 -> Custom@0.5 -> Product@0.75 -> Commodity@1.0

        anchor Customer [0.90, 0.95]

        component "Mobile App" [0.80, 0.85] (build)
        component "Web App" [0.75, 0.80] label [-60, 10] (build)
        component "API Gateway" [0.70, 0.65] (buy)
        component "Auth Service" [0.60, 0.55] (outsource)
        component "Database" [0.50, 0.45] (buy) (inertia)
        component "Cloud Platform" [0.30, 0.95] (market)

        Customer -> "Mobile App"
        Customer -> "Web App"
        "Mobile App" -> "API Gateway"
        "Web App" -> "API Gateway"
        "API Gateway" -> "Auth Service"
        "API Gateway" -> "Database"
        "Database" -> "Cloud Platform"

        evolve "API Gateway" 0.85
        evolve "Database" 0.75

        accelerator "Cloud Native" [0.20, 0.85]
        deaccelerator "Legacy Data" [0.45, 0.35]

        annotations [0.10, 0.20]
        annotation 1,[0.78, 0.82] "User touchpoints"
        annotation 2,[0.70, 0.60] "Integration layer"
        annotation 3,[0.50, 0.40] "Data persistence"

        note "Build mobile-first experience" [0.85, 0.90]
        note "Migrate to cloud-native database" [0.60, 0.50]
        """.trimIndent(),
    ),
)
