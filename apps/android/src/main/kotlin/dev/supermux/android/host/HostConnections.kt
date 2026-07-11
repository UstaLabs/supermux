package dev.supermux.android.host

import dev.supermux.host.PairedHost
import dev.supermux.net.BrokerApi
import dev.supermux.net.BrokerClient
import dev.supermux.proto.ServerFrame
import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Per-host connection registry (spec §5): ONE [BrokerApi] + ONE control WebSocket per paired host,
 * lifecycle-driven by the [dev.supermux.host.PairedHostStore]'s current list.
 *
 * Every host's frames are funneled to a single [onFrame] callback tagged with the originating
 * `recordId`; the [dev.supermux.android.AppViewModel] reducer folds them into unified, sessionId-keyed
 * state (session ids are globally unique across hosts, so the existing maps merge without collision).
 * Socket open/close transitions drive [onConnState] for the offline/greyed group rendering.
 *
 * This class owns sockets + routing only — no UI state, no reducer logic.
 */
class HostConnections(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    private val onFrame: (recordId: String, frame: ServerFrame) -> Unit,
    private val onConnState: (recordId: String, online: Boolean) -> Unit,
) {
    /** One host's live connection: HTTP api + control WS + the jobs that drive them. */
    class Conn(
        val recordId: String,
        val baseUrl: String,   // relayUrl ?: directUrl — ws/wss/http/https all accepted downstream
        val token: String,
        val api: BrokerApi,
        val client: BrokerClient,
    ) {
        val jobs = mutableListOf<Job>()
    }

    // Insertion-ordered so all()/active-fallback follow the store's host order.
    private val conns = LinkedHashMap<String, Conn>()

    fun all(): List<Conn> = conns.values.toList()
    fun conn(recordId: String?): Conn? = recordId?.let { conns[it] }
    fun api(recordId: String?): BrokerApi? = conn(recordId)?.api
    fun client(recordId: String?): BrokerClient? = conn(recordId)?.client

    /**
     * Reconcile live connections against [hosts]: open a socket for each newly-added host, close
     * the one for each removed host, and rebuild a host whose effective URL or token changed.
     * Idempotent — safe to call on every store mutation. Hosts with a blank token or no reachable
     * URL are skipped (kept in the store/list, just not dialed).
     */
    fun sync(hosts: List<PairedHost>) {
        val wanted = hosts.mapNotNull { h -> effectiveUrl(h)?.let { h to it } }
            .filter { (h, _) -> h.token.isNotBlank() }
        val wantedIds = wanted.map { it.first.recordId }.toSet()
        (conns.keys - wantedIds).toList().forEach { close(it) }
        for ((h, url) in wanted) {
            val existing = conns[h.recordId]
            when {
                existing == null -> open(h.recordId, url, h.token)
                existing.baseUrl != url || existing.token != h.token -> {
                    close(h.recordId); open(h.recordId, url, h.token)
                }
            }
        }
    }

    fun closeAll() { conns.keys.toList().forEach { close(it) } }

    private fun open(recordId: String, url: String, token: String) {
        val client = BrokerClient(
            url, token, http,
            onConnectionChange = { online -> onConnState(recordId, online) },
        )
        val conn = Conn(recordId, url, token, BrokerApi(url, token, http), client)
        // Collector launched BEFORE run() so the connect→subscribe→snapshot round-trip lands on a
        // live subscriber (same ordering the single-host AppViewModel has always relied on).
        conn.jobs += scope.launch { client.frames.collect { onFrame(recordId, it) } }
        conn.jobs += scope.launch { client.run() }
        conns[recordId] = conn
    }

    private fun close(recordId: String) {
        val c = conns.remove(recordId) ?: return
        c.jobs.forEach { it.cancel() }
        onConnState(recordId, false)
    }

    companion object {
        /** Transport preference for a paired host: relay first, else the direct/BYO URL (spec §3). */
        fun effectiveUrl(h: PairedHost): String? =
            h.relayUrl?.takeIf { it.isNotBlank() } ?: h.directUrl?.takeIf { it.isNotBlank() }
    }
}
