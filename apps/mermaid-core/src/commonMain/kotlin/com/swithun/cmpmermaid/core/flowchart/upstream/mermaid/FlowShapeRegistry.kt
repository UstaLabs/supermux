package com.swithun.cmpmermaid.core.flowchart.upstream.mermaid

/**
 * Kotlin data port of Mermaid 12.0.0
 * rendering-util/rendering-elements/shapes.ts#generateShapeMap.
 *
 * Values identify the upstream shape handler. FlowDb deliberately retains the
 * source shape ID, matching Mermaid; Native rendering can resolve aliases
 * through [handlerFor].
 */
internal object FlowShapeRegistry {
    private val handlersByShapeId: Map<String, String> = buildMap {
        fun register(handler: String, vararg shapeIds: String) {
            shapeIds.forEach { shapeId -> put(shapeId, handler) }
        }

        // Mermaid's undocumentedShapes object.
        register("state", "state")
        register("choice", "choice")
        register("note", "note")
        register("composite", "composite")
        register("rectWithTitle", "rectWithTitle")
        register("labelRect", "labelRect")
        register("block_arrow", "block_arrow")
        register("collapsedGroup", "collapsedGroup")
        register("iconSquare", "iconSquare")
        register("iconCircle", "iconCircle")
        register("icon", "icon")
        register("iconRounded", "iconRounded")
        register("imageSquare", "imageSquare")
        register("anchor", "anchor")
        register("kanbanItem", "kanbanItem")
        register("mindmapCircle", "mindmapCircle")
        register("defaultMindmapNode", "defaultMindmapNode")
        register("classBox", "classBox")
        register("erBox", "erBox")
        register("requirementBox", "requirementBox")
        register("usecaseActor", "usecaseActor")
        register("usecaseActorHollow", "usecaseActorHollow")
        register("usecaseActorAwesome", "usecaseActorAwesome")
        register("usecaseActorIcon", "usecaseActorIcon")
        register("usecaseBusiness", "usecaseBusiness")
        register("usecaseEllipse", "usecaseEllipse")
        register("usecaseJsonTable", "usecaseJsonTable")

        // Mermaid's shapesDefs short names, aliases, and internal aliases.
        register("squareRect", "rect", "proc", "process", "rectangle", "squareRect")
        register("roundedRect", "rounded", "event", "roundedRect")
        register("stadium", "stadium", "terminal", "pill")
        register(
            "subroutine",
            "fr-rect",
            "subprocess",
            "subproc",
            "framed-rectangle",
            "subroutine",
        )
        register("cylinder", "cyl", "db", "database", "cylinder")
        register("datastore", "datastore", "data-store")
        register("folder", "folder", "directory")
        register("bucket", "bucket")
        register("consoleWindow", "console")
        register("browser", "browser")
        register("person", "person")
        register("circle", "circle", "circ")
        register("bang", "bang")
        register("cloud", "cloud")
        register("question", "diam", "decision", "diamond", "question")
        register("hexagon", "hex", "hexagon", "prepare")
        register("lean_right", "lean-r", "lean-right", "in-out", "lean_right")
        register("lean_left", "lean-l", "lean-left", "out-in", "lean_left")
        register(
            "trapezoid",
            "trap-b",
            "priority",
            "trapezoid-bottom",
            "trapezoid",
        )
        register(
            "inv_trapezoid",
            "trap-t",
            "manual",
            "trapezoid-top",
            "inv-trapezoid",
            "inv_trapezoid",
        )
        register("doublecircle", "dbl-circ", "double-circle", "doublecircle")
        register("text", "text")
        register("card", "notch-rect", "card", "notched-rectangle")
        register(
            "shadedProcess",
            "lin-rect",
            "lined-rectangle",
            "lined-process",
            "lin-proc",
            "shaded-process",
        )
        register("stateStart", "sm-circ", "start", "small-circle", "stateStart")
        register("stateEnd", "fr-circ", "stop", "framed-circle", "stateEnd")
        register("forkJoin", "fork", "join", "forkJoin")
        register("hourglass", "hourglass", "collate")
        register("curlyBraceLeft", "brace", "comment", "brace-l")
        register("curlyBraceRight", "brace-r")
        register("curlyBraces", "braces")
        register("lightningBolt", "bolt", "com-link", "lightning-bolt")
        register("waveEdgedRectangle", "doc", "document")
        register("halfRoundedRectangle", "delay", "half-rounded-rectangle")
        register("tiltedCylinder", "h-cyl", "das", "horizontal-cylinder")
        register("linedCylinder", "lin-cyl", "disk", "lined-cylinder")
        register("curvedTrapezoid", "curv-trap", "curved-trapezoid", "display")
        register(
            "dividedRectangle",
            "div-rect",
            "div-proc",
            "divided-rectangle",
            "divided-process",
        )
        register("triangle", "tri", "extract", "triangle")
        register("windowPane", "win-pane", "internal-storage", "window-pane")
        register("filledCircle", "f-circ", "junction", "filled-circle")
        register(
            "trapezoidalPentagon",
            "notch-pent",
            "loop-limit",
            "notched-pentagon",
        )
        register("flippedTriangle", "flip-tri", "manual-file", "flipped-triangle")
        register("slopedRect", "sl-rect", "manual-input", "sloped-rectangle")
        register(
            "multiWaveEdgedRectangle",
            "docs",
            "documents",
            "st-doc",
            "stacked-document",
        )
        register("multiRect", "st-rect", "procs", "processes", "stacked-rectangle")
        register("bowTieRect", "bow-rect", "stored-data", "bow-tie-rectangle")
        register("crossedCircle", "cross-circ", "summary", "crossed-circle")
        register("taggedWaveEdgedRectangle", "tag-doc", "tagged-document")
        register(
            "taggedRect",
            "tag-rect",
            "tagged-rectangle",
            "tag-proc",
            "tagged-process",
        )
        register("waveRectangle", "flag", "paper-tape")
        register("rect_left_inv_arrow", "odd", "rect_left_inv_arrow")
        register("linedWaveEdgedRect", "lin-doc", "lined-document")
    }

    fun isValid(shapeId: String): Boolean = shapeId in handlersByShapeId

    fun handlerFor(shapeId: String): String? = handlersByShapeId[shapeId]
}
