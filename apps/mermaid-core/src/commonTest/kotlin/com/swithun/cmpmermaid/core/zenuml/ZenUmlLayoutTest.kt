package com.swithun.cmpmermaid.core.zenuml

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidEngine
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.MermaidRenderContext
import com.swithun.cmpmermaid.core.MermaidRenderOptions
import com.swithun.cmpmermaid.core.MermaidScene
import com.swithun.cmpmermaid.core.SceneArrowHead
import com.swithun.cmpmermaid.core.ScenePath
import com.swithun.cmpmermaid.core.ScenePathFillRule
import com.swithun.cmpmermaid.core.SceneShape
import com.swithun.cmpmermaid.core.SceneText
import com.swithun.cmpmermaid.core.TextMetricProvider
import com.swithun.cmpmermaid.core.TextMetrics
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlDocument
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlFragmentKind
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlMessageKind
import com.swithun.cmpmermaid.core.zenuml.upstream.zenuml.ZenUmlParser
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ZenUmlLayoutTest {
    private val context = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = request.text.length * 7f,
                height = request.fontSize * request.lineHeight,
                lineCount = request.text.lines().size,
            )
        },
    )
    private val officialFallbackContext = MermaidRenderContext(
        textMetrics = TextMetricProvider { request ->
            TextMetrics(
                width = ceil(request.text.trim().length * request.fontSize * 0.6f),
                height = request.fontSize * request.lineHeight,
                lineCount = request.text.lines().size,
            )
        },
    )

    @Test
    fun matchesOfficialSimpleAsyncGeometryAndViewport() {
        val document = parse("zenuml\nA -> B: hello")
        val geometry = ZenUmlGeometryBuilder(document, context).build()

        assertEquals(200.0, geometry.width)
        assertEquals(132.0, geometry.height)
        assertEquals(listOf(50.0, 150.0), geometry.participants.map { it.x })
        assertEquals(listOf(28.0, 28.0), geometry.participants.map { it.y })
        assertEquals(listOf(80.0, 80.0), geometry.participants.map { it.width })
        val message = geometry.messages.single()
        assertEquals(ZenUmlMessageKind.Async, message.kind)
        assertEquals(87.5, message.y)

        val scene = render("zenuml\nA -> B: hello")
        assertEquals(222f, scene.width)
        assertEquals(179f, scene.height)
        val paths = scene.elements.filterIsInstance<ScenePath>().associateBy(ScenePath::id)
        assertEquals(SceneArrowHead.None, paths.getValue("zenuml-message-0-line").arrowEnd)
        val arrow = scene.elements
            .filterIsInstance<SceneShape>()
            .single { shape -> shape.id == "zenuml-message-0-arrow" }
        assertEquals(
            "M1 1.25L6.15 4.5L1 7.75",
            arrow.geometry?.paths?.single()?.pathData,
        )
        assertTrue(paths.containsKey("zenuml-lifeline-A"))
        assertTrue(paths.containsKey("zenuml-lifeline-B"))
    }

    @Test
    fun rendersDslTitleButIgnoresFrontmatterTitleLikeMermaidPlugin() {
        val dslTexts = render("zenuml\ntitle DSL Title\nA -> B: hello")
            .elements
            .filterIsInstance<SceneText>()
        val frontmatterTexts = render(
            """
                ---
                title: Frontmatter Title
                ---
                zenuml
                A -> B: hello
            """.trimIndent(),
        ).elements.filterIsInstance<SceneText>()

        assertTrue(dslTexts.any { text -> text.text == "DSL Title" })
        assertTrue(frontmatterTexts.none { text -> text.text == "Frontmatter Title" })
    }

    @Test
    fun matchesNestedOccurrencesAndExplicitReturnGeometry() {
        val document = parse(
            """
                zenuml
                A.method() {
                  B.inner()
                  return done
                }
            """.trimIndent(),
        )
        val geometry = ZenUmlGeometryBuilder(document, context).build()

        assertEquals(
            listOf(86.0 to 90.0, 118.0 to 24.0),
            geometry.occurrences.map { occurrence -> occurrence.y to occurrence.height },
        )
        assertEquals(174.5, geometry.returns.single().y)
        assertEquals("done", geometry.returns.single().label)
        assertEquals(220.0, geometry.height)
    }

    @Test
    fun matchesCapturedUpstreamFallbackGeometry() {
        val nested = geometry(
            "zenuml\nA.method(){ B.inner(); return done }",
            officialFallbackContext,
        )
        assertEquals(307.0, nested.width)
        assertEquals(220.0, nested.height)
        assertEquals(listOf(53.5, 157.0, 257.0), nested.participants.map { it.x })
        assertEquals(listOf(87.5, 119.5), nested.messages.map { it.y })
        assertEquals(listOf(86.0 to 90.0, 118.0 to 24.0), nested.occurrences.map {
            occurrence -> occurrence.y to occurrence.height
        })
        assertEquals(174.5, nested.returns.single().y)

        val creation = geometry(
            "zenuml\nclient = new Service(){ Repo.load() }",
            officialFallbackContext,
        )
        assertEquals(410.5, creation.width)
        assertEquals(240.0, creation.height)
        assertEquals(listOf(53.5, 233.0, 360.5), creation.participants.map { it.x })
        assertEquals(151.0, creation.participants[1].width)
        assertEquals(72.0, creation.participants[1].y)
        assertEquals(143.5, creation.messages.single { it.kind == ZenUmlMessageKind.Sync }.y)
        assertEquals(listOf(110.0 to 86.0, 142.0 to 24.0), creation.occurrences.map {
            occurrence -> occurrence.y to occurrence.height
        })
        assertEquals(194.0, creation.returns.single().y)

        val tcf = geometry(
            """
                zenuml
                try {
                  A.work()
                } catch(error) {
                  B.recover()
                } finally {
                  C.close()
                }
            """.trimIndent(),
            officialFallbackContext,
        )
        assertEquals(407.0, tcf.width)
        assertEquals(421.0, tcf.height)
        assertEquals(listOf(53.5, 157.0, 257.0, 357.0), tcf.participants.map { it.x })
        val fragment = tcf.fragments.single()
        assertEquals(-10.0, fragment.x)
        assertEquals(72.0, fragment.y)
        assertEquals(427.0, fragment.width)
        assertEquals(305.0, fragment.height)
        assertEquals(listOf(72.0, 176.0, 275.0), fragment.sections.map { it.y })
    }

    @Test
    fun rendersCreationFragmentsDividersCommentsAndGroups() {
        val scene = render(
            """
                zenuml
                group Backend {
                  @Database Repo
                }
                // **create**
                client = new Service() {
                  if(found) {
                    Repo.load()
                  } else {
                    client->Repo: miss
                  }
                  ===== phase two =====
                }
            """.trimIndent(),
        )

        val shapes = scene.elements.filterIsInstance<SceneShape>()
        val paths = scene.elements.filterIsInstance<ScenePath>()
        val texts = scene.elements.filterIsInstance<SceneText>()
        assertTrue(shapes.any { shape -> shape.id == "zenuml-group-Backend" })
        assertTrue(shapes.any { shape -> shape.id.startsWith("zenuml-fragment-") })
        assertTrue(shapes.any { shape -> shape.id.startsWith("zenuml-occurrence-") })
        assertTrue(paths.any { path -> path.id.startsWith("zenuml-creation-") })
        assertTrue(paths.any { path -> path.id.startsWith("zenuml-divider-") })
        assertTrue(texts.any { text -> text.text == "create" && text.spans.isNotEmpty() })
        assertTrue(texts.any { text -> text.text == "Alt" })
        assertTrue(scene.width > 0f)
        assertTrue(scene.height > 0f)
    }

    @Test
    fun returnsStructuredParseAndRuntimeFailures() {
        assertIs<GMResult.Err<MermaidError>>(
            MermaidEngine().render("zenuml\nif(x) {", context),
        )

        val failure = MermaidEngine().render(
            "zenuml\nA -> B: hello",
            context.copy(
                textMetrics = TextMetricProvider {
                    throw IllegalStateException("metric failed")
                },
            ),
        )
        assertIs<MermaidError.Unexpected>(
            assertIs<GMResult.Err<MermaidError>>(failure).error,
        )
    }

    @Test
    fun emitsEveryFragmentFamily() {
        val document = parse(
            """
                zenuml
                loop(3) { A.tick() }
                if(x) { A->B: yes } else { B->A: no }
                par { A->B: one; A->C: two }
                opt(enabled) { B.optional() }
                section(frame) { A.section() }
                critical(lock) { C.lock() }
                try { A.work() } catch(error) { B.recover() } finally { C.close() }
                ref(A, B)
            """.trimIndent(),
        )
        val geometry = ZenUmlGeometryBuilder(document, context).build()

        assertEquals(
            ZenUmlFragmentKind.entries.toSet(),
            geometry.fragments.map { fragment -> fragment.kind }.toSet(),
        )
    }

    @Test
    fun exposesExactParticipantAndFragmentVectors() {
        val participantTypes = listOf(
            "actor",
            "boundary",
            "control",
            "database",
            "entity",
            "ec2",
            "iam",
            "lambda",
            "sns",
            "sqs",
            "azurefunction",
        )

        participantTypes.forEach { type ->
            val vector = requireNotNull(ZenUmlParticipantVectors.find(type))
            assertTrue(vector.paths.isNotEmpty(), type)
            assertTrue(vector.paths.all { path -> !path.pathData.isNullOrBlank() }, type)
        }
        val database = requireNotNull(ZenUmlParticipantVectors.find("database"))
        assertEquals(ScenePathFillRule.EvenOdd, database.paths.single().fillRule)
        val azure = requireNotNull(ZenUmlParticipantVectors.find("azurefunction"))
        assertTrue(azure.paths.take(4).all { path -> path.transform != null })
        assertTrue(azure.paths.last().fillGradient != null)

        ZenUmlFragmentKind.entries.forEach { kind ->
            val vector = ZenUmlVectors.fragment(kind)
            assertTrue(vector.paths.isNotEmpty(), kind.name)
            assertTrue(vector.paths.all { path -> !path.pathData.isNullOrBlank() }, kind.name)
        }
    }

    @Test
    fun rendersFragmentLabelsAsUpstreamSegments() {
        val source = """
                zenuml
                if(found) {
                  A.work()
                } else if(missing) {
                  B.recover()
                } else {
                  C.close()
                }
                try {
                  A.work()
                } catch(error) {
                  B.recover()
                } finally {
                  C.close()
                }
            """.trimIndent()
        val geometry = geometry(source, context)
        val scene = render(source)
        val texts = scene.elements.filterIsInstance<SceneText>()
        val shapes = scene.elements.filterIsInstance<SceneShape>()

        assertTrue(texts.any { text -> text.text == "[" })
        assertTrue(texts.any { text -> text.text == "found" })
        assertTrue(texts.any { text -> text.text == "missing" })
        assertTrue(texts.any { text -> text.text == "[else]" })
        assertTrue(texts.any { text -> text.text == "catch" })
        assertTrue(texts.any { text -> text.text == "error" })
        assertTrue(shapes.any { shape -> shape.id.endsWith("-section-label-0-bg") })
        val fragment = geometry.fragments.first()
        val icon = shapes.single { shape -> shape.id == "${fragment.id}-icon" }
        assertEquals((34.0 + fragment.headerY).toFloat(), icon.bounds.top)
    }

    @Test
    fun appliesCommentDirectiveUnderlineToCommentAndMessageText() {
        val scene = render(
            """
                zenuml
                A
                B
                // [underline] note
                A -> B: hello
            """.trimIndent(),
        )
        val texts = scene.elements.filterIsInstance<SceneText>()

        val comment = assertNotNull(
            texts.firstOrNull { text -> text.text == "note" },
            texts.map(SceneText::text).toString(),
        )
        val message = assertNotNull(
            texts.firstOrNull { text -> text.text == "hello" },
            texts.map(SceneText::text).toString(),
        )
        assertTrue(comment.spans.any { span -> span.underline })
        assertTrue(message.spans.any { span -> span.underline })
    }

    @Test
    fun rendersParameterizedCreationGuillemetsWithUpstreamSpacing() {
        val scene = render("zenuml\nnew Service(with, parameters)")
        val texts = scene.elements.filterIsInstance<SceneText>()

        assertEquals(1, texts.count { text -> text.text == "« with,parameters »" })
        assertTrue(texts.none { text -> text.text in setOf("«", "with,parameters", "»") })
    }

    @Test
    fun keepsDefaultCreationLabelAsOneTextElement() {
        val texts = render("zenuml\nnew Service")
            .elements
            .filterIsInstance<SceneText>()

        assertEquals(1, texts.count { text -> text.text == "«create»" })
        assertTrue(texts.none { text -> text.text == "«" || text.text == "»" })
    }

    @Test
    fun offsetsLeadingCodeCommentLikeUpstreamMarkdownRenderer() {
        val plain = geometry("zenuml\nA\nB\n// note\nA -> B: hello", context)
        val code = geometry("zenuml\nA\nB\n// `note`\nA -> B: hello", context)

        assertEquals(plain.comments.single().x + 2.0, code.comments.single().x)
    }

    private fun parse(source: String): ZenUmlDocument =
        assertIs<GMResult.Ok<ZenUmlDocument>>(
            ZenUmlParser(
                options = MermaidRenderOptions(),
            ).parse(source),
        ).value

    private fun render(source: String): MermaidScene =
        assertIs<GMResult.Ok<MermaidScene>>(
            MermaidEngine().render(source, context),
        ).value

    private fun geometry(
        source: String,
        renderContext: MermaidRenderContext,
    ): ZenUmlGeometry = ZenUmlGeometryBuilder(parse(source), renderContext).build()
}
