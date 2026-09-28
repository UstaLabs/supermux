package com.swithun.cmpmermaid.core

/**
 * Generated from Mermaid 12.0.0
 * packages/mermaid/src/docs/syntax/treeView.md.
 * Upstream document SHA-256: d79f6af9cf8fe48418037582b86b5aa226d99db325b207ba2a866e9c10fb2f7f
 *
 * Do not edit manually. Run:
 *   MERMAID_SOURCE_DIR=/path/to/mermaid npm run generate:treeview-doc-fixtures
 */
internal data class MermaidTreeViewDocCase(
    val id: String,
    val title: String,
    val source: String,
)

internal val officialTreeViewDocumentationCases: List<MermaidTreeViewDocCase> = listOf(
    MermaidTreeViewDocCase(
        id = "001_box_drawing_input",
        title = "Box-Drawing Input",
        source = """
treeView-beta
├── src/
│   ├── index.ts
│   └── utils.ts
├── package.json
└── README.md
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "002_box_drawing_input",
        title = "Box-Drawing Input",
        source = """
treeView-beta
├── src/
│   ├── App.tsx :::highlight icon(logos:react) ## main component
│   └── index.ts ## entry point
├── .env ## environment variables
├── Dockerfile
└── package.json
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "003_box_drawing_input",
        title = "Box-Drawing Input",
        source = """
treeView-beta
├── packages/
│   ├── mermaid/
│   │   ├── src/
│   │   │   ├── parser.ts
│   │   │   └── renderer.ts
│   │   └── package.json
│   └── parser/
│       └── src/
└── README.md
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "004_highlighting_with_class",
        title = "Highlighting with :::class",
        source = """
treeView-beta
    src/
        App.tsx :::highlight
        index.js
    package.json
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "005_inline_descriptions_with",
        title = "Inline descriptions with `##`",
        source = """
treeView-beta
    src/
        index.js ## app entry point
        config.ts ## runtime configuration
    package.json ## project manifest
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "006_icons",
        title = "Icons",
        source = """
---
config:
  treeView:
    showIcons: true
---
treeView-beta
    src/
        index.js
    package.json
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "007_file_type_icons_via_config_maps",
        title = "File-type icons via config maps",
        source = """
---
config:
  treeView:
    showIcons: true
    defaultIconPack: material-icon-theme
    filenameIcons:
      Dockerfile: docker
    extensionIcons:
      .ts: typescript
      .tsx: react-ts
      .txt: none
---
treeView-beta
    src/
        App.tsx
        utils.ts
    Dockerfile
    notes.txt
    README.md
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "008_icon_overrides_with_icon",
        title = "Icon overrides with icon()",
        source = """
treeView-beta
    src/
        App.tsx icon(logos:react)
        index.js
    package.json
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "009_hiding_icons",
        title = "Hiding icons",
        source = """
---
config:
  treeView:
    showIcons: true
---
treeView-beta
    src/
        index.js icon(none)
    package.json
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "010_combined_annotations",
        title = "Combined annotations",
        source = """
treeView-beta
    my-project/
        src/
            App.tsx :::highlight icon(logos:react) ## main component
            index.js ## entry point
        .env ## environment variables
        Dockerfile
        package.json
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "011_examples",
        title = "Examples",
        source = """
treeView-beta
    "packages"
        "mermaid"
            "src"
        "parser"
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "012_examples",
        title = "Examples",
        source = """
treeView-beta
    🚀 rocket-app/
        📦 packages/
            🎨 ui/
            🛠️ utils/
        🧪 tests/
        📝 README.md
        ⚙️ config.yaml
        """.trimIndent(),
    ),
    MermaidTreeViewDocCase(
        id = "013_examples",
        title = "Examples",
        source = """
---
config:
    treeView:
        rowIndent: 80
        lineThickness: 3
    themeVariables:
        treeView:
            labelFontSize: '20px'
            labelColor: '#FF0000'
            lineColor: '#00FF00'
---
treeView-beta
    "packages"
        "mermaid"
            "src"
        "parser"
        """.trimIndent(),
    ),
)
