package dev.supermux.webview.engine.android

import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import java.util.concurrent.Executors

/**
 * WebView's startup, once per process (spec §7: the warm spare is built after `startUpWebView` has warmed the
 * provider). Its background parts run on a thread of their own, so the first engine's construction blocks the main
 * thread as little as WebView allows. androidx.webkit asks that no WebView API is used before it finishes, so the
 * factory awaits it before its feature checks. Main thread only.
 */
internal object WebViewStartup {
    sealed interface Result {
        data object Ready : Result

        /** WebView can't start ([detail] says why): "calling any other WebView APIs is likely to throw". */
        data class Failed(val detail: String) : Result
    }

    private var started: CompletableDeferred<Result>? = null

    /** Starts WebView once; later calls return the same result. */
    fun begin(context: Context): Deferred<Result> {
        started?.let { return it }
        val result = CompletableDeferred<Result>()
        started = result
        val background = Executors.newSingleThreadExecutor { task -> Thread(task, "webview-startup").apply { isDaemon = true } }
        try {
            WebViewCompat.startUpWebView(
                context.applicationContext,
                WebViewStartUpConfig.Builder(background).build(),
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(outcome: WebViewStartUpResult) {
                        result.complete(Result.Ready)
                        background.shutdown()
                    }

                    override fun onError(error: WebViewStartupException) {
                        result.complete(Result.Failed(error.message ?: error.toString()))
                        background.shutdown()
                    }
                },
            )
        } catch (e: RuntimeException) {
            result.complete(Result.Failed(e.toString()))
            background.shutdown()
        }
        return result
    }
}
