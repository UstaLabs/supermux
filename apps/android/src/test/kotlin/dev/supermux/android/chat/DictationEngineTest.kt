package dev.supermux.android.chat

import android.speech.SpeechRecognizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-logic tests for the continuous-dictation helpers extracted from [DictationEngine]:
 * segment stitching across pauses, and which [SpeechRecognizer] errors keep the mic open.
 * These reference only compile-time `int` constants, so they run on the plain JVM unit-test
 * classpath without a real recognizer.
 */
class DictationEngineTest {
    @Test fun joins_segment_when_committed_empty() {
        assertEquals("hello", joinSttSegments("", "hello"))
    }

    @Test fun keeps_committed_when_segment_empty() {
        assertEquals("hello world", joinSttSegments("hello world", ""))
    }

    @Test fun joins_with_single_space() {
        assertEquals("hello there world", joinSttSegments("hello there", "world"))
    }

    @Test fun trims_and_never_doubles_or_dangles_spaces() {
        assertEquals("hello world", joinSttSegments("hello  ", "  world"))
        assertEquals("", joinSttSegments("   ", "   "))
        assertEquals("solo", joinSttSegments("   ", " solo "))
    }

    @Test fun silence_and_busy_errors_keep_mic_open() {
        assertTrue(isRecoverableSttError(SpeechRecognizer.ERROR_NO_MATCH))
        assertTrue(isRecoverableSttError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT))
        assertTrue(isRecoverableSttError(SpeechRecognizer.ERROR_RECOGNIZER_BUSY))
    }

    @Test fun real_failures_are_not_recoverable() {
        assertFalse(isRecoverableSttError(SpeechRecognizer.ERROR_AUDIO))
        assertFalse(isRecoverableSttError(SpeechRecognizer.ERROR_CLIENT))
        assertFalse(isRecoverableSttError(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS))
        assertFalse(isRecoverableSttError(SpeechRecognizer.ERROR_NETWORK))
        assertFalse(isRecoverableSttError(SpeechRecognizer.ERROR_SERVER))
    }
}
