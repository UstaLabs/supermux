package dev.supermux.editor.plugins.lsp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

// Only the LSP messages the client uses are modelled, read from kotlinx.serialization's JSON tree
// (no generated serializers: a server's extra fields and odd shapes are ignored, never an error).

internal val JsonElement?.obj: JsonObject? get() = this as? JsonObject
internal val JsonElement?.arr: JsonArray? get() = this as? JsonArray
internal val JsonElement?.str: String? get() = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
internal val JsonElement?.int: Int? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
internal val JsonElement?.bool: Boolean? get() = (this as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
internal operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)?.takeUnless { it is JsonNull }

internal fun LspPosition.json() = buildJsonObject { put("line", line); put("character", character) }
internal fun LspRange.json() = buildJsonObject { put("start", start.json()); put("end", end.json()) }

internal fun position(e: JsonElement?): LspPosition? {
    val l = e["line"].int ?: return null
    val c = e["character"].int ?: return null
    return LspPosition(l, c)
}

internal fun range(e: JsonElement?): LspRange? {
    val s = position(e["start"]) ?: return null
    val t = position(e["end"]) ?: return null
    return LspRange(s, t)
}

/** A text edit: [range] replaced by [newText]. */
data class LspTextEdit(val range: LspRange, val newText: String)

internal fun textEdit(e: JsonElement?): LspTextEdit? {
    // An InsertReplaceEdit has insert / replace instead of range: take insert (CM6's choice).
    val r = range(e["range"]) ?: range(e["insert"]) ?: return null
    return LspTextEdit(r, e["newText"].str ?: return null)
}

/** A location: a document and a range in it. */
data class LspLocation(val uri: String, val range: LspRange)

/** `Location`, `Location[]` or `LocationLink[]` (definition, references). */
internal fun locations(e: JsonElement?): List<LspLocation> {
    val items = e.arr ?: e.obj?.let { JsonArray(listOf(it)) } ?: return emptyList()
    return items.mapNotNull { i ->
        val uri = i["uri"].str ?: i["targetUri"].str ?: return@mapNotNull null
        val r = range(i["range"]) ?: range(i["targetSelectionRange"]) ?: range(i["targetRange"]) ?: return@mapNotNull null
        LspLocation(uri, r)
    }
}

/** One document's edits in a workspace edit, and the document version they were computed on (null: not said). */
data class VersionedEdits(val version: Int?, val edits: List<LspTextEdit>)

/** A workspace edit: per document, its edits (`changes`, or `documentChanges` text edits with their version). */
internal fun workspaceEdit(e: JsonElement?): Map<String, VersionedEdits> {
    val out = LinkedHashMap<String, VersionedEdits>()
    e["changes"].obj?.forEach { (uri, edits) ->
        val prev = out[uri]
        out[uri] = VersionedEdits(prev?.version, prev?.edits.orEmpty() + edits.arr.orEmpty().mapNotNull(::textEdit))
    }
    e["documentChanges"].arr?.forEach { dc ->
        val uri = dc["textDocument"]["uri"].str ?: return@forEach
        val v = dc["textDocument"]["version"].int
        val prev = out[uri]
        out[uri] = VersionedEdits(prev?.version ?: v, prev?.edits.orEmpty() + dc["edits"].arr.orEmpty().mapNotNull(::textEdit))
    }
    return out
}

/** Markup (`MarkupContent`, a `MarkedString` or an array of them) as text. */
internal fun markup(e: JsonElement?): String? = when {
    e == null -> null
    e is JsonPrimitive -> e.contentOrNull
    e is JsonArray -> e.mapNotNull { markup(it) }.joinToString("\n\n").ifEmpty { null }
    e["kind"] != null -> e["value"].str
    e["language"] != null -> e["value"].str?.let { "```" + e["language"].str.orEmpty() + "\n" + it + "\n```" }
    else -> e["value"].str
}

/** What the server said it can do (the parts the client uses). */
data class ServerFeatures(
    val positionEncoding: PositionEncoding = PositionEncoding.UTF16,
    /** `textDocumentSync` kind: 0 none, 1 full, 2 incremental. */
    val sync: Int = 0,
    val completion: Boolean = false,
    val completionTriggers: Set<Char> = emptySet(),
    val completionResolve: Boolean = false,
    val hover: Boolean = false,
    val signatureHelp: Boolean = false,
    val signatureTriggers: Set<Char> = emptySet(),
    val signatureRetriggers: Set<Char> = emptySet(),
    val definition: Boolean = false,
    val references: Boolean = false,
    val rename: Boolean = false,
    val formatting: Boolean = false,
    val codeAction: Boolean = false,
    val codeActionResolve: Boolean = false,
) {
    companion object {
        private fun provided(e: JsonElement?): Boolean = e != null && e.bool != false

        private fun chars(e: JsonElement?): Set<Char> = e.arr.orEmpty().mapNotNull { it.str?.firstOrNull() }.toSet()

        fun parse(caps: JsonElement?): ServerFeatures {
            val sync = caps["textDocumentSync"].let { s -> s.int ?: s["change"].int ?: if (s != null) 0 else 0 }
            val comp = caps["completionProvider"]
            val sig = caps["signatureHelpProvider"]
            val ca = caps["codeActionProvider"]
            return ServerFeatures(
                positionEncoding = PositionEncoding.of(caps["positionEncoding"].str),
                sync = sync,
                completion = comp != null,
                completionTriggers = chars(comp["triggerCharacters"]),
                completionResolve = comp["resolveProvider"].bool == true,
                hover = provided(caps["hoverProvider"]),
                signatureHelp = sig != null,
                signatureTriggers = chars(sig["triggerCharacters"]),
                signatureRetriggers = chars(sig["retriggerCharacters"]),
                definition = provided(caps["definitionProvider"]),
                references = provided(caps["referencesProvider"]),
                rename = provided(caps["renameProvider"]),
                formatting = provided(caps["documentFormattingProvider"]),
                codeAction = provided(ca),
                codeActionResolve = ca["resolveProvider"].bool == true,
            )
        }
    }
}

