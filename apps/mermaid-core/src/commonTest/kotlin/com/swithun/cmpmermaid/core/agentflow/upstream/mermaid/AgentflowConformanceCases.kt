package com.swithun.cmpmermaid.core.agentflow.upstream.mermaid

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/diagrams/agentflow/conformance/fixtures.
 * Combined fixture SHA-256: 0ad09f839cf919f91a6cc10897ad92df230e6a37e5507026ebbc0964a16b7a75
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:agentflow-conformance-fixtures
 */
internal data class AgentflowExpectedDiagnostic(
    val id: String,
    val nodeId: String? = null,
    val edgeId: String? = null,
    val line: Int? = null,
)

internal data class AgentflowExpectedVertex(
    val id: String,
    val vertexKind: String? = null,
    val metadata: Map<String, Any?>? = null,
)

internal data class AgentflowExpectedEdge(
    val start: String? = null,
    val end: String? = null,
    val id: String? = null,
    val edgeSemantic: String? = null,
)

internal data class AgentflowConformanceCase(
    val name: String,
    val source: String,
    val outcome: String,
    val diagnostics: List<AgentflowExpectedDiagnostic>? = null,
    val allowExtraDiagnostics: Boolean = false,
    val parseErrorContains: String? = null,
    val vertices: List<AgentflowExpectedVertex> = emptyList(),
    val edges: List<AgentflowExpectedEdge> = emptyList(),
)

