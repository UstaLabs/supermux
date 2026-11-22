package dev.supermux.ui.browser

import dev.supermux.net.PreviewCookieDto
import dev.supermux.state.HostStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock

/**
 * One host's preview key in the Browser tabs' cookie store (#155). The broker keeps one key per device, valid 24 hours,
 * and a mint replaces the last one at once, so the whole process shares this one keeper per host: two keepers would kill
 * each other's key. It renews when the app starts or reconnects and the key is missing or [renewAfterMs] old, and on a
 * timer while the app runs, and each new key goes straight into the store through a live Browser engine ([attach]).
 *
 * It mints only when a live engine can take the key at once: a key minted with nowhere to put it would kill the one the
 * store holds. With no live engine nothing loads a preview, so the renewal waits for the next page ([ready]).
 */
class PreviewKeeper<C : Any>(
    private val mint: suspend () -> C?,
    // The wall clock: a phone's monotonic clock stops while it sleeps, and a key's 24 hours on the broker don't.
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    private val renewAfterMs: Long = RENEW_AFTER_MS,
    private val retryMs: Long = 60_000,
) {
    /** Puts a key in the Browser profile's store; false when the engine couldn't. */
    fun interface Sink<C> {
        suspend fun put(key: C): Boolean
    }

    private val lock = Mutex()
    private var key: C? = null
    private var mintedAt = 0L
    private var inStore = false
    private var failedAt: Long? = null
    private val sinks = mutableListOf<Sink<C>>()
    private var driver: Job? = null

    /** A live Browser engine the keeper can renew through until the returned detach runs. */
    fun attach(sink: Sink<C>): () -> Unit {
        sinks += sink
        return { sinks.remove(sink) }
    }

    /** The key for a page about to load through [sink], renewed first when missing or due. Null when the host gives none. */
    suspend fun ready(sink: Sink<C>): C? = lock.withLock {
        if (due()) renew(listOf(sink)) else if (!inStore) key?.let { inStore = sink.put(it) }
        key
    }

    /** Start, reconnect or the timer: renew through a live engine if the key is missing or due; without one, the next page does. */
    suspend fun renewIfDue() {
        lock.withLock { if (due() && sinks.isNotEmpty()) renew(sinks.toList()) }
    }

    /**
     * Runs the renewals in [scope]: now (the app started), on each [reconnects], and every [tickMs] (a short tick, not one
     * 12-hour delay: a phone's sleep pauses the clock a delay waits on, not the wall clock [now] reads). One driver at a
     * time, returned to every caller: join it, and drive again when it ends (the scope it ran in left), to take over.
     */
    fun drive(scope: CoroutineScope, reconnects: Flow<Unit>, tickMs: Long = TICK_MS): Job {
        driver?.takeIf { it.isActive }?.let { return it }
        return scope.launch {
            launch { reconnects.collect { renewIfDue() } }
            while (true) {
                renewIfDue()
                delay(tickMs)
            }
        }.also { driver = it }
    }

    private fun due(): Boolean {
        if (key != null && now() - mintedAt < renewAfterMs) return false
        val failed = failedAt
        return failed == null || now() - failed >= retryMs
    }

    // The mint and the store write finish together even when the page that asked is gone: a key minted and then dropped
    // would leave the store with the one it just killed.
    private suspend fun renew(through: List<Sink<C>>) = withContext(NonCancellable) {
        val got = try {
            mint()
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            println("[PreviewKeeper] preview key request failed: ${e::class.simpleName}: ${e.message?.take(160)}")
            null
        }
        if (got == null) {
            failedAt = now()
            return@withContext
        }
        failedAt = null
        key = got
        mintedAt = now()
        inStore = through.any { it.put(got) }
    }

    companion object {
        /** The broker's keys live 24 hours (PREVIEW_TTL_MS): renew at half that. */
        const val RENEW_AFTER_MS = 12 * 60 * 60_000L
        const val TICK_MS = 15 * 60_000L

        private val keepers = mutableMapOf<HostStore, PreviewKeeper<PreviewCookieDto>>()

        /** The process's one keeper for [app]'s host. */
        fun of(app: HostStore): PreviewKeeper<PreviewCookieDto> =
            keepers.getOrPut(app) { PreviewKeeper(mint = { app.api.previewToken().cookie }) }

        /** Drops [app]'s keeper (its host was forgotten), and says where its last key was for: its origin, or null. */
        fun forget(app: HostStore): String? = keepers.remove(app)?.key?.url?.let { url ->
            val scheme = url.indexOf("://")
            if (scheme < 0) null else url.indexOf('/', scheme + 3).let { slash -> if (slash < 0) url else url.substring(0, slash) }
        }
    }
}
