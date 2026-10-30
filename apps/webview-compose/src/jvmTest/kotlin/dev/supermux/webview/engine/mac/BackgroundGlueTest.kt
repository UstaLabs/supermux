package dev.supermux.webview.engine.mac

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import dev.supermux.webview.UnavailableReason
import dev.supermux.webview.engine.WebEngineFactory
import dev.supermux.webview.engine.WebEngineResult
import dev.supermux.webview.engine.desktop.MemoryHooks
import dev.supermux.webview.runtime.RuntimeConfig
import dev.supermux.webview.runtime.WebViewRuntime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The glue loads off the UI thread (moved here from the runtime's review): extracting, hashing and `System.load` take
 * tens of milliseconds, a dropped frame or more if they ran in a composition. The factory starts it when the runtime
 * makes it, and engines and the memory hook wait for it without blocking.
 */
@OptIn(ExperimentalTestApi::class)
class BackgroundGlueTest {
    @Test fun theGlueLoadsOnceOffTheCallersThread() = runBlocking {
        val threads = ConcurrentLinkedQueue<Thread>()
        val glue = BackgroundGlue(GlueLoading { threads += Thread.currentThread() })
        glue.prewarm()
        assertNull(glue.await())
        assertNull(glue.await())
        assertEquals(1, threads.size, "loaded once")
        assertNotEquals(Thread.currentThread(), threads.single())
    }

    @Test fun aFailureReachesEveryoneWhoWaits() = runBlocking {
        val glue = BackgroundGlue(GlueLoading { throw MacGlueException("no glue here") })
        assertEquals("no glue here", glue.await())
        assertEquals("no glue here", glue.await())
    }

    @Test fun theFactoryStartsLoadingWhenTheRuntimeMakesIt() = runBlocking {
        val loadedOn = CompletableDeferred<Thread>()
        MacWebEngineFactory(BackgroundGlue(GlueLoading { loadedOn.complete(Thread.currentThread()) }))
        val thread = withTimeout(10_000) { loadedOn.await() }
        assertNotEquals(Thread.currentThread(), thread, "never on the thread that made the runtime")
    }

    @Test fun theMemoryHookWaitsForTheGlueOffTheUiThread() = runComposeUiTest {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val runtime = WebViewRuntime(
            factory = WebEngineFactory { WebEngineResult.Unavailable(UnavailableReason.NotImplemented) },
            config = RuntimeConfig(maxLive = 2, maxIdle = 0, snapshotCacheBytes = 1L shl 20),
            scope = scope,
        )
        val loadedOn = CompletableDeferred<Thread>()
        val glue = BackgroundGlue(GlueLoading { loadedOn.complete(Thread.currentThread()) })
        var started = 0
        val hooks = MemoryHooks(load = { null }, start = { started++ }, register = { MacPages.onMemoryPressure = it })
        var uiThread: Thread? = null
        var shown by mutableStateOf(true)
        setContent {
            uiThread = Thread.currentThread()
            if (shown) MacMemoryHost(runtime, glue, hooks)
        }
        waitUntil(timeoutMillis = 10_000) { hooks.observers(runtime) == 1 }
        assertEquals(1, started)
        assertTrue(loadedOn.isCompleted)
        assertNotEquals(uiThread, runBlocking { loadedOn.await() }, "the glue never loads on the UI thread")
        shown = false
        waitUntil(timeoutMillis = 10_000) { hooks.observers(runtime) == 0 }
        scope.cancel()
    }
}
