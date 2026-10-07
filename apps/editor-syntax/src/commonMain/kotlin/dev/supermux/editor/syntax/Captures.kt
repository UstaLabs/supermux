package dev.supermux.editor.syntax

/**
 * The fixed token vocabulary the M3 theme colours. Highlight capture names (tree-sitter's standard
 * names, Helix's, nvim-treesitter's, the npm packages') all map onto these classes.
 */
object TokenClasses {
    const val KEYWORD = "tok-keyword"
    const val STRING = "tok-string"
    const val STRING_SPECIAL = "tok-string-special"
    const val NUMBER = "tok-number"
    const val CONSTANT = "tok-constant"
    const val CONSTANT_BUILTIN = "tok-constant-builtin"
    const val COMMENT = "tok-comment"
    const val FUNCTION = "tok-function"
    const val FUNCTION_BUILTIN = "tok-function-builtin"
    const val METHOD = "tok-method"
    const val TYPE = "tok-type"
    const val TYPE_BUILTIN = "tok-type-builtin"
    const val VARIABLE = "tok-variable"
    const val VARIABLE_BUILTIN = "tok-variable-builtin"
    const val PARAMETER = "tok-parameter"
    const val PROPERTY = "tok-property"
    const val OPERATOR = "tok-operator"
    const val PUNCTUATION = "tok-punctuation"
    const val TAG = "tok-tag"
    const val ATTRIBUTE = "tok-attribute"
    const val NAMESPACE = "tok-namespace"
    const val LABEL = "tok-label"
    const val ESCAPE = "tok-escape"
    const val REGEXP = "tok-regexp"
    const val MARKUP_HEADING = "tok-markup-heading"
    const val MARKUP_EMPHASIS = "tok-markup-emphasis"
    const val MARKUP_LINK = "tok-markup-link"
    const val DIFF_PLUS = "tok-diff-plus"
    const val DIFF_MINUS = "tok-diff-minus"

    val ALL: List<String> = listOf(
        KEYWORD, STRING, STRING_SPECIAL, NUMBER, CONSTANT, CONSTANT_BUILTIN, COMMENT, FUNCTION, FUNCTION_BUILTIN,
        METHOD, TYPE, TYPE_BUILTIN, VARIABLE, VARIABLE_BUILTIN, PARAMETER, PROPERTY, OPERATOR, PUNCTUATION, TAG,
        ATTRIBUTE, NAMESPACE, LABEL, ESCAPE, REGEXP, MARKUP_HEADING, MARKUP_EMPHASIS, MARKUP_LINK, DIFF_PLUS, DIFF_MINUS,
    )
}

/**
 * The token class of a highlight capture, by its LONGEST dotted prefix in [CAPTURE_CLASSES]:
 * `function.method.builtin` -> `tok-method`, `string.special.key` -> `tok-string-special`,
 * `punctuation.bracket` -> `tok-punctuation`. Null means "not drawn": `_private` helper captures,
 * explicit no-colour names (`none`, `spell`, `conceal`, ...) and anything unknown.
 */
fun tokenClassFor(capture: String): String? {
    if (capture.isEmpty() || capture.startsWith("_")) return null
    var name = capture
    while (true) {
        CAPTURE_CLASSES[name]?.let { return it.ifEmpty { null } }
        val dot = name.lastIndexOf('.')
        if (dot < 0) return null
        name = name.substring(0, dot)
    }
}

