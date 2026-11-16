package dev.supermux.webview

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where a download goes, the answer to [WebClient.onDownload]. [path] is an absolute path in a folder the app may
 * write; a file already there is replaced. [onStarted] gets the transfer once the engine has it, on the main thread.
 */
public class DownloadTarget(public val path: String, public val onStarted: (WebDownload) -> Unit = {}) {
    override fun toString(): String = "DownloadTarget(path=$path)"
}

/**
 * How far a [WebDownload] got. [received] is the bytes saved so far, [total] the size once the engine knows it.
 *
 * Open to new states, as [PermissionKind] is: a `when` over one needs an `else` branch.
 */
public sealed interface WebDownloadState {
    public val received: Long
    public val total: Long?

    public data class Running(override val received: Long, override val total: Long?) : WebDownloadState

    /** Stopped by [WebDownload.pause], with what arrived kept: [WebDownload.resume] goes on from there. */
    public data class Paused(override val received: Long, override val total: Long?) : WebDownloadState

    /** The file is whole at [WebDownload.path]. */
    public data class Done(override val received: Long) : WebDownloadState {
        override val total: Long? get() = received
    }

    /**
     * It stopped on its own: the network, the server, the disk. [WebDownload.resume] tries again where it can. [reason]
     * names what the app can say in its own words; [message] is the engine's, in English.
     */
    public data class Failed(
        val message: String,
        override val received: Long,
        override val total: Long?,
        val reason: DownloadFailure = DownloadFailure.Other,
    ) : WebDownloadState

    /** Stopped by [WebDownload.cancel], for good: the partial file is gone. */
    public data class Cancelled(override val received: Long, override val total: Long?) : WebDownloadState
}

/**
 * Why a download failed, where the library can say it. Open to new reasons: a `when` over one needs an `else` branch.
 * - [FormPost]: the file is a form's answer (a POST), and this engine can't save it: Android's WebView hands over only
 *   the URL of such a download, and asking again would submit the form a second time.
 */
public enum class DownloadFailure { Other, FormPost }

/**
 * A download the engine is saving to [path], with the page's own session. It outlives the view and the page that
 * started it: closing the tab doesn't stop it, only [cancel] does (or the app's process ending).
 *
 * Every method is safe on any thread and does nothing in a state it doesn't apply to.
 */
public interface WebDownload {
    public val request: DownloadRequest
    public val path: String
    public val state: StateFlow<WebDownloadState>

    /**
     * Whether a [pause] keeps what arrived, so [resume] goes on from there. Where it doesn't (an engine that can't, a
     * `blob:` the page held), a pause still stops it, and [resume] starts it over.
     */
    public val canPause: Boolean

    /** Stops it for now: [resume] goes on, from where it was if [canPause], else from the start. */
    public fun pause()

    /** Goes on after [pause], or tries a [WebDownloadState.Failed] one again. */
    public fun resume()

    public fun cancel()
}

/**
 * The state half of a [WebDownload], for engines: they drive it through [running], [paused], [done], [failed] and
 * [cancelled], and do the transfer's work in [onPause], [onResume] and [onCancel]. A state that has ended (done,
 * cancelled) stays ended.
 */
internal abstract class EngineDownload(
    final override val request: DownloadRequest,
    final override val path: String,
) : WebDownload {
    private val current = MutableStateFlow<WebDownloadState>(WebDownloadState.Running(0, request.contentLength))
    final override val state: StateFlow<WebDownloadState> = current.asStateFlow()

    protected abstract fun onPause()
    protected abstract fun onResume()
    protected abstract fun onCancel()

    final override fun pause() {
        if (state.value is WebDownloadState.Running) onPause()
    }

    final override fun resume() {
        val now = state.value
        if (now is WebDownloadState.Paused || now is WebDownloadState.Failed) onResume()
    }

    final override fun cancel() {
        val now = state.value
        if (now is WebDownloadState.Done || now is WebDownloadState.Cancelled) return
        onCancel()
    }

    fun running(received: Long, total: Long?) = move { WebDownloadState.Running(received, total ?: it.total) }

    fun paused() = move { WebDownloadState.Paused(it.received, it.total) }

    fun done(size: Long) = move { WebDownloadState.Done(size) }

    fun failed(message: String, reason: DownloadFailure = DownloadFailure.Other) =
        move { WebDownloadState.Failed(message, it.received, it.total, reason) }

    fun cancelled() = move { WebDownloadState.Cancelled(it.received, it.total) }

    private fun move(next: (WebDownloadState) -> WebDownloadState) = current.update {
        if (it is WebDownloadState.Done || it is WebDownloadState.Cancelled) it else next(it)
    }

    override fun toString(): String = "WebDownload(url=${request.url}, path=$path, state=${state.value})"
}
