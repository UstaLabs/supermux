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

    val cachedCount: Int get() = synchronized(lock) { slots.size }

    private fun slot(path: String): Slot = slots.getOrPut(path) { Slot(MutableStateFlow(DirState.Unloaded)) }.also { it.used = ++tick }

    fun dir(path: String): StateFlow<DirState> = synchronized(lock) { slot(path).state.asStateFlow() }

    fun subscribe(path: String): DirSubscription {
        val frame: ClientFrame? = synchronized(lock) {
            val s = slot(path)
            s.refs++
            s.grace?.cancel(); s.grace = null
            if (s.subscribed) return@synchronized null
            s.subscribed = true
            val cached = s.state.value.snapshotOrPrevious
            if (cached == null) s.state.value = DirState.Loading(null)
            ClientFrame.FsSub(path, since = cached?.version)
        }
        if (frame != null) scope.launch { send(frame) }
        var closed = false
        return DirSubscription {
            if (closed) return@DirSubscription
            closed = true
            release(path)
        }
    }

    /** Re-send `fs_sub` WITHOUT `since` for a currently-subscribed path (a hard refresh); no-op otherwise. */
    fun refresh(path: String) {
        val frame: ClientFrame? = synchronized(lock) {
            val s = slots[path] ?: return@synchronized null
            if (s.refs <= 0) return@synchronized null
            ClientFrame.FsSub(path)
        }
        if (frame != null) scope.launch { send(frame) }
    }

    private fun release(path: String) {
        synchronized(lock) {
            val s = slots[path] ?: return
            s.refs--
            if (s.refs > 0 || !s.subscribed) return
            s.grace = scope.launch {
                delay(graceMs)
                val unsub = synchronized(lock) {
                    if (s.refs > 0 || !s.subscribed) false else { s.subscribed = false; s.grace = null; evictLocked(); true }
                }
                if (unsub) send(ClientFrame.FsUnsub(path))
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

    /** The socket (re)connected: re-assert every live subscription, like `viewing` frames. */
    fun onReconnect() {
        val frames = synchronized(lock) {
            slots.filter { (_, s) -> s.refs > 0 }.map { (path, s) ->
                s.subscribed = true
                ClientFrame.FsSub(path, since = s.state.value.snapshotOrPrevious?.version)
            }
        }
        if (frames.isNotEmpty()) scope.launch { frames.forEach { send(it) } }
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
