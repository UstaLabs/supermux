package dev.supermux.android.push

import io.ktor.client.HttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Run a push registration that must outlive whatever asked for it, on an HTTP client of its own.
 * Android destroys an FCM service as soon as onMessageReceived or onNewToken returns, and the
 * service closed its client in onDestroy, so a registration launched on that client failed with
 * "Parent job is Completed" before it reached the broker (card #86).
 */
internal fun launchDetached(scope: CoroutineScope, newClient: () -> HttpClient, work: suspend (HttpClient) -> Unit): Job =
    scope.launch { newClient().use { work(it) } }
