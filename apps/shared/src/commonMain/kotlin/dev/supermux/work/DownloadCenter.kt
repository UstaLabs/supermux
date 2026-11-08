package dev.supermux.work

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * A finished download: a real file this app holds ([path]: desktop, Android, iOS), or — on the web, which has no
 * files — an opaque browser [handle] (a JS `Blob`, held by the browser, never in the Wasm heap).
 */
data class DownloadedFile(val name: String, val mime: String, val size: Long, val path: String?, val handle: Any? = null)

/**
 * Whether this finished file is still there to hand over: the media store lives in OS-reclaimable cache folders,
 * and Storage › Clear empties it. A browser [DownloadedFile.handle] (the web) cannot vanish under the app, and is
 * answered without suspending, so a hand-over the user's gesture started still runs inside that gesture.
 */
suspend fun DownloadedFile.isAvailable(): Boolean {
    val file = path ?: return handle != null
    return withContext(Dispatchers.Default) { SystemFileSystem.exists(Path(file)) }
}

/** How one download is fetched. [cancel] stops a transfer that is shared with someone else (the media cache's). */
interface DownloadSource {
    /** The file, or null when it could not be had. [progress] hears the bytes so far and, once known, the total. */
    suspend fun fetch(progress: (received: Long, total: Long?) -> Unit): DownloadedFile?

    fun cancel() {}
}

/** What a finished download is for: a file to open, save or share, or an app update to install. */
enum class DownloadPurpose { File, Installer }

/**
 * One listed download. [key] names what it fetches — `att:<host>/<fileId>`, `fs:<host>:<path>@<version>`,
 * `app-update:<version>` — so a second start of the same thing finds this one.
 */
data class Download(
    val key: String,
    val name: String,
    val mime: String,
    val purpose: DownloadPurpose,
    val state: DownloadState,
)

sealed interface DownloadState {
    data class Running(val received: Long, val total: Long?) : DownloadState

    data class Done(val file: DownloadedFile) : DownloadState

    data class Failed(val message: String) : DownloadState

    data object Cancelled : DownloadState
}

/**
 * A screen's hold on a download it started. Registered BEFORE the fetch is scheduled, so a fast (cached) finish
 * can never slip between "start" and "wait".
 */
interface DownloadClaim {
    /** The file once the download is done; null when it failed or was cancelled. */
    suspend fun await(): DownloadedFile?

    /** In the caller's `finally`: a screen that is gone lets go, so a finish it no longer waits for is announced. */
    fun release()
}

/**
 * The app's downloads (#95). The fleet owns this, so a download outlives the screen that started it: switching
 * chat, closing a viewer or scrolling a row away no longer cancels one. Screens start a download by key and watch
 * it by key.
 *
 * A finish that a screen still waits for (it holds a [DownloadClaim]) is that screen's to act on; one that nobody
 * waits for is announced on [unclaimedCompletions]. Every item stays listed until [clearFinished] or the app
 * closes.
 *
 * @param scope runs the jobs: the fleet's, which ends only when the app closes.
 */
class DownloadCenter(private val scope: CoroutineScope) {
    private val lock = SynchronizedObject()

    // What the list does not show, by key. Changed together with [listed], under [lock].
    private val entries = HashMap<String, Entry>()
    private val listed = MutableStateFlow<List<Download>>(emptyList())
    private val completions = MutableSharedFlow<Download>(extraBufferCapacity = 16)

    /** Every download, newest first. */
    val items: StateFlow<List<Download>> = listed.asStateFlow()

    /** A download that finished with no screen waiting for it: the shell turns each into a notice. */
    val unclaimedCompletions: SharedFlow<Download> = completions.asSharedFlow()

    /** How to fetch a key again ([retry]), the job fetching it now, and how many screens hold a claim on it. */
    private class Entry(var source: DownloadSource) {
        var job: Job? = null
        var claims = 0
    }

    /**
     * Start [key] — or join it while it runs or once it is done, so a second tap never fetches twice — and claim
     * it for the caller. A failed or cancelled key starts again (that is what a second tap means) and moves to
     * the front. The claim is counted before the job can run, so even an instant finish is the caller's.
     */
    fun start(key: String, name: String, mime: String, purpose: DownloadPurpose, source: DownloadSource): DownloadClaim {
        val (claim, job) = synchronized(lock) {
            val entry = entries.getOrPut(key) { Entry(source) }
            entry.claims++
            val job = when (stateOf(key)) {
                is DownloadState.Running, is DownloadState.Done -> null
                else -> {
                    entry.source = source
                    relaunch(key, entry, Download(key, name, mime, purpose, STARTING))
                }
            }
            Claim(key, entry) to job
        }
        job?.start()
        return claim
    }

    /** The user's Cancel. The source keeps what arrived, so [retry] continues from there. */
    fun cancel(key: String) {
        val (source, job) = synchronized(lock) {
            val entry = entries[key]
            if (entry == null || stateOf(key) !is DownloadState.Running) return
            set(key) { DownloadState.Cancelled }
            entry.source to entry.job
        }
        // Told here, at once: a transfer the source shares with the media cache runs on that cache's own scope,
        // and the job's catch runs only later, on the dispatcher — after the fleet's close() has gone on to close
        // the hosts, while that transfer carried on until its next range failed.
        source.cancel()
        job?.cancel()
    }

