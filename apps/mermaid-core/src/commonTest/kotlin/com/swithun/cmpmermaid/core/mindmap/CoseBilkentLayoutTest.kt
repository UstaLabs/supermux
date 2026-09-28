package com.swithun.cmpmermaid.core.mindmap

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import com.swithun.cmpmermaid.core.SceneSize
import com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseBilkentLayout
import com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseEdgeInput
import com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseLayoutResult
import com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseNodeInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CoseBilkentLayoutTest {
    @Test
    fun laysOutAFlatTreeDeterministically() {
        val nodes = listOf(
            CoseNodeInput("root", SceneSize(80f, 50f)),
            CoseNodeInput("a", SceneSize(70f, 34f)),
            CoseNodeInput("b", SceneSize(90f, 34f)),
            CoseNodeInput("c", SceneSize(60f, 34f)),
        )
        val edges = listOf(
            CoseEdgeInput("root-a", "root", "a"),
            CoseEdgeInput("root-b", "root", "b"),
            CoseEdgeInput("a-c", "a", "c"),
        )

        val first = assertIs<GMResult.Ok<*>>(
            CoseBilkentLayout.layout(nodes, edges),
        ).value
        val second = assertIs<GMResult.Ok<*>>(
            CoseBilkentLayout.layout(nodes, edges),
        ).value

        assertEquals(first, second)
        val result = first as
            com.swithun.cmpmermaid.core.mindmap.upstream.cose.CoseLayoutResult
        assertEquals(nodes.map(CoseNodeInput::id).toSet(), result.nodeCenters.keys)
        assertEquals(result.nodeCenters.size, result.nodeCenters.values.toSet().size)
        assertTrue(result.nodeCenters.values.all { point ->
            point.x.isFinite() && point.y.isFinite()
        })
    }

    @Test
    fun matchesMermaid1200CoseBilkentRelativeNodePositions() {
        val result = assertIs<GMResult.Ok<CoseLayoutResult>>(
            CoseBilkentLayout.layout(
                nodeInputs = listOf(
                    CoseNodeInput("0", SceneSize(72.1875f, 34f)),
                    CoseNodeInput("1", SceneSize(69.09375f, 44f)),
                    CoseNodeInput("2", SceneSize(101.79352f, 71.85897f)),
                    CoseNodeInput("3", SceneSize(64.10216f, 64.10216f)),
                    CoseNodeInput("4", SceneSize(158.33984f, 80f)),
                ),
                edgeInputs = listOf(
                    CoseEdgeInput("edge_0_1", "0", "1"),
                    CoseEdgeInput("edge_1_2", "1", "2"),
                    CoseEdgeInput("edge_0_3", "0", "3"),
                    CoseEdgeInput("edge_3_4", "3", "4"),
                ),
            ),
        ).value
        val root = result.nodeCenters.getValue("0")
        val expectedDeltas = mapOf(
            "1" to (-13.174703f to -91.96764f),
            "2" to (-3.553519f to -203.19424f),
            "3" to (-6.899124f to 102.41664f),
            "4" to (-15.008171f to 227.83658f),
        )

        expectedDeltas.forEach { (id, expected) ->
            val point = result.nodeCenters.getValue(id)
            assertEquals(expected.first, point.x - root.x, 0.02f, "$id x")
            assertEquals(expected.second, point.y - root.y, 0.02f, "$id y")
        }
    }

    @Test
    fun rejectsEmptyAndInvalidNodeInputs() {
        val empty = CoseBilkentLayout.layout(emptyList(), emptyList())
        val duplicate = CoseBilkentLayout.layout(
            nodeInputs = listOf(
                CoseNodeInput("same", SceneSize(10f, 10f)),
                CoseNodeInput("same", SceneSize(20f, 20f)),
            ),
            edgeInputs = emptyList(),
        )
        val invalidSize = CoseBilkentLayout.layout(
            nodeInputs = listOf(CoseNodeInput("node", SceneSize(0f, 10f))),
            edgeInputs = emptyList(),
        )

        assertIs<GMResult.Err<MermaidError.Layout>>(empty)
        assertIs<GMResult.Err<MermaidError.Layout>>(duplicate)
        assertIs<GMResult.Err<MermaidError.Layout>>(invalidSize)
    }

    @Test
    fun rejectsEdgesWithMissingEndpoints() {
        val result = CoseBilkentLayout.layout(
            nodeInputs = listOf(CoseNodeInput("root", SceneSize(10f, 10f))),
            edgeInputs = listOf(CoseEdgeInput("missing", "root", "child")),
        )

        assertIs<GMResult.Err<MermaidError.Layout>>(result)
    }

    @Test
    fun rejectsNonForestGraphsInsteadOfChangingAlgorithms() {
        val result = CoseBilkentLayout.layout(
            nodeInputs = listOf(
                CoseNodeInput("a", SceneSize(10f, 10f)),
                CoseNodeInput("b", SceneSize(10f, 10f)),
                CoseNodeInput("c", SceneSize(10f, 10f)),
            ),
            edgeInputs = listOf(
                CoseEdgeInput("a-b", "a", "b"),
                CoseEdgeInput("b-c", "b", "c"),
                CoseEdgeInput("c-a", "c", "a"),
            ),
        )

        val error = assertIs<GMResult.Err<MermaidError.UnsupportedFeature>>(result).error
        assertNotEquals("", error.message)
    }
}
