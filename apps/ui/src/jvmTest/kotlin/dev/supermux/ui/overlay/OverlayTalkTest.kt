package dev.supermux.ui.overlay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OverlayTalkTest {
    private fun screen(vararg bytes: Byte) = CompletableDeferred<ByteArray?>(bytes)

    // The bug Ahmet hit: the effect that sees a talk finish restarts the moment it marks the talk done, and
    // took an upload still in flight down with it, so a talk with the screen was never sent.
    @Test
    fun aTalksSendOutlivesTheEffectThatSawItFinish() = runBlocking {
        val uploading = CompletableDeferred<Unit>()
        val sent = CompletableDeferred<Pair<String, List<String>>>()
        val talks = CoroutineScope(SupervisorJob())
        val effect = launch {
            finishTalk(
                talks, "what's this?", "screen.png", screen(1, 2, 3),
                upload = { _, _, _, _, _ -> uploading.await(); "file-1" },
                send = { text, ids -> sent.complete(text to ids) },
            )
            awaitCancellation()
        }
        yield()
        effect.cancel() // the talk is marked done: the effect restarts
        uploading.complete(Unit)
        assertEquals("what's this?" to listOf("file-1"), withTimeout(2_000) { sent.await() })
        talks.cancel()
    }

    @Test
    fun whenTheScreenWontGoUpTheWordsStillGo() = runBlocking {
        val sent = CompletableDeferred<Pair<String, List<String>>>()
        val talks = CoroutineScope(SupervisorJob())
        finishTalk(
            talks, "and this?", "screen.png", screen(1),
            upload = { _, _, _, _, _ -> error("offline") },
            send = { text, ids -> sent.complete(text to ids) },
        )
        assertEquals("and this?" to emptyList(), withTimeout(2_000) { sent.await() })
        talks.cancel()
    }

    @Test
    fun aDroppedScreenNeverGoesUp() = runBlocking {
        var uploads = 0
        val sent = CompletableDeferred<Pair<String, List<String>>>()
        val talks = CoroutineScope(SupervisorJob())
        finishTalk(
            talks, "just words", "screen.png", screen = null,
            upload = { _, _, _, _, _ -> uploads++; "file-x" },
            send = { text, ids -> sent.complete(text to ids) },
        )
        assertEquals("just words" to emptyList(), withTimeout(2_000) { sent.await() })
        assertEquals(0, uploads)
        talks.cancel()
    }

    @Test
    fun theScreenGoesUpAsAPng() = runBlocking {
        var seen: Triple<String, String, Long>? = null
        val sent = CompletableDeferred<List<String>>()
        val talks = CoroutineScope(SupervisorJob())
        finishTalk(
            talks, "look", "screen-1.png", screen(9, 9, 9, 9),
            upload = { src, name, mime, kind, _ -> seen = Triple(name, mime, src.size); assertNull(kind); "file-2" },
            send = { _, ids -> sent.complete(ids) },
        )
        assertEquals(listOf("file-2"), withTimeout(2_000) { sent.await() })
        assertEquals(Triple("screen-1.png", "image/png", 4L), seen)
        talks.cancel()
    }
}
