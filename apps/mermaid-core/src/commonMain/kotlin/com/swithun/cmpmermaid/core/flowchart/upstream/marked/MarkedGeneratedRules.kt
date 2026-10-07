package com.swithun.cmpmermaid.core.flowchart.upstream.marked

/**
 * Generated from the Marked 16.4.2 dependency resolved by Mermaid 12.0.0.
 * marked.esm.js SHA-256: 6540aa2544be84b3abdd9424fa287120e9ed7c7600512dd92c097493121a5951
 *
 * Do not edit manually. Run:
 *   cd tools/official-reference && npm run generate:marked-rules
 */
internal object MarkedGeneratedRules {
    const val VERSION: String = "16.4.2"

    val block: Map<String, MarkedRegexSource> = mapOf(
        "blockquote" to MarkedRegexSource("""^( {0,3}> ?(([^\n]+(?:\n(?! {0,3}((?:-[\t ]*){3,}|(?:_[ \t]*){3,}|(?:\*[ \t]*){3,})(?:\n+|${'$'})| {0,3}#{1,6}(?:\s|${'$'})| {0,3}>| {0,3}(?:`{3,}(?=[^`\n]*\n)|~{3,})[^\n]*\n| {0,3}(?:[*+-]|1[.)]) |<\/?(?:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|meta|nav|noframes|ol|optgroup|option|p|param|search|section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?: +|\n|\/?>)|<(?:script|pre|style|textarea|!--)| +\n)[^\n]+)*)|[^\n]*)(?:\n|${'$'}))+""", ignoreCase = false),
        "code" to MarkedRegexSource("""^((?: {4}| {0,3}\t)[^\n]+(?:\n(?:[ \t]*(?:\n|${'$'}))*)?)+""", ignoreCase = false),
        "def" to MarkedRegexSource("""^ {0,3}\[((?!\s*\])(?:\\[\s\S]|[^\[\]\\])+)\]: *(?:\n[ \t]*)?([^<\s][^\s]*|<.*?>)(?:(?: +(?:\n[ \t]*)?| *\n[ \t]*)((?:"(?:\\"?|[^"\\])*"|'[^'\n]*(?:\n[^'\n]+)*\n?'|\([^()]*\))))? *(?:\n+|${'$'})""", ignoreCase = false),
        "fences" to MarkedRegexSource("""^ {0,3}(`{3,}(?=[^`\n]*(?:\n|${'$'}))|~{3,})([^\n]*)(?:\n|${'$'})(?:|([\s\S]*?)(?:\n|${'$'}))(?: {0,3}\1[~`]* *(?=\n|${'$'})|${'$'})""", ignoreCase = false),
        "heading" to MarkedRegexSource("""^ {0,3}(#{1,6})(?=\s|${'$'})(.*)(?:\n+|${'$'})""", ignoreCase = false),
        "hr" to MarkedRegexSource("""^ {0,3}((?:-[\t ]*){3,}|(?:_[ \t]*){3,}|(?:\*[ \t]*){3,})(?:\n+|${'$'})""", ignoreCase = false),
        "html" to MarkedRegexSource("""^ {0,3}(?:<(script|pre|style|textarea)[\s>][\s\S]*?(?:<\/\1>[^\n]*\n+|${'$'})|<!--(?:-?>|[\s\S]*?(?:-->|${'$'}))[^\n]*(\n+|${'$'})|<\?[\s\S]*?(?:\?>\n*|${'$'})|<![A-Z][\s\S]*?(?:>\n*|${'$'})|<!\[CDATA\[[\s\S]*?(?:\]\]>\n*|${'$'})|<\/?(address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|meta|nav|noframes|ol|optgroup|option|p|param|search|section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?: +|\n|\/?>)[\s\S]*?(?:(?:\n[ 	]*)+\n|${'$'})|<(?!script|pre|style|textarea)([a-z][\w-]*)(?: +[a-zA-Z:_][\w.:-]*(?: *= *"[^"\n]*"| *= *'[^'\n]*'| *= *[^\s"'=<>`]+)?)*? *\/?>(?=[ \t]*(?:\n|${'$'}))[\s\S]*?(?:(?:\n[ 	]*)+\n|${'$'})|<\/(?!script|pre|style|textarea)[a-z][\w-]*\s*>(?=[ \t]*(?:\n|${'$'}))[\s\S]*?(?:(?:\n[ 	]*)+\n|${'$'}))""", ignoreCase = true),
        "lheading" to MarkedRegexSource("""^(?!(?:[*+-]|\d{1,9}[.)]) |(?: {4}| {0,3}\t)| {0,3}(?:`{3,}|~{3,})| {0,3}>| {0,3}#{1,6}| {0,3}<[^\n>]+>\n| {0,3}\|?(?:[:\- ]*\|)+[\:\- ]*\n)((?:.|\n(?!\s*?\n|(?:[*+-]|\d{1,9}[.)]) |(?: {4}| {0,3}\t)| {0,3}(?:`{3,}|~{3,})| {0,3}>| {0,3}#{1,6}| {0,3}<[^\n>]+>\n| {0,3}\|?(?:[:\- ]*\|)+[\:\- ]*\n))+?)\n {0,3}(=+|-+) *(?:\n+|${'$'})""", ignoreCase = false),
        "list" to MarkedRegexSource("""^( {0,3}(?:[*+-]|\d{1,9}[.)]))([ \t][^\n]+?)?(?:\n|${'$'})""", ignoreCase = false),
        "newline" to MarkedRegexSource("""^(?:[ \t]*(?:\n|${'$'}))+""", ignoreCase = false),
        "paragraph" to MarkedRegexSource("""^([^\n]+(?:\n(?! {0,3}((?:-[\t ]*){3,}|(?:_[ \t]*){3,}|(?:\*[ \t]*){3,})(?:\n+|${'$'})| {0,3}#{1,6}(?:\s|${'$'})| {0,3}>| {0,3}(?:`{3,}(?=[^`\n]*\n)|~{3,})[^\n]*\n| {0,3}(?:[*+-]|1[.)]) |<\/?(?:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|meta|nav|noframes|ol|optgroup|option|p|param|search|section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?: +|\n|\/?>)|<(?:script|pre|style|textarea|!--)| *([^\n ].*)\n {0,3}((?:\| *)?:?-+:? *(?:\| *:?-+:? *)*(?:\| *)?)(?:\n((?:(?! *\n| {0,3}((?:-[\t ]*){3,}|(?:_[ \t]*){3,}|(?:\*[ \t]*){3,})(?:\n+|${'$'})| {0,3}#{1,6}(?:\s|${'$'})| {0,3}>|(?: {4}| {0,3}	)[^\n]| {0,3}(?:`{3,}(?=[^`\n]*\n)|~{3,})[^\n]*\n| {0,3}(?:[*+-]|1[.)]) |<\/?(?:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|meta|nav|noframes|ol|optgroup|option|p|param|search|section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?: +|\n|\/?>)|<(?:script|pre|style|textarea|!--)).*(?:\n|${'$'}))*)\n*|${'$'})| +\n)[^\n]+)*)""", ignoreCase = false),
        "table" to MarkedRegexSource("""^ *([^\n ].*)\n {0,3}((?:\| *)?:?-+:? *(?:\| *:?-+:? *)*(?:\| *)?)(?:\n((?:(?! *\n| {0,3}((?:-[\t ]*){3,}|(?:_[ \t]*){3,}|(?:\*[ \t]*){3,})(?:\n+|${'$'})| {0,3}#{1,6}(?:\s|${'$'})| {0,3}>|(?: {4}| {0,3}	)[^\n]| {0,3}(?:`{3,}(?=[^`\n]*\n)|~{3,})[^\n]*\n| {0,3}(?:[*+-]|1[.)]) |<\/?(?:address|article|aside|base|basefont|blockquote|body|caption|center|col|colgroup|dd|details|dialog|dir|div|dl|dt|fieldset|figcaption|figure|footer|form|frame|frameset|h[1-6]|head|header|hr|html|iframe|legend|li|link|main|menu|menuitem|meta|nav|noframes|ol|optgroup|option|p|param|search|section|summary|table|tbody|td|tfoot|th|thead|title|tr|track|ul)(?: +|\n|\/?>)|<(?:script|pre|style|textarea|!--)).*(?:\n|${'$'}))*)\n*|${'$'})""", ignoreCase = false),
        "text" to MarkedRegexSource("""^[^\n]+""", ignoreCase = false),
    )

