package dev.supermux.webview.engine.android

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import dev.supermux.webview.WebContent
import dev.supermux.webview.WebProfile
import dev.supermux.webview.engine.EngineConfig
import dev.supermux.webview.engine.EngineEvent
import dev.supermux.webview.engine.PageSetup
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.localOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One engine on the phone, made by the real factory and shown in an [EngineTestActivity]. Engine calls run on main.
 * Events are collected from the moment the engine exists, as the runtime does, into [await]'s channel.
 */
internal class EngineHarness private constructor(
    val scenario: ActivityScenario<EngineTestActivity>,
    val activity: EngineTestActivity,
    val engine: AndroidWebEngine,
    private val collector: Job,
    private val received: Channel<EngineEvent>,
) {
    /** Every event [await] and [expectNone] took, oldest first, for failure messages. */
    val seen = mutableListOf<EngineEvent>()

    /** Runs [block] on main with the engine as its receiver: as the runtime calls engines. */
    suspend fun <T> main(block: suspend AndroidWebEngine.() -> T): T = withContext(Dispatchers.Main) { engine.block() }

    /** The next event that matches [predicate], within [timeoutMillis]. The ones before it go to [seen]. */
    suspend fun await(what: String, timeoutMillis: Long = 15_000, predicate: (EngineEvent) -> Boolean): EngineEvent =
        withTimeoutOrNull(timeoutMillis) {
            var found: EngineEvent? = null
            while (found == null) {
                val event = received.receive()
                seen += event
                if (predicate(event)) found = event
            }
            found
        } ?: throw AssertionError("no $what within $timeoutMillis ms; saw $seen")

    /**
     * The first event matching [predicate] that was already [seen], or else the next one to arrive. For what a page may
     * do before its first paint (set its title, log a line) while [loadAndPaint] waits for that paint.
     */
    suspend fun seenOrAwait(what: String, timeoutMillis: Long = 15_000, predicate: (EngineEvent) -> Boolean): EngineEvent =
        seen.firstOrNull(predicate) ?: await(what, timeoutMillis, predicate)

    /** Fails if an event matching [unwanted] arrives within [millis]. */
    suspend fun expectNone(what: String, millis: Long, unwanted: (EngineEvent) -> Boolean) {
        withTimeoutOrNull(millis) {
            while (true) {
                val event = received.receive()
                seen += event
                if (unwanted(event)) throw AssertionError("unexpected $what: $event; saw $seen")
            }
        }
    }

    /**
     * Configures and loads [content] as the runtime does, and returns how many events had been delivered when `load()`
     * returned: `seen.drop(mark)` is what came after. It runs on main, as the collector does, so nothing is delivered
     * while it drains what was already delivered.
     */
    suspend fun loadMarked(content: WebContent, setup: PageSetup = setupFor(content)): Int = withContext(Dispatchers.Main) {
        while (true) seen += received.tryReceive().getOrNull() ?: break
        engine.configure(setup)
        engine.load(content)
        seen.size
    }

    /** Stops collecting, as a view that parks its page does: from then on the engine has no subscriber. */
    suspend fun stopCollecting() = withContext(Dispatchers.Main) { collector.cancelAndJoin() }

    /** Configures [content]'s lease with [setup], loads it, and waits for its commit and first paint. */
    suspend fun loadAndPaint(content: WebContent, setup: PageSetup = setupFor(content)) {
        main {
            configure(setup)
            load(content)
        }
        await("the commit of ${content.key}") { it is EngineEvent.UrlChanged }
        await("the first paint of ${content.key}") { it == EngineEvent.FirstPaint }
    }

    fun close() {
        runBlocking {
            withContext(Dispatchers.Main) {
                collector.cancel()
                engine.release()
            }
        }
        scenario.close()
    }

    companion object {
        /** Opens an Activity, builds an engine for [config] with the real factory, and composes [surface] for it. */
        fun open(
            config: EngineConfig = EngineConfig(WebProfile.Default),
            surface: @Composable (AndroidWebEngine) -> Unit = { it.Surface(Modifier.fillMaxSize()) },
        ): EngineHarness = runBlocking {
            val scenario = ActivityScenario.launch(EngineTestActivity::class.java)
            val launched = CompletableDeferred<EngineTestActivity>()
            scenario.onActivity { launched.complete(it) }
            val activity = launched.await()
            withContext(Dispatchers.Main) {
                ActivityRegistry.register(activity)
                val created = AndroidWebEngineFactory().create(config)
                val engine = (created as? WebEngineResult.Created)?.engine as? AndroidWebEngine
                    ?: throw AssertionError("no engine: $created")
                val received = Channel<EngineEvent>(Channel.UNLIMITED)
                val collector = CoroutineScope(Dispatchers.Main).launch { engine.events.collect { received.send(it) } }
                activity.setContent { surface(engine) }
                EngineHarness(scenario, activity, engine, collector, received)
            }
        }

        /** Runs [block] with a fresh harness, and closes it whatever happens. */
        fun run(config: EngineConfig = EngineConfig(WebProfile.Default), block: suspend EngineHarness.() -> Unit) {
            val harness = open(config)
            try {
                runBlocking { harness.block() }
            } finally {
                harness.close()
            }
        }
    }
}

/** A lease's setup for [content]: JavaScript on, light, opaque, the bridge for its own local origin. */
internal fun setupFor(
    content: WebContent,
    dark: Boolean = false,
    bridgeOrigins: Set<String> = setOfNotNull(content.localOrigin?.origin),
): PageSetup = PageSetup(
    javaScriptEnabled = true,
    inspectable = false,
    dark = dark,
    transparentBackground = false,
    userAgentSuffix = null,
    bridgeOrigins = bridgeOrigins,
)
