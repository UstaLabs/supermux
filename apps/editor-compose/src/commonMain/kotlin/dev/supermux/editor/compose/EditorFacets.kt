package dev.supermux.editor.compose

import dev.supermux.editor.core.Facet

/** Columns per tab stop, for drawing tabs and for [insertTab] (default 4). */
val tabSizeFacet: Facet<Int, Int> = Facet.first("tabSize", 4)

/** What one level of indentation inserts: spaces (the default, 4) or "\t". */
val indentUnitFacet: Facet<String, String> = Facet.first("indentUnit", "    ")
