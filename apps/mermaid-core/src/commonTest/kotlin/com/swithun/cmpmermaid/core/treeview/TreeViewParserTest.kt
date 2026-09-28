package com.swithun.cmpmermaid.core.treeview

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidTreeViewOptions
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewBoxDrawingPreprocessor
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewDb
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewIcons
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewNodeType
import com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TreeViewParserTest {
    @Test
    fun parsesHierarchyNamesAndAnnotationsLikeLangiumPipeline() {
        val db = parse(
            """
            treeView-beta
                my-project/
                    App.tsx :::highlight icon(logos:react) ## main component
                    "file with spaces.txt" ## supporting file
                    .gitignore
            """.trimIndent(),
        )

        val project = db.getRoot().children.single()
        assertEquals("my-project", project.name)
        assertEquals(TreeViewNodeType.Directory, project.nodeType)
        assertEquals(3, project.children.size)
        assertEquals("App.tsx", project.children[0].name)
        assertEquals("highlight", project.children[0].cssClass)
        assertEquals("logos:react", project.children[0].icon)
        assertEquals("main component", project.children[0].description)
        assertEquals("file with spaces.txt", project.children[1].name)
        assertEquals(".gitignore", project.children[2].name)
        assertEquals(5, db.getCount())
    }

    @Test
    fun preservesUnicodeSpacesAndMetadata() {
        val db = parse(
            """
            treeView-beta
              title Project Tree
              accTitle: Accessible Tree
              accDescr {
                First   line

                Second line
              }
                🚀  app/
                    "But  _  _ton💓.tsx"
                    notes.txt icon() ##
            """.trimIndent(),
            diagramTitle = "Frontmatter",
        )

        assertEquals("Project Tree", db.diagramTitle)
        assertEquals("Accessible Tree", db.accessibilityTitle)
        assertEquals("First line\nSecond line", db.accessibilityDescription)
        val rootNode = db.getRoot().children.single()
        assertEquals("🚀  app", rootNode.name)
        assertEquals("But  _  _ton💓.tsx", rootNode.children[0].name)
        assertEquals("none", rootNode.children[1].icon)
        assertNull(rootNode.children[1].description)
    }

    @Test
    fun boxDrawingAndIndentFormatsBuildEquivalentTrees() {
        val indent = parse(
            """
            treeView-beta
                src/
                    App.tsx :::highlight ## main
                    index.ts
                README.md
            """.trimIndent(),
        )
        val box = parse(
            """
            treeView-beta
            ├── src/
            │   ├── App.tsx :::highlight ## main
            │   └── index.ts
            └── README.md
            """.trimIndent(),
        )

        assertEquals(snapshot(indent), snapshot(box))

        val heavy = parse(
            """
            treeView-beta
            ┣━━ src/
            ┃   ┗━━ index.ts
            ┗━━ README.md
            """.trimIndent(),
        )
        assertEquals(listOf("src", "README.md"), heavy.getRoot().children.map { it.name })
        assertEquals("index.ts", heavy.getRoot().children.first().children.single().name)
    }

    @Test
    fun boxDrawingPreprocessorPreservesLineMappingAndReturnsTypedErrors() {
        val transformed = assertIs<GMResult.Ok<*>>(
            TreeViewBoxDrawingPreprocessor.preprocess(
                """
                treeView-beta
                ├── a.txt
                │
                └── b.txt
                """.trimIndent(),
                lineOffset = 4,
            ),
        ).value as com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewPreprocessResult

        assertEquals(4, transformed.lineMap[3])
        val malformed = parser(lineOffset = 5).parse(
            """
            treeView-beta
            ├── src/
                index.ts
            """.trimIndent(),
        )
        val error = assertIs<MermaidError.Parse>(
            assertIs<GMResult.Err<MermaidError>>(malformed).error,
        )
        assertEquals(8, error.line)
    }

    @Test
    fun resolvesBuiltInExplicitAndConfiguredIcons() {
        val options = MermaidTreeViewOptions(
            showIcons = true,
            defaultIconPack = "material-icon-theme",
            filenameIcons = mapOf("Dockerfile" to "docker"),
            extensionIcons = mapOf(".ts" to "typescript", "txt" to "none"),
        )
        val db = parse(
            """
            treeView-beta
                src/
                App.ts icon(logos:react)
                util.ts
                Dockerfile
                notes.txt
                README.md icon(folder)
            """.trimIndent(),
            options = options,
        )
        val nodes = db.getRoot().children

        assertEquals(TreeViewIcons.BUILT_IN_FOLDER, TreeViewIcons.getNodeIcon(nodes[0], options))
        assertEquals("logos:react", TreeViewIcons.getNodeIcon(nodes[1], options))
        assertEquals("material-icon-theme:typescript", TreeViewIcons.getNodeIcon(nodes[2], options))
        assertEquals("material-icon-theme:docker", TreeViewIcons.getNodeIcon(nodes[3], options))
        assertNull(TreeViewIcons.getNodeIcon(nodes[4], options))
        assertEquals(TreeViewIcons.BUILT_IN_FOLDER, TreeViewIcons.getNodeIcon(nodes[5], options))
    }

    @Test
    fun rejectsMalformedInputsWithoutThrowing() {
        val malformed = listOf(
            "not-tree\nfile.txt",
            "treeView-beta\n\"unterminated",
            "treeView-beta\nfile.txt icon(logos:)",
            "treeView-beta\n:::highlight",
            "treeView-beta\naccTitle missing colon",
            "treeView-beta\naccDescr { missing close",
            "treeView-beta\n├── ",
        )

        malformed.forEach { source ->
            assertIs<GMResult.Err<MermaidError>>(parser().parse(source), source)
        }
    }

    private fun parse(
        source: String,
        diagramTitle: String? = null,
        options: MermaidTreeViewOptions = MermaidTreeViewOptions(),
    ): TreeViewDb = assertIs<GMResult.Ok<TreeViewDb>>(
        parser(options = options, diagramTitle = diagramTitle).parse(source),
        source,
    ).value

    private fun parser(
        options: MermaidTreeViewOptions = MermaidTreeViewOptions(),
        diagramTitle: String? = null,
        lineOffset: Int = 0,
    ): TreeViewParser = TreeViewParser(
        config = options,
        diagramTitle = diagramTitle,
        lineOffset = lineOffset,
    )

    private fun snapshot(db: TreeViewDb): String {
        fun appendNode(
            node: com.swithun.cmpmermaid.core.treeview.upstream.mermaid.TreeViewNode,
            output: StringBuilder,
        ) {
            output.append(node.level)
                .append('|')
                .append(node.name)
                .append('|')
                .append(node.nodeType)
                .append('|')
                .append(node.cssClass)
                .append('|')
                .append(node.icon)
                .append('|')
                .append(node.description)
                .append('\n')
            node.children.forEach { child -> appendNode(child, output) }
        }
        return buildString { appendNode(db.getRoot(), this) }
    }
}
