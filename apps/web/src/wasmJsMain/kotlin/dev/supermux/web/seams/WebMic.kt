package dev.supermux.web.seams

import dev.supermux.ui.platform.CapturedAudio
import dev.supermux.ui.platform.LiveTranscript
import dev.supermux.ui.platform.MicCapture

/** Plan 2 has no dictation; `available=false` hides the mic button. Plan 3 brings MediaRecorder. */
object NoWebMic : MicCapture {
    override fun start(): Boolean = false
    override fun stop(): CapturedAudio? = null
    override fun cancel() = Unit
    override suspend fun requestPermission(): Boolean = false
    override val available: Boolean = false
    override val liveTranscript: LiveTranscript? = null
}
