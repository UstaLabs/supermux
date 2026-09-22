package dev.supermux.state

import dev.supermux.net.BrokerApi
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HostStoreWorktreesTest {
    private data class Fixture(
        val store: HostStore,
        val seen: MutableList<String>,
        val bodies: MutableList<String>,
        val queries: MutableList<String>,
        val settings: FakeSettingsStore,
    )

    private fun fixture(
        scope: CoroutineScope,
        settings: FakeSettingsStore = FakeSettingsStore(),
        bodyFor: (String) -> String = { "{}" },
    ): Fixture {
        val seen = mutableListOf<String>()
        val bodies = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val http = HttpClient(MockEngine { req ->
            val path = "${req.method.value} ${req.url.encodedPath}"
            synchronized(seen) { seen += path }
            synchronized(bodies) { bodies += (req.body as? TextContent)?.text.orEmpty() }
            synchronized(queries) { queries += req.url.encodedQuery }
            respond(bodyFor(path), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        })
        val store = HostStore(
            "http://h", "t", scope, testDeps(http = http, settings = settings),
            connectOnInit = false,
            apiOverride = BrokerApi("http://h", "t", http),
        )
        return Fixture(store, seen, bodies, queries, settings)
    }

    private val listJson = """{"root":"/r","worktrees":[{"id":"s/u","path":"/r/s/u","repoName":"supermux","branch":"mux/a",
        "owners":[{"id":"x","name":"X","status":"live"}],"mtime":1700000000000,"uncommitted":2,"unmerged":null,
        "ignored":[{"name":"docs","wellKnown":false},{"name":"node_modules","wellKnown":true}],"hasChanges":true}]}"""

    @Test fun worktreesDecodesTheList() = runBlocking {
        val f = fixture(CoroutineScope(Dispatchers.Default), bodyFor = { if (it == "GET /worktrees") listJson else "{}" })
        val list = f.store.worktrees()!!
        assertEquals("s/u", list.single().id)
        assertEquals("live", list.single().owners.single().status)
        assertNull(list.single().unmerged)
        assertEquals(listOf(false, true), list.single().ignored.map { it.wellKnown })
    }

    @Test fun changesEncodesTheSlashInTheId() = runBlocking {
        val f = fixture(CoroutineScope(Dispatchers.Default), bodyFor = {
            """{"id":"s/u","files":[],"commits":[],"ignored":[],"truncated":{"files":0,"commits":0,"ignored":0}}"""
        })
        f.store.worktreeChanges("s/u")
        assertEquals("GET /worktrees/s%2Fu/changes", f.seen.single())
    }

    @Test fun deleteSendsIdsAndDecodesResults() = runBlocking {
        val f = fixture(CoroutineScope(Dispatchers.Default), bodyFor = { """{"results":[{"id":"s/u","ok":false,"error":"in_use","inUseBy":["X"]}]}""" })
        val r = f.store.deleteWorktrees(listOf("s/u"))!!
        assertEquals("DELETE /worktrees", f.seen.single())
        assertEquals("""{"ids":["s/u"]}""", f.bodies.single())
        assertEquals(listOf("X"), r.single().inUseBy)
    }

    @Test fun archiveSessionAndDeleteWorktreeSendsTheIds() = runBlocking {
        val f = fixture(CoroutineScope(Dispatchers.Default), bodyFor = { """{"worktree":[{"id":"s/u","ok":true}]}""" })
        val r = f.store.killAndDeleteWorktree("abc", listOf("s/u"))!!
        assertEquals("DELETE /sessions/abc", f.seen.single())
        assertEquals(true, r.single().ok)
    }

    @Test fun archiveWorktreeIdsAreRepeatedQueryParams() = runBlocking {
        val f = fixture(CoroutineScope(Dispatchers.Default), bodyFor = { """{"worktree":[]}""" })
        f.store.killAndDeleteWorktree("abc", listOf("s/u"))
        f.store.archiveWorkspaceAndDeleteWorktree("w1", listOf("s/u", "t/v"))
        f.store.closeViewAndDeleteWorktree("w1", "v1", listOf("s/u"))
        assertEquals(
            listOf("deleteWorktree=s%2Fu", "deleteWorktree=s%2Fu&deleteWorktree=t%2Fv", "deleteWorktree=s%2Fu"),
            f.queries,
        )
        assertEquals(listOf("DELETE /sessions/abc", "DELETE /workspaces/w1", "DELETE /workspaces/w1/views/v1"), f.seen)
    }
}
