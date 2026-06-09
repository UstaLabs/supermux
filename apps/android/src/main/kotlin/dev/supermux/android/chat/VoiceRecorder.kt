package dev.supermux.android.chat

class VoiceRecorder(private val context: android.content.Context) {
    private var recorder: android.media.MediaRecorder? = null
    private var file: java.io.File? = null
    fun start() {
        val f = java.io.File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        file = f
        val r = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(context) else @Suppress("DEPRECATION") android.media.MediaRecorder()
        r.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
        r.setOutputFile(f.absolutePath)
        r.prepare(); r.start()
        recorder = r
    }
    /** Stops and returns the recorded file (or null on failure). */
    fun stop(): java.io.File? {
        return try { recorder?.stop(); recorder?.release(); file } catch (e: Throwable) { null } finally { recorder = null }
    }
    fun cancel() { try { recorder?.stop() } catch (_: Throwable) {}; recorder?.release(); recorder = null; file?.delete() }
}
