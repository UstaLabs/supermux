package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/agentflow.md.
 * Upstream document SHA-256: 02a247ee27d90edfb16c965141e2bc05777710320aeccee4f85441af1fa8b6d4
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:agentflow-doc-fixtures
 */
internal data class MermaidAgentflowDocCase(
    val id: String,
    val title: String,
    val source: String,
    val expectedUnsupportedFeature: String?,
)

internal val officialAgentflowDocumentationCases: List<MermaidAgentflowDocCase> = listOf(
    MermaidAgentflowDocCase(
        id = "001_basic_example",
        title = "Basic example",
        source = """
agentflow-beta TB
  flow reviewer["Review Agent"]
    changes["Gather changes"]@{ shape: input }
    analyse["Analyse diff"]@{ shape: task }
    lint["run_linter"]@{ shape: tool }
    ok["Clean?"]@{ shape: decision }

    changes --> analyse --> lint --> ok
  end
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "002_with_the_defaults",
        title = "With the defaults",
        source = """
agentflow-beta TB
  brief["Release brief"]@{ shape: input }
  flow writer["Drafting Agent"]
    draft["Draft the notes"]@{ shape: task }
    lookup["changelog_search"]@{ shape: tool }
    guide["Tone of voice"]@{ shape: refdoc }
    draft --> lookup
    draft -.- guide
  end
  flow reviewer["Review Agent"]
    check["Check the claims"]@{ shape: task }
    ok["Accurate?"]@{ shape: decision }
    check --> ok
  end
  publish["Publish"]@{ shape: action }
  brief --> writer
  writer --> reviewer
  ok --> publish
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "003_the_previous_appearance",
        title = "The previous appearance",
        source = """
---
config:
  theme: default
  look: classic
  layout: dagre
---
agentflow-beta TB
  brief["Release brief"]@{ shape: input }
  flow writer["Drafting Agent"]
    draft["Draft the notes"]@{ shape: task }
    lookup["changelog_search"]@{ shape: tool }
    guide["Tone of voice"]@{ shape: refdoc }
    draft --> lookup
    draft -.- guide
  end
  flow reviewer["Review Agent"]
    check["Check the claims"]@{ shape: task }
    ok["Accurate?"]@{ shape: decision }
    check --> ok
  end
  publish["Publish"]@{ shape: action }
  brief --> writer
  writer --> reviewer
  ok --> publish
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "004_nodes_and_shapes",
        title = "Nodes and shapes",
        source = """
agentflow-beta LR
  brief["Brief"]@{ shape: input }
  draft["Draft copy"]@{ shape: task }
  spellcheck["spell_check"]@{ shape: tool }
  guide["Style guide"]@{ shape: refdoc }
  publish["Publish"]@{ shape: action }

  brief --> draft --> spellcheck --> publish
  draft -.- guide
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "005_edges",
        title = "Edges",
        source = """
agentflow-beta TB
  check["Tests pass?"]@{ shape: decision }
  ship["Ship it"]@{ shape: action }
  fix["Fix the build"]@{ shape: task }
  logs["Build logs"]@{ shape: refdoc }

  check -- yes --> ship
  check -- no --> fix
  fix --x check
  fix -.- logs
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "006_containers",
        title = "Containers",
        source = """
agentflow-beta TB
  flow team["Content Team"]
    flow researcher["Researcher"]
      gather["Gather sources"]@{ shape: task }
    end

    flow writer["Writer"]
      compose["Compose draft"]@{ shape: task }
    end

    researcher --> writer
  end
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "007_the_global_block",
        title = "The `global` block",
        source = """
agentflow-beta TB
  global
    corpus["Shared corpus"]@{ shape: refdoc }
  end

  flow summariser["Summariser"]
    summarise["Summarise"]@{ shape: task }
    summarise -.- corpus
  end

  flow indexer["Indexer"]
    index["Build index"]@{ shape: task }
    index -.- corpus
  end
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "008_collapsing_a_container",
        title = "Collapsing a container",
        source = """
agentflow-beta TB
  flow intake["Intake"]
    receive["Receive request"]@{ shape: input }
  end

  flow processing["Processing"]
    validate["Validate"]@{ shape: task }
    enrich["Enrich"]@{ shape: task }
    validate --> enrich
  end
  processing@{ view: "collapsed" }

  flow output["Output"]
    publish["Publish"]@{ shape: action }
  end

  receive --> validate
  enrich --> publish
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "009_connectors",
        title = "Connectors",
        source = """
agentflow-beta LR
  connector github["GitHub API"]
  github@{ protocol: "http", endpoint: "https://api.github.com" }

  title["Issue title"]@{ shape: input }
  create["create_issue"]@{ shape: tool, connectorRef: "github.create_issue" }

  title --> create
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "010_a_worked_example",
        title = "A worked example",
        source = """
agentflow-beta TB
  connector llm["LLM API"]
  llm@{ protocol: "http", endpoint: "https://api.example.com/chat" }

  flow coffee_team["Coffee Team"]
    city["city"]@{ shape: input, value: "Stockholm" }

    flow researcher["Researcher"]
      research["research_location"]@{ shape: tool, params: "city :: String", returns: "Report" }
      write["write_copy"]@{ shape: tool, connectorRef: "llm.chat", returns: "CoffeeCopy" }
      city --> research --> write
    end
    researcher@{ instruction: "Research the city and draft English coffee copy citing sources." }

    flow designer["Designer"]
      render["generate_html"]@{ shape: tool, connectorRef: "llm.chat", returns: "String" }
      brand["Nordic Brand Guide"]@{ shape: refdoc }
      render -.- brand
    end

    researcher --> designer
  end
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "011_configuration",
        title = "Configuration",
        source = """
---
config:
  agentflow:
    nodeSpacing: 70
    rankSpacing: 70
---
agentflow-beta TB
  a["A"] --> b["B"]
        """.trimIndent(),
        expectedUnsupportedFeature = null,
    ),
    MermaidAgentflowDocCase(
        id = "012_layout",
        title = "Layout",
        source = """
---
config:
  layout: elk
---
agentflow-beta TB
  flow tools["Tools"]
    a["a"]@{ shape: tool }
    b["b"]@{ shape: tool }
  end
  tools@{ algorithm: "elk.rectpacking" }
        """.trimIndent(),
        expectedUnsupportedFeature = "ELK layout",
    ),
)
