package com.swithun.cmpmermaid.core.gitgraph.upstream.mermaid

import com.swithun.cmpmermaid.core.GMResult
import com.swithun.cmpmermaid.core.MermaidError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class GitGraphParserTest {
    @Test
    fun parsesHeadersDirectionsAndBasicCommits() {
        listOf(
            "gitGraph:\ncommit" to GitGraphDirection.LR,
            "gitGraph LR:\ncommit" to GitGraphDirection.LR,
            "gitGraph TB:\ncommit" to GitGraphDirection.TB,
            "gitGraph BT:\ncommit" to GitGraphDirection.BT,
        ).forEach { (source, direction) ->
            val db = parse(source)

            assertEquals(direction, db.getDirection(), source)
            assertEquals(1, db.getCommits().size, source)
            assertEquals("main", db.getCurrentBranch(), source)
        }
    }

    @Test
    fun parsesCommitAttributesInAnyOrder() {
        val db = parse(
            """
            gitGraph
                commit tag:"v1" type: HIGHLIGHT msg: "Release\ncandidate" id:"abc"
                commit "single quoted value" type: REVERSE tag:'v2'
            """.trimIndent(),
        )

        val commits = db.getCommitsArray()
        assertEquals(2, commits.size)
        assertEquals("abc", commits[0].id)
        assertEquals("Release\ncandidate", commits[0].message)
        assertEquals(GitGraphCommitType.Highlight, commits[0].type)
        assertEquals(listOf("v1"), commits[0].tags)
        assertEquals("single quoted value", commits[1].message)
        assertEquals(GitGraphCommitType.Reverse, commits[1].type)
        assertEquals(listOf("v2"), commits[1].tags)
        assertEquals(listOf("abc"), commits[1].parents)
    }

    @Test
    fun createsOrdersAndChecksOutQuotedBranches() {
        val db = parse(
            """
            gitGraph
                commit id:"root"
                branch "release branch" order: 2
                commit id:"release"
                checkout main
                branch feature/new-ui order: 1
                switch "release branch"
            """.trimIndent(),
        )

        assertEquals("release branch", db.getCurrentBranch())
        assertEquals(
            listOf("main", "feature/new-ui", "release branch"),
            db.getBranchesAsArray().map(GitGraphBranch::name),
        )
        assertEquals("root", db.getBranches().getValue("main"))
        assertEquals("release", db.getBranches().getValue("release branch"))
    }

    @Test
    fun createsMergeCommitWithTwoParentsAndOverrides() {
        val db = parse(
            """
            gitGraph
                commit id:"root"
                branch feature
                commit id:"feature-1"
                checkout main
                commit id:"main-1"
                merge feature id:"merge-1" tag:"release" type: REVERSE
            """.trimIndent(),
        )

        val merge = db.getCommits().getValue("merge-1")
        assertEquals(GitGraphCommitType.Merge, merge.type)
        assertEquals(GitGraphCommitType.Reverse, merge.customType)
        assertTrue(merge.customId)
        assertEquals(listOf("main-1", "feature-1"), merge.parents)
        assertEquals(listOf("release"), merge.tags)
        assertEquals("main", merge.branch)
    }

    @Test
    fun cherryPicksNormalAndMergeCommits() {
        val db = parse(
            """
            gitGraph
                commit id:"root"
                branch feature
                commit id:"feature-1"
                checkout main
                cherry-pick id:"feature-1"
                branch release
                commit id:"release-1"
                checkout feature
                commit id:"feature-2"
                checkout main
                merge feature id:"merge-1"
                checkout release
                cherry-pick id:"merge-1" parent:"feature-2" tag:"v2"
            """.trimIndent(),
        )

        val cherryPicks = db.getCommitsArray()
            .filter { commit -> commit.type == GitGraphCommitType.CherryPick }
        assertEquals(2, cherryPicks.size)
        assertEquals(listOf("cherry-pick:feature-1"), cherryPicks[0].tags)
        assertEquals("main", cherryPicks[0].branch)
        assertEquals(listOf("v2"), cherryPicks[1].tags)
        assertEquals("release", cherryPicks[1].branch)
        assertEquals(
            listOf("release-1", "merge-1"),
            cherryPicks[1].parents,
        )
    }

    @Test
    fun parsesMetadataCommentsAndEntityEncodedValues() {
        val db = parse(
            """
            gitGraph:
                %% comment
                title Release   history
                accTitle: Release graph
                accDescr {
                    Main branch

                    and feature branch
                }
                commit id:"A&amp;B" msg:"quoted %% value" %% inline comment
            """.trimIndent(),
        )

        assertEquals("Release history", db.diagramTitle)
        assertEquals("Release graph", db.accessibilityTitle)
        assertEquals("Main branch\nand feature branch", db.accessibilityDescription)
        assertEquals("A&amp;B", db.getHead()?.id)
        assertEquals("quoted %% value", db.getHead()?.message)
    }

    @Test
    fun preservesUpstreamDuplicateCommitReplacementSemantics() {
        val db = parse(
            """
            gitGraph
                commit id:"same" msg:"first"
                commit id:"same" msg:"second"
            """.trimIndent(),
        )

        assertEquals(1, db.getCommits().size)
        assertEquals("second", db.getCommits().getValue("same").message)
        assertEquals(1, db.getCommits().getValue("same").seq)
        assertEquals("same", db.getHead()?.id)
    }

    @Test
    fun generatesStableDistinctIdsWithUpstreamShape() {
        val first = parse("gitGraph\ncommit\ncommit").getCommitsArray().map(GitGraphCommit::id)
        val second = parse("gitGraph\ncommit\ncommit").getCommitsArray().map(GitGraphCommit::id)

        assertEquals(first, second)
        assertEquals(2, first.distinct().size)
        assertTrue(first[0].matches(Regex("0-[a-z0-9]{7}")))
        assertTrue(first[1].matches(Regex("1-[a-z0-9]{7}")))
        assertNotEquals(first[0], first[1])
    }

    @Test
    fun rejectsInvalidBranchCheckoutAndMergeStates() {
        val cases = listOf(
            """
            gitGraph
                branch main
            """.trimIndent() to "Trying to create an existing branch",
            """
            gitGraph
                checkout missing
            """.trimIndent() to "Trying to checkout branch which is not yet created",
            """
            gitGraph
                merge missing
            """.trimIndent() to "Current branch (main)has no commits",
            """
            gitGraph
                commit id:"root"
                merge missing
            """.trimIndent() to "Branch to be merged (missing) does not exist",
            """
            gitGraph
                commit id:"root"
                branch feature
                merge feature
            """.trimIndent() to "Cannot merge a branch to itself",
            """
            gitGraph
                commit id:"root"
                branch feature
                checkout main
                merge feature
            """.trimIndent() to "Both branches have same head",
        )

        cases.forEach { (source, message) ->
            val error = parseError(source)
            assertTrue(error.message.contains(message), source)
        }
    }

    @Test
    fun rejectsInvalidCherryPickStates() {
        val cases = listOf(
            """
            gitGraph
                commit id:"root"
                cherry-pick id:"missing"
            """.trimIndent() to "Source commit id should exist",
            """
            gitGraph
                commit id:"root"
                cherry-pick id:"root"
            """.trimIndent() to "Source commit is already on current branch",
            """
            gitGraph
                commit id:"root"
                branch feature
                commit id:"feature-1"
                checkout main
                merge feature id:"merge"
                branch release
                cherry-pick id:"merge"
            """.trimIndent() to "an immediate parent commit must be specified",
            """
            gitGraph
                commit id:"root"
                branch feature
                commit id:"feature-1"
                checkout main
                merge feature id:"merge"
                branch release
                cherry-pick id:"merge" parent:"missing"
            """.trimIndent() to "not an immediate parent",
        )

        cases.forEach { (source, message) ->
            val error = parseError(source)
            assertTrue(error.message.contains(message), source)
        }
    }

    @Test
    fun rejectsMalformedGrammarWithSourceLocations() {
        val cases = listOf(
            "gitgraph\ncommit",
            "gitGraph XX:\ncommit",
            "gitGraph\ncommit id:abc",
            "gitGraph\nbranch feature order: 01",
            "gitGraph\ncommit type: UNKNOWN",
            "gitGraph\nbranch feature trailing",
            "gitGraph\ncherry-pick id:\"unterminated",
        )

        cases.forEach { source ->
            val error = parseError(source, lineOffset = 3)
            assertTrue(error.line >= 4, source)
            assertTrue(error.column >= 1, source)
        }
    }

    private fun parse(source: String): GitGraphDb {
        val result = GitGraphParser().parse(source + "\n")
        return assertIs<GMResult.Ok<GitGraphDb>>(
            result,
            "Expected Git Graph parse success:\n$source\n$result",
        ).value
    }

    private fun parseError(
        source: String,
        lineOffset: Int = 0,
    ): MermaidError.Parse {
        val result = GitGraphParser(lineOffset = lineOffset).parse(source + "\n")
        return assertIs<MermaidError.Parse>(
            assertIs<GMResult.Err<MermaidError>>(
                result,
                "Expected Git Graph parse failure:\n$source\n$result",
            ).error,
        )
    }
}
