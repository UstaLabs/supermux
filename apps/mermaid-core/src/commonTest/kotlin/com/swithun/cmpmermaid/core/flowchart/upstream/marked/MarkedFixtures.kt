package com.swithun.cmpmermaid.core.flowchart.upstream.marked

/**
 * Generated with Marked 16.4.2.
 *
 * Do not edit manually. Run:
 *   cd tools/official-reference && npm run generate:marked-fixtures
 */
internal object MarkedFixtures {
    val cases: List<MarkedFixtureCase> = listOf(
        MarkedFixtureCase(
            id = "basic",
            source = "This **is** _Markdown_",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "This **is** _Markdown_",
                    text = "This **is** _Markdown_",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "text",
                            raw = "This ",
                            text = "This ",
                        ),
                        MarkedFixtureToken(
                            type = "strong",
                            raw = "**is**",
                            text = "is",
                            tokens = listOf(
                                MarkedFixtureToken(
                                    type = "text",
                                    raw = "is",
                                    text = "is",
                                ),
                            ),
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " ",
                            text = " ",
                        ),
                        MarkedFixtureToken(
                            type = "em",
                            raw = "_Markdown_",
                            text = "Markdown",
                            tokens = listOf(
                                MarkedFixtureToken(
                                    type = "text",
                                    raw = "Markdown",
                                    text = "Markdown",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "intraword_underscore",
            source = "a_b_c foo_bar",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "a_b_c foo_bar",
                    text = "a_b_c foo_bar",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "text",
                            raw = "a_b_c foo_bar",
                            text = "a_b_c foo_bar",
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "nested_emphasis",
            source = "***both*** and __a _b_ c__",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "***both*** and __a _b_ c__",
                    text = "***both*** and __a _b_ c__",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "em",
                            raw = "***both***",
                            text = "**both**",
                            tokens = listOf(
                                MarkedFixtureToken(
                                    type = "strong",
                                    raw = "**both**",
                                    text = "both",
                                    tokens = listOf(
                                        MarkedFixtureToken(
                                            type = "text",
                                            raw = "both",
                                            text = "both",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " and ",
                            text = " and ",
                        ),
                        MarkedFixtureToken(
                            type = "strong",
                            raw = "__a _b_ c__",
                            text = "a _b_ c",
                            tokens = listOf(
                                MarkedFixtureToken(
                                    type = "text",
                                    raw = "a ",
                                    text = "a ",
                                ),
                                MarkedFixtureToken(
                                    type = "em",
                                    raw = "_b_",
                                    text = "b",
                                    tokens = listOf(
                                        MarkedFixtureToken(
                                            type = "text",
                                            raw = "b",
                                            text = "b",
                                        ),
                                    ),
                                ),
                                MarkedFixtureToken(
                                    type = "text",
                                    raw = " c",
                                    text = " c",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "links_and_image",
            source = "before [label](https://example.com/a_(b) \"title\") and ![alt](image.png) after",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "before [label](https://example.com/a_(b) \"title\") and ![alt](image.png) after",
                    text = "before [label](https://example.com/a_(b) \"title\") and ![alt](image.png) after",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "text",
                            raw = "before ",
                            text = "before ",
                        ),
                        MarkedFixtureToken(
                            type = "link",
                            raw = "[label](https://example.com/a_(b) \"title\")",
                            text = "label",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " and ",
                            text = " and ",
                        ),
                        MarkedFixtureToken(
                            type = "image",
                            raw = "![alt](image.png)",
                            text = "alt",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " after",
                            text = " after",
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "code_and_delete",
            source = "`code` and ~~gone~~",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "`code` and ~~gone~~",
                    text = "`code` and ~~gone~~",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "codespan",
                            raw = "`code`",
                            text = "code",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " and ",
                            text = " and ",
                        ),
                        MarkedFixtureToken(
                            type = "del",
                            raw = "~~gone~~",
                            text = "gone",
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "inline_html",
            source = "<strong>bold</strong> <em>x</em><br/>next",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "<strong>bold</strong> <em>x</em><br/>next",
                    text = "<strong>bold</strong> <em>x</em><br/>next",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "html",
                            raw = "<strong>",
                            text = "<strong>",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = "bold",
                            text = "bold",
                        ),
                        MarkedFixtureToken(
                            type = "html",
                            raw = "</strong>",
                            text = "</strong>",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " ",
                            text = " ",
                        ),
                        MarkedFixtureToken(
                            type = "html",
                            raw = "<em>",
                            text = "<em>",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = "x",
                            text = "x",
                        ),
                        MarkedFixtureToken(
                            type = "html",
                            raw = "</em>",
                            text = "</em>",
                        ),
                        MarkedFixtureToken(
                            type = "html",
                            raw = "<br/>",
                            text = "<br/>",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = "next",
                            text = "next",
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "block_tokens",
            source = "# heading\n\n- one\n- two\n\nparagraph",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "heading",
                    raw = "# heading\n\n",
                    text = "heading",
                ),
                MarkedFixtureToken(
                    type = "list",
                    raw = "- one\n- two",
                    text = "",
                ),
                MarkedFixtureToken(
                    type = "space",
                    raw = "\n\n",
                    text = "",
                ),
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "paragraph",
                    text = "paragraph",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "text",
                            raw = "paragraph",
                            text = "paragraph",
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "escape_and_entity",
            source = "\\*literal\\* &amp;",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "\\*literal\\* &amp;",
                    text = "\\*literal\\* &amp;",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "escape",
                            raw = "\\*",
                            text = "*",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = "literal",
                            text = "literal",
                        ),
                        MarkedFixtureToken(
                            type = "escape",
                            raw = "\\*",
                            text = "*",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " &amp;",
                            text = " &amp;",
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "line_breaks",
            source = "first  \nsecond\nthird",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "first  \nsecond\nthird",
                    text = "first  \nsecond\nthird",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "text",
                            raw = "first",
                            text = "first",
                        ),
                        MarkedFixtureToken(
                            type = "br",
                            raw = "  \n",
                            text = "",
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = "second\nthird",
                            text = "second\nthird",
                        ),
                    ),
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "reference_link",
            source = "[foo][id]\n\n[id]: /url \"title\"",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "[foo][id]",
                    text = "[foo][id]",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "link",
                            raw = "[foo][id]",
                            text = "foo",
                        ),
                    ),
                ),
                MarkedFixtureToken(
                    type = "space",
                    raw = "\n\n",
                    text = "",
                ),
                MarkedFixtureToken(
                    type = "def",
                    raw = "[id]: /url \"title\"",
                    text = "",
                ),
            ),
        ),
        MarkedFixtureCase(
            id = "punctuation_emphasis",
            source = "foo***bar***baz ___x___ #*z*",
            tokens = listOf(
                MarkedFixtureToken(
                    type = "paragraph",
                    raw = "foo***bar***baz ___x___ #*z*",
                    text = "foo***bar***baz ___x___ #*z*",
                    tokens = listOf(
                        MarkedFixtureToken(
                            type = "text",
                            raw = "foo",
                            text = "foo",
                        ),
                        MarkedFixtureToken(
                            type = "em",
                            raw = "***bar***",
                            text = "**bar**",
                            tokens = listOf(
                                MarkedFixtureToken(
                                    type = "strong",
                                    raw = "**bar**",
                                    text = "bar",
                                    tokens = listOf(
                                        MarkedFixtureToken(
                                            type = "text",
                                            raw = "bar",
                                            text = "bar",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = "baz ",
                            text = "baz ",
                        ),
                        MarkedFixtureToken(
                            type = "em",
                            raw = "___x___",
                            text = "__x__",
                            tokens = listOf(
                                MarkedFixtureToken(
                                    type = "strong",
                                    raw = "__x__",
                                    text = "x",
                                    tokens = listOf(
                                        MarkedFixtureToken(
                                            type = "text",
                                            raw = "x",
                                            text = "x",
                                        ),
                                    ),
                                ),
                            ),
                        ),
                        MarkedFixtureToken(
                            type = "text",
                            raw = " #",
                            text = " #",
                        ),
                        MarkedFixtureToken(
                            type = "em",
                            raw = "*z*",
                            text = "z",
                            tokens = listOf(
                                MarkedFixtureToken(
                                    type = "text",
                                    raw = "z",
                                    text = "z",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )
}

internal data class MarkedFixtureCase(
    val id: String,
    val source: String,
    val tokens: List<MarkedFixtureToken>,
)

internal data class MarkedFixtureToken(
    val type: String,
    val raw: String,
    val text: String,
    val tokens: List<MarkedFixtureToken> = emptyList(),
)