    val inline: Map<String, MarkedRegexSource> = mapOf(
        "_backpedal" to MarkedRegexSource("""(?:[^?!.,:;*_'"~()&]+|\([^)]*\)|&(?![a-zA-Z0-9]+;${'$'})|[?!.,:;*_'"~)]+(?!${'$'}))+""", ignoreCase = false),
        "anyPunctuation" to MarkedRegexSource("""\\([\p{P}\p{S}])""", ignoreCase = false),
        "autolink" to MarkedRegexSource("""^<([a-zA-Z][a-zA-Z0-9+.-]{1,31}:[^\s\x00-\x1f<>]*|[a-zA-Z0-9.!#${'$'}%&'*+/=?_`{|}~-]+(@)[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)+(?![-_]))>""", ignoreCase = false),
        "blockSkip" to MarkedRegexSource("""\[(?:[^\[\]`]|(?<a>`+)[^`]+\k<a>(?!`))*?\]\((?:\\[\s\S]|[^\\\(\)]|\((?:\\[\s\S]|[^\\\(\)])*\))*\)|(?<!`)()(?<b>`+)[^`]+\k<b>(?!`)|<(?! )[^<>]*?>""", ignoreCase = false),
        "br" to MarkedRegexSource("""^( {2,}|\\)\n(?!\s*${'$'})""", ignoreCase = false),
        "code" to MarkedRegexSource("""^(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)""", ignoreCase = false),
        "del" to MarkedRegexSource("""^(~~?)(?=[^\s~])((?:\\[\s\S]|[^\\])*?(?:\\[\s\S]|[^\s~\\]))\1(?=[^~]|${'$'})""", ignoreCase = false),
        "emStrongLDelim" to MarkedRegexSource("""^(?:\*+(?:((?!\*)(?!~)[\p{P}\p{S}])|[^\s*]))|^_+(?:((?!_)(?!~)[\p{P}\p{S}])|([^\s_]))""", ignoreCase = false),
        "emStrongRDelimAst" to MarkedRegexSource("""^[^_*]*?__[^_*]*?\*[^_*]*?(?=__)|[^*]+(?=[^*])|(?!\*)(?!~)[\p{P}\p{S}](\*+)(?=[\s]|${'$'})|(?:[^\s\p{P}\p{S}]|~)(\*+)(?!\*)(?=(?!~)[\s\p{P}\p{S}]|${'$'})|(?!\*)(?!~)[\s\p{P}\p{S}](\*+)(?=(?:[^\s\p{P}\p{S}]|~))|[\s](\*+)(?!\*)(?=(?!~)[\p{P}\p{S}])|(?!\*)(?!~)[\p{P}\p{S}](\*+)(?!\*)(?=(?!~)[\p{P}\p{S}])|(?:[^\s\p{P}\p{S}]|~)(\*+)(?=(?:[^\s\p{P}\p{S}]|~))""", ignoreCase = false),
        "emStrongRDelimUnd" to MarkedRegexSource("""^[^_*]*?\*\*[^_*]*?_[^_*]*?(?=\*\*)|[^_]+(?=[^_])|(?!_)[\p{P}\p{S}](_+)(?=[\s]|${'$'})|[^\s\p{P}\p{S}](_+)(?!_)(?=[\s\p{P}\p{S}]|${'$'})|(?!_)[\s\p{P}\p{S}](_+)(?=[^\s\p{P}\p{S}])|[\s](_+)(?!_)(?=[\p{P}\p{S}])|(?!_)[\p{P}\p{S}](_+)(?!_)(?=[\p{P}\p{S}])""", ignoreCase = false),
        "escape" to MarkedRegexSource("""^\\([!"#${'$'}%&'()*+,\-./:;<=>?@\[\]\\^_`{|}~])""", ignoreCase = false),
        "link" to MarkedRegexSource("""^!?\[((?:\[(?:\\[\s\S]|[^\[\]\\])*\]|\\[\s\S]|`+[^`]*?`+(?!`)|[^\[\]\\`])*?)\]\(\s*(<(?:\\.|[^\n<>\\])+>|[^ \t\n\x00-\x1f]*)(?:(?:[ \t]*(?:\n[ \t]*)?)("(?:\\"?|[^"\\])*"|'(?:\\'?|[^'\\])*'|\((?:\\\)?|[^)\\])*\)))?\s*\)""", ignoreCase = false),
        "nolink" to MarkedRegexSource("""^!?\[((?!\s*\])(?:\\[\s\S]|[^\[\]\\])+)\](?:\[\])?""", ignoreCase = false),
        "punctuation" to MarkedRegexSource("""^((?![*_])[\s\p{P}\p{S}])""", ignoreCase = false),
        "reflink" to MarkedRegexSource("""^!?\[((?:\[(?:\\[\s\S]|[^\[\]\\])*\]|\\[\s\S]|`+[^`]*?`+(?!`)|[^\[\]\\`])*?)\]\[((?!\s*\])(?:\\[\s\S]|[^\[\]\\])+)\]""", ignoreCase = false),
        "reflinkSearch" to MarkedRegexSource("""!?\[((?:\[(?:\\[\s\S]|[^\[\]\\])*\]|\\[\s\S]|`+[^`]*?`+(?!`)|[^\[\]\\`])*?)\]\[((?!\s*\])(?:\\[\s\S]|[^\[\]\\])+)\]|!?\[((?!\s*\])(?:\\[\s\S]|[^\[\]\\])+)\](?:\[\])?(?!\()""", ignoreCase = false),
        "tag" to MarkedRegexSource("""^<!--(?:-?>|[\s\S]*?-->)|^<\/[a-zA-Z][\w:-]*\s*>|^<[a-zA-Z][\w-]*(?:\s+[a-zA-Z:_][\w.:-]*(?:\s*=\s*"[^"]*"|\s*=\s*'[^']*'|\s*=\s*[^\s"'=<>`]+)?)*?\s*\/?>|^<\?[\s\S]*?\?>|^<![a-zA-Z]+\s[\s\S]*?>|^<!\[CDATA\[[\s\S]*?\]\]>""", ignoreCase = false),
        "text" to MarkedRegexSource("""^([`~]+|[^`~])(?:(?= {2,}\n)|(?=[a-zA-Z0-9.!#${'$'}%&'*+\/=?_`{\|}~-]+@)|[\s\S]*?(?:(?=[\\<!\[`*~_]|\b_|[hH][tT][tT][pP][sS]?|[fF][tT][pP]:\/\/|www\.|${'$'})|[^ ](?= {2,}\n)|[^a-zA-Z0-9.!#${'$'}%&'*+\/=?_`{\|}~-](?=[a-zA-Z0-9.!#${'$'}%&'*+\/=?_`{\|}~-]+@)))""", ignoreCase = false),
        "url" to MarkedRegexSource("""^((?:[hH][tT][tT][pP][sS]?|[fF][tT][pP]):\/\/|www\.)(?:[a-zA-Z0-9\-]+\.?)+[^\s<]*|^[A-Za-z0-9._+-]+(@)[a-zA-Z0-9-_]+(?:\.[a-zA-Z0-9-_]*[a-zA-Z0-9])+(?![-_])""", ignoreCase = false),
    )
}
