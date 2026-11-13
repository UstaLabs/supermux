package dev.supermux.ui.work

import dev.supermux.work.AudioOutput
import io.github.kdroidfilter.composemediaplayer.VideoPlayerError
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The library's outputs report a player that cannot play the item (#118): macOS's backend sits loading ~7 s on an
 * audio-only file and then sets `UnknownError(Failed to retrieve video size)` — the dock must hear it, not hang.
 */
class LibraryAudioOutputsTest {
    /** A library player state that does what each test says: load, fail, throw. */
    private class FakeState {
        @Volatile var loading = false
        @Volatile var hasMedia = false
        @Volatile var error: VideoPlayerError? = null
        @Volatile var openThrows: Throwable? = null

        /** Applied when openUri is called: what loading looks like after it. */
        @Volatile var onOpen: () -> Unit = { loading = true }

        val state: VideoPlayerState = Proxy.newProxyInstance(
            VideoPlayerState::class.java.classLoader,
            arrayOf(VideoPlayerState::class.java),
        ) { _, method, args ->
            when (method.name) {
                "openUri" -> {
                    openThrows?.let { throw it }
                    onOpen()
                    Unit
                }
                "isLoading" -> loading
                "getHasMedia" -> hasMedia
                "getError" -> error
                "isPlaying" -> false
                "getDuration", "getCurrentTime" -> 0.0
                "getPlaybackSpeed", "getSliderPos", "getVolume", "getAspectRatio" -> 1f
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.firstOrNull() === this
                "toString" -> "FakeState"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Integer.TYPE -> 0
                    else -> null
                }
            }
        } as VideoPlayerState
    }

    private class Heard : AudioOutput.Events {
        val errors = CopyOnWriteArrayList<String>()
        override fun onProgress(positionMs: Long, durationMs: Long) = Unit
        override fun onPlayingChanged(playing: Boolean) = Unit
        override fun onEnded() = Unit
        override fun onError(message: String) {
            errors += message
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest fun end() = scope.cancel()

    private fun waitFor(ms: Long = 5_000, what: () -> Boolean): Boolean {
        val until = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < until) {
            if (what()) return true
            Thread.sleep(20)
        }
        return what()
    }

    @Test fun anErrorTheLibrarySetsWhileLoadingIsHeard() {
        val fake = FakeState()
        val outputs = LibraryAudioOutputs(scope, make = { fake.state })
        val heard = Heard()
        outputs.create(heard).apply { open("file:///tmp/note.m4a"); play() }
        Thread.sleep(400)
        fake.error = VideoPlayerError.UnknownError("Failed to retrieve video size")
        assertTrue(waitFor { heard.errors.isNotEmpty() }, "the error never reached the player")
        assertTrue(heard.errors.single().contains("Failed to retrieve video size"), "${heard.errors}")
    }

    @Test fun anOpenThatThrowsIsAnError() {
        val fake = FakeState().apply { openThrows = IllegalStateException("no backend") }
        val outputs = LibraryAudioOutputs(scope, make = { fake.state })
        val heard = Heard()
        outputs.create(heard).apply { open("/tmp/note.m4a"); play() }
        assertTrue(waitFor { heard.errors.isNotEmpty() }, "a thrown open was swallowed")
    }

    @Test fun aLoadThatNeverEndsTimesOut() {
        val fake = FakeState()
        val outputs = LibraryAudioOutputs(scope, make = { fake.state }, loadTimeoutMs = 600)
        val heard = Heard()
        outputs.create(heard).apply { open("/tmp/note.m4a"); play() }
        assertTrue(waitFor { heard.errors.isNotEmpty() }, "still loading, and the dock hangs")
    }

    @Test fun aReleasedItemSaysNothing() {
        val fake = FakeState()
        val outputs = LibraryAudioOutputs(scope, make = { fake.state }, loadTimeoutMs = 600)
        val heard = Heard()
        val item = outputs.create(heard).apply { open("/tmp/note.m4a"); play() }
        Thread.sleep(300)
        item.release()
        fake.error = VideoPlayerError.UnknownError("late")
        Thread.sleep(1_000)
        assertTrue(heard.errors.isEmpty(), "${heard.errors}")
    }
}