/** Capture name (or dotted prefix) -> token class; "" = deliberately not drawn. */
internal val CAPTURE_CLASSES: Map<String, String> = with(TokenClasses) {
    mapOf(
        // keywords, including the old nvim names and Helix's keyword.* / storage tree
        "keyword" to KEYWORD, "include" to KEYWORD, "conditional" to KEYWORD, "repeat" to KEYWORD,
        "exception" to KEYWORD, "storageclass" to KEYWORD, "storage" to KEYWORD, "preproc" to KEYWORD,
        "define" to KEYWORD, "type.qualifier" to KEYWORD, "keyword.operator" to KEYWORD,
        // strings
        "string" to STRING, "character" to STRING, "char" to STRING, "text.literal" to STRING, "markup.raw" to STRING,
        "string.special" to STRING_SPECIAL, "symbol" to STRING_SPECIAL, "string.symbol" to STRING_SPECIAL,
        "string.regexp" to REGEXP, "string.regex" to REGEXP, "regexp" to REGEXP, "regex" to REGEXP,
        "string.escape" to ESCAPE, "escape" to ESCAPE, "constant.character.escape" to ESCAPE, "string.special.escape" to ESCAPE,
        "string.special.url" to MARKUP_LINK, "string.special.uri" to MARKUP_LINK,
        // numbers and constants
        "number" to NUMBER, "float" to NUMBER, "constant.numeric" to NUMBER, "number.float" to NUMBER,
        "boolean" to CONSTANT_BUILTIN, "constant.builtin" to CONSTANT_BUILTIN, "constant.boolean" to CONSTANT_BUILTIN,
        "constant" to CONSTANT, "constant.character" to STRING, "constant.macro" to CONSTANT,
        // comments
        "comment" to COMMENT,
        // functions
        "function" to FUNCTION, "function.builtin" to FUNCTION_BUILTIN, "function.method" to METHOD,
        "method" to METHOD, "function.macro" to FUNCTION, "macro" to FUNCTION, "function.call" to FUNCTION,
        "function.method.call" to METHOD, "method.call" to METHOD,
        "constructor" to TYPE, "function.special" to FUNCTION,
        // types
        "type" to TYPE, "type.builtin" to TYPE_BUILTIN, "type.definition" to TYPE, "union" to TYPE,
        // variables
        "variable" to VARIABLE, "identifier" to VARIABLE, "variable.builtin" to VARIABLE_BUILTIN, "variable.parameter" to PARAMETER,
        "parameter" to PARAMETER, "variable.member" to PROPERTY, "variable.other.member" to PROPERTY,
        "property" to PROPERTY, "field" to PROPERTY, "variable.field" to PROPERTY, "property.definition" to PROPERTY,
        // operators and punctuation
        "operator" to OPERATOR, "punctuation" to PUNCTUATION, "delimiter" to PUNCTUATION,
        // markup-ish languages
        "tag" to TAG, "tag.attribute" to ATTRIBUTE, "attribute" to ATTRIBUTE, "tag.delimiter" to PUNCTUATION,
        "decorator" to ATTRIBUTE, "annotation" to ATTRIBUTE,
        "namespace" to NAMESPACE, "module" to NAMESPACE,
        "label" to LABEL,
        "markup.heading" to MARKUP_HEADING, "text.title" to MARKUP_HEADING, "title" to MARKUP_HEADING,
        "markup.italic" to MARKUP_EMPHASIS, "markup.bold" to MARKUP_EMPHASIS, "markup.strong" to MARKUP_EMPHASIS,
        "markup.emphasis" to MARKUP_EMPHASIS, "markup.strikethrough" to MARKUP_EMPHASIS,
        "text.emphasis" to MARKUP_EMPHASIS, "text.strong" to MARKUP_EMPHASIS, "text.strike" to MARKUP_EMPHASIS,
        "markup.link" to MARKUP_LINK, "text.uri" to MARKUP_LINK, "text.reference" to MARKUP_LINK,
        "markup.quote" to COMMENT, "markup.list" to PUNCTUATION, "markup.math" to STRING_SPECIAL,
        "text.math" to STRING_SPECIAL, "markup.environment" to KEYWORD,
        "diff.plus" to DIFF_PLUS, "diff.minus" to DIFF_MINUS, "text.diff.add" to DIFF_PLUS, "text.diff.delete" to DIFF_MINUS,
        "diff.delta" to "",
        // deliberately not drawn
        "none" to "", "spell" to "", "nospell" to "", "conceal" to "", "embedded" to "", "text" to "",
        "markup" to "", "local" to "", "injection" to "", "error" to "", "special" to "", "ui" to "", "info" to "",
    )
}
