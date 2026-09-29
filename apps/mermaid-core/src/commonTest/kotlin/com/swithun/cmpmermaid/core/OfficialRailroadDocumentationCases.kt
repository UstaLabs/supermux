package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/railroad.md.
 * Upstream document SHA-256: d496f3f2d473898424f0cb86c617aacc463dfb9c4ef7a06c420890f9019d8349
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:railroad-doc-fixtures
 */
internal data class MermaidRailroadDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialRailroadDocumentationCases: List<MermaidRailroadDocCase> = listOf(
    MermaidRailroadDocCase(
        id = "001_railroad_diagrams_v11_16_0",
        title = "Railroad Diagrams (v11.16.0+)",
        source = """
railroad-ebnf-beta
title "Digit Definition"

digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "002_terminals_and_non_terminals",
        title = "Terminals and Non-terminals",
        source = """
railroad-ebnf-beta
letter = "a" | "b" | "c" ;
identifier = letter ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "003_sequence_concatenation",
        title = "Sequence (Concatenation)",
        source = """
railroad-ebnf-beta
greeting = "Hello" " " "World" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "004_choice_alternation",
        title = "Choice (Alternation)",
        source = """
railroad-ebnf-beta
sign = "+" | "-" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "005_optional_elements",
        title = "Optional Elements",
        source = """
railroad-ebnf-beta
title "Optional Sign"

sign = "+" | "-" ;
number = sign? digit+ ;
digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "006_repetition",
        title = "Repetition",
        source = """
railroad-ebnf-beta
title "Identifier with Repetition"

identifier = letter ( letter | digit | "_" )* ;
letter = "a" | "b" | "c" | "d" | "e" ;
digit = "0" | "1" | "2" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "007_grouping",
        title = "Grouping",
        source = """
railroad-ebnf-beta
expression = term ( ( "+" | "-" ) term )* ;
term = "number" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "008_ebnf_examples",
        title = "EBNF Examples",
        source = """
railroad-ebnf-beta
title "Arithmetic Expression Grammar"

expression = term ( ( "+" | "-" ) term )* ;
term = factor ( ( "*" | "/" ) factor )* ;
factor = number | "(" expression ")" ;
number = digit+ ;
digit = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" | "8" | "9" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "009_ebnf_examples",
        title = "EBNF Examples",
        source = """
railroad-ebnf-beta
title "JSON Grammar"

json = element ;
element = object | array | string | number | "true" | "false" | "null" ;
object = "{" [ member ( "," member )* ] "}" ;
array = "[" [ element ( "," element )* ] "]" ;
member = string ":" element ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "010_abnf_railroad_abnf_beta",
        title = "ABNF (`railroad-abnf-beta`)",
        source = """
railroad-abnf-beta
title "Email Address"

address = local-part "@" domain ;
local-part = 1*( ALPHA / DIGIT / "." / "-" ) ;
domain = label *( "." label ) ;
label = 1*( ALPHA / DIGIT / "-" ) ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "011_abnf_railroad_abnf_beta",
        title = "ABNF (`railroad-abnf-beta`)",
        source = """
railroad-abnf-beta
title "Phone Number"

phone = [ "+" country-code ] subscriber ;
country-code = 1*DIGIT ;
subscriber = 1*( DIGIT / "-" / " " ) ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "012_peg_railroad_peg_beta",
        title = "PEG (`railroad-peg-beta`)",
        source = """
railroad-peg-beta
title "Calculator Grammar"

Expression <- Term (("+" / "-") Term)* ;
Term <- Factor (("*" / "/") Factor)* ;
Factor <- Number / "(" Expression ")" ;
Number <- Digit+ ;
Digit <- "0" / "1" / "2" / "3" / "4" / "5" / "6" / "7" / "8" / "9" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "013_peg_railroad_peg_beta",
        title = "PEG (`railroad-peg-beta`)",
        source = """
railroad-peg-beta
title "Identifiers (keywords excluded)"

Identifier <- !Keyword Letter Letter* ;
Keyword <- "if" / "else" / "while" ;
Letter <- "a" / "b" / "c" / "_" ;
        """.trimIndent(),
    ),
    MermaidRailroadDocCase(
        id = "014_ir_primitives_railroad_beta",
        title = "IR Primitives (`railroad-beta`)",
        source = """
railroad-beta
title Expression Grammar

expression = sequence(
    nonterminal("term"),
    zeroOrMore(sequence(
        choice(terminal("+"), terminal("-")),
        nonterminal("term")
    ))
) ;
term = sequence(
    nonterminal("factor"),
    zeroOrMore(sequence(
        choice(terminal("*"), terminal("/")),
        nonterminal("factor")
    ))
) ;
factor = choice(
    nonterminal("number"),
    sequence(terminal("("), nonterminal("expression"), terminal(")"))
) ;
number = oneOrMore(nonterminal("digit")) ;
digit = choice(terminal("0"), terminal("1"), terminal("2"), terminal("3"), terminal("4"), terminal("5"), terminal("6"), terminal("7"), terminal("8"), terminal("9")) ;
        """.trimIndent(),
    ),
)