internal val agentflowConformanceCases: List<AgentflowConformanceCase> = listOf(
    AgentflowConformanceCase(
        name = "complete-example",
        source = """
%% §17 Complete Example — Coffee Team coffee-website builder (v0.8.1).
agentflow-beta TB
  connector llm_api["LLM API"]
  llm_api@{ protocol: "http", endpoint: "https://api.example.com/chat" }

  flow coffee_team["Coffee Team"]
    city["city"]
    city@{ shape: input, type: "String", value: "Stockholm" }

    flow researcher["Researcher"]
      research_loc["research_location"]
      research_loc@{ shape: tool, params: "city :: String", returns: "String", cache: "24h" }
      write_copy["write_copy"]
      write_copy@{ shape: tool, connectorRef: "llm_api.chat", params: "brief :: String", returns: "CoffeeCopy", retry: 2 }
      city --> research_loc --> write_copy
    end
    researcher@{ model: "claude-sonnet-4-20250514", instruction: "Research the city and draft English coffee copy citing sources." }

    flow translator["Translator"]
      translate_sv["translate_to_swedish"]
      translate_sv@{ shape: tool, connectorRef: "llm_api.chat", params: "english :: CoffeeCopy", returns: "BilingualPage" }
    end
    translator@{ model: "claude-sonnet-4-20250514" }

    flow designer["Designer"]
      gen_html["generate_html"]
      gen_html@{ shape: tool, connectorRef: "llm_api.chat", params: "page :: BilingualPage", returns: "String" }
      style_guide["Nordic Brand Guide"]
      style_guide@{ shape: refdoc }
      gen_html -.- style_guide
    end
    designer@{ model: "claude-sonnet-4-20250514", instruction: "Render the bilingual page as semantic HTML following the brand guide." }

    researcher --> translator --> designer
  end
        """.trimIndent(),
        outcome = "valid",
        diagnostics = emptyList(),
        allowExtraDiagnostics = false,
        parseErrorContains = null,
        vertices = listOf(
            AgentflowExpectedVertex(
                id = "city",
                vertexKind = "input",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "research_loc",
                vertexKind = "tool",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "write_copy",
                vertexKind = "tool",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "translate_sv",
                vertexKind = "tool",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "gen_html",
                vertexKind = "tool",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "style_guide",
                vertexKind = "refdoc",
                metadata = null,
            ),
        ),
        edges = listOf(
            AgentflowExpectedEdge(
                start = "gen_html",
                end = "style_guide",
                id = null,
                edgeSemantic = "reference",
            ),
        ),
    ),
    AgentflowConformanceCase(
        name = "error-shape-removed",
        source = """
%% `cylinder` is one of the shapes v0.8.1 removed (§4.3.3). It is a hard error:
%% the node still renders, coerced to the default shape, but the diagram is
%% classified `error` rather than `warning`.
agentflow-beta TB
  input["q"]
  input@{ shape: input }
  store["Store"]
  store@{ shape: "cylinder" }
  input --> store
        """.trimIndent(),
        outcome = "error",
        diagnostics = listOf(
            AgentflowExpectedDiagnostic(
                id = "SHAPE_REMOVED",
                nodeId = "store",
                edgeId = null,
                line = null,
            ),
        ),
        allowExtraDiagnostics = false,
        parseErrorContains = null,
        vertices = emptyList(),
        edges = emptyList(),
    ),
    AgentflowConformanceCase(
        name = "parse-error-unterminated-container",
        source = """
%% A `flow` block with no `end`. The JISON parser raises, so no semantic model
%% is produced and the outcome is `parse-error` rather than any diagnostic.
agentflow-beta TB
  flow orphan["Orphan"]
    a["Alpha"]
    b["Beta"]
    a --> b
        """.trimIndent(),
        outcome = "parse-error",
        diagnostics = null,
        allowExtraDiagnostics = false,
        parseErrorContains = "Parse error",
        vertices = emptyList(),
        edges = emptyList(),
    ),
    AgentflowConformanceCase(
        name = "pattern-connector-dotted-form",
        source = """
%% Connector reference validator — dotted-operation form uses
%% prefix-before-first-dot for resolution. With a declared `github`
%% connector, `github.create_issue` resolves cleanly.
agentflow-beta TB
  connector github["GitHub"]
  payload["payload"]
  payload@{ shape: input }
  create_issue["Create Issue"]
  create_issue@{ shape: tool, connectorRef: "github.create_issue" }
  payload --> create_issue
        """.trimIndent(),
        outcome = "valid",
        diagnostics = null,
        allowExtraDiagnostics = false,
        parseErrorContains = null,
        vertices = listOf(
            AgentflowExpectedVertex(
                id = "payload",
                vertexKind = "input",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "create_issue",
                vertexKind = "tool",
                metadata = mapOf("connectorRef" to "github.create_issue"),
            ),
        ),
        edges = listOf(
            AgentflowExpectedEdge(
                start = "payload",
                end = "create_issue",
                id = null,
                edgeSemantic = "sequence",
            ),
        ),
    ),
    AgentflowConformanceCase(
        name = "pattern-parallel",
        source = """
%% §16 Parallel Execution Pattern — `&` fan-out in edges only.
agentflow-beta TB
  query["query"]
  query@{ shape: input }
  orchestrator["Orchestrate"]
  search["Search"]
  analyze["Analyze"]
  validate["Validate"]
  query --> orchestrator --> search & analyze & validate
        """.trimIndent(),
        outcome = "valid",
        diagnostics = null,
        allowExtraDiagnostics = false,
        parseErrorContains = null,
        vertices = listOf(
            AgentflowExpectedVertex(
                id = "query",
                vertexKind = "input",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "search",
                vertexKind = null,
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "analyze",
                vertexKind = null,
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "validate",
                vertexKind = null,
                metadata = null,
            ),
        ),
        edges = listOf(
            AgentflowExpectedEdge(
                start = "query",
                end = "orchestrator",
                id = null,
                edgeSemantic = "sequence",
            ),
            AgentflowExpectedEdge(
                start = "orchestrator",
                end = "search",
                id = null,
                edgeSemantic = "sequence",
            ),
            AgentflowExpectedEdge(
                start = "orchestrator",
                end = "analyze",
                id = null,
                edgeSemantic = "sequence",
            ),
            AgentflowExpectedEdge(
                start = "orchestrator",
                end = "validate",
                id = null,
                edgeSemantic = "sequence",
            ),
        ),
    ),
    AgentflowConformanceCase(
        name = "pattern-reference-doc",
        source = """
%% §16 Reference Document Pattern — `-.-` reference edge to a refdoc node.
agentflow-beta TB
  query["query"]
  query@{ shape: input }
  gen_html["generate_html"]
  style_guide["Brand Guide"]
  style_guide@{ shape: refdoc }
  query --> gen_html
  gen_html -.- style_guide
        """.trimIndent(),
        outcome = "valid",
        diagnostics = null,
        allowExtraDiagnostics = false,
        parseErrorContains = null,
        vertices = listOf(
            AgentflowExpectedVertex(
                id = "query",
                vertexKind = "input",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "style_guide",
                vertexKind = "refdoc",
                metadata = null,
            ),
        ),
        edges = listOf(
            AgentflowExpectedEdge(
                start = "query",
                end = "gen_html",
                id = null,
                edgeSemantic = "sequence",
            ),
            AgentflowExpectedEdge(
                start = "gen_html",
                end = "style_guide",
                id = null,
                edgeSemantic = "reference",
            ),
        ),
    ),
    AgentflowConformanceCase(
        name = "smoke-valid",
        source = """
agentflow-beta TB
  input["x"]
  input@{ shape: input }
  a["Alpha"]
  b["Beta"]
  input --> a --> b
        """.trimIndent(),
        outcome = "valid",
        diagnostics = null,
        allowExtraDiagnostics = false,
        parseErrorContains = null,
        vertices = listOf(
            AgentflowExpectedVertex(
                id = "input",
                vertexKind = "input",
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "a",
                vertexKind = null,
                metadata = null,
            ),
            AgentflowExpectedVertex(
                id = "b",
                vertexKind = null,
                metadata = null,
            ),
        ),
        edges = listOf(
            AgentflowExpectedEdge(
                start = "input",
                end = "a",
                id = null,
                edgeSemantic = "sequence",
            ),
            AgentflowExpectedEdge(
                start = "a",
                end = "b",
                id = null,
                edgeSemantic = "sequence",
            ),
        ),
    ),
    AgentflowConformanceCase(
        name = "warning-shape-unsupported",
        source = """
%% A Mermaid-valid shape that's not in agentflow's v0.8.1 catalogue and
%% not in the removed-shape blacklist emits a SHAPE_UNSUPPORTED warning
%% from transformData. (Removed shapes like `cylinder`, `doc`, `procs`
%% emit SHAPE_REMOVED at error tier — see those fixtures.)
agentflow-beta TB
  input["q"]
  input@{ shape: input }
  x["X"]
  y["Y"]
  x@{ shape: "triangle" }
  input --> x --> y
        """.trimIndent(),
        outcome = "warning",
        diagnostics = listOf(
            AgentflowExpectedDiagnostic(
                id = "SHAPE_UNSUPPORTED",
                nodeId = "x",
                edgeId = null,
                line = null,
            ),
        ),
        allowExtraDiagnostics = false,
        parseErrorContains = null,
        vertices = emptyList(),
        edges = emptyList(),
    ),
)