    /** Fetch a failed or cancelled [key] again from the same source. Nobody claims it: its finish is announced. */
    fun retry(key: String) {
        val job = synchronized(lock) {
            val entry = entries[key] ?: return
            val item = listed.value.firstOrNull { it.key == key } ?: return
            if (item.state is DownloadState.Running || item.state is DownloadState.Done) return
            relaunch(key, entry, item.copy(state = STARTING))
        }
        job.start()
    }

    /** A done [key] whose file is gone (the OS reclaimed it, Storage › Clear): failed, so Retry fetches it again. */
    fun markMissing(key: String) {
        synchronized(lock) { set(key) { if (it is DownloadState.Done) DownloadState.Failed(MISSING) else it } }
    }

    /** The bar's ✕: forget everything that is no longer running. */
    fun clearFinished() {
        synchronized(lock) {
            val finished = listed.value.filter { it.state !is DownloadState.Running }.map { it.key }.toSet()
            entries.keys.removeAll(finished)
            listed.update { all -> all.filter { it.key !in finished } }
        }
    }

    /** The fleet is closing: cancel every download, each source told at once, as with the user's Cancel. */
    fun close() {
        synchronized(lock) { entries.keys.toList() }.forEach { cancel(it) }
    }

    /** List [item] first and make [entry]'s next job; under [lock]. The caller starts the job once the lock is let go. */
    private fun relaunch(key: String, entry: Entry, item: Download): Job {
        listed.update { all -> listOf(item) + all.filter { it.key != key } }
        val source = entry.source
        val job = scope.launch(start = CoroutineStart.LAZY) { download(key, source) }
        entry.job = job
        // A job cancelled before its body ran — a Cancel right after the start, or a scope already over (a start
        // after the fleet closed) — never reaches the catch in [download]: settle it here, or it would stay Running
        // and every claim's await() would hang. [end] ignores a run that has already settled.
        job.invokeOnCompletion { cause -> if (cause != null) end(key, job, DownloadState.Cancelled) }
        return job
    }

    private suspend fun download(key: String, source: DownloadSource) {
        val me = currentCoroutineContext().job
        val ended = try {
            source.fetch { received, total -> progress(key, me, received, total) }
                ?.let { DownloadState.Done(it) }
                ?: DownloadState.Failed(FAILED)
        } catch (c: CancellationException) {
            // A request timing out inside the source surfaces like this while the job is still active: a failure.
            if (currentCoroutineContext().isActive) {
                DownloadState.Failed(c.message ?: FAILED)
            } else {
                // A Cancel or close() has told the source already. A job its scope ended alone is still listed
                // Running here, and its source is told now: the transfer may live on another scope (the media
                // cache's). Never twice: a late second word could stop the transfer a Retry has started since.
                if (end(key, me, DownloadState.Cancelled)) source.cancel()
                throw c
            }
        } catch (e: Throwable) {
            DownloadState.Failed(e.message ?: FAILED)
        }
        end(key, me, ended)
    }

    private fun progress(key: String, me: Job, received: Long, total: Long?) {
        synchronized(lock) {
            if (entries[key]?.job === me) set(key) { if (it is DownloadState.Running) DownloadState.Running(received, total) else it }
        }
    }

    /**
     * Settle [me]'s run of [key] — unless a Cancel, a restart or [clearFinished] came first — and announce a
     * finish that no screen waits for. True when this call settled it.
     */
    private fun end(key: String, me: Job, state: DownloadState): Boolean {
        val unclaimed = synchronized(lock) {
            val entry = entries[key] ?: return false
            if (entry.job !== me || stateOf(key) !is DownloadState.Running) return false
            set(key) { state }
            if (state is DownloadState.Done && entry.claims == 0) listed.value.first { it.key == key } else null
        }
        unclaimed?.let { completions.tryEmit(it) }
        return true
    }

    private fun stateOf(key: String): DownloadState? = listed.value.firstOrNull { it.key == key }?.state

    private fun set(key: String, next: (DownloadState) -> DownloadState) =
        listed.update { all -> all.map { if (it.key == key) it.copy(state = next(it.state)) else it } }

    private inner class Claim(private val key: String, private val entry: Entry) : DownloadClaim {
        private var released = false

        override suspend fun await(): DownloadedFile? {
            // A key no longer listed was cleared by clearFinished, which only takes one that has ended.
            val state = items.map { all -> all.firstOrNull { it.key == key }?.state }.first { it !is DownloadState.Running }
            return (state as? DownloadState.Done)?.file
        }

        override fun release() {
            synchronized(lock) {
                if (!released) {
                    released = true
                    entry.claims--
                }
            }
        }
    }

    private companion object {
        val STARTING = DownloadState.Running(0, null)
        const val FAILED = "Download failed"
        const val MISSING = "The file was removed — retry to download it again"
    }
}