/** The client's capabilities (CM6's `clientCapabilities`, plus `positionEncodings` offering utf-16 first). */
internal fun clientCapabilities(): JsonObject = buildJsonObject {
    put("general", buildJsonObject {
        put("positionEncodings", buildJsonArray { add(JsonPrimitive("utf-16")); add(JsonPrimitive("utf-8")); add(JsonPrimitive("utf-32")) })
        put("markdown", buildJsonObject { put("parser", "supermux") })
    })
    put("textDocument", buildJsonObject {
        put("synchronization", buildJsonObject { put("didSave", false); put("willSave", false) })
        put("completion", buildJsonObject {
            put("completionItem", buildJsonObject {
                put("snippetSupport", true)
                put("documentationFormat", buildJsonArray { add(JsonPrimitive("markdown")); add(JsonPrimitive("plaintext")) })
                put("insertReplaceSupport", false)
                put("resolveSupport", buildJsonObject { put("properties", buildJsonArray { add(JsonPrimitive("documentation")); add(JsonPrimitive("detail")) }) })
            })
            put("completionList", buildJsonObject { put("itemDefaults", buildJsonArray { add(JsonPrimitive("editRange")); add(JsonPrimitive("insertTextFormat")) }) })
            put("contextSupport", true)
        })
        put("hover", buildJsonObject { put("contentFormat", buildJsonArray { add(JsonPrimitive("markdown")); add(JsonPrimitive("plaintext")) }) })
        put("formatting", buildJsonObject {})
        put("rename", buildJsonObject {})
        put("signatureHelp", buildJsonObject {
            put("contextSupport", true)
            put("signatureInformation", buildJsonObject {
                put("documentationFormat", buildJsonArray { add(JsonPrimitive("markdown")); add(JsonPrimitive("plaintext")) })
                put("parameterInformation", buildJsonObject { put("labelOffsetSupport", true) })
                put("activeParameterSupport", true)
            })
        })
        put("definition", buildJsonObject { put("linkSupport", true) })
        put("references", buildJsonObject {})
        put("codeAction", buildJsonObject {
            put("codeActionLiteralSupport", buildJsonObject {
                put("codeActionKind", buildJsonObject { put("valueSet", buildJsonArray { for (k in listOf("", "quickfix", "refactor", "source")) add(JsonPrimitive(k)) }) })
            })
            put("resolveSupport", buildJsonObject { put("properties", buildJsonArray { add(JsonPrimitive("edit")) }) })
        })
        put("publishDiagnostics", buildJsonObject { put("versionSupport", true) })
    })
    put("workspace", buildJsonObject { put("applyEdit", true); put("workspaceEdit", buildJsonObject { put("documentChanges", true) }) })
    put("window", buildJsonObject { put("showMessage", buildJsonObject {}) })
}

/** LSP's CompletionItemKind to the autocomplete plugin's types (CM6's `kindToType`). */
internal fun kindToType(kind: Int?): String? = when (kind) {
    1 -> "text"; 2 -> "method"; 3 -> "function"; 4 -> "class"; 5 -> "property"; 6 -> "variable"
    7 -> "class"; 8 -> "interface"; 9 -> "namespace"; 10 -> "property"; 11 -> "keyword"; 12 -> "constant"
    13 -> "enum"; 14 -> "keyword"; 15 -> "snippet"; 16 -> "constant"; 17 -> "text"; 18 -> "text"
    19 -> "namespace"; 20 -> "constant"; 21 -> "constant"; 22 -> "class"; 23 -> "keyword"; 24 -> "keyword"; 25 -> "type"
    else -> null
}

/**
 * Markdown as plain text, for the hover and signature tooltips (the M5 host may give a renderer):
 * fences and backticks go (their content stays), `**` / `__` / `*` emphasis goes, `[text](url)`
 * becomes text, heading and quote markers go, `\` escapes are undone.
 */
object LspMarkdown {
    fun toPlainText(md: String): String {
        val out = StringBuilder()
        var fence = false
        for (raw in md.lines()) {
            if (raw.trimStart().startsWith("```")) { fence = !fence; continue }
            if (fence) { out.append(raw).append('\n'); continue }
            var l = raw.replace(Regex("^\\s{0,3}#{1,6}\\s+"), "").replace(Regex("^\\s{0,3}>\\s?"), "")
            l = l.replace(Regex("\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            l = l.replace(Regex("(\\*\\*|__)(.+?)\\1"), "$2").replace(Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])"), "$1")
            l = l.replace(Regex("`([^`]*)`"), "$1").replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!<>])"), "$1")
            out.append(l).append('\n')
        }
        return out.toString().trim('\n').replace(Regex("\n{3,}"), "\n\n")
    }
}
