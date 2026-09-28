// The app side of the host file-system service: one per HostStore. Folder listings are shared by
// every consumer on this host; subscriptions are ref-counted with a grace period, re-sent on
// reconnect with `since`, and cached snapshots stay on screen while a refresh is in flight.
package dev.supermux.fs

import dev.supermux.net.BrokerApi
import dev.supermux.proto.ClientFrame
import dev.supermux.proto.ServerFrame
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface DirState {
    data object Unloaded : DirState
    data class Loading(val previous: DirSnapshot?) : DirState
    data class Ready(val snap: DirSnapshot) : DirState
    data class Failed(val code: String, val message: String, val previous: DirSnapshot?) : DirState
    data object Gone : DirState
}

/** The snapshot to draw for a state: the current one, or the previous one while loading/failed. */
val DirState.snapshotOrPrevious: DirSnapshot?
    get() = when (this) {
        is DirState.Ready -> snap
        is DirState.Loading -> previous
        is DirState.Failed -> previous
        else -> null
    }

fun interface DirSubscription { fun close() }

class FileSystemService(
    private val api: BrokerApi,
    private val send: suspend (ClientFrame) -> Unit,
    private val scope: CoroutineScope,
    private val graceMs: Long = 10_000,
    private val maxCached: Int = 1_000,
) {
    private class Slot(val state: MutableStateFlow<DirState>) {
        var refs = 0
        var subscribed = false      // an fs_sub is live on the broker
        var grace: Job? = null
        var used = 0L
    }

    private val lock = SynchronizedObject()
    private val slots = LinkedHashMap<String, Slot>()
    private var tick = 0L

    // Outbound frames are decided under `lock` but must reach the broker in that same decision
    // order. A grace-driven FsUnsub and a concurrent re-subscribe's FsSub each run in their own
    // suspend call, so launching a send per-frame can reorder them on the wire. Instead every
    // decision site `trySend`s (non-suspending) into this unlimited channel from inside the same
    // synchronized block, and a single long-lived consumer drains it strictly in order.
    private val outbox = Channel<ClientFrame>(Channel.UNLIMITED)

    init {
        scope.launch { for (frame in outbox) send(frame) }
    }

    val cachedCount: Int get() = synchronized(lock) { slots.size }

    private fun slot(path: String): Slot = slots.getOrPut(path) { Slot(MutableStateFlow(DirState.Unloaded)) }.also { it.used = ++tick }

    fun dir(path: String): StateFlow<DirState> = synchronized(lock) { slot(path).state.asStateFlow() }

    fun subscribe(path: String): DirSubscription {
        synchronized(lock) {
            val s = slot(path)
            s.refs++
            s.grace?.cancel(); s.grace = null
            if (s.subscribed) return@synchronized
            s.subscribed = true
            val cached = s.state.value.snapshotOrPrevious
            // A retry after an error leaves Failed at once, so the view shows progress, not the old error.
            if (cached == null || s.state.value is DirState.Failed) s.state.value = DirState.Loading(cached)
            outbox.trySend(ClientFrame.FsSub(path, since = cached?.version))
        }
        var closed = false
        return DirSubscription {
            if (closed) return@DirSubscription
            closed = true
            release(path)
        }
    }

    /** Re-send `fs_sub` WITHOUT `since` for a currently-subscribed path (a hard refresh); no-op otherwise. */
    fun refresh(path: String) {
        synchronized(lock) {
            val s = slots[path] ?: return@synchronized
            if (s.refs <= 0) return@synchronized
            outbox.trySend(ClientFrame.FsSub(path))
        }
    }

    private fun release(path: String) {
        synchronized(lock) {
            val s = slots[path] ?: return@synchronized
            s.refs--
            if (s.refs > 0 || !s.subscribed) return@synchronized
            s.grace = scope.launch {
                delay(graceMs)
                synchronized(lock) {
                    if (s.refs > 0 || !s.subscribed) return@synchronized
                    s.subscribed = false; s.grace = null; evictLocked()
                    outbox.trySend(ClientFrame.FsUnsub(path))
                }
            }
        }
    }

    /** Called by HostStore's reducer. */
    fun onFrame(frame: ServerFrame) {
        synchronized(lock) {
            when (frame) {
                is ServerFrame.FsDir -> {
                    val s = slots[frame.path] ?: return
                    if (frame.unchanged) {
                        val prev = s.state.value.snapshotOrPrevious ?: return
                        s.state.value = DirState.Ready(prev)
                        return
                    }
                    val cur = (s.state.value as? DirState.Ready)?.snap
                    if (cur != null && cur.version == frame.version) return
                    s.state.value = DirState.Ready(
                        DirSnapshot(path = frame.path, version = frame.version, entries = frame.entries, truncated = frame.truncated),
                    )
                }
                is ServerFrame.FsGone -> {
                    val s = slots[frame.path] ?: return
                    s.subscribed = false
                    s.state.value = DirState.Gone
                }
                is ServerFrame.FsErr -> {
                    val s = slots[frame.path] ?: return
                    s.subscribed = false
                    s.state.value = DirState.Failed(frame.code, frame.message, s.state.value.snapshotOrPrevious)
                }
                else -> {}
            }
        }
    }

    /**
     * The socket (re)connected: re-assert every live subscription, like `viewing` frames. A slot
     * with `refs == 0` that is still `subscribed` is mid-grace; the broker has forgotten it across
     * the reconnect, so we cancel the grace job and mark it unsubscribed locally (no frame to
     * send — there's nothing to unsubscribe from) rather than leaving it falsely "subscribed".
     */
    fun onReconnect() {
        synchronized(lock) {
            for ((path, s) in slots) {
                if (s.refs > 0) {
                    s.subscribed = true
                    outbox.trySend(ClientFrame.FsSub(path, since = s.state.value.snapshotOrPrevious?.version))
                } else if (s.subscribed) {
                    s.grace?.cancel(); s.grace = null
                    s.subscribed = false
                }
            }
        }
    }

    private fun evictLocked() {
        if (slots.size <= maxCached) return
        val victims = slots.entries
            .filter { it.value.refs == 0 && !it.value.subscribed }
            .sortedBy { it.value.used }
            .take(slots.size - maxCached)
        for (v in victims) slots.remove(v.key)
    }

    // ── request / response ────────────────────────────────────────────────────

    private suspend fun <T> call(block: suspend () -> T): Result<T> =
        try { Result.success(block()) } catch (c: CancellationException) { throw c } catch (e: Throwable) { Result.failure(e) }

    suspend fun list(path: String): Result<DirSnapshot> = call { api.hostFsList(path) }.onSuccess { snap ->
        synchronized(lock) {
            val s = slot(path)
            if (s.state.value !is DirState.Ready || (s.state.value as DirState.Ready).snap.version != snap.version) {
                s.state.value = DirState.Ready(snap)
            }
            evictLocked()
        }
    }
    suspend fun stat(path: String): Result<FsStat> = call { api.hostFsStat(path) }
    suspend fun read(path: String): Result<String> = call { api.hostFsRead(path) }
    suspend fun write(path: String, text: String): Result<FsWriteResult> = call { api.hostFsWrite(path, text) }
    suspend fun search(scope: String, q: String, limit: Int = 50): Result<List<SearchHit>> = call { api.hostFsSearch(scope, q, limit) }
    suspend fun op(op: FsOpRequest): Result<Unit> = call { api.hostFsOp(op) }
}
